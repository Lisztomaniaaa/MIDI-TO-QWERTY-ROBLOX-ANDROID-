package com.lisztomaniaaa.papiano

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Floating overlay panel — pure read-only info window:
 *   - Status indicator (service active/inactive)
 *   - MIDI input info (device name)
 *   - Opacity slider 0-100
 *
 * NO toggle service di sini. User pas iseng pencet switch malah matiin
 * accessibility, panel ke-stop, balik ke home. Daripada bikin bingung,
 * popup ini purely informational. Buat start/stop service, user balik ke
 * MainActivity (tombol Start Playing / Stop Playing).
 *
 * Tombol minimize (−) -> bubble bulet 48dp (logo dragon).
 * Tombol close (✕)    -> stopSelf() doang. TIDAK ngirim intent.tuoluoyi.exit
 *                        (yang itu bakal matiin AccessibilityService juga).
 *                        Set flag KEY_USER_DISMISSED biar panel gak di-auto-restart
 *                        sampe user pencet Start Playing lagi. Buat fully stop
 *                        service, user balik ke MainActivity -> Stop Playing.
 */
class FloatingPanelService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var sp: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    // Cached children of panel (re-bound tiap inflatePanel).
    private var tvMidi: TextView? = null
    private var tvServiceDot: TextView? = null
    private var tvServiceStatus: TextView? = null
    private var sbOpacity: SeekBar? = null
    private var tvOpacityValue: TextView? = null



    // MIDI File Player
    private var midiFilePlayer: MidiFilePlayer? = null
    private var tvMidiFileName: TextView? = null
    private var btnMidiLoad: ImageView? = null
    private var btnMidiPlay: ImageView? = null
    private var btnMidiStop: ImageView? = null
    private var sbSpeed: SeekBar? = null
    private var tvSpeedValue: TextView? = null
    private var swVelocity: Switch? = null
    private var filePickerPending = false

    companion object {
        const val TAG = "PapianoFloating"
        private const val CHANNEL_ID = "floating"
        private const val NOTIF_ID = 2

        private const val PREFS = "data"
        private const val KEY_OPACITY = "floating_opacity"

        private const val KEY_X = "floating_x"
        private const val KEY_Y = "floating_y"
        private const val KEY_BUBBLE_X = "floating_bubble_x"
        private const val KEY_BUBBLE_Y = "floating_bubble_y"

        /**
         * User explicitly closed the floating panel via ✕. While true, none
         * of the auto-launch entry points (MainActivity.onResume,
         * tuoluoyiService.onServiceConnected, MainActivity.startBtn re-press)
         * should bring the panel back. Reset only when user presses
         * Start Playing again from MainActivity.
         */
        const val KEY_USER_DISMISSED = "panel_user_dismissed"

        private const val DEFAULT_OPACITY = 80
        private const val POLL_MS = 2000L
        private const val DRAG_SLOP_DP = 5

        /** Pending MIDI file URI from file picker (set by MidiPickerActivity) */
        @Volatile
        @JvmStatic
        var pendingMidiUri: Uri? = null
    }

    private var lastMidiName: String = ""
    private var lastMidiConnected: Boolean = false
    private var receiverRegistered = false
    private var foregroundStarted = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                when (intent.action) {
                    "intent.tuoluoyi.midi_device" -> {
                        val name = intent.getStringExtra("name") ?: ""
                        val connected = intent.getBooleanExtra(
                            "connected", name.isNotEmpty()
                        )
                        onMidiUpdate(name, connected)
                    }
                    "intent.tuoluoyi.exit" -> {
                        // panel close button OR daemon stop -> tear down
                        stopSelf()
                    }
                    "intent.tuoluoyi.midi_file_picked" -> {
                        val uri = pendingMidiUri
                        if (uri != null) {
                            pendingMidiUri = null
                            loadMidiFromUri(uri)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "receiver onReceive", t)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "no SYSTEM_ALERT_WINDOW permission, stopping")
            stopSelf()
            return
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        sp = getSharedPreferences(PREFS, 0)

        registerReceivers()
        inflatePanel()
        startStatusPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    /* ---------------- FOREGROUND NOTIFICATION ---------------- */

    private fun startForegroundCompat() {
        ensureChannel()

        // Custom dark purple notification layout
        val customView = RemoteViews(packageName, R.layout.notification_floating)

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(Icon.createWithResource(this, R.drawable.kali))
            .setOngoing(true)
            .setColor(getColor(R.color.accent_glow))
            .setCustomContentView(customView)
            .setContentIntent(
                PendingIntent.getActivity(this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE)
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(
                Notification.FOREGROUND_SERVICE_IMMEDIATE
            )
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID, builder.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, builder.build())
            }
            foregroundStarted = true
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground", t)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "Floating Panel",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    /* ---------------- RECEIVERS ---------------- */

    private fun registerReceivers() {
        val f = IntentFilter().apply {
            addAction("intent.tuoluoyi.midi_device")
            addAction("intent.tuoluoyi.exit")
            addAction("intent.tuoluoyi.midi_file_picked")
        }
        try {
            ContextCompat.registerReceiver(
                this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        } catch (t: Throwable) {
            Log.e(TAG, "registerReceiver", t)
        }
    }

    /**
     * Minta tuoluoyiService re-broadcast MIDI device state-nya yg sekarang.
     * Tanpa ini, panel yg baru di-inflate gak tau device apa yg lagi
     * connected (broadcastMidiDevice di service di-dedup pake lastBroadcastName,
     * jadi gak ada re-broadcast spontan kalau device-nya stay connected).
     */
    private fun requestMidiSnapshot() {
        try {
            sendBroadcast(
                Intent("intent.tuoluoyi.midi_request").setPackage(packageName)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "requestMidiSnapshot", t)
        }
    }

    /* ---------------- PANEL ---------------- */

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    private fun inflatePanel() {
        if (panelView != null) return
        val view = try {
            LayoutInflater.from(this).inflate(R.layout.floating_panel, null)
        } catch (t: Throwable) {
            Log.e(TAG, "inflate panel", t); return
        }

        // Panel: fixed size based on shorter dimension (portrait width).
        // This keeps panel consistent across portrait & landscape.
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val shortSide = minOf(screenWidth, screenHeight)
        val panelWidth = (shortSide * 0.85).toInt()
        val panelHeight = (shortSide * 0.42).toInt()

        val params = WindowManager.LayoutParams(
            panelWidth,
            panelHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sp.getInt(KEY_X, (screenWidth - panelWidth) / 2)
            y = sp.getInt(KEY_Y, dp(80))
        }
        clampParams(params, isBubble = false)

        try {
            wm.addView(view, params)
        } catch (t: Throwable) {
            Log.e(TAG, "addView panel", t); return
        }
        panelView = view
        panelParams = params

        bindPanelChildren(view)
        setupPanelHandlers(view, params)
        applyOpacity(sp.getInt(KEY_OPACITY, DEFAULT_OPACITY))
        renderMidi(lastMidiName, lastMidiConnected)
        requestMidiSnapshot()
    }

    private fun bindPanelChildren(view: View) {
        tvMidi = view.findViewById(R.id.tv_midi)
        tvServiceDot = view.findViewById(R.id.tv_service_dot)
        tvServiceStatus = view.findViewById(R.id.tv_service_status)
        sbOpacity = view.findViewById(R.id.sb_opacity)
        tvOpacityValue = view.findViewById(R.id.tv_opacity_value)

        // MIDI File Player views
        tvMidiFileName = view.findViewById(R.id.tv_midi_file_name)
        btnMidiLoad = view.findViewById(R.id.btn_midi_load)
        btnMidiPlay = view.findViewById(R.id.btn_midi_play)
        btnMidiStop = view.findViewById(R.id.btn_midi_stop)
        sbSpeed = view.findViewById(R.id.sb_speed)
        tvSpeedValue = view.findViewById(R.id.tv_speed_value)
        swVelocity = view.findViewById(R.id.sw_velocity)

        // Velocity toggle ("Visual Piano" dynamics). State shared dgn home
        // screen via pref "velocity_enabled". Broadcast ke tuoluoyiService
        // biar update live.
        swVelocity?.setOnCheckedChangeListener(null) // clear before set (re-inflate safe)
        swVelocity?.isChecked = sp.getBoolean("velocity_enabled", false)
        swVelocity?.setOnCheckedChangeListener { _, checked ->
            sp.edit().putBoolean("velocity_enabled", checked).apply()
            try {
                sendBroadcast(Intent("intent.tuoluoyi.set_velocity")
                    .setPackage(packageName)
                    .putExtra("enabled", checked))
            } catch (t: Throwable) {
                Log.w(TAG, "broadcast set_velocity", t)
            }
        }

        val saved = sp.getInt(KEY_OPACITY, DEFAULT_OPACITY)
        sbOpacity?.progress = saved
        tvOpacityValue?.text = "$saved%"



        // Init MIDI player
        if (midiFilePlayer == null) {
            midiFilePlayer = MidiFilePlayer()
            midiFilePlayer?.setNoteCallback { noteNumber, isDown, velocity ->
                if (noteNumber < 21 || noteNumber > 108) return@setNoteCallback
                // FAST PATH: call the daemon binder directly (no broadcast hop).
                // The old sendBroadcast route went through ActivityManager and
                // added tens of ms latency + jitter per note. qwertyKey is
                // oneway so this returns immediately.
                val gp = GamePadBridge.gamePad
                if (gp != null) {
                    try {
                        val v = if (GamePadBridge.velocityEnabled && isDown) velocity else 0
                        gp.qwertyKey(noteNumber, isDown, v)
                        return@setNoteCallback
                    } catch (_: Throwable) {
                        // stale/dead binder mid-respawn — fall through to broadcast
                    }
                }
                // FALLBACK: bridge not ready (daemon connecting/respawning).
                // tuoluoyiService receives this and applies gating + calls binder.
                try {
                    sendBroadcast(Intent("intent.tuoluoyi.midi_file_note")
                        .setPackage(packageName)
                        .putExtra("note", noteNumber)
                        .putExtra("down", isDown)
                        .putExtra("vel", velocity))
                } catch (_: Throwable) {}
            }
            midiFilePlayer?.setStateCallback(object : MidiFilePlayer.StateCallback {
                override fun onPlaybackStarted() {
                    mainHandler.post { btnMidiPlay?.setImageResource(R.drawable.ic_pause) }
                }
                override fun onPlaybackStopped() {
                    mainHandler.post { btnMidiPlay?.setImageResource(R.drawable.ic_play) }
                }
                override fun onProgress(percent: Float) {}
            })
        }
        // Restore file name if already loaded
        if (midiFilePlayer?.isLoaded == true) {
            tvMidiFileName?.text = midiFilePlayer?.fileName ?: "No file loaded"
            tvMidiFileName?.setTextColor(ContextCompat.getColor(this, R.color.accent_glow))
        }
    }

    private fun setupPanelHandlers(view: View, params: WindowManager.LayoutParams) {
        val header: View = view.findViewById(R.id.panel_header)
        attachDragListener(header, params, view, isBubble = false, onTap = null)

        view.findViewById<View>(R.id.btn_minimize).setOnClickListener {
            showBubble()
        }
        view.findViewById<View>(R.id.btn_close).setOnClickListener {
            // Panel-only close. Set flag biar auto-launch dari accessibility
            // service / MainActivity.onResume / dst gak nge-restart panel
            // pas user emang gak mau liat. NOTE: TIDAK kirim
            // intent.tuoluoyi.exit — broadcast itu matiin accessibility juga.
            try {
                sp.edit().putBoolean(KEY_USER_DISMISSED, true).apply()
            } catch (_: Throwable) {}
            stopSelf()
        }

        sbOpacity?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                applyOpacity(p)
                tvOpacityValue?.text = "$p%"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                sp.edit()
                    .putInt(KEY_OPACITY, s?.progress ?: DEFAULT_OPACITY)
                    .apply()
            }
        })

        // MIDI File Player handlers
        btnMidiLoad?.setOnClickListener { openMidiFilePicker() }
        btnMidiPlay?.setOnClickListener {
            val player = midiFilePlayer ?: return@setOnClickListener
            if (player.isPlaying) player.pause()
            else player.play()
        }
        btnMidiStop?.setOnClickListener { midiFilePlayer?.stop() }

        sbSpeed?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val speed = p / 100f
                midiFilePlayer?.setSpeed(speed)
                tvSpeedValue?.text = "${String.format("%.1f", speed)}x"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })


    }

    private fun applyOpacity(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        try {
            panelView?.background?.alpha = (255 * clamped / 100).coerceIn(0, 255)
        } catch (t: Throwable) {
            Log.w(TAG, "applyOpacity", t)
        }
    }

    /* ---------------- BUBBLE ---------------- */

    private fun showBubble() {
        // remove panel
        panelView?.let { v ->
            saveCurrentParams(panelParams, isBubble = false)
            try { wm.removeView(v) } catch (_: Throwable) {}
        }
        panelView = null
        panelParams = null
        tvMidi = null; tvServiceDot = null; tvServiceStatus = null
        sbOpacity = null; tvOpacityValue = null

        if (bubbleView != null) return

        val img = ImageView(this).apply {
            setImageResource(R.drawable.iconpop)
            background = null  // No background on bubble icon
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val size = dp(48)
        val params = WindowManager.LayoutParams(
            size, size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sp.getInt(KEY_BUBBLE_X, dp(16))
            y = sp.getInt(KEY_BUBBLE_Y, dp(120))
        }
        clampParams(params, isBubble = true)

        try {
            wm.addView(img, params)
        } catch (t: Throwable) {
            Log.e(TAG, "addView bubble", t)
            inflatePanel()
            return
        }
        bubbleView = img
        bubbleParams = params

        attachDragListener(img, params, img, isBubble = true) {
            hideBubbleShowPanel()
        }
    }

    private fun hideBubbleShowPanel() {
        bubbleView?.let { v ->
            saveCurrentParams(bubbleParams, isBubble = true)
            try { wm.removeView(v) } catch (_: Throwable) {}
        }
        bubbleView = null
        bubbleParams = null
        inflatePanel()
    }

    /* ---------------- DRAG / TOUCH ---------------- */

    private class DragState {
        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
    }

    private fun attachDragListener(
        target: View,
        params: WindowManager.LayoutParams,
        rootForLayout: View,
        isBubble: Boolean,
        onTap: (() -> Unit)?
    ) {
        val slop = dp(DRAG_SLOP_DP)
        val state = DragState()
        target.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    state.initX = params.x
                    state.initY = params.y
                    state.touchX = ev.rawX
                    state.touchY = ev.rawY
                    state.moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - state.touchX
                    val dy = ev.rawY - state.touchY
                    if (!state.moved && (abs(dx) > slop || abs(dy) > slop)) {
                        state.moved = true
                    }
                    if (state.moved) {
                        params.x = (state.initX + dx).toInt()
                        params.y = (state.initY + dy).toInt()
                        clampParams(params, isBubble)
                        try { wm.updateViewLayout(rootForLayout, params) }
                        catch (_: Throwable) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!state.moved && onTap != null) {
                        try { onTap.invoke() } catch (_: Throwable) {}
                    } else {
                        saveCurrentParams(params, isBubble)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun saveCurrentParams(p: WindowManager.LayoutParams?, isBubble: Boolean) {
        p ?: return
        val e = sp.edit()
        if (isBubble) e.putInt(KEY_BUBBLE_X, p.x).putInt(KEY_BUBBLE_Y, p.y)
        else          e.putInt(KEY_X, p.x).putInt(KEY_Y, p.y)
        e.apply()
    }

    private fun clampParams(p: WindowManager.LayoutParams, isBubble: Boolean) {
        val dm: DisplayMetrics = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val edgeMin = -dp(8)
        val maxX = w - dp(if (isBubble) 32 else 100)
        val maxY = h - dp(if (isBubble) 32 else 80)
        if (p.x < edgeMin) p.x = edgeMin
        if (p.y < edgeMin) p.y = edgeMin
        if (p.x > maxX) p.x = maxX
        if (p.y > maxY) p.y = maxY
    }

    /* ---------------- STATUS POLL (read-only) ---------------- */

    /** Single worker thread for status polling — no more Thread spam. */
    private var pollThread: HandlerThread? = null
    private var pollHandler: Handler? = null

    private val statusPoll = object : Runnable {
        override fun run() {
            // Run binder call on dedicated poll thread (not main, not new Thread each time)
            pollHandler?.post {
                val running = isAccessibilityRunning()
                mainHandler.post {
                    renderStatus(running)
                }
            }
            // Adaptive interval: 5s when panel visible, pause when in bubble mode
            // (panelView != null means panel is showing)
            val interval = if (panelView != null) 5000L else 15000L
            mainHandler.postDelayed(this, interval)
        }
    }

    private fun startStatusPolling() {
        if (pollThread == null) {
            pollThread = HandlerThread("poll-thread").also { it.start() }
            pollHandler = Handler(pollThread!!.looper)
        }
        mainHandler.removeCallbacks(statusPoll)
        mainHandler.postDelayed(statusPoll, 500L)
    }

    private fun isAccessibilityRunning(): Boolean = try {
        val svc = ComponentName(packageName, tuoluoyiService::class.java.name)
            .flattenToString()
        val cur = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        cur.contains(svc)
    } catch (t: Throwable) {
        Log.w(TAG, "isAccessibilityRunning", t); false
    }

    private fun renderStatus(running: Boolean) {
        val dotColor = ContextCompat.getColor(
            this, if (running) R.color.green else R.color.red
        )
        val labelColor = ContextCompat.getColor(
            this, if (running) R.color.text_primary else R.color.text_muted
        )
        tvServiceDot?.setTextColor(dotColor)
        tvServiceStatus?.text = if (running) "Service Active" else "Service Inactive"
        tvServiceStatus?.setTextColor(labelColor)
    }

    /* ---------------- MIDI UPDATE ---------------- */

    private fun renderMidi(name: String, connected: Boolean) {
        // Cuma 2 state: Connected / Disconnected. Nama device gak ditampilin
        // (sesuai request user — info dasar aja).
        if (connected && name.isNotEmpty()) {
            tvMidi?.text = "Connected"
            tvMidi?.setTextColor(ContextCompat.getColor(this, R.color.green))
        } else {
            tvMidi?.text = "Disconnected"
            tvMidi?.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        }
    }

    private fun onMidiUpdate(name: String, connected: Boolean) {
        // De-dupe: jangan toast tiap re-broadcast yg sama persis.
        if (name == lastMidiName && connected == lastMidiConnected) return
        lastMidiName = name
        lastMidiConnected = connected

        renderMidi(name, connected)
        try {
            val msg = if (connected && name.isNotEmpty())
                "MIDI Connected"
            else
                "MIDI Disconnected"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Log.w(TAG, "midi toast", t)
        }
    }

    /* ---------------- MIDI FILE PICKER ---------------- */

    private fun openMidiFilePicker() {
        try {
            val intent = Intent(this, MidiPickerActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "openMidiFilePicker", t)
            try { /* toast removed */ }
            catch (_: Throwable) {}
        }
    }

    /** Called when MidiPickerActivity returns with a file URI */
    fun loadMidiFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "midi_file.mid"
            val success = midiFilePlayer?.load(inputStream, fileName) ?: false
            mainHandler.post {
                if (success) {
                    tvMidiFileName?.text = midiFilePlayer?.fileName ?: fileName
                    tvMidiFileName?.setTextColor(ContextCompat.getColor(this, R.color.accent_glow))
                } else {
                    tvMidiFileName?.text = "Failed to load"
                    tvMidiFileName?.setTextColor(ContextCompat.getColor(this, R.color.red))
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "loadMidiFromUri", t)
            mainHandler.post {
                tvMidiFileName?.text = "Error loading"
                tvMidiFileName?.setTextColor(ContextCompat.getColor(this, R.color.red))
            }
        }
    }



    /* ---------------- LIFECYCLE ---------------- */

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Size is fixed (based on short side) so just clamp position
        panelParams?.let {
            clampParams(it, isBubble = false)
            try { panelView?.let { v -> wm.updateViewLayout(v, it) } }
            catch (_: Throwable) {}
        }
        bubbleParams?.let {
            clampParams(it, isBubble = true)
            try { bubbleView?.let { v -> wm.updateViewLayout(v, it) } }
            catch (_: Throwable) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        try { midiFilePlayer?.destroy() } catch (_: Throwable) {}
        midiFilePlayer = null
        try { pollThread?.quitSafely() } catch (_: Throwable) {}
        pollThread = null
        pollHandler = null

        if (receiverRegistered) {
            try { unregisterReceiver(receiver) } catch (_: Throwable) {}
            receiverRegistered = false
        }
        panelView?.let {
            saveCurrentParams(panelParams, isBubble = false)
            try { wm.removeView(it) } catch (_: Throwable) {}
        }
        bubbleView?.let {
            saveCurrentParams(bubbleParams, isBubble = true)
            try { wm.removeView(it) } catch (_: Throwable) {}
        }
        panelView = null
        bubbleView = null

        if (foregroundStarted) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(Service.STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()
}
