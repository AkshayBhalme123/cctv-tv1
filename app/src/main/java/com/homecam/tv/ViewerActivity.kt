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
 * Live viewer built to run 24/7:
 *  - screen kept on + partial wake lock
 *  - unlimited auto-reconnect with backoff (2s .. 30s)
 *  - watchdog restarts any stream whose picture has frozen
 *  - full refresh of all streams every 4 hours to clear leaks
 *
 * Grid: 4 cameras per page (low-res substreams). OK = fullscreen (main stream).
 * Fullscreen: Left/Right = other camera, Back = grid. Menu or long-press OK = settings.
 */
class ViewerActivity : Activity() {

    private lateinit var cfg: CamConfig
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private val tiles = mutableListOf<Tile>()
    private var page = 0
    private var full = -1
    private var wakeLock: PowerManager.WakeLock? = null

    private val perPage = 4

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

    private fun pageCount() = (cfg.channels + perPage - 1) / perPage

    private fun render() {
        releaseTiles()
        if (full >= 0) {
            val t = Tile(full, main = true)
            tiles.add(t)
            root.addView(t.frame, FrameLayout.LayoutParams(-1, -1))
            t.start()
            t.frame.requestFocus()
            return
        }
        val first = page * perPage + 1
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (r in 0 until 2) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (cidx in 0 until 2) {
                val cam = first + r * 2 + cidx
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                if (cam <= cfg.channels) {
                    val t = Tile(cam, main = false)
                    tiles.add(t)
                    row.addView(t.frame, lp)
                } else {
                    row.addView(View(this), lp)
                }
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        root.addView(grid, FrameLayout.LayoutParams(-1, -1))
        tiles.forEach { it.start() }
        tiles.firstOrNull()?.frame?.requestFocus()
    }

    private fun openSettings() {
        startActivity(Intent(this, SetupActivity::class.java).putExtra("edit", true))
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { openSettings(); return true }
        if (full >= 0) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                full = if (full >= cfg.channels) 1 else full + 1; render(); return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                full = if (full <= 1) cfg.channels else full - 1; render(); return true
            }
        } else if (pageCount() > 1) {
            val idx = tiles.indexOfFirst { it.frame.hasFocus() }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && idx >= 2) {
                page = (page + 1) % pageCount(); render(); return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && idx in 0..1) {
                page = (page - 1 + pageCount()) % pageCount(); render(); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (full >= 0) {
            val back = full
            full = -1
            page = (back - 1) / perPage
            render()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private inner class Tile(val cam: Int, val main: Boolean) {
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
            frame.setBackgroundColor(Color.DKGRAY)
            frame.isFocusable = true
            frame.isFocusableInTouchMode = true
            frame.setOnFocusChangeListener { _, f ->
                frame.setBackgroundColor(if (f && !main) Color.parseColor("#2E86DE") else Color.DKGRAY)
            }
            frame.setOnClickListener {
                if (!main) { full = cam; render() }
            }
            frame.setOnLongClickListener { openSettings(); true }

            view.useController = false
            view.setShutterBackgroundColor(Color.BLACK)
            frame.addView(view, FrameLayout.LayoutParams(-1, -1))

            label.text = "Camera $cam"
            label.setTextColor(Color.WHITE)
            label.textSize = 16f
            label.setBackgroundColor(Color.parseColor("#99000000"))
            label.setPadding(12, 4, 12, 4)
            frame.addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
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
                .createMediaSource(MediaItem.fromUri(cfg.url(cam, main)))
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
                    label.text = "Camera $cam"
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
            label.text = "Camera $cam - reconnecting..."
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
