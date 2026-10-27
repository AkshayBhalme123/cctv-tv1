package com.homecam.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * First-run login screen. Once a config is saved, launching the app goes
 * straight to the viewer; this screen only appears again via Menu / long-press.
 */
class SetupActivity : Activity() {

    private var brand = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val existing = SecureStore.load(this)
        if (existing != null && !intent.getBooleanExtra("edit", false)) {
            startActivity(Intent(this, ViewerActivity::class.java))
            finish()
            return
        }
        brand = existing?.brand ?: 0

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101820"))
            setPadding(160, 60, 160, 40)
        }

        col.addView(TextView(this).apply {
            text = "CCTV Setup (enter once, saved securely)"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })

        fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
            val e = EditText(this).apply {
                this.hint = hint
                setText(value)
                inputType = type
                textSize = 20f
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                isSingleLine = true
            }
            col.addView(e, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return e
        }

        val host = field("DVR / NVR / camera IP (e.g. 192.168.1.64)", existing?.host ?: "")
        val port = field("RTSP port", (existing?.port ?: 554).toString(), InputType.TYPE_CLASS_NUMBER)
        val user = field("Username", existing?.user ?: "")
        val pass = field("Password", existing?.pass ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val channels = field("Number of cameras", (existing?.channels ?: 4).toString(), InputType.TYPE_CLASS_NUMBER)
        val path = field("Custom path (only for 'Custom', use {ch})",
            existing?.customPath ?: "/Streaming/Channels/{ch}02")

        val brandBtn = Button(this).apply {
            textSize = 20f
            fun refresh() { text = "Brand: ${CamConfig.BRANDS[brand]}  (press to change)" }
            refresh()
            setOnClickListener { brand = (brand + 1) % CamConfig.BRANDS.size; refresh() }
        }
        col.addView(brandBtn)

        val save = Button(this).apply {
            text = "Save and start"
            textSize = 22f
            setOnClickListener {
                val h = host.text.toString().trim()
                val n = channels.text.toString().toIntOrNull() ?: 0
                if (h.isEmpty() || n < 1) {
                    Toast.makeText(context, "Enter the IP and number of cameras", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                SecureStore.save(context, CamConfig(
                    h,
                    port.text.toString().toIntOrNull() ?: 554,
                    user.text.toString().trim(),
                    pass.text.toString(),
                    brand,
                    n.coerceAtMost(64),
                    path.text.toString().trim()
                ))
                startActivity(Intent(context, ViewerActivity::class.java))
                finish()
            }
        }
        col.addView(save)

        setContentView(android.widget.ScrollView(this).apply { addView(col) })
        host.requestFocus()
    }
}
