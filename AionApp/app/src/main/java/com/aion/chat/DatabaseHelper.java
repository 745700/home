package com.aion.chat;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * 本地 SQLite 数据库 — 存储活动日志和哨兵快照
 *
 * 表 activity_logs:
 *   id          INTEGER PRIMARY KEY AUTOINCREMENT
 *   timestamp   INTEGER  -- Unix 秒
 *   app         TEXT     -- App 中文名
 *   pkg         TEXT     -- 包名
 *   duration    INTEGER  -- 使用时长（秒），仅结束事件有值
 *   source      TEXT     -- "usagestats" | "accessibility"
 *
 * 表 sentinel_alerts:
 *   id          INTEGER PRIMARY KEY AUTOINCREMENT
 *   timestamp   INTEGER  -- Unix 秒
 *   type        TEXT     -- "motion" | "sos" | "fence_enter" | "fence_leave"
 *   frame_b64   TEXT     -- 告警帧 base64（JPEG）
 *   description TEXT     -- 描述文本
 */
public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "aion_local.db";
    private static final int DB_VERSION = 1;

    public static final String TABLE_ACTIVITY = "activity_logs";
    public static final String TABLE_ALERTS  = "sentinel_alerts";

    public DatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_ACTIVITY + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "timestamp INTEGER NOT NULL,"
                + "app TEXT NOT NULL,"
                + "pkg TEXT NOT NULL DEFAULT '',"
                + "duration INTEGER DEFAULT 0,"
                + "source TEXT DEFAULT 'accessibility'"
                + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_activity_ts ON " + TABLE_ACTIVITY + "(timestamp)");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_ALERTS + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "timestamp INTEGER NOT NULL,"
                + "type TEXT NOT NULL,"
                + "frame_b64 TEXT,"
                + "description TEXT"
                + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_alerts_ts ON " + TABLE_ALERTS + "(timestamp)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 暂时不做升级，保持简单
    }

    /** 清理 N 小时之前的旧数据 */
    public void cleanupOldData(int keepHours) {
        SQLiteDatabase db = getWritableDatabase();
        long cutoff = (System.currentTimeMillis() / 1000) - (keepHours * 3600L);
        db.delete(TABLE_ACTIVITY, "timestamp < ?", new String[]{String.valueOf(cutoff)});
        db.delete(TABLE_ALERTS,  "timestamp < ?", new String[]{String.valueOf(cutoff)});
    }
}
