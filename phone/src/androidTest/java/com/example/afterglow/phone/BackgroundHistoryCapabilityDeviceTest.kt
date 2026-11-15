package com.example.afterglow.phone

import androidx.health.connect.client.HealthConnectClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Feature/permission metadata only; never reads health records or enables transmission. */
@RunWith(AndroidJUnit4::class)
class BackgroundHistoryCapabilityDeviceTest {
    @Test fun reportBackgroundReadCapabilityWithoutGrantOrHealthRead()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(HealthConnectClient.SDK_AVAILABLE,HealthConnectClient.getSdkStatus(context))
        val client=HealthConnectClient.getOrCreate(context)
        val grants=client.permissionController.getGrantedPermissions()
        val result=JSONObject().put("featureAvailable",PhoneAutoHistoryService.backgroundSupported(client))
            .put("backgroundReadGranted",PhoneAutoHistoryService.BACKGROUND in grants)
            .put("notificationsAllowed",PhoneAutoHistoryService.notificationsAllowed(context))
            .put("healthRecordsRead",false).put("permissionsGrantedByTest",false)
        File(context.filesDir,"owned-background-capability.json").writeText(result.toString())
    }
}
