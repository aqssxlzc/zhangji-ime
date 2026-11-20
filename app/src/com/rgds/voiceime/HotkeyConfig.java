package com.rgds.voiceime;

import android.content.Context;
import android.content.SharedPreferences;

/** 自定义热键的持久化配置；热键值是 evdev 的 scan code。 */
public final class HotkeyConfig {

    public static final int DEFAULT_CODE = 311; // R1
    public static final String DEFAULT_NAME = "R1";

    private static final String STORE = "voiceime";
    private static final String KEY_CODE = "hotkey_code";
    private static final String KEY_NAME = "hotkey_name";

    private static volatile int code = DEFAULT_CODE;
    private static volatile String name = DEFAULT_NAME;
    private static volatile boolean loaded;
    private static volatile boolean captureMode;

    private HotkeyConfig() {
    }

    public static synchronized void init(Context context) {
        if (loaded) {
            return;
        }
        SharedPreferences p = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        code = p.getInt(KEY_CODE, DEFAULT_CODE);
        name = p.getString(KEY_NAME, KeyNames.of(code));
        loaded = true;
    }

    public static int getCode() {
        return code;
    }

    public static String getName() {
        return name;
    }

    public static synchronized void set(Context context, int newCode, String newName) {
        code = newCode;
        name = newName;
        loaded = true;
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_CODE, newCode)
                .putString(KEY_NAME, newName)
                .apply();
    }

    public static void reset(Context context) {
        set(context, DEFAULT_CODE, DEFAULT_NAME);
    }

    /** 捕获新热键期间，输入法服务忽略原有的热键触发。 */
    public static boolean isCaptureMode() {
        return captureMode;
    }

    public static void setCaptureMode(boolean active) {
        captureMode = active;
    }
}
