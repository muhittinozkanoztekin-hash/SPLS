package com.mhttn.sunplusremote

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

class PlayerActivity : Activity() {

    companion object {
        fun start(ctx: Context, url: String, title: String, radio: Boolean, ua: String?) {
            val i = Intent(ctx, PlayerActivity::class.java)
            i.putExtra("url", url)
            i.putExtra("title", title)
            i.putExtra("radio", radio)
            if (ua != null) i.putExtra("ua", ua)
            ctx.startActivity(i)
        }
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var spinner: ProgressBar
    private lateinit var overlay: LinearLayout
    private lateinit var message: TextView
    private lateinit var aspectBtn: Button

    private var url = ""
    private var title = ""
    private var radio = false
    private var ua: String? = null
    private var errors = 0

    private val modes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private val modeNames = arrayOf("Sığdır", "Uzat", "Yakınlaştır")
    private var modeIdx = 0

    private val hideRunner = Runnable { overlay.visibility = View.GONE }
    private val retryRunner = Runnable { startPlayback() }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.init(this)
        url = intent.getStringExtra("url") ?: ""
        title = intent.getStringExtra("title") ?: ""
        radio = intent.getBooleanExtra("radio", false)
        ua = intent.getStringExtra("ua")

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        playerView = PlayerView(this)
        playerView.useController = false
        playerView.setShutterBackgroundColor(Color.BLACK)
        playerView.resizeMode = modes[modeIdx]
        playerView.setOnClickListener { toggleOverlay() }
        root.addView(playerView, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        message = TextView(this)
        message.setTextColor(Color.WHITE)
        message.textSize = 16f
        message.gravity = Gravity.CENTER
        message.setPadding(Ui.dp(24), 0, Ui.dp(24), 0)
        message.text = if (radio) "📻  $title" else ""
        message.visibility = if (radio) View.VISIBLE else View.GONE
        val mlp = FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP)
        mlp.gravity = Gravity.CENTER
        root.addView(message, mlp)

        spinner = ProgressBar(this)
        val slp = FrameLayout.LayoutParams(Ui.dp(48), Ui.dp(48))
        slp.gravity = Gravity.CENTER
        root.addView(spinner, slp)

        overlay = LinearLayout(this)
        overlay.orientation = LinearLayout.HORIZONTAL
        overlay.gravity = Gravity.CENTER_VERTICAL
        overlay.setBackgroundColor(Color.parseColor("#99000000"))
        overlay.setPadding(Ui.dp(8), Ui.dp(6), Ui.dp(8), Ui.dp(6))

        val back = Ui.button(this, "←", Ui.BTN, 18f)
        back.setOnClickListener { finish() }
        overlay.addView(back, LinearLayout.LayoutParams(Ui.dp(48), Ui.dp(40)))

        val t = Ui.label(this, title, 16f, Color.WHITE, true)
        t.maxLines = 1
        t.setPadding(Ui.dp(12), 0, Ui.dp(8), 0)
        overlay.addView(t, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))

        aspectBtn = Ui.button(this, modeNames[modeIdx], Ui.BTN, 12f)
        aspectBtn.setOnClickListener {
            modeIdx = (modeIdx + 1) % modes.size
            playerView.resizeMode = modes[modeIdx]
            aspectBtn.text = modeNames[modeIdx]
            scheduleHide()
        }
        val alp = LinearLayout.LayoutParams(Ui.dp(96), Ui.dp(40))
        alp.rightMargin = Ui.dp(6)
        overlay.addView(aspectBtn, alp)

        val ext = Ui.button(this, "Harici oynatıcı", Ui.BTN, 12f)
        ext.setOnClickListener { openExternal() }
        overlay.addView(ext, LinearLayout.LayoutParams(Ui.dp(130), Ui.dp(40)))

        val olp = FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP)
        olp.gravity = Gravity.TOP
        root.addView(overlay, olp)

        setContentView(root)
        scheduleHide()
    }

    override fun onStart() {
        super.onStart()
        errors = 0
        startPlayback()
    }

    override fun onStop() {
        App.main.removeCallbacks(retryRunner)
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        App.main.removeCallbacks(hideRunner)
        super.onDestroy()
    }

    private fun toggleOverlay() {
        if (overlay.visibility == View.VISIBLE) {
            overlay.visibility = View.GONE
        } else {
            overlay.visibility = View.VISIBLE
            scheduleHide()
        }
    }

    private fun scheduleHide() {
        App.main.removeCallbacks(hideRunner)
        App.main.postDelayed(hideRunner, 3500)
    }

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        playerView.player = null
        try {
            p.release()
        } catch (e: Exception) {
        }
    }

    private fun startPlayback() {
        releasePlayer()
        if (url.isEmpty()) {
            message.text = "Yayın adresi yok"
            message.visibility = View.VISIBLE
            spinner.visibility = View.GONE
            return
        }
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(15000)
        val agent = ua
        if (agent != null) http.setUserAgent(agent)
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                spinner.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_READY) {
                    errors = 0
                    message.visibility = if (radio) View.VISIBLE else View.GONE
                    if (radio) message.text = "📻  $title"
                }
                if (state == Player.STATE_ENDED) retryLater("Yayın kesildi, yeniden bağlanılıyor…")
            }

            override fun onPlayerError(error: PlaybackException) {
                retryLater("Oynatılamadı (${error.errorCodeName}). Yeniden deneniyor…")
            }
        })
        playerView.player = p
        p.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        p.prepare()
        p.playWhenReady = true
        player = p
        spinner.visibility = View.VISIBLE
    }

    private fun retryLater(msg: String) {
        errors++
        spinner.visibility = View.GONE
        message.visibility = View.VISIBLE
        if (errors > 25) {
            message.text = "Oynatılamadı. \"Harici oynatıcı\" ile deneyebilirsiniz."
            overlay.visibility = View.VISIBLE
            return
        }
        message.text = msg
        App.main.removeCallbacks(retryRunner)
        App.main.postDelayed(retryRunner, 5000)
    }

    private fun openExternal() {
        try {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(Uri.parse(url), "video/*")
            startActivity(Intent.createChooser(i, "Oynatıcı seç"))
        } catch (e: ActivityNotFoundException) {
            toast("Uygun oynatıcı bulunamadı (VLC kurabilirsiniz)")
        }
    }
}
