package com.rgds.voiceime;

import android.os.Handler;
import android.os.Looper;

import java.io.FileOutputStream;
import java.io.IOException;

/**
 * RG DS 的红色状态灯位于 /sys/class/leds/low_power。
 * 该节点权限为 0666，系统又处于 permissive，因此普通应用可以直接读写。
 */
public final class Led {

    private static final String PATH = "/sys/class/leds/low_power/brightness";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static boolean lit = false;
    private static String status = "未测试";

    private Led() {
    }

    public static synchronized void on() {
        if (lit) {
            return;
        }
        try {
            write("1");
            lit = true;
            status = "红灯亮";
            Diag.log("LED 红 开");
        } catch (Throwable t) {
            status = "写入失败：" + t.getMessage();
            Diag.log("LED 开失败: " + t);
        }
    }

    public static synchronized void off() {
        if (!lit) {
            status = "熄灭";
            return;
        }
        try {
            write("0");
        } catch (Throwable t) {
            Diag.log("LED 关失败: " + t);
        }
        lit = false;
        status = "熄灭";
        Diag.log("LED 红 关");
    }

    public static synchronized boolean isLit() {
        return lit;
    }

    /** 录音期间周期性强制写 1，防止其它驱动/服务把灯写灭。 */
    public static synchronized void keepOn() {
        if (!lit) {
            return;
        }
        try {
            write("1");
        } catch (Throwable ignored) {
        }
    }

    public static synchronized String getStatus() {
        return status;
    }

    /** Setup 页面的“测试红灯”。 */
    public static void flash() {
        on();
        MAIN.postDelayed(Led::off, 1200);
    }

    private static void write(String value) throws IOException {
        try (FileOutputStream out = new FileOutputStream(PATH)) {
            out.write(value.getBytes("US-ASCII"));
            out.flush();
        }
    }
}
