package com.rgds.voiceime;

import android.app.Application;
import android.util.Log;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Diag.init(this);
        HotkeyConfig.init(this);
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            Diag.log("!!! 未捕获异常 " + thread.getName() + ": "
                    + Log.getStackTraceString(error));
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }
}
