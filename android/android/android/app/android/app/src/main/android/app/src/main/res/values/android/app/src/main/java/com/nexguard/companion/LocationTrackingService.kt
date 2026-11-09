
package com.nexguard.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class LocationTrackingService : Service() {

    companion object {
        const val ACTION_START =
            "com.nexguard.companion.START_TRACKING"

        const val ACTION_STOP =
            "com.nexguard.companion.STOP_TRACKING"

        private const val CHANNEL_ID = "nexguard_location"
        private const val NOTIFICATION_ID = 7001

        private const val PREFS = "nexguard"
        private const val PREF_CONSENT = "location_consent"

        private const val BASE_URL =
            "https://sfygmyyupndinfyuivec.supabase.co"

        private const val API_KEY =
            "sb_publishable_7j84isDkIbhGr0WuRS6UYQ_W-V4N8oe"
    }

    private lateinit var fusedClient: FusedLocationProviderClient

    private var locationCallback: LocationCallback? = null

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val prefs by lazy {
        getSharedPreferences(PREFS, MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()

        fusedClient =
            LocationServices.getFusedLocationProviderClient(this)

        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val action = intent?.action

        if (action == ACTION_STOP || action == "STOP_TRACKING") {
            stopTracking()
            return START_NOT_STICKY
        }

        if (!hasConsent() || !hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification()
        )

        startLocationUpdates()

        return START_STICKY
    }

    private fun hasConsent(): Boolean {
        return prefs.getBoolean(PREF_CONSENT, false)
    }

    private fun hasLocationPermission(): Boolean {

        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    private fun hasBackgroundPermission(): Boolean {
        return Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startLocationUpdates() {

        if (!hasConsent() ||
            !hasLocationPermission() ||
            !hasBackgroundPermission()
        ) {
            stopTracking()
            return
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            TimeUnit.MINUTES.toMillis(1)
        )
            .setMinUpdateIntervalMillis(
                TimeUnit.SECONDS.toMillis(30)
            )
            .setWaitForAccurateLocation(false)
            .build()

        locationCallback = object : LocationCallback() {

            override fun onLocationResult(
                result: LocationResult
            ) {
                if (!hasConsent()) {
                    stopTracking()
                    return
                }

                val location = result.lastLocation ?: return

                uploadLocation(location)
            }
        }

        try {
            fusedClient.requestLocationUpdates(
                request,
                locationCallback!!,
                mainLooper
            )
        } catch (e: SecurityException) {
            stopTracking()
        }
    }

    private fun uploadLocation(location: Location) {

        if (!hasConsent()) return

        val token = prefs.getString("access_token", null)
            ?: return

        val deviceId = prefs.getString("device_id", null)
            ?: return

        serviceScope.launch {

            try {
                if (!hasConsent()) return@launch

                val batteryManager =
                    getSystemService(BATTERY_SERVICE)
                        as android.os.BatteryManager

                val batteryLevel = batteryManager.getIntProperty(
                    android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY
                )

                val body = JSONObject()
                    .put("device_id", deviceId)
                    .put("latitude", location.latitude)
                    .put("longitude", location.longitude)
                    .put("location_consent", true)
                    .put(
                        "battery_level",
                        batteryLevel.coerceIn(0, 100)
                    )

                val request = Request.Builder()
                    .url(
                        "$BASE_URL/functions/v1/sync-device-location"
                    )
                    .post(
                        body.toString().toRequestBody(
                            "application/json".toMediaType()
                        )
                    )
                    .header("apikey", API_KEY)
                    .header("Authorization", "Bearer $token")
                    .build()

                http.newCall(request).execute().use { response ->

                    if (!response.isSuccessful) {
                        android.util.Log.e(
                            "NEXGUARD",
                            "Location sync failed: HTTP ${response.code}"
                        )
                    } else {
                        android.util.Log.d(
                            "NEXGUARD",
                            "Location sync response received"
                        )
                    }
                }

            } catch (e: Exception) {
                android.util.Log.e(
                    "NEXGUARD",
                    "Location sync error",
                    e
                )
            }
        }
    }

    private fun buildNotification(): Notification {

        val stopIntent = Intent(
            this,
            LocationTrackingService::class.java
        ).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            7002,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("NEXGUARD Location Sharing")
            .setContentText(
                "Location sharing is active. Tap Stop to disable."
            )
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop Sharing",
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "NEXGUARD Location Sharing",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun stopTracking() {

        locationCallback?.let {
            fusedClient.removeLocationUpdates(it)
        }

        locationCallback = null

        prefs.edit()
            .putBoolean(PREF_CONSENT, false)
            .apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {

        locationCallback?.let {
            fusedClient.removeLocationUpdates(it)
        }

        serviceScope.cancel()

        super.onDestroy()
    }
}
