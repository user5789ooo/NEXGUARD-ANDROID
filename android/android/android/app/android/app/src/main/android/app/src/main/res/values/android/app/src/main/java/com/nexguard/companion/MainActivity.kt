package com.nexguard.companion

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val http = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val prefs by lazy {
        getSharedPreferences("nexguard", MODE_PRIVATE)
    }

    private var token: String? = null

    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var status: TextView
    private lateinit var codeView: TextView
    private lateinit var loginButton: Button
    private lateinit var permissionButton: Button
    private lateinit var codeButton: Button
    private lateinit var syncButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        token = prefs.getString("access_token", null)

        val root = LinearLayout(this).apply {
            orientation = 1
            setPadding(32, 28, 32, 24)
        }

        root.addView(TextView(this).apply {
            text = "NEXGUARD"
            textSize = 28f
            setTextColor(0xFF142A46.toInt())
        })

        root.addView(TextView(this).apply {
            text = "Customer Device Companion"
            textSize = 14f
        })

        email = EditText(this).apply {
            hint = "Account email"
            inputType = 33
        }

        password = EditText(this).apply {
            hint = "Password"
            inputType = 129
        }

        loginButton = Button(this).apply {
            text = "Sign in"
            setOnClickListener { signIn() }
        }

        permissionButton = Button(this).apply {
            text = "Review & request permissions"
            setOnClickListener { requestNextPermission() }
        }

        codeButton = Button(this).apply {
            text = "Generate Website Connection Code"
            setOnClickListener { issueCode() }
        }

        codeView = TextView(this).apply {
            textSize = 20f
        }

        syncButton = Button(this).apply {
            text = "Sync battery & network status"
            setOnClickListener { syncTelemetry() }
        }

        status = TextView(this).apply {
            textSize = 14f
        }

        listOf(
            email, password, loginButton, permissionButton,
            codeButton, codeView, syncButton, status
        ).forEach { root.addView(it) }

        setContentView(ScrollView(this).apply {
            addView(root)
        })

        updateStatus()
    }

    private fun permissions() = buildList {
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestNextPermission() {
        val missing = permissions().firstOrNull {
            ContextCompat.checkSelfPermission(this, it) !=
                PackageManager.PERMISSION_GRANTED
        }

        if (missing == null) {
            toast("All requested permissions have been reviewed.")
            updateStatus()
            return
        }

        ActivityCompat.requestPermissions(
            this,
            arrayOf(missing),
            501
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)

        if (requestCode == 501) {
            updateStatus()
        }
    }

    private fun signIn() {
        val body = JSONObject()
            .put("email", email.text.toString().trim())
            .put("password", password.text.toString())
            .toString()

        task {
            val j = post(
                "/auth/v1/token?grant_type=password",
                body,
                false
            )

            token = j.getString("access_token")

            prefs.edit()
                .putString("access_token", token)
                .apply()

            "Signed in"
        }
    }

    private fun issueCode() {
        if (token == null) {
            return toast("Sign in first")
        }

        task {
            val j = post(
                "/functions/v1/issue-device-code",
                JSONObject()
                    .put(
                        "device_name",
                        "${Build.MANUFACTURER} ${Build.MODEL}"
                    )
                    .toString()
            )

            val code = j.getString("code")

            withContext(Dispatchers.Main) {
                codeView.text =
                    "Website connection code: $code\nExpires in 10 minutes • one use only"
            }

            "Connection code generated"
        }
    }

    private fun syncTelemetry() {
        if (token == null) {
            return toast("Sign in first")
        }

        task {
            val user = get("/auth/v1/user")

            val battery = registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )

            val level = battery?.getIntExtra(
                BatteryManager.EXTRA_LEVEL, -1
            ) ?: -1

            val scale = battery?.getIntExtra(
                BatteryManager.EXTRA_SCALE, -1
            ) ?: -1

            val percent = if (scale > 0) {
                level * 100 / scale
            } else {
                -1
            }

            val state = battery?.getIntExtra(
                BatteryManager.EXTRA_STATUS, -1
            ) ?: -1

            val charging =
                state == BatteryManager.BATTERY_STATUS_CHARGING ||
                state == BatteryManager.BATTERY_STATUS_FULL

            val cm = getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)

            val network = when {
                caps == null -> "Offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
                else -> "Other"
            }

            val row = JSONObject()
                .put("owner_id", user.getString("id"))
                .put("battery_percent", percent)
                .put("is_charging", charging)
                .put("network_type", network)

            post(
                "/rest/v1/device_telemetry",
                row.toString(),
                true,
                mapOf("Prefer" to "return=minimal")
            )

            "Synced: battery $percent%, $network"
        }
    }

    private fun label(permission: String, name: String): String {
        val granted = ContextCompat.checkSelfPermission(
            this, permission
        ) == PackageManager.PERMISSION_GRANTED

        return "$name: " + if (granted) "Allowed" else "Not allowed"
    }

    private fun updateStatus() {
        status.text = buildString {
            appendLine(
                "Account: ${if (token == null) "Signed out" else "Signed in"}"
            )
            appendLine(label(Manifest.permission.READ_CONTACTS, "Contacts"))
            appendLine(label(Manifest.permission.RECORD_AUDIO, "Microphone"))
            appendLine(label(Manifest.permission.ACCESS_FINE_LOCATION, "Location"))

            if (Build.VERSION.SDK_INT >= 33) {
                appendLine(
                    label(
                        Manifest.permission.POST_NOTIFICATIONS,
                        "Notifications"
                    )
                )
            }

            appendLine(
                "Location/voice/file/contact data is not collected in this starter."
            )
        }
    }

    private fun task(block: suspend () -> String) {
        busy(true)

        scope.launch {
            try {
                toast(block())
            } catch (e: Exception) {
                toast("Error: ${e.message}")
            } finally {
                busy(false)
                updateStatus()
            }
        }
    }

    private suspend fun post(
        path: String,
        body: String,
        auth: Boolean = true,
        headers: Map<String, String> = emptyMap()
    ) = withContext(Dispatchers.IO) {

        val request = Request.Builder()
            .url(BuildConfig.SUPABASE_URL + path)
            .post(
                body.toRequestBody(
                    "application/json".toMediaType()
                )
            )
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)

        if (auth) {
            token?.let {
                request.header("Authorization", "Bearer $it")
            }
        }

        headers.forEach { (key, value) ->
            request.header(key, value)
        }

        http.newCall(request.build()).execute().use { response ->
            val result = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                error("HTTP ${response.code}: $result")
            }

            if (result.isBlank()) JSONObject() else JSONObject(result)
        }
    }

    private suspend fun get(path: String) =
        withContext(Dispatchers.IO) {

            val request = Request.Builder()
                .url(BuildConfig.SUPABASE_URL + path)
                .get()
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .header(
                    "Authorization",
                    "Bearer ${token ?: ""}"
                )
                .build()

            http.newCall(request).execute().use { response ->
                val result = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    error("HTTP ${response.code}: $result")
                }

                JSONObject(result)
            }
        }

    private fun busy(value: Boolean) {
        loginButton.isEnabled = !value
        permissionButton.isEnabled = !value
        codeButton.isEnabled = !value
        syncButton.isEnabled = !value
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
