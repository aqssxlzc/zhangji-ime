package com.rgds.voiceime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机即把模型加载进常驻服务。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            ModelService.start(context);
        }
    }
}
