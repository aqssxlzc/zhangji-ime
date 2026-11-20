package com.rgds.voiceime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 备用触发：adb / 其它应用发送 com.rgds.voiceime.TOGGLE 广播。 */
public class ToggleReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && "com.rgds.voiceime.TOGGLE".equals(intent.getAction())) {
            VoiceImeService.toggleFrom(context);
        }
    }
}
