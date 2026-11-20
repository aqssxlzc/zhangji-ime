package com.rgds.voiceime;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/** 内存里的短日志，SetupActivity 会实时显示，便于本机诊断。 */
public final class Diag {
    private static final int MAX_LINES = 60;
    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private static File logFile;

    private Diag() {
    }

    /** 把日志同时写到应用外部目录，Termux 可以直接读取用于排查。 */
    public static synchronized void init(Context context) {
        if (logFile != null) {
            return;
        }
        try {
            File dir = context.getExternalFilesDir("logs");
            if (dir == null) {
                dir = new File(context.getFilesDir(), "logs");
            }
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            logFile = new File(dir, "voiceime.log");
            append("==== VoiceIME start ====");
        } catch (Throwable ignored) {
        }
    }

    public static void log(String msg) {
        String line;
        synchronized (FMT) {
            line = FMT.format(new Date()) + "  " + msg;
        }
        synchronized (LINES) {
            LINES.addLast(line);
            while (LINES.size() > MAX_LINES) {
                LINES.removeFirst();
            }
        }
        append(line);
        Log.i("VoiceIME", line);
    }

    private static synchronized void append(String line) {
        File f = logFile;
        if (f == null) {
            return;
        }
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write((line + "\n").getBytes("UTF-8"));
        } catch (Throwable ignored) {
        }
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (LINES) {
            for (String s : LINES) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(s);
            }
        }
        return sb.toString();
    }
}
