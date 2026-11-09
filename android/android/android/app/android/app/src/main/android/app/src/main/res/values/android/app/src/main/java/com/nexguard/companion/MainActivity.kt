
package com.nexguard.companion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

    private val baseUrl = "https://sfygmyyupndinfyuivec.supabase.co"
    private val apiKey = "sb_publishable_7j84isDkIbhGr0WuRS6UYQ_W-V4N8oe"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val http = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val prefs by lazy {
        getSharedPreferences("nexguard", MODE_PRIVATE)
    }

    private var accessToken: String? = null
    private var userEmail = ""
    private var syncInProgress = false

    private lateinit var emailInput: EditText
    private lateinit var continueButton: Button
    private lateinit var permissionButton: Button
    private lateinit var codeButton: Button
    private lateinit var codeText: TextView
    private lateinit var statusText: TextView

    private val deviceId: String
        get() {
            val saved = prefs.getString("device_id", null)
            if (!saved.isNullOrBlank()) return saved

            val created = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", created).apply()
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

        if (::statusText.isInitialized) {
            updateScreen()
            syncPermissionStatuses()
        }
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
            textSize = 15f
        })

        emailInput = EditText(this).apply {
            hint = "Enter your email address"
            inputType = 33
            setText(userEmail)
        }

        continueButton = Button(this).apply {
            text = "Continue"
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
            textSize = 22f
            setPadding(0, 20, 0, 20)
        }

        statusText = TextView(this).apply {
            textSize = 14f
        }

        listOf(
            emailInput,
            continueButton,
            permissionButton,
            codeButton,
            codeText,
            statusText
        ).forEach { root.addView(it) }

        setContentView(ScrollView(this).apply {
            addView(root)
        })

        updateScreen()
    }

    private fun continueSetup() {
        val email = emailInput.text.toString().trim()

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            toast("Enter a valid email address")
            return
        }

        userEmail = email
        prefs.edit().putString("email", email).apply()

        if (!accessToken.isNullOrBlank()) {
            updateScreen()
            syncPermissionStatuses()
            toast("Continue to permissions")
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
                    authenticated = false
                )

                val token = result.optString("access_token")

                if (token.isBlank()) {
                    error(
                        "Anonymous session unavailable. Enable Supabase Anonymous Sign-Ins."
                    )
                }

                accessToken = token

                prefs.edit()
                    .putString("access_token", token)
                    .apply()

                updateScreen()
                syncPermissionStatuses()
                toast("Email saved. Review permissions now.")

            } catch (e: Exception) {
                toast("Setup error: ${e.message}")
            } finally {
                busy(false)
            }
        }
    }

    private fun requiredPermissions(): List<String> {
        val list = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
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
            toast("All available permissions reviewed")
            updateScreen()
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

        if (requestCode == 501) {
            updateScreen()
            syncPermissionStatuses()
        }
    }

    private fun permissionStatus(permission: String): String {
        if (
            ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return "allowed"
        }

        return if (prefs.getBoolean("requested_$permission", false)) {
            "denied"
        } else {
            "not_requested"
        }
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

        val fineGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val locationStatus = when {
            fineGranted || coarseGranted -> "allowed"

            prefs.getBoolean(
                "requested_${Manifest.permission.ACCESS_FINE_LOCATION}",
                false
            ) || prefs.getBoolean(
                "requested_${Manifest.permission.ACCESS_COARSE_LOCATION}",
                false
            ) -> "denied"

            else -> "not_requested"
        }

        rows.put(
            JSONObject()
                .put("permission_name", "location")
                .put("permission_status", locationStatus)
        )

        val notificationStatus =
            if (Build.VERSION.SDK_INT < 33) {
                "not_available"
            } else {
                permissionStatus(Manifest.permission.POST_NOTIFICATIONS)
            }

        rows.put(
            JSONObject()
                .put("permission_name", "notifications")
                .put("permission_status", notificationStatus)
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
                    authenticated = true
                )

                updateScreen()

            } catch (e: Exception) {
                statusText.text =
                    statusText.text.toString() +
                    "\nPermission sync: Pending (will retry)"

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
                        .put(
                            "device_name",
                            "${Build.MANUFACTURER} ${Build.MODEL}"
                        )
                        .toString(),
                    authenticated = true
                )

                val code = result.optString("code")

                if (code.isBlank()) {
                    error("Connection code was not returned")
                }

                syncPermissionStatuses()

                codeText.text =
                    "Your Connection Code\n\n$code\n\nExpires in 10 minutes"

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
            val responseBody = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                error("HTTP ${response.code}: $responseBody")
            }

            JSONObject(responseBody)
        }
    }

    private fun updateScreen() {
        val hasSession = !accessToken.isNullOrBlank()

        continueButton.isEnabled = !hasSession
        emailInput.isEnabled = !hasSession
        permissionButton.isEnabled = hasSession
        codeButton.isEnabled = hasSession

        statusText.text = buildString {
            appendLine("Email: ${if (userEmail.isBlank()) "Not entered" else userEmail}")
            appendLine("Device session: ${if (hasSession) "Ready" else "Not started"}")

            appendLine(
                "Contacts: ${permissionStatus(Manifest.permission.READ_CONTACTS)}"
            )

            appendLine(
                "Microphone: ${permissionStatus(Manifest.permission.RECORD_AUDIO)}"
            )

            appendLine(
                "Location: ${currentPermissionRows().getJSONObject(2).optString("permission_status")}"
            )

            appendLine(
                "Notifications: ${currentPermissionRows().getJSONObject(3).optString("permission_status")}"
            )
        }
    }

    private fun busy(value: Boolean) {
        continueButton.isEnabled =
            !value && accessToken.isNullOrBlank()

        permissionButton.isEnabled =
            !value && !accessToken.isNullOrBlank()

        codeButton.isEnabled =
            !value && !accessToken.isNullOrBlank()
    }

    private fun toast(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
