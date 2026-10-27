package com.homecam.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

/**
 * Live viewer for one or two DVRs, built to run 24/7. Layout like KVMS Pro Lite:
 * one big camera on the left (full-quality stream), all other cameras as small tiles
 * on the right (low-res streams).
 *
 *  - OK on a small tile = make it the big camera
 *  - OK on the big camera = fullscreen on/off
 *  - Fullscreen: Left/Right = previous/next camera, Back = leave fullscreen
 *  - Menu or long-press OK = settings
 *
 * 24/7 safety: screen kept on, wake lock, unlimited auto-reconnect, watchdog for frozen
 * pictures, 4-hourly refresh, and a careful search for a DVR that moved to a new IP.
 */
class ViewerActivity : Activity() {

    private lateinit var cfg: CamConfig
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var mainHolder: FrameLayout
    private lateinit var thumbHolder: LinearLayout
    private val tiles = mutableListOf<Tile>()
    private var selected = 0
    private var fullMode = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val watchdog = object : Runnable {
        override fun run() {
            tiles.forEach { it.checkAlive() }
            handler.postDelayed(this, 10_000)
        }
    }
    private val refreshAll = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 4 * 60 * 60 * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = SecureStore.load(this)
        if (c == null) {
            startActivity(Intent(this, SetupActivity::class.java).putExtra("edit", true))
            finish()
            return
        }
        cfg = c
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cctv:viewer").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!::cfg.isInitialized) return
        hideSystemUi()
        render()
        handler.postDelayed(watchdog, 10_000)
        handler.postDelayed(refreshAll, 4 * 60 * 60 * 1000L)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        releaseTiles()
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    private fun releaseTiles() {
        tiles.forEach { it.release() }
        tiles.clear()
        if (::root.isInitialized) root.removeAllViews()
    }

    /** Builds all camera tiles and starts them (big camera first, others staggered). */
    private fun render() {
        releaseTiles()
        val list = cfg.cameras()
        if (list.isEmpty()) { openSettings(); return }
        if (selected >= list.size) selected = 0
        for (i in list.indices) tiles.add(Tile(i, i == selected))
        buildLayout()
        tiles.sortedByDescending { it.main }.forEachIndexed { n, t ->
            handler.postDelayed({ t.start() }, n * 250L)
        }
        tiles[selected].frame.requestFocus()
    }

