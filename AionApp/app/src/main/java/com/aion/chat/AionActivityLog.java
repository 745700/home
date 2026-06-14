package com.aion.chat;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 活动日志 JS 桥 — 离线版
 *
 * 数据来源（按优先级）:
 *   1. UsageStatsManager（Android 5.0+，需要"使用情况访问权限"）
 *   2. 本地 JSONL 文件（无障碍服务写入的 app 切换记录）
 *
 * 前端调用:
 *   window.AionActivity.hasPermission()       → boolean
 *   window.AionActivity.requestPermission()    → void（跳转设置页）
 *   window.AionActivity.getRecent(hours)      → JSONArray
 *   window.AionActivity.getDates()            → JSONArray
 *   window.AionActivity.getByDate(date)       → JSONArray
 *   window.AionActivity.getSummary(hours)     → JSONObject
 *   window.AionActivity.clear()               → void
 */
public class AionActivityLog {

    private static final String TAG = "AionActivityLog";
    private static final int MAX_LOG_LINES = 1000;
    private static final int KEEP_HOURS = 72;

    private final WebView webView;

    public AionActivityLog(WebView webView) {
        this.webView = webView;
    }

    private File getLogFile() {
        File dir = new File(webView.getContext().getFilesDir(), "activity");
        return new File(dir, "app_switches.jsonl");
    }

    // ── 权限 ──────────────────────────────────────────

