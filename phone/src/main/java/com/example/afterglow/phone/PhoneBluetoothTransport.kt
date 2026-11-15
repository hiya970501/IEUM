package com.example.afterglow.phone

import android.content.Context
import com.example.afterglow.net.AndroidBluetoothChannel
import com.example.afterglow.net.BluetoothRequestClient
import com.example.afterglow.net.BluetoothRelayWire
import org.json.JSONObject

/** Same consent/session/Health Connect pipeline, with a different authenticated wire. */
internal class PhoneBluetoothTransport(context: Context) {
    private val appContext=context.applicationContext
    private val client=BluetoothRequestClient({ AndroidBluetoothChannel(appContext,it) })
    fun cancel()=client.cancel()
    fun pair(address: String,code: String,allowed: ()->Boolean): RelayDestination {
        require(code.matches(Regex("[0-9]{6}")))
        val result=client.request(address,BluetoothRelayWire.Request("/pair-watch",JSONObject().put("code",code).toString()),allowed)
        check(result.status==200)
        val json=JSONObject(result.body);check(json.get("ok")==true)
        return RelayDestination.bluetooth(address,json.getString("token"))
    }
    fun post(target: RelayDestination,requestId: String,body: String,path: String,allowed: ()->Boolean) {
        require(path in setOf("/connect","/workout","/health-heart-rate","/workout-history"))
        val result=client.request(checkNotNull(target.bluetoothAddress),BluetoothRelayWire.Request(path,body,target.token),allowed)
        check(result.status==200)
        val json=JSONObject(result.body)
        check(json.get("ok")==true && json.optString("requestId")==requestId)
    }
}
