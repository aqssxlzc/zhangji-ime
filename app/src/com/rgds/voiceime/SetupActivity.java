package com.rgds.voiceime;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 唯一的可见界面：首次设置与诊断，不参与输入。 */
public class SetupActivity extends Activity {

    private static final int REQ_MIC = 42;
    private static final int REQ_NOTIF = 43;

    private static final int BG = 0xFF0C1115;
    private static final int PANEL = 0xFF121A1F;
    private static final int TEXT = 0xFFEAF2F1;
    private static final int MUTED = 0xFF8CA0A6;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvMic;
    private TextView tvIme;
    private TextView tvService;
    private TextView tvModel;
    private TextView tvPunct;
    private TextView tvNotif;
    private TextView tvR1;
    private TextView tvLed;
    private TextView tvLog;
    private String lastLog = "";
    private KeyCapture keyCapture;
    private boolean capturing;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        HotkeyConfig.init(this);
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        w.getDecorView().setSystemUiVisibility(0);
        buildUi();
        ModelService.start(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ModelService.start(this);
        handler.removeCallbacks(tick);
        refresh();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        stopCapture();
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopCapture();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(22);
        root.setPadding(pad, dp(28), pad, dp(36));
        scroll.addView(root, new ScrollView.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("语音输入");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(0xFFF3F8F7);
        root.addView(title);

        TextView sub = new TextView(this);
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
        }
        sub.setText("本地 Paraformer-zh · 按住热键听写 · v" + version);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setTextColor(MUTED);
        sub.setPadding(0, dp(6), 0, dp(8));
        root.addView(sub);

        root.addView(section("状态"));
        tvMic = addStatus(root);
        tvIme = addStatus(root);
        tvService = addStatus(root);
        tvModel = addStatus(root);
        tvPunct = addStatus(root);
        tvNotif = addStatus(root);
        tvR1 = addStatus(root);
        tvLed = addStatus(root);

        root.addView(section("设置"));
        addButton(root, "授权麦克风权限", v -> requestMic());
        addButton(root, "打开输入法设置（启用本输入法）", v -> openImeSettings());
        addButton(root, "选择当前输入法", v -> openImePicker());
        addButton(root, "测试红灯（亮 1.2 秒）", v -> {
            Led.flash();
            toast("如果侧边红灯亮起，说明 LED 控制正常");
            refresh();
        });
        addButton(root, "允许常驻通知（常驻服务需要）", v -> requestNotif());
        addButton(root, "刷新状态", v -> refresh());
        addButton(root, "复制诊断信息", v -> copyDiagnostics());

        root.addView(section("热键"));
        addButton(root, "设置热键（按下手柄上的键）", v -> beginCapture());
        addButton(root, "恢复默认热键 R1", v -> {
            stopCapture();
            HotkeyConfig.reset(this);
            refresh();
            toast("已恢复默认热键 R1");
        });

        root.addView(section("选项"));
        CheckBox punct = new CheckBox(this);
        punct.setText("自动添加标点");
        punct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        punct.setTextColor(TEXT);
        punct.setChecked(Prefs.punct(this));
        punct.setOnCheckedChangeListener((buttonView, checked) -> {
            Prefs.setPunct(this, checked);
            toast(checked ? "已开启自动标点" : "已关闭自动标点");
        });
        root.addView(punct);

        root.addView(section("测试输入框"));
        EditText test = new EditText(this);
        test.setHint("点这里，然后按住热键说话，松开后文字会出现在这里");
        test.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        test.setTextColor(TEXT);
        test.setHintTextColor(MUTED);
        test.setGravity(Gravity.TOP | Gravity.START);
        test.setMinLines(3);
        test.setPadding(dp(12), dp(12), dp(12), dp(12));
        test.setBackground(rounded(PANEL, 12));
        test.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        root.addView(test, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(110)));

