package com.example.afterglow.phone

import org.json.JSONObject

/** Only an explicitly consented, permission-approved destination; no health data. */
internal object SavedPhoneConnection {
    fun encode(destination:RelayDestination):String = JSONObject().put("v",1).put("consent",true)
        .put("transport",if(destination.bluetoothAddress==null) "https" else "bluetooth")
        .put("address",destination.bluetoothAddress ?: destination.endpoint.address)
        .put("token",destination.token).toString()
    fun decode(value:String):RelayDestination {
        com.example.afterglow.net.BoundedRelayJson.requireObject(value,4096,2)
        val json=JSONObject(value)
        require(json.keys().asSequence().toSet()==setOf("v","consent","transport","address","token"))
        require(json.get("v")==1 && json.get("consent")==true)
        require(json.get("address") is String && json.get("token") is String)
        return when(json.getString("transport")) {
            "https"->RelayDestination(json.getString("address"),json.getString("token"))
            "bluetooth"->RelayDestination.bluetooth(json.getString("address"),json.getString("token"))
            else->error("Unknown transport")
        }
    }
}
