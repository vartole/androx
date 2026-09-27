kotlin
package com.system.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val svc = Intent(c, CoreService::class.java)
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(svc) else c.startService(svc)
    }
}
