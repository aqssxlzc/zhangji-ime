package com.rgds.voiceime;

import android.os.SystemClock;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * 直接读取 RG DS 内置手柄的 evdev 节点，监听可配置热键（默认 R1）。
 *
 * 该机器 /dev/input/event* 的权限是 0666，SELinux 为 permissive，
 * 普通应用可以直接打开，不需要无障碍服务或 root。
 */
public final class JoypadMonitor {

    public interface Listener {
        void onHotkeyDown();

        void onHotkeyUp();
    }

    private static final int EVENT_SIZE = 24;

    private static volatile String status = "未启动";
    private static volatile String lastEvent = "无";
    private static volatile long lastEventAt = 0L;

    private final Listener listener;
    private volatile boolean running;
    private volatile FileInputStream input;
    private Thread thread;

    public JoypadMonitor(Listener listener) {
        this.listener = listener;
    }

    public static String getStatus() {
        return status;
    }

    public static String getLastEvent() {
        return lastEvent;
    }

    public static long getLastEventAt() {
        return lastEventAt;
    }

    public static String getLastEventText() {
        String last = lastEvent;
        long at = lastEventAt;
        if (at <= 0) {
            return last;
        }
        long ago = Math.max(0, SystemClock.elapsedRealtime() - at);
        if (ago < 1000) {
            return last + "（刚刚）";
        }
        return last + "（" + (ago / 1000) + " 秒前）";
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        status = "启动中…";
        thread = new Thread(this::loop, "joypad-monitor");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        FileInputStream in = input;
        input = null;
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
        }
        status = "已停止";
    }

    private void loop() {
        while (running) {
            String node = InputDevices.findJoypadNode();
            if (node == null) {
                status = "未找到 retrogame_joypad";
                Diag.log("热键监听：未找到手柄输入节点");
                sleepQuietly(2000);
                continue;
            }
            FileInputStream in = null;
            try {
                in = new FileInputStream(node);
                input = in;
                status = "监听中 " + node;
                Diag.log("热键监听已启动: " + node);
                byte[] buf = new byte[EVENT_SIZE * 64];
                while (running) {
                    int n = in.read(buf);
                    if (n < 0) {
                        break;
                    }
                    parse(buf, n);
                }
            } catch (Throwable t) {
                if (running) {
                    status = "中断：" + t;
                    Diag.log("热键监听异常: " + t);
                }
            } finally {
                if (input == in) {
                    input = null;
                }
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            if (running) {
                sleepQuietly(1500);
            }
        }
        if (!running) {
            status = "已停止";
        }
    }

    private void parse(byte[] b, int n) {
        for (int off = 0; off + EVENT_SIZE <= n; off += EVENT_SIZE) {
            int type = InputDevices.u16(b, off + 16);
            int code = InputDevices.u16(b, off + 18);
            int value = InputDevices.s32(b, off + 20);
            if (type != InputDevices.EV_KEY || value == 2) {
                continue;
            }
            lastEvent = "code " + code + (value == 1 ? " 按下" : " 松开");
            lastEventAt = SystemClock.elapsedRealtime();
            if (HotkeyConfig.isCaptureMode()) {
                continue;
            }
            if (code == HotkeyConfig.getCode()) {
                if (value == 1) {
                    Diag.log("热键 " + HotkeyConfig.getName() + " 按下");
                    listener.onHotkeyDown();
                } else if (value == 0) {
                    Diag.log("热键 " + HotkeyConfig.getName() + " 松开");
                    listener.onHotkeyUp();
                }
            }
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
