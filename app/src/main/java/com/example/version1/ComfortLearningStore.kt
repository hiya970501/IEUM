package com.example.version1

import android.content.ContentValues
import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject

/** Small private database. No raw sensor series, names, pairing keys or full network bodies. */
class ComfortLearningStore(context: Context, private val databaseName: String = "comfort_learning.db") : java.io.Closeable {
    private val context = context.applicationContext
    private val database = lazy { ProtectedLearningDatabase.open(context.applicationContext, databaseName, ::onConfigure, ::onCreate) }
    val readableDatabase: SQLiteDatabase get() = database.value
    val writableDatabase: SQLiteDatabase get() = database.value
    @Synchronized fun prepareProtectedStorage() { database.value }
    internal val storageOpened: Boolean get() = database.isInitialized()
    internal fun needsPreparation(consent: Boolean, deletionPending: Boolean): Boolean =
        ProtectedLearningDatabase.needsPreparation(context, databaseName, consent, deletionPending)
    @Synchronized override fun close() { if (database.isInitialized()) database.value.close() }
    private fun onConfigure(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA secure_delete=ON", null).use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 1) { "Secure deletion unavailable" }
        }
        db.execSQL("PRAGMA cache_size=-256")
        db.execSQL("PRAGMA temp_store=MEMORY")
        db.rawQuery("PRAGMA max_page_count=8192", null).use { it.moveToFirst() }
    }
    private fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cases (id INTEGER PRIMARY KEY, profile TEXT NOT NULL, vehicle TEXT NOT NULL, bucket TEXT NOT NULL, session TEXT NOT NULL, phase TEXT NOT NULL, at INTEGER NOT NULL, satisfied INTEGER NOT NULL, payload TEXT NOT NULL, UNIQUE(profile,session,phase))")
        db.execSQL("CREATE INDEX context_lookup ON cases(profile,vehicle,bucket,at)")
    }
    @Synchronized fun save(case: ComfortCase, allowed: () -> Boolean = { true }) {
        if (!allowed()) return
        require(case.context.valid() && case.setting.valid() && case.validFeedback() && case.session.length in 1..80 && case.at > 0)
        val c = case.context; val s = case.setting
        val json = JSONObject().put("activity", c.activity).put("wet", c.wet)
            .put("outdoor", c.outdoorC).put("humidity", c.humidity).put("radiation", c.radiation)
            .put("cabin", c.cabinC).put("bpm", c.bpm).put("minutes", c.minutesAfterExercise)
            .put("weatherTime", c.weatherTime).put("temperature", s.temperature)
            .put("fan", s.fan).put("heat", s.heat).put("vent", s.ventilation)
            .put("steeringHeat",s.steeringHeat).put("rating",case.rating).toString()
        require(json.length <= 2048)
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(db.insertWithOnConflict("cases", null, ContentValues().apply {
                put("profile",c.profile); put("vehicle",c.vehicle); put("bucket",c.bucket)
                put("session",case.session); put("phase",c.phase.name); put("at",case.at)
                put("satisfied",if (case.satisfied) 1 else 0); put("payload",json)
            }, SQLiteDatabase.CONFLICT_REPLACE) >= 0)
            // Keep at least three representatives of rare climate/phase buckets when pruning.
            db.execSQL("DELETE FROM cases WHERE id IN (SELECT a.id FROM cases a WHERE a.profile=? AND (SELECT COUNT(*) FROM cases b WHERE b.profile=a.profile AND b.bucket=a.bucket AND b.at>=a.at)>3 ORDER BY a.at ASC LIMIT MAX(0,(SELECT COUNT(*)-1200 FROM cases WHERE profile=?)))", arrayOf(c.profile,c.profile))
            db.execSQL("DELETE FROM cases WHERE id IN (SELECT id FROM cases WHERE profile=? ORDER BY at ASC LIMIT MAX(0,(SELECT COUNT(*)-1200 FROM cases WHERE profile=?)))", arrayOf(c.profile,c.profile))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun candidates(context: ComfortContext): List<ComfortCase> {
        val result = ArrayList<ComfortCase>(AdaptiveComfort.MAX_CANDIDATES)
        readableDatabase.rawQuery("SELECT session,at,phase,satisfied,payload FROM cases WHERE profile=? AND vehicle=? ORDER BY CASE WHEN bucket=? THEN 0 ELSE 1 END,at DESC LIMIT 96",
            arrayOf(context.profile,context.vehicle,context.bucket)).use { cursor ->
            while (cursor.moveToNext()) runCatching {
                val j=JSONObject(cursor.getString(4))
                fun f(key:String)=if(j.isNull(key)) null else j.getDouble(key).toFloat()
                fun n(key:String)=if(j.isNull(key)) null else j.getInt(key)
                result += ComfortCase(cursor.getString(0),cursor.getLong(1),context.copy(
                    activity=if(j.isNull("activity")) null else j.getString("activity"),wet=j.getBoolean("wet"),
                    phase=ComfortPhase.valueOf(cursor.getString(2)),outdoorC=f("outdoor"),humidity=f("humidity"),
                    radiation=f("radiation"),cabinC=f("cabin"),bpm=n("bpm"),minutesAfterExercise=n("minutes"),
                    weatherTime=if(j.isNull("weatherTime")) null else j.getLong("weatherTime")),
                    LearnedSetting(j.getDouble("temperature").toFloat(),j.getInt("fan"),n("heat"),n("vent"),n("steeringHeat")),cursor.getInt(3)==1,n("rating"))
            }
        }; return result
    }
    @Synchronized fun count(profile:String): Int = readableDatabase.rawQuery("SELECT COUNT(DISTINCT session) FROM cases WHERE profile=?",arrayOf(profile)).use { it.moveToFirst(); it.getInt(0) }
    @Synchronized fun delete(profile:String) { writableDatabase.delete("cases","profile=?",arrayOf(profile)) }
}
