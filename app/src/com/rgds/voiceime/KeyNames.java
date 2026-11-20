package com.rgds.voiceime;

/** evdev scan code 的可读名称（RG DS 内置手柄）。 */
public final class KeyNames {

    private KeyNames() {
    }

    public static String of(int code) {
        switch (code) {
            case 304:
                return "A";
            case 305:
                return "B";
            case 307:
                return "X";
            case 308:
                return "Y";
            case 310:
                return "L1";
            case 311:
                return "R1";
            case 312:
                return "L2";
            case 313:
                return "R2";
            case 314:
                return "Select";
            case 315:
                return "Start";
            case 316:
                return "Mode";
            case 317:
                return "L3";
            case 318:
                return "R3";
            default:
                return "按键" + code;
        }
    }
}
