package com.rgds.voiceime;

import android.content.Context;

public final class Prefs {
    private static final String NAME = "voiceime";
    private static final String KEY_PUNCT = "punct";

    private Prefs() {
    }

    public static boolean punct(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_PUNCT, true);
    }

    public static void setPunct(Context context, boolean enabled) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_PUNCT, enabled).apply();
    }
}
