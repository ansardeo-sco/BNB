package com.ansardeo.bnb.demo.exec

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Persistence of Demo execution state. Separate from the Virtual engine's database: nothing of the Virtual data is touched. */
interface DemoExecStore {
    fun all(): List<DemoEntry>
    fun put(entry: DemoEntry)
    fun addEvent(atMillis: Long, text: String)
    fun events(limit: Int): List<Pair<Long, String>>
    /** Reason new entries are blocked (set by the fail-safe), or null. */
    fun halt(): String?
    fun setHalt(reason: String)
    fun clearHalt()
    fun get(id: String): DemoEntry? = all().firstOrNull { it.id == id }
    // Phase 3B: strategy cycles
    fun cycles(): List<DemoCycle>
    fun putCycle(cycle: DemoCycle)
    fun getCycle(id: String): DemoCycle? = cycles().firstOrNull { it.id == id }
}

class InMemoryExecStore : DemoExecStore {
    private val entries = LinkedHashMap<String, DemoEntry>()
    private val ev = ArrayList<Pair<Long, String>>()
    private var haltReason: String? = null
    private val cyc = LinkedHashMap<String, DemoCycle>()
    @Synchronized override fun cycles() = cyc.values.toList()
    @Synchronized override fun putCycle(cycle: DemoCycle) { cyc[cycle.id] = cycle }
    @Synchronized override fun all() = entries.values.toList()
    @Synchronized override fun put(entry: DemoEntry) { entries[entry.id] = entry }
    @Synchronized override fun addEvent(atMillis: Long, text: String) { ev.add(atMillis to text) }
    @Synchronized override fun events(limit: Int) = ev.takeLast(limit).reversed()
    @Synchronized override fun halt() = haltReason
    @Synchronized override fun setHalt(reason: String) { haltReason = reason }
    @Synchronized override fun clearHalt() { haltReason = null }
}

/** Own small SQLite file (bnb_demo_exec.db): entries as JSON rows, an event log and a key/value table for the halt flag. */
class SqliteExecStore(context: Context) : DemoExecStore {
    private class Helper(c: Context) : SQLiteOpenHelper(c.applicationContext, "bnb_demo_exec.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS demo_entries (id TEXT PRIMARY KEY NOT NULL, json TEXT NOT NULL, createdAt INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS demo_events (seq INTEGER PRIMARY KEY AUTOINCREMENT, atMillis INTEGER NOT NULL, text TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS demo_kv (k TEXT PRIMARY KEY NOT NULL, v TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS demo_cycles (id TEXT PRIMARY KEY NOT NULL, json TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { onCreate(db) /* additive only: existing tables are kept */ }
    }
    private val helper = Helper(context)

    @Synchronized override fun all(): List<DemoEntry> {
        val out = ArrayList<DemoEntry>()
        helper.readableDatabase.rawQuery("SELECT json FROM demo_entries ORDER BY createdAt ASC", null).use { c -> while (c.moveToNext()) DemoEntry.fromJson(c.getString(0))?.let { out += it } }
        return out
    }
    @Synchronized override fun put(entry: DemoEntry) {
        val v = ContentValues().apply { put("id", entry.id); put("json", entry.toJson()); put("createdAt", entry.createdAt) }
        helper.writableDatabase.insertWithOnConflict("demo_entries", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized override fun addEvent(atMillis: Long, text: String) {
        helper.writableDatabase.insert("demo_events", null, ContentValues().apply { put("atMillis", atMillis); put("text", text) })
    }
    @Synchronized override fun events(limit: Int): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        helper.readableDatabase.rawQuery("SELECT atMillis, text FROM demo_events ORDER BY seq DESC LIMIT ?", arrayOf(limit.toString())).use { c -> while (c.moveToNext()) out += c.getLong(0) to c.getString(1) }
        return out
    }
    @Synchronized override fun halt(): String? =
        helper.readableDatabase.rawQuery("SELECT v FROM demo_kv WHERE k = 'halt'", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    @Synchronized override fun setHalt(reason: String) {
        helper.writableDatabase.insertWithOnConflict("demo_kv", null, ContentValues().apply { put("k", "halt"); put("v", reason) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized override fun clearHalt() { helper.writableDatabase.delete("demo_kv", "k = ?", arrayOf("halt")) }
    @Synchronized override fun cycles(): List<DemoCycle> {
        val out = ArrayList<DemoCycle>()
        helper.readableDatabase.rawQuery("SELECT json FROM demo_cycles ORDER BY createdAt ASC", null).use { c -> while (c.moveToNext()) DemoCycle.fromJson(c.getString(0))?.let { out += it } }
        return out
    }
    @Synchronized override fun putCycle(cycle: DemoCycle) {
        val v = ContentValues().apply { put("id", cycle.id); put("json", cycle.toJson()); put("createdAt", cycle.createdAt) }
        helper.writableDatabase.insertWithOnConflict("demo_cycles", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
