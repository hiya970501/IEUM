package com.example.version1

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComfortLearningStoreDeviceTest {
    @Test fun orderedIoPreservesLatestSettingAndFeedbackInEncryptedStorage() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database="qa_write_order_${java.util.UUID.randomUUID()}.db"
        val c=ComfortContext("qa-order","ioniq","RUN",false,ComfortPhase.ARRIVAL,30f,60f,cabinC=29f)
        val earlier=ComfortCase("same-trip",1000,c,LearnedSetting(23f,2,0,0),false)
        val later=earlier.copy(at=1001,setting=LearnedSetting(25f,3,0,0))
        val store=ComfortLearningStore(context,database)
        try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(10_000) {
                    // Negative control: the old direct-IO pattern permits a late
                    // earlier write to replace a more recent observation.
                    val entered=kotlinx.coroutines.CompletableDeferred<Unit>()
                    val release=kotlinx.coroutines.CompletableDeferred<Unit>()
                    val old=async(kotlinx.coroutines.Dispatchers.IO) {
                        entered.complete(Unit);release.await();store.save(earlier)
                    }
                    entered.await()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.save(later) }
                    release.complete(Unit);old.await()
                    assertEquals(earlier,store.candidates(c).single())
                    store.delete(c.profile)
                    val writes=LearningWriteCoordinator.shared
                    val started=kotlinx.coroutines.CompletableDeferred<Unit>()
                    val resume=kotlinx.coroutines.CompletableDeferred<Unit>()
                    val first=launch(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        writes.write { started.complete(Unit);resume.await();store.save(earlier) }
                    }
                    started.await()
                    val second=launch(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        writes.write { store.save(later) }
                    }
                    val rating=launch(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        writes.write { store.save(later.copy(satisfied=true,rating=5)) }
                    }
                    resume.complete(Unit);first.join();second.join();rating.join()
                    assertEquals(later.copy(satisfied=true,rating=5),store.candidates(c).single())
                    assertEquals(1,store.count(c.profile))
                }
            }
        } finally {
            store.close();context.deleteDatabase(database);context.deleteDatabase("$database.enc")
            ProtectedPreferences.get(context,"learning_database_keys").edit()
                .remove("key_$database").remove("migration_$database").commit()
        }
    }
    @Test fun starFeedbackSurvivesRestartAndChangesRecommendationWithoutCrossingDrivers() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database="qa_stars_${java.util.UUID.randomUUID()}.db"
        val c=ComfortContext("qa-stars","ioniq","RUN",false,ComfortPhase.ARRIVAL,30f,60f,cabinC=29f)
        val setting=LearnedSetting(23f,2,-2,0,1)
        var store=ComfortLearningStore(context,database)
        try {
            for(i in 1..3) store.save(ComfortCase("trip-$i",1000L+i,c,setting,true,5))
            assertNotNull(AdaptiveComfort.recommend(c,store.candidates(c),2000).setting)
            store.save(ComfortCase("bad-trip",1500,c,setting,false,1))
            store.save(ComfortCase("other-trip",1501,c.copy(profile="other"),setting,true,5))
            store.close();store=ComfortLearningStore(context,database)
            val records=store.candidates(c)
            assertEquals(4,records.size)
            assertEquals(1,records.first().rating)
            assertEquals(-2,records.first().setting.heat)
            assertEquals(1,records.first().setting.steeringHeat)
            assertNull(AdaptiveComfort.recommend(c,records,2000).setting)
            store.save(ComfortCase("bad-trip",1600,c,setting,false,2))
            assertEquals(4,store.count(c.profile)) // Re-rating one trip does not fabricate more evidence.
            store.delete(c.profile)
            assertEquals(1,store.count("other"))
        } finally {
            store.close();context.deleteDatabase(database);context.deleteDatabase("$database.enc")
            ProtectedPreferences.get(context,"learning_database_keys").edit()
                .remove("key_$database").remove("migration_$database").commit()
        }
    }
    @Test fun preRatingEncryptedPayloadRemainsReadable() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database="qa_legacy_rating_${java.util.UUID.randomUUID()}.db"
        val c=ComfortContext("qa-legacy","ioniq","RUN",false,ComfortPhase.ARRIVAL,30f,60f,cabinC=29f)
        var store=ComfortLearningStore(context,database)
        try {
            store.save(ComfortCase("trip",1000,c,LearnedSetting(23f,2,0,0),true))
            val raw=store.readableDatabase.rawQuery("SELECT payload FROM cases",null).use { it.moveToFirst();it.getString(0) }
            val legacy=org.json.JSONObject(raw).apply { remove("rating");remove("steeringHeat") }
            store.writableDatabase.execSQL("UPDATE cases SET payload=?",arrayOf(legacy.toString()))
            store.close();store=ComfortLearningStore(context,database)
            val loaded=store.candidates(c).single()
            assertNull(loaded.rating);assertNull(loaded.setting.steeringHeat);assertTrue(loaded.satisfied)
        } finally {
            store.close();context.deleteDatabase(database);context.deleteDatabase("$database.enc")
            ProtectedPreferences.get(context,"learning_database_keys").edit()
                .remove("key_$database").remove("migration_$database").commit()
        }
    }
    @Test fun boundedPersistenceRestartIsolationAndDeletion() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database="comfort-learning-device-test.db"
        context.deleteDatabase(database); context.deleteDatabase("$database.enc")
        val c=ComfortContext("test-driver","ioniq","RUN",false,ComfortPhase.ARRIVAL,30f,60f,cabinC=29f)
        var store=ComfortLearningStore(context,database)
        try {
            for(i in 1..1250) store.save(ComfortCase("trip-$i",1000L+i,c,LearnedSetting(23f,2,0,0),true))
            assertEquals(1200,store.count(c.profile))
            assertEquals(96,store.candidates(c).size)
            store.save(ComfortCase("trip-1250",9999,c,LearnedSetting(24f,2,0,0),false))
            assertEquals(1200,store.count(c.profile))
            assertFalse(store.candidates(c).first().satisfied)
            store.save(ComfortCase("trip-other",10000,c.copy(profile="other-driver"),LearnedSetting(25f,2,0,0),true))
            assertEquals(1,store.count("other-driver"))
            store.close();store=ComfortLearningStore(context,database)
            assertEquals(1200,store.count(c.profile))
            store.delete(c.profile)
            assertTrue(store.candidates(c).isEmpty());assertEquals(1,store.count("other-driver"))
            store.save(ComfortCase("cancelled-trip",11000,c,LearnedSetting(24f,2,0,0),true)) { false }
            assertEquals(0,store.count(c.profile))
        } finally {
            store.close(); context.deleteDatabase(database); context.deleteDatabase("$database.enc")
            ProtectedPreferences.get(context, "learning_database_keys").edit()
                .remove("key_$database").remove("migration_$database").commit()
        }
    }
}
