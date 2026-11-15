package com.example.afterglow.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated owned fixture; does not read Health Connect or a user's saved vehicle pairing. */
@RunWith(AndroidJUnit4::class)
class SavedConnectionDeviceTest {
    @Test fun encryptedConnectionSurvivesRecreationAndRejectsTampering() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="qa_owned_connection_${java.util.UUID.randomUUID()}"
        val prefs=context.getSharedPreferences(name,0)
        try {
            val target=RelayDestination("https://owned.example/ingest/native","d".repeat(32))
            val store=PhoneConnectionStore(context,name)
            assertNull(store.load())
            assertFalse(store.save(target){false});assertTrue(prefs.all.isEmpty())
            assertTrue(store.save(target){true})
            val sealed=prefs.getString("sealed",null)!!
            assertFalse(sealed.contains("owned.example"));assertFalse(sealed.contains("d".repeat(32)))
            val restored=PhoneConnectionStore(context,name).load()!!
            assertEquals(target.endpoint.address,restored.endpoint.address);assertEquals(target.token,restored.token)
            val parts=sealed.split(':').toMutableList()
            parts[2]=(if(parts[2][0]=='A') "B" else "A")+parts[2].substring(1)
            check(prefs.edit().putString("sealed",parts.joinToString(":")).commit())
            assertThrows(Exception::class.java) {PhoneConnectionStore(context,name).load()}
            assertNotNull(prefs.getString("sealed",null))
            store.clear();assertNull(store.load())
        } finally {check(prefs.edit().clear().commit());context.deleteSharedPreferences(name)}
    }
}