    /** Places the tile views: selected camera on the left, the rest in a grid on the right. */
    private fun buildLayout() {
        tiles.forEach { (it.frame.parent as? ViewGroup)?.removeView(it.frame) }
        root.removeAllViews()

        val content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mainHolder = FrameLayout(this)
        thumbHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(mainHolder, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 66f))
        content.addView(thumbHolder, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 34f))
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        // Copyright line, bottom-right corner (text comes from strings.xml)
        root.addView(TextView(this).apply {
            text = getString(R.string.copyright)
            textSize = 11f
            setTextColor(Color.parseColor("#CCFFFFFF"))
            setBackgroundColor(Color.parseColor("#66000000"))
            setPadding(10, 2, 10, 2)
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))

        tiles.firstOrNull { it.main }?.let {
            mainHolder.addView(it.frame, FrameLayout.LayoutParams(-1, -1))
        }

        val thumbs = tiles.filter { !it.main }
        val rows = thumbs.chunked(2)
        thumbHolder.weightSum = maxOf(6, rows.size).toFloat()
        for (pair in rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (t in pair) row.addView(t.frame, LinearLayout.LayoutParams(0, -1, 1f))
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f))
            thumbHolder.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        applyFull()
    }

    private fun applyFull() {
        thumbHolder.visibility = if (fullMode) View.GONE else View.VISIBLE
        val lp = mainHolder.layoutParams as LinearLayout.LayoutParams
        lp.weight = if (fullMode) 100f else 66f
        mainHolder.layoutParams = lp
    }

    /** Makes camera [i] the big one; only the two cameras involved restart their streams. */
    private fun select(i: Int) {
        if (i == selected || i !in tiles.indices) return
        val old = tiles[selected]
        val new = tiles[i]
        selected = i
        old.setMain(false)
        new.setMain(true)
        buildLayout()
        new.start()
        old.start()
        new.frame.requestFocus()
    }

    private var discovering = false
    private var lastDiscovery = 0L

    /**
     * Called when a stream keeps failing. Scans the home network for devices with the
     * RTSP port open. Only changes a saved IP when it is certain which DVR moved:
     * exactly one saved DVR is missing AND exactly one unknown device appeared.
     * Otherwise it does nothing (a fixed IP on the router is the reliable fix).
     */
    private fun maybeRediscover() {
        val now = SystemClock.elapsedRealtime()
        if (discovering || now - lastDiscovery < 60_000) return
        discovering = true
        lastDiscovery = now
        Thread {
            val found = Discovery.scan(cfg.port)
            handler.post {
                discovering = false
                if (isDestroyed || isFinishing) return@post
                val hosts = listOf(cfg.host1, cfg.host2).filter { it.isNotBlank() }
                val missing = hosts.filter { it !in found }
                val unclaimed = found.filter { it !in hosts }
                if (missing.size == 1 && unclaimed.size == 1) {
                    cfg = if (missing[0] == cfg.host1) cfg.copy(host1 = unclaimed[0])
                    else cfg.copy(host2 = unclaimed[0])
                    SecureStore.save(this, cfg)
                    render()
                }
            }
        }.start()
    }

    private fun openSettings() {
        startActivity(Intent(this, SetupActivity::class.java).putExtra("edit", true))
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { openSettings(); return true }
        if (fullMode && tiles.isNotEmpty()) {
            val n = tiles.size
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { select((selected + 1) % n); return true }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { select((selected - 1 + n) % n); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fullMode) {
            fullMode = false
            applyFull()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private inner class Tile(val index: Int, var main: Boolean) {
        private val cam: Cam = cfg.cameras()[index]
        private val name = "DVR ${cam.dvr} - Camera ${cam.ch}"
        val frame = FrameLayout(this@ViewerActivity)
        private val view = PlayerView(this@ViewerActivity)
        private val label = TextView(this@ViewerActivity)
        private var player: ExoPlayer? = null
        private var retry = 0
        private var lastPos = -1L
        private var lastProgress = SystemClock.elapsedRealtime()
        private var released = false
        private val retryRunnable = Runnable { start() }

        init {
            frame.setPadding(3, 3, 3, 3)
            frame.isFocusable = true
            frame.isFocusableInTouchMode = true
            frame.setOnFocusChangeListener { _, _ -> updateLook() }
            frame.setOnClickListener {
                if (main) {
                    fullMode = !fullMode
                    applyFull()
                } else {
                    select(index)
                }
            }
            frame.setOnLongClickListener { openSettings(); true }

            view.useController = false
            view.setShutterBackgroundColor(Color.BLACK)
            frame.addView(view, FrameLayout.LayoutParams(-1, -1))

            label.setTextColor(Color.WHITE)
            label.setBackgroundColor(Color.parseColor("#99000000"))
            label.setPadding(10, 2, 10, 2)
            frame.addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
            updateLook()
        }

        /** Blue border = big camera, white border = remote focus, grey = others. */
        private fun updateLook() {
            label.text = name
            label.textSize = if (main) 16f else 11f
            frame.setBackgroundColor(
                when {
                    frame.hasFocus() -> Color.WHITE
                    main -> Color.parseColor("#2E86DE")
                    else -> Color.DKGRAY
                }
            )
        }

        fun setMain(m: Boolean) {
            main = m
            updateLook()
        }

        fun start() {
            if (released) return
            handler.removeCallbacks(retryRunnable)
            player?.release()

            val load = DefaultLoadControl.Builder()
                .setBufferDurationsMs(1000, 5000, 500, 1000)
                .build()
            val p = ExoPlayer.Builder(this@ViewerActivity).setLoadControl(load).build()
            val src = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(10_000)
                .createMediaSource(MediaItem.fromUri(cfg.url(cam, main || cfg.sharp)))
            p.setMediaSource(src)
            p.volume = 0f
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) = scheduleRetry()
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) scheduleRetry()
                }
                override fun onRenderedFirstFrame() {
                    retry = 0
                    lastProgress = SystemClock.elapsedRealtime()
                    label.text = name
                }
            })
            p.playWhenReady = true
            p.prepare()
            view.player = p
            player = p
            lastPos = -1L
            lastProgress = SystemClock.elapsedRealtime()
        }

        private fun scheduleRetry() {
            if (released) return
            val delay = minOf(30_000L, 2_000L shl minOf(retry, 4))
            retry++
            if (retry >= 3) maybeRediscover()
            label.text = "$name - reconnecting..."
            handler.removeCallbacks(retryRunnable)
            handler.postDelayed(retryRunnable, delay)
        }

        /** Restarts the stream if the playback position has not moved for 20s. */
        fun checkAlive() {
            val p = player ?: return
            val pos = p.currentPosition
            val now = SystemClock.elapsedRealtime()
            if (pos != lastPos) {
                lastPos = pos
                lastProgress = now
            } else if (now - lastProgress > 20_000) {
                scheduleRetry()
            }
        }

        fun release() {
            released = true
            handler.removeCallbacks(retryRunnable)
            view.player = null
            player?.release()
            player = null
        }
    }
}
