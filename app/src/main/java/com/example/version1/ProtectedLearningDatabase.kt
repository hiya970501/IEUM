package com.example.version1

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom

/** SQLCipher encrypts data pages, indexes and journals. Legacy plaintext is read-only:
 * publish and verify the complete encrypted copy before persisting its cleanup marker.
 * A crash during legacy erasure resumes from that marker, never from half-erased data.
 */
internal object ProtectedLearningDatabase {
    private val gate = Any()
    private val library by lazy { System.loadLibrary("sqlcipher") }
    private const val COLUMNS = "id,profile,vehicle,bucket,session,phase,at,satisfied,payload"
    private const val MAX_LEGACY_BYTES = 64L * 1024 * 1024

    private val legacySuffixes = listOf("", "-journal", "-wal", "-shm")

    /** File inspection only: optional learning must not load SQLCipher just to stay OFF.
     * Legacy protection and durable privacy cleanup still run without learning consent.
     */
    fun needsPreparation(context: Context, name: String, consent: Boolean, deletionPending: Boolean): Boolean {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
        val path = context.getDatabasePath(name).path
        return consent || deletionPending || legacySuffixes.any { File(path + it).exists() }
    }

    fun open(context: Context, name: String, configure: (SQLiteDatabase) -> Unit,
             create: (SQLiteDatabase) -> Unit): SQLiteDatabase = synchronized(gate) {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
        val legacy = context.getDatabasePath(name)
        check(legacy.exists() || legacySuffixes.drop(1).none { File(legacy.path + it).exists() }) {
            "Orphan legacy learning journal; preserve for recovery"
        }
        library
        val target = context.getDatabasePath("$name.enc")
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val secrets = ProtectedPreferences.get(context, "learning_database_keys")
        var password = secrets.getString("key_$name", null)
        if (password == null) {
            check(!target.exists()) { "Protected learning key unavailable" }
            password = ByteArray(32).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            check(secrets.edit().putString("key_$name", password).commit()) { "Learning key persistence unconfirmed" }
        }
        val db = SQLiteDatabase.openOrCreateDatabase(target, password, null,
            DatabaseErrorHandler { _, _ -> throw IllegalStateException("Protected learning database unavailable") })
        try {
            configure(db)
            if (db.version == 0) {
                db.beginTransaction()
                try { create(db); db.version = 1; db.setTransactionSuccessful() }
                finally { db.endTransaction() }
            }
            check(db.version == 1) { "Unsupported protected learning database version" }
            val marker = secrets.getString("migration_$name", null)
            if (legacy.exists()) {
                check(marker != "new") { "Unexpected legacy learning database; preserve both copies" }
                if (marker == null) {
                    check(legacy.length() in 1..MAX_LEGACY_BYTES) { "Legacy learning database size rejected" }
                    val source = android.database.sqlite.SQLiteDatabase.openDatabase(legacy.path, null,
                        android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                        android.database.DatabaseErrorHandler { throw IllegalStateException("Legacy learning database unavailable") })
                    val expected = source.use {
                        check(source.version == 1) { "Unsupported legacy learning database version" }
                        // The legacy APK is stopped before upgrade. Compare both passes instead
                        // of trying to start a write transaction on a read-only connection.
                        run {
                            val snapshot = fingerprint(source.rawQuery("SELECT $COLUMNS FROM cases ORDER BY id", null))
                            db.beginTransaction()
                            try {
                                db.delete("cases", null, null)
                                source.rawQuery("SELECT $COLUMNS FROM cases ORDER BY id", null).use { rows ->
                                    var count = 0
                                    while (rows.moveToNext()) {
                                        check(++count <= 50_000) { "Legacy record bound exceeded" }
                                        val values = ContentValues()
                                        for (i in 0 until rows.columnCount) when (rows.getType(i)) {
                                            Cursor.FIELD_TYPE_INTEGER -> values.put(rows.getColumnName(i), rows.getLong(i))
                                            Cursor.FIELD_TYPE_STRING -> values.put(rows.getColumnName(i), rows.getString(i))
                                            else -> error("Unsupported legacy record value")
                                        }
                                        check(db.insertOrThrow("cases", null, values) >= 0)
                                    }
                                }
                                check(fingerprint(db.rawQuery("SELECT $COLUMNS FROM cases ORDER BY id", null)) == snapshot) {
                                    "Learning migration verification failed"
                                }
                                db.setTransactionSuccessful()
                            } finally { db.endTransaction() }
                            snapshot
                        }
                    }
                    check(secrets.edit().putString("migration_$name", expected).commit()) { "Learning migration publication unconfirmed" }
                }
                val published = checkNotNull(secrets.getString("migration_$name", null))
                check(published.matches(Regex("[a-f0-9]{64}")) &&
                    fingerprint(db.rawQuery("SELECT $COLUMNS FROM cases ORDER BY id", null)) == published) {
                    "Protected learning copy changed before legacy cleanup"
                }
                // No legacy connection remains open, and no caller can write until cleanup succeeds.
                for (suffix in listOf("-journal", "-wal", "-shm", "")) eraseLegacy(File(legacy.path + suffix))
            } else if (marker == null) {
                check(secrets.edit().putString("migration_$name", "new").commit()) { "Learning initialization unconfirmed" }
            }
            db
        } catch (failure: Throwable) { db.close(); throw failure }
    }

    private fun fingerprint(cursor: Cursor): String = cursor.use { rows ->
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0
        while (rows.moveToNext()) {
            check(++count <= 50_000) { "Learning record bound exceeded" }
            val row = JSONArray()
            for (i in 0 until rows.columnCount) row.put(rows.getString(i))
            digest.update(row.toString().toByteArray(Charsets.UTF_8)); digest.update(0.toByte())
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun eraseLegacy(file: File) {
        if (!file.exists()) return
        check(file.isFile && file.length() <= MAX_LEGACY_BYTES) { "Legacy cleanup path rejected" }
        RandomAccessFile(file, "rw").use { output ->
            var remaining = output.length()
            val zero = ByteArray(65_536)
            while (remaining > 0) {
                val count = minOf(remaining, zero.size.toLong()).toInt()
                output.write(zero, 0, count); remaining -= count
            }
            output.fd.sync()
        }
        check(file.delete()) { "Legacy learning cleanup incomplete" }
    }
}
