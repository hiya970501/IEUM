package com.example.afterglow.phone

import kotlinx.coroutines.sync.Mutex

/** One owner for Health Connect history reads during foreground/service handoff. No health cache. */
internal object PhoneHistorySyncControl {
    val gate=Mutex()
    @Volatile var visible=false
    @Volatile var serviceRunning=false
    @Volatile var status="자동 운동 동기화 꺼짐"
}
