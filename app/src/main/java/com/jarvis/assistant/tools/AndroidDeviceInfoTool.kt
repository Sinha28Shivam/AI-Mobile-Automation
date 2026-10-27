package com.jarvis.assistant.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class AndroidDeviceInfoTool(context: Context) : DeviceInfoTool {

    private val appContext = context.applicationContext

    override fun batteryStatus(): BatteryStatus? {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        var percent = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: Int.MIN_VALUE
        // Sticky broadcast: null receiver just reads the last value, no registration retained.
        val sticky: Intent? = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (percent !in 0..100 && sticky != null) {
            val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) percent = level * 100 / scale
        }
        if (percent !in 0..100) return null
        val charging = bm?.isCharging ?: run {
            val st = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        }
        return BatteryStatus(percent, charging)
    }

    override fun currentTimeText(): String =
        LocalTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()))
}
