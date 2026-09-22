package dk.fynogjylland.paafartennav

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class GpsService : Service() {
    companion object {
        const val ACTION_UPDATE = "dk.fynogjylland.paafartennav.UPDATE"
        private const val CHANNEL = "paafarten_nav_gps"
        private const val NOTIFICATION_ID = 1101
        private const val ENDPOINT = "https://minside.fynogjylland.dk/navigator/location_update.php"
    }

    private lateinit var fused: FusedLocationProviderClient
    private val io = Executors.newSingleThreadExecutor()
    private var busReg = ""
    private var busName = ""
    private var cookie = ""

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            if (busReg.isNotBlank()) send(location)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE) {
            busReg = intent.getStringExtra("bus_reg") ?: busReg
            busName = intent.getStringExtra("bus_name") ?: busName
            cookie = intent.getStringExtra("cookie") ?: cookie
        }
        startForeground(NOTIFICATION_ID, notification())
        beginUpdates()
        return START_STICKY
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_nav)
        .setContentTitle("På farten Nav")
        .setContentText(if (busReg.isBlank()) "GPS starter…" else "Bus $busReg · position deles")
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    private fun beginUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        fused.removeLocationUpdates(callback)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .setMinUpdateDistanceMeters(5f)
            .build()
        fused.requestLocationUpdates(req, callback, mainLooper)
    }

    private fun send(loc: Location) {
        val reg = busReg
        val name = busName
        val sessionCookie = cookie
        io.execute {
            try {
                val body = JSONObject().apply {
                    put("bus_reg", reg)
                    put("bus_name", name)
                    put("lat", loc.latitude)
                    put("lon", loc.longitude)
                    put("accuracy", loc.accuracy.toDouble())
                    put("speed", if (loc.hasSpeed()) loc.speed * 3.6 else 0.0)
                    put("heading", if (loc.hasBearing()) loc.bearing.toDouble() else JSONObject.NULL)
                }.toString()
                val c = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    if (sessionCookie.isNotBlank()) setRequestProperty("Cookie", sessionCookie)
                }
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
                stream?.use { it.readBytes() }
                c.disconnect()
            } catch (_: Exception) { }
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "På farten Nav GPS", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        io.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