        root.addView(section("用法"));
        TextView help = new TextView(this);
        help.setText(
                "1. 打开「输入法设置」，启用「语音输入」。\n"
                        + "2. 点「选择当前输入法」，选中「语音输入」。\n"
                        + "3. 在任意输入框里按住热键（默认 R1）说话，松开后自动识别并上屏。\n"
                        + "4. 录音时机身侧面红灯亮，松开立即熄灭，随后出字。\n\n"
                        + "热键\n"
                        + "· 点「设置热键（按下手柄上的键）」，然后按手柄上想要的那个键，\n"
                        + "  12 秒内捕获并立刻生效。默认 R1，可随时恢复。\n"
                        + "· 支持 A/B/X/Y/L1/R1/L2/R2/Select/Start 等手柄按键。\n\n"
                        + "说明\n"
                        + "· 打开本应用或开机后，模型会自动加载并常驻内存（约 10 秒），\n"
                        + "  不需要等到按键才加载。\n"
                        + "· 为保证常驻，通知栏会有一条低优先级的「语音输入」通知。\n"
                        + "· 自动标点默认开启，可在「选项」里关闭；标点由本地 int8 模型添加。\n"
                        + "· 本输入法没有可见键盘，只占 1 像素；点输入框看不到键盘是正常的。\n"
                        + "· 模型与音频全部在本机处理，不联网。\n"
                        + "· 备用触发：通知栏快捷设置磁贴「语音输入」（需先在快捷设置里添加），\n"
                        + "  或广播 am broadcast -a com.rgds.voiceime.TOGGLE -p com.rgds.voiceime。\n\n"
                        + "排查\n"
                        + "· 热键没反应：确认本输入法是当前输入法，并且已点选输入框；\n"
                        + "  再看「热键」行是否为“监听中 /dev/input/event9”。\n"
                        + "· 红灯不亮：点「测试红灯」，失败时看最下方的诊断日志。\n"
                        + "· 模型状态看「常驻服务 / 本地模型」两行；失败时看诊断日志。");
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        help.setTextColor(0xFFC9D6D8);
        help.setLineSpacing(dp(3), 1.0f);
        root.addView(help);

        root.addView(section("诊断日志"));
        tvLog = new TextView(this);
        tvLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextColor(0xFF8FA3AA);
        tvLog.setBackground(rounded(PANEL, 12));
        tvLog.setPadding(dp(12), dp(12), dp(12), dp(12));
        tvLog.setTextIsSelectable(true);
        root.addView(tvLog, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private TextView section(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(0xFF6F858C);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, dp(20), 0, dp(2));
        return t;
    }

    private TextView addStatus(LinearLayout root) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(TEXT);
        t.setPadding(0, dp(7), 0, dp(7));
        root.addView(t, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private Button addButton(LinearLayout root, String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(TEXT);
        b.setBackground(rounded(PANEL, 14));
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        lp.topMargin = dp(8);
        root.addView(b, lp);
        return b;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    private void refresh() {
        try {
            boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            boolean enabled = imeEnabled();
            boolean selected = imeSelected();
            boolean running = VoiceImeService.isRunning();
            boolean modelSvc = ModelService.isRunning();
            boolean notif = Build.VERSION.SDK_INT < 33
                    || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;

            tvMic.setText("麦克风权限　" + (mic ? "已授权" : "未授权"));
            tvIme.setText("输入法　" + (enabled ? "已启用" : "未启用")
                    + " · " + (selected ? "当前已选中" : "未选中"));
            tvService.setText("常驻服务　" + (modelSvc ? "运行中" : "未运行"));
            tvModel.setText("本地模型　" + AsrEngine.getStateText());
            tvPunct.setText("标点模型　" + AsrEngine.getPunctStateText());
            tvNotif.setText("常驻通知　" + (notif ? "已授权" : "未授权（通知不可见）"));
            String hotkey = HotkeyConfig.getName()
                    + "(" + HotkeyConfig.getCode() + ")";
            if (capturing) {
                tvR1.setText("热键　等待按键…（请按手柄上的键）");
            } else {
                tvR1.setText("热键　" + hotkey + (running
                        ? " · " + JoypadMonitor.getStatus()
                        + " · 最近：" + JoypadMonitor.getLastEventText()
                        : "（输入法未启动）"));
            }
            tvLed.setText("红色 LED　" + Led.getStatus());

            String log = Diag.dump();
            if (!log.equals(lastLog)) {
                lastLog = log;
                tvLog.setText(log);
            }
        } catch (Throwable t) {
            Diag.log("刷新状态失败: " + t);
        }
    }

    private boolean imeEnabled() {
        try {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) {
                ComponentName cn = new ComponentName(this, VoiceImeService.class);
                for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
                    if (componentMatches(info.getId(), cn)) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            Diag.log("查询已启用输入法失败: " + t);
        }
        return VoiceImeService.isRunning();
    }

    private boolean imeSelected() {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                InputMethodManager imm = getSystemService(InputMethodManager.class);
                if (imm != null) {
                    InputMethodInfo current = imm.getCurrentInputMethodInfo();
                    if (current != null) {
                        return componentMatches(current.getId(),
                                new ComponentName(this, VoiceImeService.class));
                    }
                }
            }
        } catch (Throwable t) {
            Diag.log("查询当前输入法失败: " + t);
        }
        return VoiceImeService.isRunning();
    }

