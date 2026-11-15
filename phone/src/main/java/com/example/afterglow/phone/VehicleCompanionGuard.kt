package com.example.afterglow.phone

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Health Connect relay runs on the user's phone, never on the vehicle host. */
internal object VehicleCompanionGuard {
    fun showIfVehicle(activity: Activity): Boolean {
        if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return false
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(28), dp(32), dp(28))
            setBackgroundColor(Color.rgb(17, 21, 27))
        }
        fun text(value: String, size: Float) = TextView(activity).apply {
            text = value; textSize = size; setTextColor(Color.rgb(240, 246, 250))
            setPadding(0, dp(10), 0, dp(18))
        }
        column.addView(text("IEUM / 휴대폰용 앱", 20f))
        column.addView(text("차량 앱에서 워치를 연결해 주세요", 30f))
        column.addView(text("현재 열린 앱은 Android 휴대폰의 운동 기록을 전달하는 중계 앱입니다. 차량에는 읽을 휴대폰 건강 기록이 없어 여기서 연결할 수 없습니다.\n\n차량 앱으로 돌아간 뒤 상단의 ‘워치 연결’을 눌러 주세요. iPhone·Apple Watch는 iPhone에서 안내를 진행하고, 갤럭시 워치는 Android 휴대폰에 이 중계 앱을 설치합니다.", 22f))
        val message = text("", 20f)
        column.addView(Button(activity).apply {
            text = "차량 앱으로 돌아가기"; textSize = 24f; minHeight = dp(64)
            setOnClickListener {
                runCatching {
                    activity.startActivity(Intent().setClassName("com.example.version1", "com.example.version1.MainActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
                    activity.finish()
                }.onFailure { message.text = "차량 앱을 찾지 못했어요. 차량 앱 목록에서 IEUM을 열어 주세요." }
            }
        })
        column.addView(Button(activity).apply { text = "닫기"; minHeight = dp(56); setOnClickListener { activity.finish() } })
        column.addView(message)
        activity.setContentView(ScrollView(activity).apply {
            setBackgroundColor(Color.rgb(17, 21, 27)); isFillViewport = true
            addView(column, android.widget.FrameLayout.LayoutParams(-1, -2))
        })
        return true
    }
}
