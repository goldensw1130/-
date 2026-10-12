package com.hongdangmu.mobile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.UUID

/** Offline-first event queue for Android sync. Remote events are stored separately;
 * they are not silently merged into legacy business tables. */
data class SyncNote(val id: String, val title: String, val body: String, val updatedAt: String)

class SyncLocalStore(context: Context) : SQLiteOpenHelper(context, "hongdangmu_sync.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE outbox(event_id TEXT PRIMARY KEY, entity_type TEXT NOT NULL, entity_id TEXT NOT NULL, revision INTEGER NOT NULL, payload_json TEXT NOT NULL, origin_device TEXT NOT NULL, created_at TEXT NOT NULL)")
        db.execSQL("CREATE TABLE remote_events(event_id TEXT PRIMARY KEY, entity_type TEXT NOT NULL, entity_id TEXT NOT NULL, revision INTEGER NOT NULL, payload_json TEXT NOT NULL, origin_device TEXT NOT NULL, created_at TEXT NOT NULL)")
        db.execSQL("CREATE TABLE sync_notes(id TEXT PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL, updated_at TEXT NOT NULL)")
        db.execSQL("CREATE TABLE sync_note_versions(id TEXT PRIMARY KEY, revision INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if (oldVersion < 2) db.execSQL("CREATE TABLE IF NOT EXISTS sync_note_versions(id TEXT PRIMARY KEY, revision INTEGER NOT NULL)") }

    private fun nextRevision(db: SQLiteDatabase, id: String): Int {
        val current = db.rawQuery("SELECT revision FROM sync_note_versions WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val next = current + 1
        db.insertWithOnConflict("sync_note_versions", null, ContentValues().apply { put("id", id); put("revision", next) }, SQLiteDatabase.CONFLICT_REPLACE)
        return next
    }

    private fun queueNoteEvent(db: SQLiteDatabase, id: String, operation: String, title: String, body: String, now: String): String {
        val eventId = UUID.randomUUID().toString()
        val revision = nextRevision(db, id)
        val payload = JSONObject().put("operation", operation).put("record", JSONObject()
            .put("id", id).put("title", title).put("body", body).put("updated_at", now)).put("schema_version", 1)
        db.insertOrThrow("outbox", null, ContentValues().apply {
            put("event_id", eventId); put("entity_type", "sync_notes"); put("entity_id", id)
            put("revision", revision); put("payload_json", payload.toString()); put("origin_device", "android-local"); put("created_at", now)
        })
        return eventId
    }

    @Synchronized fun addNote(title: String, body: String): String {
        val cleanTitle = title.trim().take(200)
        val cleanBody = body.trim().take(8000)
        require(cleanTitle.isNotEmpty()) { "메모 제목을 입력하세요" }
        require(cleanBody.isNotEmpty()) { "메모 내용을 입력하세요" }
        val id = UUID.randomUUID().toString()
        val now = java.time.Instant.now().toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("sync_notes", null, ContentValues().apply { put("id", id); put("title", cleanTitle); put("body", cleanBody); put("updated_at", now) })
            val eventId = queueNoteEvent(db, id, "upsert", cleanTitle, cleanBody, now)
            db.setTransactionSuccessful()
            return eventId
        } finally { db.endTransaction() }
    }

    @Synchronized fun updateNote(id: String, title: String, body: String): String {
        val cleanTitle = title.trim().take(200); val cleanBody = body.trim().take(8000)
        require(cleanTitle.isNotEmpty()) { "메모 제목을 입력하세요" }; require(cleanBody.isNotEmpty()) { "메모 내용을 입력하세요" }
        val now = java.time.Instant.now().toString(); val db = writableDatabase
        db.beginTransaction()
        try {
            require(db.update("sync_notes", ContentValues().apply { put("title", cleanTitle); put("body", cleanBody); put("updated_at", now) }, "id=?", arrayOf(id)) == 1) { "메모를 찾을 수 없습니다" }
            val eventId = queueNoteEvent(db, id, "upsert", cleanTitle, cleanBody, now)
            db.setTransactionSuccessful(); return eventId
        } finally { db.endTransaction() }
    }

    @Synchronized fun deleteNote(id: String): String {
        val db = writableDatabase; val now = java.time.Instant.now().toString()
        db.beginTransaction()
        try {
            val prior = db.rawQuery("SELECT title,body FROM sync_notes WHERE id=?", arrayOf(id)).use { c -> if (c.moveToFirst()) Pair(c.getString(0), c.getString(1)) else null }
            require(prior != null) { "메모를 찾을 수 없습니다" }
            db.delete("sync_notes", "id=?", arrayOf(id))
            val eventId = queueNoteEvent(db, id, "delete", prior.first, prior.second, now)
            db.setTransactionSuccessful(); return eventId
        } finally { db.endTransaction() }
    }

    @Synchronized fun pending(): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        readableDatabase.rawQuery("SELECT * FROM outbox ORDER BY created_at LIMIT 200", null).use { c ->
            while (c.moveToNext()) result.add(JSONObject().put("event_id", c.getString(0)).put("entity_type", c.getString(1))
                .put("entity_id", c.getString(2)).put("revision", c.getInt(3)).put("payload", JSONObject(c.getString(4)))
                .put("origin_device", c.getString(5)).put("created_at", c.getString(6)))
        }
        return result
    }

    @Synchronized fun acknowledge(ids: Set<String>): Int {
        val db = writableDatabase; var count = 0
        db.beginTransaction()
        try { ids.forEach { count += db.delete("outbox", "event_id=?", arrayOf(it)) }; db.setTransactionSuccessful() }
        finally { db.endTransaction() }
        return count
    }

    @Synchronized fun storeRemote(event: JSONObject) {
        val eventId = event.getString("event_id")
        val entityType = event.getString("entity_type")
        val entityId = event.getString("entity_id")
        val payload = event.getJSONObject("payload")
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("event_id", eventId); put("entity_type", entityType)
                put("entity_id", entityId); put("revision", event.getInt("revision"))
                put("payload_json", payload.toString()); put("origin_device", event.getString("origin_device"))
                put("created_at", event.optString("created_at", ""))
            }
            val inserted = db.insertWithOnConflict("remote_events", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            if (inserted && entityType == "sync_notes") {
                val incomingRevision = event.getInt("revision")
                require(incomingRevision > 0) { "메모 이벤트 revision은 1 이상이어야 합니다" }
                val oldRevision = db.rawQuery("SELECT revision FROM sync_note_versions WHERE id=?", arrayOf(entityId)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
                val record = payload.optJSONObject("record") ?: JSONObject()
                require(record.optString("id") == entityId) { "메모 이벤트 ID가 일치하지 않습니다" }

                // A previously received newer revision must never be overwritten by a late,
                // out-of-order event. Store the event for idempotency, but apply only newer state.
                if (incomingRevision > oldRevision) {
                    db.insertWithOnConflict("sync_note_versions", null, ContentValues().apply {
                        put("id", entityId); put("revision", incomingRevision)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                    when (payload.optString("operation")) {
                        "delete" -> db.delete("sync_notes", "id=?", arrayOf(entityId))
                        "insert", "update", "upsert" -> {
                            val title = record.optString("title", "").take(200)
                            val body = record.optString("body", "").take(8000)
                            val updated = record.optString("updated_at", event.optString("created_at", ""))
                            require(title.isNotBlank() && body.isNotBlank()) { "수신 메모 내용이 비어 있습니다" }
                            db.insertWithOnConflict("sync_notes", null, ContentValues().apply {
                                put("id", entityId); put("title", title); put("body", body); put("updated_at", updated)
                            }, SQLiteDatabase.CONFLICT_REPLACE)
                        }
                        else -> throw IllegalArgumentException("지원하지 않는 메모 변경 유형입니다")
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun recentNotes(limit: Int = 100): List<SyncNote> {
        val result = mutableListOf<SyncNote>()
        readableDatabase.rawQuery("SELECT id,title,body,updated_at FROM sync_notes ORDER BY updated_at DESC LIMIT ?", arrayOf(limit.coerceIn(1, 100).toString())).use { c ->
            while (c.moveToNext()) result.add(SyncNote(c.getString(0), c.getString(1), c.getString(2), c.getString(3)))
        }
        return result
    }

    fun status(): JSONObject {
        val db = readableDatabase
        fun count(table: String): Int = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return JSONObject().put("pending", count("outbox")).put("received", count("remote_events")).put("notes", count("sync_notes"))
    }
    fun recentRemote(limit: Int = 10): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT entity_type, payload_json FROM remote_events ORDER BY created_at DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) + " · " + JSONObject(c.getString(1)).optJSONObject("record")?.optString("title", "데이터").orEmpty())
        }
        return out
    }
}