    @JavascriptInterface
    public boolean hasPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return false;
        try {
            AppOpsManager aom = (AppOpsManager) webView.getContext()
                    .getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) return false;
            int mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), webView.getContext().getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            Log.e(TAG, "hasPermission: " + e.getMessage());
            return false;
        }
    }

    @JavascriptInterface
    public void requestPermission() {
        try {
            android.content.Intent intent = new android.content.Intent(
                    Settings.ACTION_USAGE_ACCESS_SETTINGS);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            webView.getContext().startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "requestPermission: " + e.getMessage());
        }
    }

    // ── 读数据 ──────────────────────────────────────────

    /** 读本地 JSONL 文件并解析为 Entry 列表 */
    private List<LogEntry> readLogFile() {
        List<LogEntry> entries = new ArrayList<>();
        File f = getLogFile();
        if (!f.exists()) return entries;
        try (FileInputStream fis = new FileInputStream(f);
             InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8);
             BufferedReader br = new BufferedReader(isr)) {
            String line;
            while ((line = br.readLine()) != null) {
                try {
                    JSONObject o = new JSONObject(line.trim());
                    LogEntry e = new LogEntry();
                    e.timestamp = o.optLong("timestamp", 0);
                    e.app = o.optString("app", "");
                    e.pkg = o.optString("pkg", "");
                    e.source = o.optString("source", "accessibility");
                    if (e.timestamp > 0 && !e.app.isEmpty()) entries.add(e);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "readLogFile: " + e.getMessage());
        }
        return entries;
    }

    /** 返回最近 N 小时的 App 使用统计（合并同名 App） */
    @JavascriptInterface
    public String getRecent(int hours) {
        // 优先用 UsageStatsManager
        if (hasPermission()) {
            try { return getRecentFromUsageStats(hours); } catch (Exception e) { Log.e(TAG, "UsageStats failed: " + e.getMessage()); }
        }
        // 降级到本地日志
        return getRecentFromLog(hours);
    }

    private String getRecentFromUsageStats(int hours) {
        UsageStatsManager usm = (UsageStatsManager) webView.getContext()
                .getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return "[]";

        long end = System.currentTimeMillis();
        long start = end - (hours * 3600L * 1000);
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end);
        if (stats == null || stats.isEmpty()) return "[]";

        // 聚合同名 App 的总时长
        Map<String, Long> totalDur = new HashMap<>();
        Map<String, Long> lastUsed = new HashMap<>();
        Map<String, String> pkgMap = new HashMap<>();
        for (UsageStats s : stats) {
            if (s.getTotalTimeInForeground() < 5000) continue; // 忽略 <5秒
            String name = resolveAppName(s.getPackageName());
            if (name == null || name.equals("系统")) continue;
            totalDur.put(name, totalDur.getOrDefault(name, 0L) + s.getTotalTimeInForeground() / 1000);
            long lu = s.getLastTimeUsed() / 1000;
            if (!lastUsed.containsKey(name) || lu > lastUsed.get(name)) {
                lastUsed.put(name, lu);
                pkgMap.put(name, s.getPackageName());
            }
        }

        // 按最后使用时间倒序
        TreeMap<Long, JSONObject> sorted = new TreeMap<>(Collections.reverseOrder());
        for (String name : totalDur.keySet()) {
            JSONObject o = new JSONObject();
            try {
                o.put("app", name);
                o.put("pkg", pkgMap.get(name));
                o.put("duration", totalDur.get(name));
                o.put("count", 1);
                o.put("lastUsed", lastUsed.get(name));
                sorted.put(lastUsed.get(name), o);
            } catch (Exception ignored) {}
        }

        JSONArray arr = new JSONArray();
        for (JSONObject o : sorted.values()) arr.put(o);
        return arr.toString();
    }

    private String getRecentFromLog(int hours) {
        long cutoff = (System.currentTimeMillis() / 1000) - (hours * 3600L);
        Map<String, LogEntry> latest = new LinkedHashMap<>(); // 保持插入顺序
        List<LogEntry> entries = readLogFile();
        for (LogEntry e : entries) {
            if (e.timestamp < cutoff) continue;
            latest.put(e.app, e); // 保留最后一个
        }
        JSONArray arr = new JSONArray();
        for (LogEntry e : latest.values()) {
            JSONObject o = new JSONObject();
            try {
                o.put("app", e.app);
                o.put("pkg", e.pkg);
                o.put("duration", 0);
                o.put("count", 1);
                o.put("lastUsed", e.timestamp);
                arr.put(o);
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }

    /** 返回有记录的日期列表 */
    @JavascriptInterface
    public String getDates() {
        Set<String> dates = new java.util.LinkedHashSet<>();
        List<LogEntry> entries = readLogFile();
        for (LogEntry e : entries) {
            try {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
                dates.add(sdf.format(new java.util.Date(e.timestamp * 1000)));
            } catch (Exception ignored) {}
        }
        // 也从 UsageStats 补入
        if (hasPermission()) {
            try {
                UsageStatsManager usm = (UsageStatsManager) webView.getContext()
                        .getSystemService(Context.USAGE_STATS_SERVICE);
                if (usm != null) {
                    long now = System.currentTimeMillis();
                    List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY,
                            now - (30L * 86400 * 1000), now);
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
                    for (UsageStats s : stats) {
                        if (s.getTotalTimeInForeground() > 0) {
                            dates.add(sdf.format(new java.util.Date(s.getLastTimeUsed())));
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        JSONArray arr = new JSONArray();
        // 降序排列
        List<String> sorted = new ArrayList<>(dates);
        Collections.sort(sorted, Collections.reverseOrder());
        for (String d : sorted) arr.put(d);
        return arr.toString();
    }

    /** 返回指定日期的记录 */
    @JavascriptInterface
    public String getByDate(String date) {
        // date 格式: "2026-06-14"
        List<LogEntry> entries = readLogFile();
        JSONArray arr = new JSONArray();
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
        for (LogEntry e : entries) {
            try {
                String entryDate = sdf.format(new java.util.Date(e.timestamp * 1000));
                if (entryDate.equals(date)) {
                    JSONObject o = new JSONObject();
                    o.put("app", e.app);
                    o.put("pkg", e.pkg);
                    o.put("timestamp", e.timestamp);
                    o.put("duration", 0);
                    arr.put(o);
                }
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }

    /** 摘要统计 */
    @JavascriptInterface
    public String getSummary(int hours) {
        JSONObject result = new JSONObject();
        try {
            String recent = getRecent(hours);
            JSONArray arr = new JSONArray(recent);
            long totalDuration = 0;
            String mostUsed = "";
            long maxDur = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                long dur = o.optLong("duration", 0);
                totalDuration += dur;
                if (dur > maxDur) {
                    maxDur = dur;
                    mostUsed = o.optString("app", "");
                }
            }
            result.put("total", totalDuration);
            result.put("mostUsed", mostUsed);
            result.put("totalApps", arr.length());
        } catch (Exception e) {
            Log.e(TAG, "getSummary: " + e.getMessage());
        }
        return result.toString();
    }

    /** 清除所有本地日志 */
    @JavascriptInterface
    public void clear() {
        File f = getLogFile();
        if (f.exists()) f.delete();
    }

    // ── 工具 ──────────────────────────────────────────

    private String resolveAppName(String pkg) {
        if (pkg == null) return null;
        String name = APP_NAME_OVERRIDE.get(pkg);
        if (name != null) return name;
        int idx = pkg.lastIndexOf('.');
        String fallback = idx >= 0 ? pkg.substring(idx + 1) : pkg;
        if (fallback.length() < 3) return null;
        return Character.toUpperCase(fallback.charAt(0)) + fallback.substring(1);
    }

    private static final Map<String, String> APP_NAME_OVERRIDE = new java.util.HashMap<String, String>() {{
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

    // ── 内部类 ──────────────────────────────────────────

    private static class LogEntry {
        long timestamp;
        String app;
        String pkg;
        String source;
    }
}