    private static boolean componentMatches(String id, ComponentName cn) {
        return id != null
                && (id.equals(cn.flattenToShortString()) || id.equals(cn.flattenToString()));
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    private void beginCapture() {
        if (capturing) {
            toast("正在等待按键…");
            return;
        }
        if (keyCapture == null) {
            keyCapture = new KeyCapture();
        }
        capturing = true;
        refresh();
        toast("请按手柄上的目标键（12 秒内）");
        keyCapture.start(new KeyCapture.Listener() {
            @Override
            public void onCaptured(int code) {
                if (!capturing) {
                    return;
                }
                capturing = false;
                String name = KeyNames.of(code);
                HotkeyConfig.set(SetupActivity.this, code, name);
                refresh();
                toast("热键已设为 " + name + "（" + code + "）");
            }

            @Override
            public void onTimeout() {
                if (!capturing) {
                    return;
                }
                capturing = false;
                refresh();
                toast("未捕获到按键，请重试");
            }
        });
    }

    private void stopCapture() {
        capturing = false;
        if (keyCapture != null) {
            keyCapture.stop();
        }
    }

    private void requestMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            toast("麦克风权限已授予");
            return;
        }
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
    }

    private void requestNotif() {
        if (Build.VERSION.SDK_INT < 33) {
            toast("当前系统无需通知权限");
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            toast("通知权限已授予");
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            boolean ok = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            toast(ok ? "麦克风权限已授予" : "麦克风权限被拒绝");
            refresh();
        } else if (requestCode == REQ_NOTIF) {
            boolean ok = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            toast(ok ? "通知权限已授予" : "通知权限被拒绝，常驻通知不可见");
            refresh();
        }
    }

    private void openImeSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
        } catch (Throwable t) {
            toast("无法打开输入法设置：" + t.getMessage());
        }
    }

    private void openImePicker() {
        try {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) {
                imm.showInputMethodPicker();
            }
            if (!imeEnabled()) {
                toast("请先在「输入法设置」里启用「语音输入」");
            }
        } catch (Throwable t) {
            toast("无法打开输入法选择器：" + t.getMessage());
        }
    }

    private void copyDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("VoiceIME diagnostics\n");
        sb.append("device=").append(Build.MODEL)
                .append(" android=").append(Build.VERSION.RELEASE)
                .append(" sdk=").append(Build.VERSION.SDK_INT).append('\n');
        sb.append(tvMic.getText()).append('\n');
        sb.append(tvIme.getText()).append('\n');
        sb.append(tvService.getText()).append('\n');
        sb.append(tvModel.getText()).append('\n');
        sb.append(tvR1.getText()).append('\n');
        sb.append(tvLed.getText()).append("\n\n");
        sb.append("--- log ---\n").append(Diag.dump());
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("VoiceIME", sb.toString()));
            toast("诊断信息已复制到剪贴板");
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
