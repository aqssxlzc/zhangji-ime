package com.rgds.voiceime;

/** 进程内共享的识别引擎；由常驻的 ModelService 负责加载，输入法只负责使用。 */
public final class ModelHolder {
    private static final AsrEngine ENGINE = new AsrEngine();

    private ModelHolder() {
    }

    public static AsrEngine engine() {
        return ENGINE;
    }
}
