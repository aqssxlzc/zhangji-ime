package com.rgds.voiceime;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/** 读取本机内置手柄事件节点的小工具。 */
final class InputDevices {

    static final int EV_KEY = 1;
    private static final String JOYPAD_NAME = "retrogame_joypad";

    private InputDevices() {
    }

    static String findJoypadNode() {
        for (int i = 0; i < 40; i++) {
            String name = readSmall("/sys/class/input/event" + i + "/device/name");
            if (name != null && JOYPAD_NAME.equals(name.trim())) {
                return "/dev/input/event" + i;
            }
        }
        // 本机兜底：event9
        File fallback = new File("/dev/input/event9");
        if (fallback.exists() && fallback.canRead()) {
            return fallback.getAbsolutePath();
        }
        return null;
    }

    static int u16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    static int s32(byte[] b, int off) {
        return (b[off] & 0xff)
                | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16)
                | ((b[off + 3] & 0xff) << 24);
    }

    private static String readSmall(String path) {
        try (InputStream in = new FileInputStream(path)) {
            byte[] buf = new byte[128];
            int n = in.read(buf);
            if (n <= 0) {
                return null;
            }
            return new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }
}
