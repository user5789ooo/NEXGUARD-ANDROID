
package com.nexguard.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val baseUrl =
        "https://sfygmyyupndinfyuivec.supabase.co"

    private val apiKey =
        "sb_publishable_7j84isDkIbhGr0WuRS6UYQ_W-V4N8oe"

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val http = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val prefs by lazy {
        getSharedPreferences("nexguard", MODE_PRIVATE)
    }

    private var accessToken: String? = null
    private var userEmail = ""
    private var syncInProgress = false
    private var pendingLocationEnable = false
    private var waitingForBackgroundSettings = false
    private var suppressConsentListener = false

    private lateinit var emailInput: EditText
    private lateinit var continueButton: Button
    private lateinit var permissionButton: Button
    private lateinit var codeButton: Button
    private lateinit var consentCheck: CheckBox
    private lateinit var stopButton: Button
    private lateinit var codeText: TextView
    private lateinit var statusText: TextView

    private val deviceId: String
        get() {
            val saved = prefs.getString("device_id", null)

            if (!saved.isNullOrBlank()) return saved

            val created = UUID.randomUUID().toString()

            prefs.edit()
                .putString("device_id", created)
                .apply()

            return created
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        accessToken = prefs.getString("access_token", null)
        userEmail = prefs.getString("email", "") ?: ""

        showScreen()
    }

    override fun onResume() {
        super.onResume()

        if (!::statusText.isInitialized) return

        if (waitingForBackgroundSettings) {
            waitingForBackgroundSettings = false

            if (hasRequiredLocationPermissions()) {
                saveConsentAndStart()
            } else {
                setConsentChecked(false)
                toast("Enable Allow all the time location permission")
            }
        }

        setConsentChecked(
            prefs.getBoolean("location_consent", false)
        )

        updateScreen()
        syncPermissionStatuses()
    }

    private fun showScreen() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 40, 36, 32)
        }

        root.addView(TextView(this).apply {
            text = "NEXGUARD"
            textSize = 30f
            setTextColor(0xFF142A46.toInt())
        })

        root.addView(TextView(this).apply {
            text = "Customer Device Companion"
            textSize = 16f
        })

        emailInput = EditText(this).apply {
            hint = "Enter your email address"
            inputType = 33
            setText(userEmail)
        }

        continueButton = Button(this).apply {
            text = "Continue Setup"
            setOnClickListener { continueSetup() }
        }

        permissionButton = Button(this).apply {
            text = "Review Permissions"
            setOnClickListener { requestNextPermission() }
        }

        codeButton = Button(this).apply {
            text = "Generate Connection Code"
            setOnClickListener { generateCode() }
        }

        codeText = TextView(this).apply {
            textSize = 20f
            setPadding(0, 20, 0, 20)
        }

        root.addView(TextView(this).apply {
            text = "LOCATION SHARING"
            textSize = 18f
            setPadding(0, 24, 0, 8)
        })

        consentCheck = CheckBox(this).apply {
            text = "I consent to share this device's location with the authorized NEXGUARD administrator."
            isChecked = prefs.getBoolean("location_consent", false)

            setOnCheckedChangeListener { _, checked ->
                if (suppressConsentListener) return@setOnCheckedChangeListener

                if (checked) {
                    enableLocationSharing()
                } else {
                    stopTracking()
                }
            }
        }

        stopButton = Button(this).apply {
            text = "Stop Location Sharing"
            setOnClickListener {
                setConsentChecked(false)
                stopTracking()
            }
        }

        statusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 20, 0, 20)
        }

        listOf(
            emailInput,
            continueButton,
            permissionButton,
            codeButton,
            codeText,
            consentCheck,
            stopButton,
            statusText
        ).forEach { root.addView(it) }

        setContentView(ScrollView(this).apply {
            addView(root)
        })

        updateScreen()
    }

    private fun continueSetup() {

        val email = emailInput.text.toString().trim()

        if (!android.util.Patterns.EMAIL_ADDRESS
                .matcher(email).matches()) {
            toast("Enter a valid email address")
            return
        }

        userEmail = email

        prefs.edit()
            .putString("email", email)
            .apply()

        if (!accessToken.isNullOrBlank()) {
            updateScreen()
            syncPermissionStatuses()
            return
        }

        busy(true)

        scope.launch {
            try {
                val result = apiPost(
                    "/auth/v1/signup",
                    JSONObject()
                        .put(
                            "data",
                            JSONObject().put("email_label", email)
                        )
                        .toString(),
                    false
                )

                val token = result.optString("access_token")

                if (token.isBlank()) {
                    error(
                        "Anonymous sign-in unavailable. Enable Anonymous Sign-Ins in Supabase."
                    )
                }

                accessToken = token

                prefs.edit()
                    .putString("access_token", token)
                    .apply()

                updateScreen()
                syncPermissionStatuses()

                toast("Device session created")

            } catch (e: Exception) {
                toast("Setup error: ${e.message}")
            } finally {
                busy(false)
            }
        }
    }

    private fun requiredPermissions(): List<String> {

        val list = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return list
    }

    private fun requestNextPermission() {

        val missing = requiredPermissions().firstOrNull {
            ContextCompat.checkSelfPermission(this, it) !=
                PackageManager.PERMISSION_GRANTED
        }

        if (missing == null) {
            syncPermissionStatuses()
            updateScreen()
            toast("Runtime permissions reviewed")

            if (
                Build.VERSION.SDK_INT >= 30 &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                toast("Enable Allow all the time in Location settings")
                openAppSettings()
            }

            return
        }

        prefs.edit()
            .putBoolean("requested_$missing", true)
            .apply()

        ActivityCompat.requestPermissions(
            this,
            arrayOf(missing),
            501
        )
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        startActivity(intent)
    }

    private fun setConsentChecked(value: Boolean) {
        suppressConsentListener = true
        consentCheck.isChecked = value
        suppressConsentListener = false
    }

    private fun hasRequiredLocationPermissions(): Boolean {

        val locationGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val backgroundGranted =
            Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        return locationGranted && backgroundGranted
    }

    private fun enableLocationSharing() {

        if (accessToken.isNullOrBlank()) {
            setConsentChecked(false)
            toast("Complete device setup first")
            return
        }

        val locationGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (!locationGranted) {
            setConsentChecked(false)
            pendingLocationEnable = true

            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                502
            )
            return
        }

        if (
            Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            setConsentChecked(false)
            waitingForBackgroundSettings = true

            toast("Allow all the time location access in Settings")
            openAppSettings()
            return
        }

        saveConsentAndStart()
    }

    private fun saveConsentAndStart() {

        if (!hasRequiredLocationPermissions()) {
            setConsentChecked(false)
            toast("Required location permission is missing")
            return
        }

        prefs.edit()
            .putBoolean("location_consent", true)
            .apply()

        setConsentChecked(true)

        startTracking()
        syncPermissionStatuses()
    }

    private fun startTracking() {

        if (!prefs.getBoolean("location_consent", false)) {
            toast("Location consent is required")
            return
        }

        if (accessToken.isNullOrBlank()) {
            toast("Complete device setup first")
            return
        }

        if (!hasRequiredLocationPermissions()) {
            toast("Required location permissions are missing")
            return
        }

        try {
            val intent = Intent(
                this,
                LocationTrackingService::class.java
            ).apply {
                action = LocationTrackingService.ACTION_START
            }

            ContextCompat.startForegroundService(this, intent)

            toast("Automatic location sharing enabled")

        } catch (e: Exception) {
            toast("Could not start sharing: ${e.message}")
        }
    }

    private fun stopTracking() {

        prefs.edit()
            .putBoolean("location_consent", false)
            .apply()

        val intent = Intent(
            this,
            LocationTrackingService::class.java
        ).apply {
            action = LocationTrackingService.ACTION_STOP
        }

        try {
            startService(intent)
        } catch (e: Exception) {
            android.util.Log.e(
                "NEXGUARD",
                "Could not stop service",
                e
            )
        }

        updateScreen()
        toast("Location sharing stopped")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == 502) {

            if (pendingLocationEnable) {
                pendingLocationEnable = false

                if (hasRequiredLocationPermissions()) {
                    saveConsentAndStart()
                } else {
                    setConsentChecked(false)
                    toast("Location permission was not granted")
                }
            }

            updateScreen()
            syncPermissionStatuses()
        }

        if (requestCode == 501) {
            updateScreen()
            syncPermissionStatuses()
        }
    }

    private fun permissionStatus(permission: String): String {

        if (
            ContextCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return "allowed"
        }

        return if (
            prefs.getBoolean("requested_$permission", false)
        ) "denied" else "not_requested"
    }

    private fun currentPermissionRows(): JSONArray {

        val rows = JSONArray()

        rows.put(
            JSONObject()
                .put("permission_name", "contacts")
                .put(
                    "permission_status",
                    permissionStatus(Manifest.permission.READ_CONTACTS)
                )
        )

        rows.put(
            JSONObject()
                .put("permission_name", "microphone")
                .put(
                    "permission_status",
                    permissionStatus(Manifest.permission.RECORD_AUDIO)
                )
        )

        val fine = permissionStatus(
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        val coarse = permissionStatus(
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val locationStatus =
            if (fine == "allowed" || coarse == "allowed") {
                "allowed"
            } else if (
                fine == "denied" || coarse == "denied"
            ) {
                "denied"
            } else {
                "not_requested"
            }

        rows.put(
            JSONObject()
                .put("permission_name", "location")
                .put("permission_status", locationStatus)
        )

        rows.put(
            JSONObject()
                .put(
                    "permission_name",
                    "background_location"
                )
                .put(
                    "permission_status",
                    if (Build.VERSION.SDK_INT < 29)
                        "not_available"
                    else
                        permissionStatus(
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        )
                )
        )

        rows.put(
            JSONObject()
                .put("permission_name", "notifications")
                .put(
                    "permission_status",
                    if (Build.VERSION.SDK_INT < 33)
                        "not_available"
                    else
                        permissionStatus(
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                )
        )

        return rows
    }

    private fun syncPermissionStatuses() {

        if (
            syncInProgress ||
            accessToken.isNullOrBlank() ||
            userEmail.isBlank()
        ) return

        syncInProgress = true

        scope.launch {
            try {
                val body = JSONObject()
                    .put("device_id", deviceId)
                    .put("email", userEmail)
                    .put(
                        "device_name",
                        "${Build.MANUFACTURER} ${Build.MODEL}"
                    )
                    .put("permissions", currentPermissionRows())

                apiPost(
                    "/functions/v1/sync-permission-status",
                    body.toString(),
                    true
                )

                updateScreen()

            } catch (e: Exception) {
                statusText.text =
                    statusText.text.toString() +
                    "\nPermission sync pending"
            } finally {
                syncInProgress = false
            }
        }
    }

    private fun generateCode() {

        if (accessToken.isNullOrBlank()) {
            toast("Complete email setup first")
            return
        }

        busy(true)

        scope.launch {
            try {
                val result = apiPost(
                    "/functions/v1/issue-device-code",
                    JSONObject()
                        .put("device_id", deviceId)
                        .put("email", userEmail)
                        .put(
                            "device_name",
                            "${Build.MANUFACTURER} ${Build.MODEL}"
                        )
                        .toString(),
                    true
                )

                val code = result.optString("code")

                if (code.isBlank()) {
                    error("Connection code was not returned")
                }

                codeText.text =
                    "Your Connection Code\n\n$code\n\nExpires in 10 minutes"

                syncPermissionStatuses()
                toast("Connection code generated")

            } catch (e: Exception) {
                toast("Code error: ${e.message}")
            } finally {
                busy(false)
            }
        }
    }

    private suspend fun apiPost(
        path: String,
        body: String,
        authenticated: Boolean
    ): JSONObject = withContext(Dispatchers.IO) {

        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .post(
                body.toRequestBody(
                    "application/json".toMediaType()
                )
            )
            .header("apikey", apiKey)
            .apply {
                if (authenticated) {
                    header(
                        "Authorization",
                        "Bearer ${accessToken ?: ""}"
                    )
                }
            }
            .build()

        http.newCall(request).execute().use { response ->

            val responseBody =
                response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                error("HTTP ${response.code}: $responseBody")
            }

    
