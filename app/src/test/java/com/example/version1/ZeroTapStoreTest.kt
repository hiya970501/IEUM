package com.example.version1

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ZeroTapStoreTest {
    private val now = 1_800_000_000_000L
    private val prefs = TestPreferences()
    private val store = ZeroTapStore(prefs)
    private fun enroll(profile: String = "default"): JSONObject = store.enroll(profile,
        JSONObject().put("vehicle", "unit-vehicle").put("values", JSONObject().put("temperature", "22")))
        .also { store.save(profile, store.config(profile)!!.put("enabled", true)) }
    private fun event(reg: JSONObject, at: Long = now) = ZeroTapEvent(reg.getString("phoneId"),
        reg.getString("credential"), UUID.randomUUID().toString(), at)

    @Test fun statusReceiptIdentifiesOnlyItsProfilesLatestEventWithoutEnrollmentSecrets() {
        val reg = enroll(); val e = event(reg)
        assertTrue(store.receipt("default").isNull("eventId"))
        store.beginEvent("default", e, true, now)
        assertEquals(e.eventId, store.receipt("default").getString("eventId"))
        assertTrue(store.receipt("default").getBoolean("running"))
        store.recordResult("default", e.eventId, "verified")
        assertFalse(store.receipt("default").getBoolean("running"))
        assertEquals("verified", store.receipt("default").getString("status"))
        assertTrue(store.receipt("other").isNull("eventId"))
        assertFalse(store.receipt("default").toString().contains(reg.getString("credential")))
        assertFalse(store.receipt("default").toString().contains(reg.getString("phoneId")))
        enroll(); assertTrue(store.receipt("default").isNull("eventId"))
    }

    @Test fun enrollmentStoresOnlyHashAndAnEventAdmissionIsDurableBeforeDispatch() {
        val reg = enroll()
        assertFalse(prefs.all.toString().contains(reg.getString("credential")))
        val e = event(reg)
        val baseline = store.beginEvent("default", e, true, now)
        assertEquals("unit-vehicle", baseline.getString("vehicle"))
        assertTrue(store.isCurrentEvent("default", e.eventId))
        assertTrue(prefs.getBoolean("running_default", false))
        assertTrue(runCatching { store.beginEvent("default", e, true, now) }.isFailure)
    }
    @Test fun newerEventCannotBeOverwrittenByOlderCompletion() {
        val reg = enroll()
        val old = event(reg)
        store.beginEvent("default", old, true, now)
        val next = event(reg, now + 60_001)
        store.beginEvent("default", next, true, now + 60_001)
        assertFalse(store.recordResult("default", old.eventId, "old failure"))
        assertEquals("기본 설정 적용 중", store.status("default"))
        assertTrue(store.recordResult("default", next.eventId, "confirmed"))
        assertEquals("confirmed", store.status("default"))
    }
    @Test fun reenrollmentInvalidatesOldEventAndOldCredential() {
        val old = event(enroll())
        store.beginEvent("default", old, true, now)
        enroll()
        assertFalse(store.recordResult("default", old.eventId, "late callback"))
        assertEquals("등록 완료 · 자동 적용 꺼짐", store.status("default"))
        assertTrue(runCatching { store.beginEvent("default", old.copy(occurredAt = now + 61_000), true, now + 61_000) }.isFailure)
    }
    @Test fun revocationAndDriverIsolationApplyToResultCallbacksToo() {
        val e = event(enroll())
        store.beginEvent("default", e, true, now)
        enroll("other")
        assertFalse(store.recordResult("other", e.eventId, "wrong driver"))
        store.remove("default")
        assertFalse(store.recordResult("default", e.eventId, "late after revoke"))
        assertEquals("등록 완료 · 자동 적용 꺼짐", store.status("other"))
    }
    @Test fun failedDurableAdmissionDoesNotGivePermissionToDispatch() {
        val e = event(enroll())
        prefs.failNextCommit = true
        var dispatched = false
        assertTrue(runCatching {
            store.beginEvent("default", e, true, now)
            dispatched = true
        }.isFailure)
        assertFalse(dispatched)
    }
    @Test fun restartReconcilesOnlyInterruptedProfilesAndRetainsReplayEvidence() {
        val reg = enroll()
        val e = event(reg)
        store.beginEvent("default", e, true, now)
        enroll("other")
        store.reconcileInterrupted(listOf("default", "other"))
        assertTrue(store.status("default").startsWith("이전 적용이 중단"))
        assertEquals("등록 완료 · 자동 적용 꺼짐", store.status("other"))
        assertTrue(runCatching { store.beginEvent("default", e, true, now) }.isFailure)
    }

    /** Matches Android's important failure behavior: memory can update even when commit=false. */
    private class TestPreferences : SharedPreferences {
        private val values = mutableMapOf<String?, Any?>()
        var failNextCommit = false
        override fun getAll(): MutableMap<String, *> = values.filterKeys { it != null }.mapKeys { it.key!! }.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val changed = mutableMapOf<String?, Any?>()
            var clearAll = false
            override fun putString(key: String?, value: String?) = apply { changed[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { changed[key] = values }
            override fun putInt(key: String?, value: Int) = apply { changed[key] = value }
            override fun putLong(key: String?, value: Long) = apply { changed[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { changed[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { changed[key] = value }
            override fun remove(key: String?) = apply { changed[key] = null }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean {
                if (clearAll) values.clear()
                changed.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                return (!failNextCommit).also { failNextCommit = false }
            }
            override fun apply() { commit() }
        }
    }
}
