package com.rgds.voiceime;

import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** 备用触发：通知栏快捷设置磁贴，点一下开始/结束。 */
public class ToggleTileService extends TileService {

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        VoiceImeService.toggleFrom(this);
        handler.postDelayed(this::updateTile, 500);
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean rec = VoiceImeService.isRecording();
        boolean transcribing = VoiceImeService.isTranscribing();
        tile.setLabel(rec ? "停止录音" : (transcribing ? "识别中…" : "语音输入"));
        tile.setState(rec ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
