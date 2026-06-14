package com.aion.chat;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.app.KeyguardManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AionAccessibilityService extends AccessibilityService {
    private static final String TAG = "AionAccessibility";
    private static final long MIN_CAPTURE_INTERVAL_MS = 8_000;
    private static final long FORCE_CAPTURE_INTERVAL_MS = 2_500;
    private static final long LOCAL_LOG_INTERVAL_MS = 30_000; // 同一 App 至少隔 30s 才记
    private static final int  MAX_LOG_LINES = 1000;
    private static volatile AionAccessibilityService instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    private volatile long lastCaptureStartedAt = 0;
    private volatile String lastPackageName = "";
    private volatile boolean serviceActive = false;
    private volatile String serverHttpBase = "";

    // ── 本地活动日志 ───────────────────────────────
    private volatile long lastLogTimestamp = 0;
    private volatile String lastLoggedPkg = "";
    private final Object logLock = new Object();
    private File localLogFile;

    // App 包名 → 中文显示名映射
    private static final Map<String, String> APP_NAMES = new HashMap<String, String>() {{
        put("com.tencent.mm", "微信"); put("com.tencent.qq", "QQ");
        put("com.ss.android.ugc.aweme", "抖音"); put("com.ss.android.ugc.trill", "抖音");
        put("com.zhiliaoapp.musically", "抖音"); put("com.baidu.searchbox", "百度搜索");
        put("com.android.browser", "浏览器"); put("com.android.launcher", "桌面");
        put("com.android.launcher3", "桌面"); put("com.miui.home", "桌面");
        put("com.android.systemui", "系统界面"); put("com.android.contacts", "通讯录");
        put("com.android.dialer", "电话"); put("com.android.mms", "短信");
        put("com.UCMobile", "UC浏览器"); put("com.alibaba.android.rimet", "钉钉");
        put("com.autonavi.minimap", "高德地图"); put("com.baidu.BaiduMap", "百度地图");
        put("com.tencent.wechat", "微信"); put("com.whatsapp", "WhatsApp");
        put("com.instagram.perm", "Instagram"); put("com.facebook.katana", "Facebook");
        put("com.twitter.android", "Twitter"); put("com.spotify.music", "Spotify");
        put("tv.danmaku.bili", "哔哩哔哩"); put("com.bilibili.app.blue", "哔哩哔哩");
        put("com.discord", "Discord"); put("com.slack", "Slack");
        put("com.xyy.mm", "小红书"); put("com.xingin.xhs", "小红书");
        put("com.taobao.taobao4", "淘宝"); put("com.taobao.taobao", "淘宝");
        put("com.jingdong.app.mall", "京东"); put("com.eg.android.AlipayGphone", "支付宝");
        put("com.tencent.wemeet", "腾讯会议"); put("com.alibaba.cloudmeeting", "阿里云会议");
    }};

    // ── 公开静态方法 ───────────────────────────────

    public static boolean isReady() {
        return instance != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    public static boolean captureLatest(Context context, String app, String reason, boolean force, long delayMs, String httpBase) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        AionAccessibilityService svc = instance;
        if (svc == null) return false;
        if (httpBase != null && !httpBase.isEmpty()) svc.serverHttpBase = httpBase;
        svc.queueCapture(app, reason, force, delayMs);
        return true;
    }

    public static boolean isEnabledInSettings(Context context) {
        try {
            String enabled = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.isEmpty()) return false;
            ComponentName expected = new ComponentName(context, AionAccessibilityService.class);
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(enabled);
            while (splitter.hasNext()) {
                String service = splitter.next();
                ComponentName component = ComponentName.unflattenFromString(service);
                if (component != null && expected.equals(component)) return true;
                if (expected.flattenToShortString().equalsIgnoreCase(service)) return true;
                if (expected.flattenToString().equalsIgnoreCase(service)) return true;
            }
        } catch (Exception e) { Log.d(TAG, "accessibility settings check failed: " + e.getMessage()); }
        return false;
    }

    // ── 生命周期 ───────────────────────────────

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        serviceActive = true;
        instance = this;
        getSharedPreferences("aion_prefs", MODE_PRIVATE)
                .edit().putBoolean("accessibility_user_opted_in", true).apply();
        // 初始化本地日志文件
        try {
            File dir = new File(getFilesDir(), "activity");
            if (!dir.exists()) dir.mkdirs();
            localLogFile = new File(dir, "app_switches.jsonl");
        } catch (Exception e) { Log.e(TAG, "init log file failed: " + e.getMessage()); }
        Log.i(TAG, "Accessibility service connected");
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Accessibility service destroyed");
        serviceActive = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "Accessibility service unbound");
        serviceActive = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;

        String pkg = event.getPackageName().toString();
        if (pkg.isEmpty() || pkg.equals(getPackageName())) return;

        if (!pkg.equals(lastPackageName)) {
            lastPackageName = pkg;
            // ── 本地存储活动日志 ──
            saveActivityLog(pkg);
        }
    }

    @Override
    public void onInterrupt() {
        Log.i(TAG, "Accessibility service interrupted");
    }

    // ── 本地活动日志写入 ───────────────────────────────

    private void saveActivityLog(String pkg) {
        if (localLogFile == null) return;
        long now = System.currentTimeMillis();
        // 节流：同一 App 30 秒内不重复记录
        if (pkg.equals(lastLoggedPkg) && (now - lastLogTimestamp) < LOCAL_LOG_INTERVAL_MS) return;
        lastLogTimestamp = now;
        lastLoggedPkg = pkg;

        new Thread(() -> {
            try {
                String appName = resolveAppName(pkg);
                JSONObject entry = new JSONObject();
                entry.put("timestamp", now / 1000);
                entry.put("time", new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
                entry.put("date", new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date()));
                entry.put("app", appName);
                entry.put("pkg", pkg);
                entry.put("source", "accessibility");

                synchronized (logLock) {
                    // 追加一行 JSONL
                    FileOutputStream fos = new FileOutputStream(localLogFile, true);
                    BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos, "UTF-8"));
                    bw.write(entry.toString());
                    bw.newLine();
                    bw.close();
                    // 限制文件行数（保留最后 MAX_LOG_LINES 行）
                    trimLogFile();
                }
            } catch (Exception e) {
                Log.e(TAG, "saveActivityLog failed: " + e.getMessage());
            }
        }, "ActivityLogWriter").start();
    }

    private String resolveAppName(String pkg) {
        if (pkg == null) return "未知";
        String name = APP_NAMES.get(pkg);
        if (name != null) return name;
        int idx = pkg.lastIndexOf('.');
        String fallback = idx >= 0 ? pkg.substring(idx + 1) : pkg;
        if (fallback.length() < 3) return "系统";
        return Character.toUpperCase(fallback.charAt(0)) + fallback.substring(1);
    }

    private void trimLogFile() {
        try {
            // 简单策略：文件超过 MAX_LOG_LINES * 80 字节时截断前面一半
            if (localLogFile.length() > MAX_LOG_LINES * 80L) {
                java.util.List<String> lines = java.nio.file.Files.readAllLines(localLogFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                if (lines.size() > MAX_LOG_LINES) {
                    List<String> kept = lines.subList(lines.size() - MAX_LOG_LINES, lines.size());
                    File tmp = new File(localLogFile.getParent(), "app_switches.jsonl.tmp");
                    java.nio.file.Files.write(tmp.toPath(), kept, java.nio.charset.StandardCharsets.UTF_8);
                    localLogFile.delete();
                    tmp.renameTo(localLogFile);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "trimLogFile failed: " + e.getMessage());
        }
    }

    /** 供 WebViewActivity 读取本地日志文件 */
    public static File getLocalLogFile(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "activity");
        return new File(dir, "app_switches.jsonl");
    }

    // ── 屏幕截图 ───────────────────────────────

    private void queueCapture(String app, String reason, boolean force, long delayMs) {
        if (!serviceActive) return;
        long now = System.currentTimeMillis();
        long minInterval = force ? FORCE_CAPTURE_INTERVAL_MS : MIN_CAPTURE_INTERVAL_MS;
        if (now - lastCaptureStartedAt < minInterval) {
            Log.d(TAG, "screenshot throttled reason=" + reason);
            return;
        }
        lastCaptureStartedAt = now;
        String targetApp = (app == null || app.isEmpty()) ? lastPackageName : app;
        long safeDelay = Math.max(0, delayMs);
        mainHandler.postDelayed(() -> {
            if (serviceActive) takeAndUploadScreenshot(targetApp, reason);
        }, safeDelay);
    }

    private boolean isPhoneUnlockedForCapture() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH && !pm.isInteractive()) return false;
            KeyguardManager kg = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (kg != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && kg.isDeviceLocked()) return false;
                if (kg.isKeyguardLocked()) return false;
            }
        } catch (Exception e) { Log.w(TAG, "lock state check failed: " + e.getMessage()); return false; }
        return true;
    }

    private void takeAndUploadScreenshot(String app, String reason) {
        if (!serviceActive) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            postSkip("accessibility_api_unavailable", app, false);
            return;
        }
        if (!isPhoneUnlockedForCapture()) {
            postSkip("locked", app, true);
            return;
        }

        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult screenshot) {
                if (!serviceActive) return;
                Bitmap wrapped = null;
                Bitmap copy = null;
                Bitmap scaled = null;
                HardwareBuffer buffer = null;
                try {
                    buffer = screenshot.getHardwareBuffer();
                    ColorSpace colorSpace = screenshot.getColorSpace();
                    wrapped = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
                    if (wrapped == null) { postSkip("accessibility_empty_bitmap", app, false); return; }
                    copy = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                    int width = copy.getWidth();
                    int height = copy.getHeight();
                    float scale = Math.min(1f, 1080f / Math.max(width, height));
                    if (scale < 1f) {
                        int sw = Math.max(1, Math.round(width * scale));
                        int sh = Math.max(1, Math.round(height * scale));
                        scaled = Bitmap.createScaledBitmap(copy, sw, sh, true);
                    } else { scaled = copy; }
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    scaled.compress(Bitmap.CompressFormat.JPEG, 82, out);
                    String b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                    uploadBase64(b64, app, reason);
                } catch (Exception e) {
                    Log.e(TAG, "accessibility screenshot failed: " + e.getMessage());
                    postSkip("accessibility_capture_failed", app, false);
                } finally {
                    if (buffer != null) try { buffer.close(); } catch (Exception ignored) {}
                    if (copy != null && copy != scaled) copy.recycle();
                    if (scaled != null) scaled.recycle();
                }
            }
            @Override
            public void onFailure(int errorCode) {
                if (!serviceActive) return;
                postSkip("accessibility_error_" + errorCode, app, false);
            }
        });
    }

    private String getHttpBase() {
        if (serverHttpBase != null && !serverHttpBase.isEmpty()) return serverHttpBase;
        SharedPreferences prefs = getSharedPreferences("aion_prefs", MODE_PRIVATE);
        String saved = prefs.getString("saved_url", "http://192.168.1.92:8080/chat");
        return saved.replace("ws://", "http://").replace("wss://", "https://").replace("/ws", "").replace("/chat", "");
    }

    private void uploadBase64(String b64, String app, String reason) {
        new Thread(() -> uploadBase64OnBackground(b64, app, reason), "AionAccessibilityUpload").start();
    }

    private void uploadBase64OnBackground(String b64, String app, String reason) {
        String url = getHttpBase() + "/api/phone-screen/upload";
        try {
            JSONObject body = new JSONObject();
            body.put("image_base64", b64);
            body.put("timestamp", System.currentTimeMillis() / 1000.0);
            body.put("app", app == null ? "" : app);
            body.put("locked", false);
            body.put("reason", reason == null ? "" : reason);
            body.put("source", "accessibility");
            MediaType jsonType = MediaType.get("application/json; charset=utf-8");
            RequestBody reqBody = RequestBody.create(body.toString(), jsonType);
            Request req = new Request.Builder().url(url).post(reqBody).build();
            try (Response resp = client.newCall(req).execute()) {
                Log.i(TAG, "accessibility phone screen uploaded -> " + resp.code() + " " + url);
            }
        } catch (Exception e) {
            Log.e(TAG, "accessibility upload failed: " + e.getClass().getSimpleName() + ":" + e.getMessage() + " url=" + url);
        }
    }

    private void postSkip(String reason, String app, boolean locked) {
        new Thread(() -> postSkipOnBackground(reason, app, locked), "AionAccessibilitySkip").start();
    }

    private void postSkipOnBackground(String reason, String app, boolean locked) {
        String url = getHttpBase() + "/api/phone-screen/skip";
        try {
            JSONObject body = new JSONObject();
            body.put("reason", reason);
            body.put("app", app == null ? "" : app);
            body.put("locked", locked);
            MediaType jsonType = MediaType.get("application/json; charset=utf-8");
            RequestBody reqBody = RequestBody.create(body.toString(), jsonType);
            Request req = new Request.Builder().url(url).post(reqBody).build();
            try (Response resp = client.newCall(req).execute()) {
                Log.d(TAG, "accessibility phone screen skipped " + reason + " -> " + resp.code());
            }
        } catch (Exception e) {
            Log.d(TAG, "accessibility skip report failed: " + e.getClass().getSimpleName() + ":" + e.getMessage() + " url=" + url);
        }
    }
}
