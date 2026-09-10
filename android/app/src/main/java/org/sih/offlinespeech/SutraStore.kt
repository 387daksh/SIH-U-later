package org.sih.offlinespeech

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import java.io.File

data class PendingFrame(val key: String, val messageId: String, val bytes: ByteArray)

class SutraStore(context: Context) : SQLiteOpenHelper(context, "sutra.db", null, 2), AutoCloseable {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE outbox(frame_key TEXT PRIMARY KEY, message_id TEXT NOT NULL, frame BLOB NOT NULL, created_ms INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX outbox_message_id ON outbox(message_id)")
        db.execSQL("CREATE TABLE receipts(message_id TEXT PRIMARY KEY, created_ms INTEGER NOT NULL, played INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE metrics(id INTEGER PRIMARY KEY AUTOINCREMENT, message_id TEXT, event TEXT NOT NULL, elapsed_ns INTEGER NOT NULL, wall_ms INTEGER NOT NULL, detail TEXT NOT NULL)")
        db.execSQL("CREATE TABLE alert_log(id INTEGER PRIMARY KEY AUTOINCREMENT, message_id TEXT, sent_ms INTEGER NOT NULL, detail TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS alert_log(id INTEGER PRIMARY KEY AUTOINCREMENT, message_id TEXT, sent_ms INTEGER NOT NULL, detail TEXT NOT NULL)")
        }
    }

    @Synchronized
    fun enqueue(message: SutraMessage, bytes: ByteArray) {
        val values = ContentValues().apply {
            put("frame_key", "${message.id}:${message.type.wire}")
            put("message_id", message.id.toString())
            put("frame", bytes)
            put("created_ms", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("outbox", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        writableDatabase.execSQL("DELETE FROM outbox WHERE frame_key NOT IN (SELECT frame_key FROM outbox ORDER BY created_ms DESC LIMIT 256)")
    }

    @Synchronized
    fun pending(): List<PendingFrame> {
        val output = ArrayList<PendingFrame>()
        readableDatabase.query(
            "outbox", arrayOf("frame_key", "message_id", "frame"),
            null, null, null, null, "created_ms ASC", "256",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                output += PendingFrame(cursor.getString(0), cursor.getString(1), cursor.getBlob(2))
            }
        }
        return output
    }

    @Synchronized
    fun markAttempt(key: String) {
        writableDatabase.execSQL("UPDATE outbox SET attempts=attempts+1 WHERE frame_key=?", arrayOf(key))
    }

    @Synchronized
    fun acknowledge(messageId: String) {
        writableDatabase.delete("outbox", "message_id=?", arrayOf(messageId))
    }

    @Synchronized
    fun recordReceipt(messageId: String): Boolean {
        val values = ContentValues().apply {
            put("message_id", messageId)
            put("created_ms", System.currentTimeMillis())
        }
        val inserted = writableDatabase.insertWithOnConflict("receipts", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        writableDatabase.execSQL("DELETE FROM receipts WHERE message_id NOT IN (SELECT message_id FROM receipts ORDER BY created_ms DESC LIMIT 2048)")
        return inserted
    }

    @Synchronized
    fun markPlayed(messageId: String) {
        writableDatabase.execSQL("UPDATE receipts SET played=1 WHERE message_id=?", arrayOf(messageId))
    }

    @Synchronized
    fun metric(messageId: String?, event: String, detail: String = "") {
        writableDatabase.insert("metrics", null, ContentValues().apply {
            put("message_id", messageId)
            put("event", event)
            put("elapsed_ns", SystemClock.elapsedRealtimeNanos())
            put("wall_ms", System.currentTimeMillis())
            put("detail", detail)
        })
        writableDatabase.execSQL("DELETE FROM metrics WHERE id NOT IN (SELECT id FROM metrics ORDER BY id DESC LIMIT 10000)")
    }

    @Synchronized
    fun exportMetrics(target: File): Int {
        target.parentFile?.mkdirs()
        var count = 0
        target.bufferedWriter().use { output ->
            output.appendLine("message_id,event,elapsed_ns,wall_ms,detail")
            readableDatabase.query(
                "metrics", arrayOf("message_id", "event", "elapsed_ns", "wall_ms", "detail"),
                null, null, null, null, "id ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val columns = listOf(cursor.getString(0) ?: "", cursor.getString(1), cursor.getLong(2).toString(), cursor.getLong(3).toString(), cursor.getString(4))
                    output.appendLine(columns.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" })
                    count++
                }
            }
        }
        return count
    }

    @Synchronized
    fun recordAlert(messageId: String = "", detail: String = "") {
        val values = ContentValues().apply {
            put("message_id", messageId)
            put("sent_ms", System.currentTimeMillis())
            put("detail", detail)
        }
        writableDatabase.insert("alert_log", null, values)
    }

    @Synchronized
    fun monthlyAlertCount(): Int {
        val monthAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000L)
        readableDatabase.rawQuery("SELECT COUNT(*) FROM alert_log WHERE sent_ms > ?", arrayOf(monthAgo.toString())).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    @Synchronized
    fun clearStalePendingFrames(maxAgeMs: Long = 60_000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        // Wire type 3 is ALERT_CORE (${message.id}:${message.type.wire})
        // 1. Remove pending frames that were already transmitted on a previous connection (attempts > 0) unless it's an ALERT_CORE
        writableDatabase.delete("outbox", "attempts > 0 AND frame_key NOT LIKE '%:3'", null)
        // 2. Remove any normal speech messages older than maxAgeMs
        writableDatabase.delete("outbox", "created_ms < ? AND frame_key NOT LIKE '%:3'", arrayOf(cutoff.toString()))
    }

    @Synchronized
    fun clearNonAlertOutbox() {
        writableDatabase.delete("outbox", "frame_key NOT LIKE '%:3'", null)
    }
}
