
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
import org.json.JSONObject
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

    private lateinit var emailInput: EditText
    private lateinit var continueButton: Button
    private lateinit var permissionButton: Button
    private lateinit var codeButton: Button
    private lateinit var codeText: TextView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        accessToken = prefs.getString("access_token", null)
        userEmail = prefs.getString("email", "") ?: ""

        showScreen()
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
            toast("Continue to permissions")
            return
        }

        busy(true)

        scope.launch {
            try {
                val result = apiPost(
                    "/auth/v1/signup",
                    JSONObject()
                        .put("data", JSONObject().put("email_label", email))
                        .toString(),
                    authenticated = false
                )

                val token = result.optString("access_token")

                if (token.isBlank()) {
                    error("Anonymous session unavailable. Check Supabase Anonymous Sign-Ins settings.")
                }

                accessToken = token

                prefs.edit()
                    .putString("access_token", token)
                    .apply()

                toast("Email saved. Review permissions now.")
                updateScreen()

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
            toast("All requested permissions reviewed")
            updateScreen()
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
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == 501) {
            updateScreen()
        }
    }

    private fun generateCode() {
        val token = accessToken

        if (token.isNullOrBlank()) {
            toast("Complete email setup first")
            return
        }

        val missing = requiredPermissions().firstOrNull {
            ContextCompat.checkSelfPermission(this, it) !=
                PackageManager.PERMISSION_GRANTED
        }

        if (missing != null) {
            toast("Review all requested permissions first")
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

        val url = baseUrl.trimEnd('/') + path

        val request = Request.Builder()
            .url(url)
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

            requiredPermissions().forEach { permission ->
                val granted = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    permission
                ) == PackageManager.PERMISSION_GRANTED

                appendLine(
                    "${permission.substringAfterLast(".")}: ${if (granted) "Allowed" else "Not allowed"}"
                )
            }
        }
    }

    private fun busy(value: Boolean) {
        continueButton.isEnabled = !value
        permissionButton.isEnabled = !value
        codeButton.isEnabled = !value
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
