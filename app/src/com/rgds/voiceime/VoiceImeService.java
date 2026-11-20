package com.rgds.voiceime;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.inputmethodservice.InputMethodService;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 无可见键盘的语音输入法。
 *
 * 录音与转写彼此解耦：转写排队进行时仍可以立刻开始下一段录音，
 * 每段热键按住期间红灯常亮，松开熄灭。
 */
public class VoiceImeService extends InputMethodService {

    private static final int SAMPLE_RATE = 16000;
    private static final int MAX_SECONDS = 60;
    private static final int CHUNK_BYTES = 3200; // 100ms, 16k mono 16bit

    private static volatile VoiceImeService instance;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Handler main = MAIN;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voice-asr");
        t.setDaemon(true);
        return t;
    });
    private final AsrEngine engine = ModelHolder.engine();
    private final Object pcmLock = new Object();
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();

    private volatile boolean recordingActive;
    private volatile boolean recordingFlag;
    private volatile boolean destroyed;
    private volatile int pendingAsr;

    private AudioRecord audioRecord;
    private InputConnection targetIc;
    private int totalSamples;
    private long recStart;
    private int sessionGen;
    private int recGen;
    private JoypadMonitor joypad;

    private final Runnable ledKeeper = new Runnable() {
        @Override
        public void run() {
            if (!recordingActive) {
                return;
            }
            Led.keepOn();
            main.postDelayed(this, 400);
        }
    };

    // ------------------------------------------------------------------
    // 对外触发接口（快捷磁贴 / 广播）
    // ------------------------------------------------------------------

    public static boolean isRunning() {
        return instance != null;
    }

    public static boolean isRecording() {
        VoiceImeService s = instance;
        return s != null && s.recordingActive;
    }

    public static boolean isTranscribing() {
        VoiceImeService s = instance;
        return s != null && s.pendingAsr > 0;
    }

    public static String getStateText() {
        VoiceImeService s = instance;
        if (s == null) {
            return "未运行";
        }
        if (s.recordingActive) {
            return "录音中";
        }
        if (s.pendingAsr > 0) {
            return "识别中";
        }
        return "空闲";
    }

    public static void toggleFrom(Context context) {
        final Context app = context.getApplicationContext();
        VoiceImeService s = instance;
        if (s == null) {
            MAIN.post(() -> Toast.makeText(app,
                    "语音输入法未启动：请先在输入框中切换到「语音输入」",
                    Toast.LENGTH_SHORT).show());
            return;
        }
        s.main.post(s::handleExternalToggle);
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Diag.log("输入法服务已创建");
        ModelService.start(this);
        joypad = new JoypadMonitor(new JoypadMonitor.Listener() {
            @Override
            public void onHotkeyDown() {
                main.post(VoiceImeService.this::onHotkeyDown);
            }

            @Override
            public void onHotkeyUp() {
                main.post(VoiceImeService.this::onHotkeyUp);
            }
        });
        joypad.start();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        if (recordingActive) {
            recordingActive = false;
            recordingFlag = false;
            AudioRecord ar = audioRecord;
            audioRecord = null;
            if (ar != null) {
                try {
                    ar.stop();
                } catch (Throwable ignored) {
                }
            }
            Led.off();
        }
        main.removeCallbacks(ledKeeper);
        if (joypad != null) {
            joypad.stop();
            joypad = null;
        }
        // 模型由常驻的 ModelService 持有，输入法销毁时不能释放它
        worker.shutdown();
        instance = null;
        Diag.log("输入法服务已销毁");
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        Diag.log("创建输入视图（1px 透明）");
        try {
            Window w = getWindow().getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
        } catch (Throwable ignored) {
        }
        return new SlimView(this);
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        Diag.log("输入框就绪: " + info.packageName);
        ModelService.start(this);
    }

    @Override
    public void onFinishInput() {
        // 输入会话结束：丢弃所有还没上屏的排队结果
        sessionGen++;
        if (recordingActive) {
            abortRecording("输入框已切换");
        }
        targetIc = null;
        super.onFinishInput();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        if (recordingActive) {
            abortRecording("键盘已隐藏");
        }
    }

    // ------------------------------------------------------------------
    // 触发
    // ------------------------------------------------------------------

    private void onHotkeyDown() {
        if (destroyed || recordingActive) {
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            Diag.log("热键：当前没有输入框，忽略");
            return;
        }
        startRecording(ic);
    }

    private void onHotkeyUp() {
        if (recordingActive) {
            stopAndTranscribe();
        }
    }

    private void handleExternalToggle() {
        if (recordingActive) {
            stopAndTranscribe();
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            toast("没有活跃的输入框，请先点选一个输入框");
            return;
        }
        startRecording(ic);
    }

    // ------------------------------------------------------------------
    // 录音
    // ------------------------------------------------------------------

    private boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean precheckFailed(InputConnection ic) {
        if (destroyed || recordingActive) {
            return true;
        }
        if (!engine.isReady()) {
            int st = AsrEngine.getStateCode();
            if (st == AsrEngine.ST_LOADING) {
                toast("模型加载中，请稍候…");
            } else if (st == AsrEngine.ST_ERROR) {
                toast("模型加载失败：" + AsrEngine.getDetail());
            } else {
                toast("语音模型未就绪");
            }
            return true;
        }
        if (!hasMic()) {
            toast("缺少麦克风权限，请打开「语音输入」应用授权");
            return true;
        }
        return ic == null;
    }

    private void startRecording(InputConnection ic) {
        if (precheckFailed(ic)) {
            return;
        }
        // 先进入录音状态并亮灯，保证极短按键也能正确响应
        recordingActive = true;
        recGen = sessionGen;
        targetIc = ic;
        recStart = SystemClock.elapsedRealtime();
        Led.on();
        main.removeCallbacks(ledKeeper);
        main.postDelayed(ledKeeper, 400);

        AudioRecord ar = createRecord();
        if (ar == null) {
            recordingActive = false;
            targetIc = null;
            main.removeCallbacks(ledKeeper);
            Led.off();
            toast("无法打开麦克风（可能被占用）");
            return;
        }
        if (!recordingActive) {
            // 用户在初始化麦克风期间已经松开按键
            try {
                ar.release();
            } catch (Throwable ignored) {
            }
            return;
        }
        synchronized (pcmLock) {
            pcm.reset();
            totalSamples = 0;
        }
        audioRecord = ar;
        recordingFlag = true;
        Diag.log("开始录音");
        Thread t = new Thread(() -> readLoop(ar), "voice-rec");
        t.start();
    }

    private AudioRecord createRecord() {
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int buf = Math.max(min > 0 ? min * 2 : 0, CHUNK_BYTES * 2);
        int[] sources = {
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
        };
        for (int src : sources) {
            AudioRecord ar = null;
            try {
                ar = new AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, buf);
                if (ar.getState() == AudioRecord.STATE_INITIALIZED) {
                    Diag.log("AudioRecord 源 " + src);
                    return ar;
                }
                ar.release();
            } catch (Throwable t) {
                Diag.log("AudioRecord 源 " + src + " 失败: " + t);
                if (ar != null) {
                    ar.release();
                }
            }
        }
        return null;
    }

    private void readLoop(AudioRecord ar) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[] buf = new byte[CHUNK_BYTES];
        try {
            ar.startRecording();
            while (recordingFlag) {
                int n = ar.read(buf, 0, buf.length);
                if (n > 0) {
                    synchronized (pcmLock) {
                        pcm.write(buf, 0, n);
                        totalSamples += n / 2;
                    }
                    if (totalSamples >= SAMPLE_RATE * MAX_SECONDS) {
                        main.post(() -> {
                            if (recordingActive) {
                                toast("已达最长录音时间");
                                stopAndTranscribe();
                            }
                        });
                        break;
                    }
                } else if (n < 0) {
                    Diag.log("AudioRecord.read 错误: " + n);
                    main.post(() -> abortRecording("麦克风读取错误"));
                    break;
                }
            }
        } catch (Throwable t) {
            if (!destroyed) {
                Diag.log("录音线程异常: " + t);
            }
            main.post(() -> abortRecording("录音异常"));
        } finally {
            try {
                ar.stop();
            } catch (Throwable ignored) {
            }
            try {
                ar.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void stopAndTranscribe() {
        if (!recordingActive) {
            return;
        }
        recordingActive = false;
        recordingFlag = false;
        main.removeCallbacks(ledKeeper);
        Led.off();

        AudioRecord ar = audioRecord;
        audioRecord = null;
        if (ar != null) {
            try {
                ar.stop();
            } catch (Throwable ignored) {
            }
        }

        final byte[] data;
        synchronized (pcmLock) {
            data = pcm.toByteArray();
        }
        final InputConnection ic = targetIc;
        targetIc = null;
        final int gen = recGen;
        long ms = SystemClock.elapsedRealtime() - recStart;
        Diag.log("录音结束 " + ms + " ms, " + data.length + " 字节");

        if (data.length < 640) {
            toast("录音太短");
            return;
        }
        pendingAsr++;
        toast(pendingAsr > 1 ? "识别中…（前面还有一段）" : "识别中…");
        try {
            worker.execute(() -> {
                String text = null;
                String err = null;
                try {
                    text = engine.transcribe(toFloat(data));
                    if (text != null && engine.isPunctReady() && Prefs.punct(VoiceImeService.this)) {
                        text = engine.punctuate(text);
                    }
                } catch (Throwable t) {
                    err = String.valueOf(t);
                    Diag.log("识别异常: " + t);
                }
                final String ft = text;
                final String fe = err;
                main.post(() -> onTranscribed(gen, ft, fe, ic));
            });
        } catch (Throwable t) {
            pendingAsr--;
            Diag.log("提交识别失败: " + t);
            toast("提交识别失败");
        }
    }

    private void abortRecording(String why) {
        if (!recordingActive) {
            return;
        }
        recordingActive = false;
        recordingFlag = false;
        main.removeCallbacks(ledKeeper);
        Led.off();

        AudioRecord ar = audioRecord;
        audioRecord = null;
        if (ar != null) {
            try {
                ar.stop();
            } catch (Throwable ignored) {
            }
        }
        targetIc = null;
        synchronized (pcmLock) {
            pcm.reset();
            totalSamples = 0;
        }
        Diag.log("录音中止: " + why);
    }

    // ------------------------------------------------------------------
    // 识别结果
    // ------------------------------------------------------------------

    private void onTranscribed(int gen, String text, String err, InputConnection ic) {
        if (destroyed) {
            return;
        }
        if (pendingAsr > 0) {
            pendingAsr--;
        }
        if (gen != sessionGen) {
            Diag.log("结果属于已结束的输入会话，丢弃");
            return;
        }
        if (err != null) {
            toast("识别失败：" + err);
            return;
        }
        if (text == null) {
            text = "";
        }
        text = text.replaceAll("(?<=[\\p{IsHan}])\\s+(?=[\\p{IsHan}])", "").trim();
        if (text.isEmpty()) {
            toast("未识别到语音");
            return;
        }
        InputConnection cur = getCurrentInputConnection();
        if (ic == null || cur != ic) {
            Diag.log("输入连接失效，结果未插入: " + text);
            toast("输入框已切换，结果未插入");
            return;
        }
        try {
            ic.commitText(text, 1);
            String preview = text.length() > 40 ? text.substring(0, 40) + "…" : text;
            Diag.log("已上屏 " + text.length() + " 字: " + preview);
        } catch (Throwable t) {
            Diag.log("上屏失败: " + t);
            toast("上屏失败：" + t.getMessage());
        }
    }

    private static float[] toFloat(byte[] data) {
        int n = data.length / 2;
        float[] f = new float[n];
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            f[i] = bb.getShort() / 32768.0f;
        }
        return f;
    }

    private void toast(String msg) {
        if (destroyed) {
            return;
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** 1 像素高、完全透明的输入视图。 */
    private static final class SlimView extends View {
        SlimView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(Math.max(w, 1), 1);
        }
    }
}
