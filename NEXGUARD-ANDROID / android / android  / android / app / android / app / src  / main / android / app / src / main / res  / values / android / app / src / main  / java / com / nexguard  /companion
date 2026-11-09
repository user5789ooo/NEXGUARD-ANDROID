
package com.nexguard.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LocationTrackingService : Service() {

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private lateinit var fusedClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private val prefs by lazy {
        getSharedPreferences("nexguard", MODE_PRIVATE)
    }

    private val supabaseUrl =
        "https://sfygmyyupndinfyuivec.supabase.co"

    private val apiKey =
        "sb_publishable_7j84isDkIbhGr0WuRS6UYQ_W-V4N8oe"

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

        if (intent?.action == "STOP_TRACKING") {
            stopTracking()
            return START_NOT_STICKY
        }

        if (!hasRequiredPermissions()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                1001,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(1001, notification)
        }

        startLocationUpdates()

        return START_STICKY
    }

    private fun hasRequiredPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val background = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        return (fine || coarse) && background
    }

    private fun startLocationUpdates() {
        if (!hasRequiredPermissions()) {
            stopSelf()
            return
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            60_000L
        )
            .setMinUpdateIntervalMillis(30_000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    serviceScope.launch {
                        uploadLocation(location)
                    }
                }
            }
        }

        try {
            fusedClient.requestLocationUpdates(
                request,
                locationCallback!!,
                mainLooper
            )
        } catch (e: SecurityException) {
            Log.e("NEXGUARD", "Location permission error", e)
            stopSelf()
        }
    }

    private suspend fun uploadLocation(location: Location) {
        if (prefs.getBoolean("location_consent", false).not()) {
            return
        }

        val token = prefs.getString("access_token", null) ?: return
        val deviceId = prefs.getString("device_id", null) ?: return

        val body = JSONObject()
            .put("device_id", deviceId)
            .put("latitude", location.latitude)
            .put("longitude", location.longitude)
            .put("location_consent", true)

        val request = Request.Builder()
            .url("$supabaseUrl/functions/v1/sync-device-location")
            .header("apikey", apiKey)
            .header("Authorization", "Bearer $token")
            .post(
                body.toString().toRequestBody(
                    "application/json".toMediaType()
                )
            )
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(
                        "NEXGUARD",
                        "Location sync failed: ${response.code}"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("NEXGUARD", "Location upload error", e)
        }
    }

    private fun stopTracking() {
        locationCallback?.let {
            fusedClient.removeLocationUpdates(it)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                "nexguard_location",
                "NEXGUARD Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(
            this,
            "nexguard_location"
        )
            .setContentTitle("NEXGUARD Location Sharing")
            .setContentText(
                "Location sharing is active with your consent."
            )
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        locationCallback?.let {
            fusedClient.removeLocationUpdates(it)
        }

        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
