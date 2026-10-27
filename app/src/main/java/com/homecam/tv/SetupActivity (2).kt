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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Login screen. Once a config is saved, launching the app goes straight to the
 * viewer; this screen only appears again via Menu / long-press.
 */
class SetupActivity : Activity() {

    private var brand = 1
    private var sharp = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val existing = SecureStore.load(this)
        if (existing != null && !intent.getBooleanExtra("edit", false)) {
            startActivity(Intent(this, ViewerActivity::class.java))
            finish()
            return
        }
        brand = existing?.brand ?: 1
        sharp = existing?.sharp ?: true

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101820"))
            setPadding(160, 40, 160, 40)
        }

        col.addView(TextView(this).apply {
            text = "CCTV Setup (enter once, saved securely)"
            textSize = 26f
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

        val num = InputType.TYPE_CLASS_NUMBER
        val host1 = field("DVR 1 IP (e.g. 192.168.31.172)", existing?.host1 ?: "")
        val ch1 = field("DVR 1: number of cameras", (existing?.ch1 ?: 4).toString(), num)
        val host2 = field("DVR 2 IP (leave empty if only one DVR)", existing?.host2 ?: "")
        val ch2 = field("DVR 2: number of cameras", (existing?.ch2 ?: 8).toString(), num)
        val port = field("RTSP port (usually 554)", (existing?.port ?: 554).toString(), num)
        val user = field("Username (same for both DVRs)", existing?.user ?: "")
        val pass = field("Password (same for both DVRs)", existing?.pass ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val path = field("Custom path (only for 'Custom', use {ch})",
            existing?.customPath ?: "/Streaming/Channels/{ch}02")

        val brandBtn = Button(this).apply {
            textSize = 20f
            fun refresh() { text = "Brand: ${CamConfig.BRANDS[brand]}  (press to change)" }
            refresh()
            setOnClickListener { brand = (brand + 1) % CamConfig.BRANDS.size; refresh() }
        }
        col.addView(brandBtn)

        val qualityBtn = Button(this).apply {
            textSize = 20f
            fun refresh() {
                text = if (sharp) "Small cameras: SHARP (main stream, heavy)  (press to change)"
                else "Small cameras: LIGHT (sub stream, smooth)  (press to change)"
            }
            refresh()
            setOnClickListener { sharp = !sharp; refresh() }
        }
        col.addView(qualityBtn)

        val save = Button(this).apply {
            text = "Save and start"
            textSize = 22f
            setOnClickListener {
                val h1 = host1.text.toString().trim()
                val h2 = host2.text.toString().trim()
                val n1 = ch1.text.toString().toIntOrNull() ?: 0
                val n2 = ch2.text.toString().toIntOrNull() ?: 0
                if (h1.isEmpty() || n1 < 1 || (h2.isNotEmpty() && n2 < 1)) {
                    Toast.makeText(context, "Enter the DVR IP(s) and number of cameras",
                        Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                SecureStore.save(context, CamConfig(
                    h1, n1.coerceAtMost(32),
                    h2, n2.coerceAtMost(32),
                    port.text.toString().toIntOrNull() ?: 554,
                    user.text.toString().trim(),
                    pass.text.toString(),
                    brand,
                    path.text.toString().trim(),
                    sharp
                ))
                startActivity(Intent(context, ViewerActivity::class.java))
                finish()
            }
        }
        col.addView(save)

        setContentView(ScrollView(this).apply { addView(col) })
        host1.requestFocus()
    }
}
