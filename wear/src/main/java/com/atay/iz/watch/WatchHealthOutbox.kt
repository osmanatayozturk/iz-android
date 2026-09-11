package com.atay.iz.watch

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.atay.iz.wearprotocol.*

/** Private durable queue; transport success never deletes a measurement, only a phone commit ACK does. */
internal class WatchHealthOutbox(context: Context, databaseName: String = "watch-health.db") : SQLiteOpenHelper(context, databaseName, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, journeyId TEXT NOT NULL, createdAt INTEGER NOT NULL, phoneAt INTEGER NOT NULL, elapsedAt INTEGER NOT NULL, boot INTEGER NOT NULL, lastSequence INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE readings (sessionId TEXT NOT NULL, sequence INTEGER NOT NULL, startAt INTEGER NOT NULL, endAt INTEGER NOT NULL, type INTEGER NOT NULL, value REAL NOT NULL, storedAt INTEGER NOT NULL, PRIMARY KEY(sessionId,sequence))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    data class Session(val id: String, val journeyId: String, val createdAt: Long, val anchor: HealthClockAnchor, val boot: Int, val sequence: Long)
    @Synchronized fun saveSession(value: Session) {
        val cv = ContentValues().apply { put("id", value.id); put("journeyId", value.journeyId); put("createdAt", value.createdAt)
            put("phoneAt", value.anchor.phoneAt); put("elapsedAt", value.anchor.elapsedAt); put("boot", value.boot); put("lastSequence", value.sequence) }
        writableDatabase.insertWithOnConflict("sessions", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }
    @Synchronized fun session(id: String): Session? = readableDatabase.rawQuery("SELECT * FROM sessions WHERE id=?", arrayOf(id)).use { c ->
        if (!c.moveToFirst()) null else Session(c.getString(0), c.getString(1), c.getLong(2), HealthClockAnchor(c.getLong(3), c.getLong(4)), c.getInt(5), c.getLong(6)) }
    @Synchronized fun add(sessionId: String, reading: HealthReading, now: Long) {
        val db = writableDatabase; db.beginTransaction()
        try {
            db.insertOrThrow("readings", null, ContentValues().apply { put("sessionId", sessionId); put("sequence", reading.sequence)
                put("startAt", reading.startAt); put("endAt", reading.endAt); put("type", reading.type.ordinal); put("value", reading.value); put("storedAt", now) })
            db.execSQL("UPDATE sessions SET lastSequence=MAX(lastSequence,?) WHERE id=?", arrayOf<Any>(reading.sequence, sessionId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun next(watchId: String): HealthBatch? {
        val id = readableDatabase.rawQuery("SELECT sessionId FROM readings ORDER BY storedAt,sequence LIMIT 1", null).use {
            if (it.moveToFirst()) it.getString(0) else null } ?: return null
        val rows = mutableListOf<HealthReading>()
        readableDatabase.rawQuery("SELECT sequence,type,startAt,endAt,value FROM readings WHERE sessionId=? ORDER BY sequence LIMIT ${WatchHealthProtocol.MAX_READINGS}", arrayOf(id)).use {
            while (it.moveToNext()) rows += HealthReading(it.getLong(0), HealthReadingType.entries[it.getInt(1)], it.getLong(2), it.getLong(3), it.getDouble(4)) }
        return HealthBatch(watchId, id, rows)
    }
    @Synchronized fun acknowledge(value: HealthAck) {
        if (value.terminal) writableDatabase.delete("readings", "sessionId=?", arrayOf(value.sessionId))
        else writableDatabase.delete("readings", "sessionId=? AND sequence<=?", arrayOf(value.sessionId, value.throughSequence.toString()))
    }
    @Synchronized fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM readings", null).use { it.moveToFirst(); it.getInt(0) }
    @Synchronized fun clear() {
        val db = writableDatabase; db.beginTransaction()
        try { db.delete("readings", null, null); db.delete("sessions", null, null); db.setTransactionSuccessful() }
        finally { db.endTransaction() }
    }
    @Synchronized fun prune(now: Long): Int {
        val db = writableDatabase
        var removed = db.delete("readings", "storedAt<?", arrayOf((now - 7 * 86_400_000L).toString()))
        val excess = (count() - 100_000).coerceAtLeast(0)
        if (excess > 0) { db.execSQL("DELETE FROM readings WHERE rowid IN (SELECT rowid FROM readings ORDER BY storedAt,sequence LIMIT ?)", arrayOf(excess)); removed += excess }
        db.execSQL("DELETE FROM sessions WHERE id NOT IN (SELECT sessionId FROM readings) AND createdAt<?", arrayOf(now - 7 * 86_400_000L))
        return removed
    }
}
