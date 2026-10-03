package dev.pi.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.UUID

class MainActivity : Activity() {
    private val runtime get() = (application as PiApplication).runtime
    private lateinit var status: TextView
    private lateinit var login: Button
    private lateinit var model: Button
    private lateinit var send: Button
    private lateinit var stop: Button
    private lateinit var input: EditText
    private lateinit var messages: LinearLayout
    private lateinit var scroller: ScrollView
    private var authenticated = false
    private var signingIn = false
    private var currentModel = "gpt-6-sol"
    private var modelIds = listOf<String>()
    private var manualDialog: AlertDialog? = null
    private var pendingBrowserUrl: String? = null
    private val listener: (JSONObject) -> Unit = { event -> onEvent(event) }
    @Suppress("DEPRECATION")
    private val version by lazy { packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown" }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 16f) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.rgb(27, 35, 45)) }
    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } }
    private fun command(type: String) = runtime.send(JSONObject().put("type", type))

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
            setBackgroundColor(Color.rgb(246, 247, 249))
        }
        // Respect system bars on Android 15, where targetSdk 35 uses edge-to-edge.
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(dp(18) + bars.left, dp(16) + bars.top, dp(18) + bars.right, dp(12) + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(dp(18), dp(16) + insets.systemWindowInsetTop, dp(18), dp(12) + insets.systemWindowInsetBottom)
            }
            insets
        }
        root.addView(text(getString(R.string.app_name), 28f).apply { setTypeface(null, Typeface.BOLD) })
        status = text("Starting embedded Node…", 12f)
        root.addView(status)
        val controls = LinearLayout(this)
        login = button("Sign in with ChatGPT") {
            if (signingIn) command("cancel_login")
            else if (authenticated) AlertDialog.Builder(this).setMessage("Sign out of ChatGPT? Your conversation stays on this device.")
                .setPositiveButton("Sign out") { _, _ -> command("logout") }.setNegativeButton("Cancel", null).show()
            else command("login")
        }.apply { isEnabled = false }
        model = button("Model") { selectModel() }.apply { isEnabled = false }
        controls.addView(login, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(model)
        root.addView(controls)
        root.addView(button("Export diagnostics") { exportDiagnostics() })

        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroller = ScrollView(this).apply { isFillViewport = true; addView(messages) }
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        input = EditText(this).apply {
            hint = "Message Pi"
            minLines = 1; maxLines = 5
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(state?.getString("draft") ?: "")
        }
        root.addView(input)
        val actions = LinearLayout(this).apply { gravity = Gravity.END }
        stop = button("Stop") { command("stop") }.apply { isEnabled = false }
        send = button("Send") {
            val value = input.text.toString().trim()
            if (value.isNotEmpty()) {
                send.isEnabled = false
                runtime.send(JSONObject().put("type", "send").put("text", value).put("requestId", UUID.randomUUID().toString()))
                input.setText("")
            }
        }.apply { isEnabled = false }
        actions.addView(stop); actions.addView(send)
        root.addView(actions)
        setContentView(root)
        runtime.attach(listener)
    }
    override fun onResume() {
        super.onResume()
        runtime.setForeground(true)
    }
    override fun onPause() {
        runtime.setForeground(false)
        super.onPause()
    }
    override fun onSaveInstanceState(state: Bundle) {
        state.putString("draft", input.text.toString())
        super.onSaveInstanceState(state)
    }
    override fun onDestroy() {
        runtime.detach(listener)
        manualDialog?.dismiss()
        super.onDestroy()
    }
    @Suppress("DEPRECATION")
    private fun exportDiagnostics() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "pi-diagnostics-${System.currentTimeMillis()}.json")
        }
        try { startActivityForResult(intent, 4701) }
        catch (_: Exception) { Toast.makeText(this, "No file picker is available to save diagnostics.", Toast.LENGTH_LONG).show() }
    }
    @Deprecated("Activity result callback retained for the framework Activity used by this PoC")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 4701 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread({
            val message = try {
                val output = contentResolver.openOutputStream(uri) ?: throw java.io.IOException("No output stream")
                output.use { runtime.exportDiagnostics(it) }
                "Diagnostics saved. You can attach the JSON file to your report."
            } catch (_: Exception) { "Could not save diagnostics. Try another folder." }
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }, "pi-diagnostics-export").start()
    }
    private fun addMessage(role: String, value: String) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(if (role == "user") Color.rgb(228, 238, 251) else Color.WHITE)
                cornerRadius = dp(12).toFloat()
            }
        }
        item.addView(text(if (role == "user") "You" else "Pi", 12f).apply { setTypeface(null, Typeface.BOLD) })
        item.addView(text(value).apply { setTextIsSelectable(true) })
        messages.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), 0, 0) })
    }
    private fun onEvent(event: JSONObject) {
        when (event.optString("type")) {
            "state" -> {
                authenticated = event.getBoolean("authenticated")
                signingIn = event.getBoolean("signingIn")
                val busy = event.getBoolean("busy")
                currentModel = event.getString("model")
                val available = event.getJSONArray("models")
                modelIds = (0 until available.length()).map { available.getString(it) }
                status.text = getString(R.string.runtime_status, event.getString("node"), currentModel, version)
                login.text = if (signingIn) "Cancel sign-in" else if (authenticated) "ChatGPT connected" else "Sign in with ChatGPT"
                login.isEnabled = true
                model.isEnabled = !busy && !signingIn
                send.isEnabled = authenticated && !busy && !signingIn
                stop.isEnabled = busy
                messages.removeAllViews()
                val transcript = event.getJSONArray("messages")
                if (transcript.length() == 0) messages.addView(text("Your conversation is stored on this device. Sign in with ChatGPT to begin.", 15f).apply { setPadding(0, dp(24), 0, 0) })
                for (i in 0 until transcript.length()) {
                    val message = transcript.getJSONObject(i)
                    addMessage(message.getString("role"), message.getString("text"))
                }
                val partial = event.optString("partial")
                if (partial.isNotEmpty()) addMessage("assistant", partial)
                else if (busy) messages.addView(text("Pi is working…", 14f))
                scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
            }
            "auth_url" -> {
                pendingBrowserUrl = event.getString("url")
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pendingBrowserUrl))) }
                catch (_: Exception) { showError("Install a browser to complete ChatGPT sign-in."); command("cancel_login") }
            }
            "auth_prompt" -> {
                pendingBrowserUrl = runtime.browserUrl()
                // Browser callback normally completes automatically. Keep a paste fallback available.
                val field = EditText(this).apply { hint = "Full callback URL"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
                manualDialog?.dismiss()
                manualDialog = AlertDialog.Builder(this).setTitle("Complete sign-in in your browser")
                    .setMessage("Return here after signing in. If the callback doesn't complete, paste the final callback URL.")
                    .setView(field).setPositiveButton("Use callback URL") { _, _ -> runtime.send(JSONObject().put("type", "auth_reply").put("text", field.text.toString())) }
                    .setNeutralButton("Open browser") { _, _ -> pendingBrowserUrl?.let {
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                        catch (_: Exception) { showError("Install a browser to complete ChatGPT sign-in."); command("cancel_login") }
                    } }
                    .setNegativeButton("Cancel sign-in") { _, _ -> command("cancel_login") }
                    .setCancelable(false).show()
            }
            "auth_prompt_closed" -> { manualDialog?.dismiss(); manualDialog = null; pendingBrowserUrl = null }
            "notice" -> Toast.makeText(this, event.getString("message"), Toast.LENGTH_SHORT).show()
            "error" -> { showError(event.getString("message")); command("state") }
            "fatal" -> {
                status.setText(R.string.runtime_unavailable)
                login.isEnabled = false; model.isEnabled = false; send.isEnabled = false; stop.isEnabled = false
                showError(event.getString("message"))
            }
        }
    }
    private fun showError(message: String) { AlertDialog.Builder(this).setTitle("Pi Durable").setMessage(message)
        .setNeutralButton("Export diagnostics") { _, _ -> exportDiagnostics() }.setPositiveButton("OK", null).show() }
    private fun selectModel() {
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelIds)
        spinner.setSelection(modelIds.indexOf(currentModel).coerceAtLeast(0))
        AlertDialog.Builder(this).setTitle("Choose a model").setView(spinner)
            .setPositiveButton("Use model") { _, _ -> runtime.send(JSONObject().put("type", "model").put("modelId", spinner.selectedItem.toString())) }
            .setNegativeButton("Cancel", null).show()
    }
}
