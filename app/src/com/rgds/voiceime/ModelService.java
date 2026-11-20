package com.rgds.voiceime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 常驻前台服务：负责加载并持有 Paraformer 模型。
 * 输入法服务随时可能被系统回收，模型不跟着它走。
 */
public class ModelService extends Service {

    private static final String CHANNEL_ID = "voice_model";
    private static final int NOTIF_ID = 1101;
    public static final String ACTION_LOAD = "com.rgds.voiceime.LOAD";

    private static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AsrEngine engine = ModelHolder.engine();
    private ExecutorService worker;

    public static void start(Context context) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, ModelService.class).setAction(ACTION_LOAD);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                app.startForegroundService(intent);
            } else {
                app.startService(intent);
            }
        } catch (Throwable t) {
            Diag.log("启动常驻服务失败: " + t);
        }
    }

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "model-loader");
            t.setDaemon(true);
            return t;
        });
        createChannel();
        try {
            startForeground(NOTIF_ID, notification(engine.isReady() ? "模型就绪" : "模型加载中…"));
        } catch (Throwable t) {
            Diag.log("startForeground 失败: " + t);
        }
        Diag.log("常驻模型服务已创建");
        ensureLoaded();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIF_ID, notification(engine.isReady() ? "模型就绪" : "模型加载中…"));
        } catch (Throwable t) {
            Diag.log("startForeground(restart) 失败: " + t);
        }
        ensureLoaded();
        return START_STICKY;
    }

    private void ensureLoaded() {
        if (engine.isReady()) {
            updateNotification(engine.isPunctReady() ? "模型就绪（含标点）" : "模型就绪");
            worker.execute(() -> {
                engine.loadPunct(getAssets());
                main.post(() -> updateNotification(engine.isPunctReady()
                        ? "模型就绪（含标点）" : "模型就绪（标点不可用）"));
            });
            return;
        }
        int st = AsrEngine.getStateCode();
        if (st == AsrEngine.ST_LOADING) {
            updateNotification("模型加载中…");
            return;
        }
        updateNotification("模型加载中…");
        Diag.log("常驻服务开始加载模型");
        worker.execute(() -> {
            engine.load(getAssets());
            main.post(() -> updateNotification(engine.isReady()
                    ? "模型就绪，正在加载标点…" : "模型加载失败"));
            if (engine.isReady()) {
                engine.loadPunct(getAssets());
            }
            main.post(() -> updateNotification(engine.isReady()
                    ? (engine.isPunctReady() ? "模型就绪（含标点）" : "模型就绪（标点不可用）")
                    : "模型加载失败"));
        });
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIF_ID, notification(text));
            }
        } catch (Throwable t) {
            Diag.log("更新通知失败: " + t);
        }
    }

    private void createChannel() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) {
                return;
            }
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "语音模型常驻",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("保持本地语音识别模型常驻内存");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        } catch (Throwable t) {
            Diag.log("创建通知通道失败: " + t);
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, SetupActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("语音输入")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) {
            worker.shutdownNow();
        }
        Diag.log("常驻模型服务已销毁");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
