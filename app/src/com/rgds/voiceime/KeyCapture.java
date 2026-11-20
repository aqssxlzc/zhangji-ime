package com.rgds.voiceime;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.FileInputStream;
import java.io.IOException;

/** 一次性捕获手柄按键，用于自定义热键。 */
public final class KeyCapture {

    public interface Listener {
        void onCaptured(int code);

        void onTimeout();
    }

    private static final int EVENT_SIZE = 24;
    private static final long TIMEOUT_MS = 12000;

    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean running;
    private volatile boolean cancelled;
    private volatile boolean resultPosted;
    private volatile FileInputStream input;
    private Thread thread;

    private final Runnable timeoutRunnable = () -> {
        if (running) {
            running = false;
            closeQuietly();
        }
    };

    public void start(Listener listener) {
        if (running) {
            return;
        }
        running = true;
        cancelled = false;
        resultPosted = false;
        HotkeyConfig.setCaptureMode(true);
        main.postDelayed(timeoutRunnable, TIMEOUT_MS);
        thread = new Thread(() -> loop(listener), "key-capture");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        cancelled = true;
        running = false;
        HotkeyConfig.setCaptureMode(false);
        main.removeCallbacks(timeoutRunnable);
        closeQuietly();
    }

    private void loop(Listener listener) {
        String node = InputDevices.findJoypadNode();
        if (node == null) {
            finish(null, listener);
            return;
        }
        try (FileInputStream f = new FileInputStream(node)) {
            input = f;
            byte[] buf = new byte[EVENT_SIZE * 32];
            while (running) {
                int n = f.read(buf);
                if (n < 0) {
                    break;
                }
                for (int off = 0; off + EVENT_SIZE <= n; off += EVENT_SIZE) {
                    int type = InputDevices.u16(buf, off + 16);
                    int code = InputDevices.u16(buf, off + 18);
                    int value = InputDevices.s32(buf, off + 20);
                    if (type == InputDevices.EV_KEY && value == 1) {
                        finish(code, listener);
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            if (running) {
                Diag.log("热键捕获异常: " + t);
            }
        } finally {
            input = null;
            if (!cancelled && !resultPosted) {
                // 超时、节点断开或读取异常
                finish(null, listener);
            }
        }
    }

    private void finish(Integer code, Listener listener) {
        if (resultPosted) {
            return;
        }
        resultPosted = true;
        running = false;
        HotkeyConfig.setCaptureMode(false);
        main.removeCallbacks(timeoutRunnable);
        closeQuietly();
        if (cancelled) {
            return;
        }
        final int captured = code == null ? -1 : code;
        main.post(() -> {
            if (captured >= 0) {
                listener.onCaptured(captured);
            } else {
                listener.onTimeout();
            }
        });
    }

    private void closeQuietly() {
        FileInputStream f = input;
        input = null;
        if (f != null) {
            try {
                f.close();
            } catch (IOException ignored) {
            }
        }
    }
}
