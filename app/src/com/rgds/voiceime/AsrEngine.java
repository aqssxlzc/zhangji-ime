package com.rgds.voiceime;

import android.content.res.AssetManager;
import android.os.SystemClock;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OfflinePunctuation;
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig;
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;

/**
 * 封装 sherpa-onnx 的离线 Paraformer-zh int8 模型。
 * 模型直接从 APK assets 读取，不落盘、不联网。
 */
public final class AsrEngine {

    public static final int ST_NOT_LOADED = 0;
    public static final int ST_LOADING = 1;
    public static final int ST_READY = 2;
    public static final int ST_ERROR = 3;

    private static volatile int state = ST_NOT_LOADED;
    private static volatile String detail = "未加载";
    private static volatile long loadMs = -1;

    private volatile OfflineRecognizer recognizer;

    private final Object punctLock = new Object();
    private volatile OfflinePunctuation punctuator;
    private static volatile int punctState = ST_NOT_LOADED;
    private static volatile String punctDetail = "未加载";
    private static volatile long punctLoadMs = -1;

    public static int getStateCode() {
        return state;
    }

    public static String getDetail() {
        return detail;
    }

    public static String getStateText() {
        switch (state) {
            case ST_LOADING:
                return "加载中…";
            case ST_READY:
                return "就绪" + (loadMs >= 0 ? "（" + loadMs + " ms）" : "");
            case ST_ERROR:
                return "失败：" + detail;
            default:
                return "未加载";
        }
    }

    public boolean isReady() {
        return recognizer != null;
    }

    public synchronized void load(AssetManager assets) {
        if (recognizer != null || state == ST_LOADING) {
            return;
        }
        state = ST_LOADING;
        detail = "加载中…";
        Diag.log("开始加载本地模型 Paraformer-zh int8 …");
        long t0 = SystemClock.elapsedRealtime();
        try {
            OfflineParaformerModelConfig para = new OfflineParaformerModelConfig();
            para.setModel("para/model.int8.onnx");

            OfflineModelConfig mc = new OfflineModelConfig();
            mc.setParaformer(para);
            mc.setTokens("para/tokens.txt");
            mc.setNumThreads(4);
            mc.setProvider("cpu");
            mc.setModelType("paraformer");
            mc.setDebug(false);

            OfflineRecognizerConfig cfg = new OfflineRecognizerConfig();
            cfg.setFeatConfig(new FeatureConfig(16000, 80, 0f));
            cfg.setModelConfig(mc);
            cfg.setDecodingMethod("greedy_search");

            recognizer = new OfflineRecognizer(assets, cfg);
            loadMs = SystemClock.elapsedRealtime() - t0;
            state = ST_READY;
            detail = "就绪";
            Diag.log("模型加载完成，用时 " + loadMs + " ms");
        } catch (Throwable t) {
            state = ST_ERROR;
            detail = t.toString();
            Diag.log("模型加载失败: " + t);
        }
    }

    public boolean isPunctReady() {
        return punctuator != null;
    }

    public static String getPunctStateText() {
        switch (punctState) {
            case ST_LOADING:
                return "加载中…";
            case ST_READY:
                return "就绪" + (punctLoadMs >= 0 ? "（" + punctLoadMs + " ms）" : "");
            case ST_ERROR:
                return "失败：" + punctDetail;
            default:
                return "未加载";
        }
    }

    public void loadPunct(AssetManager assets) {
        synchronized (punctLock) {
            if (punctuator != null || punctState == ST_LOADING) {
                return;
            }
            punctState = ST_LOADING;
            punctDetail = "加载中…";
            Diag.log("开始加载标点模型 …");
            long t0 = SystemClock.elapsedRealtime();
            try {
                OfflinePunctuationModelConfig mc = new OfflinePunctuationModelConfig();
                mc.setCtTransformer("punc/model.int8.onnx");
                mc.setNumThreads(2);
                mc.setProvider("cpu");
                mc.setDebug(false);
                OfflinePunctuationConfig cfg = new OfflinePunctuationConfig(mc);
                punctuator = new OfflinePunctuation(assets, cfg);
                punctLoadMs = SystemClock.elapsedRealtime() - t0;
                punctState = ST_READY;
                punctDetail = "就绪";
                Diag.log("标点模型加载完成，用时 " + punctLoadMs + " ms");
            } catch (Throwable t) {
                punctState = ST_ERROR;
                punctDetail = t.toString();
                Diag.log("标点模型加载失败: " + t);
            }
        }
    }

    /** 给识别结果加标点；未就绪或出错时原样返回。 */
    public String punctuate(String text) {
        OfflinePunctuation p = punctuator;
        if (p == null || text == null || text.isEmpty()) {
            return text;
        }
        synchronized (punctLock) {
            try {
                return p.addPunctuation(text);
            } catch (Throwable t) {
                Diag.log("标点处理失败: " + t);
                return text;
            }
        }
    }

    public synchronized String transcribe(float[] samples) {        OfflineRecognizer r = recognizer;
        if (r == null) {
            throw new IllegalStateException("模型未就绪");
        }
        OfflineStream stream = r.createStream();
        try {
            stream.acceptWaveform(samples, 16000);
            r.decode(stream);
            OfflineRecognizerResult res = r.getResult(stream);
            String text = res.getText();
            return text == null ? "" : text.trim();
        } finally {
            try {
                stream.release();
            } catch (Throwable ignored) {
            }
        }
    }

    public synchronized void release() {
        OfflineRecognizer r = recognizer;
        recognizer = null;
        if (r != null) {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
        synchronized (punctLock) {
            if (punctuator != null) {
                try {
                    punctuator.release();
                } catch (Throwable ignored) {
                }
                punctuator = null;
            }
            punctState = ST_NOT_LOADED;
            punctDetail = "未加载";
            punctLoadMs = -1;
        }
        state = ST_NOT_LOADED;
        detail = "未加载";
        loadMs = -1;
    }
}
