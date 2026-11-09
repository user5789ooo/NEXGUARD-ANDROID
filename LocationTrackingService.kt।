
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
import java.util.concurrent.TimeUnit

class LocationTrackingService : Service() {

    companion object {
        const val ACTION_START = "com.nexguard.companion.START_TRACKING"
        const val ACTION_STOP = "com.nexguard.companion.STOP_TRACKING"

        private const val CHANNEL_ID = "nexguard_location"
        private const val NOTIFICATION_ID = 7001
        private const val PREFS = "nexguard_prefs"
        private const val PREF_CONSENT = "location_consent"
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                if (!hasConsent()) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                if (!hasLocationPermission()) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForeground(
                    NOTIFICATION_ID,
                    buildNotification()
                )

                startLocationUpdates()
            }
        }

        return START_STICKY
    }

    private fun hasConsent(): Boolean {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(PREF_CONSENT, false)
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

    private fun startLocationUpdates() {

        if (!hasConsent() || !hasLocationPermission()) {
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
                locationCallback,
                mainLooper
            )
        } catch (e: SecurityException) {
            stopTracking()
        }
    }

    private fun uploadLocation(location: Location) {
        // TODO: Add authenticated Supabase sync-device-location
        // request here. Never upload without active consent.
        // Do not place a service-role key inside this Android app.
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

            val manager = getSystemService(
                NotificationManager::class.java
            )

            manager.createNotificationChannel(channel)
        }
    }

    private fun stopTracking() {
        if (::locationCallback.isInitialized) {
            fusedClient.removeLocationUpdates(locationCallback)
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Foreground tracking remains governed by user consent.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (::locationCallback.isInitialized) {
            fusedClient.removeLocationUpdates(locationCallback)
        }
        super.onDestroy()
    }
}
