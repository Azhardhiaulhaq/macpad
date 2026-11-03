package com.azhardhiaulhaq.macpad

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.util.Locale
import kotlin.math.roundToInt

internal class MainActivity : Activity(), Session.Listener {
    private enum class State { IDLE, CONNECTING, PAIRING, STREAMING, RECONNECTING }

    private val handler = Handler(Looper.getMainLooper())
    private val stats = Stats()
    private lateinit var prefs: Prefs
    private lateinit var decoder: VideoDecoder
    private lateinit var input: InputMapper
    private lateinit var discovery: Discovery

    private lateinit var streamRoot: FrameLayout
    private lateinit var video: SurfaceView
    private lateinit var reconnecting: TextView
    private lateinit var connectRoot: View
    private lateinit var status: TextView
    private lateinit var pairing: View
    private lateinit var pairCode: TextView
    private lateinit var macList: LinearLayout
    private lateinit var addressSection: View
    private lateinit var addressField: EditText
    private lateinit var addressConnect: Button
    private lateinit var wifiLock: WifiManager.WifiLock

    /** A Mac row: Bonjour result and/or a remembered Mac, merged by server id. */
    private class MacRow(val name: String, val serverIdHex: String?, val endpoints: List<Endpoint>)

    private var state = State.IDLE
    private var session: Session? = null
    private var target: Target? = null
    /** Name of the Mac for [target]: SERVER_INFO name, else the Bonjour name. */
    private var macName: String? = null
    /** Outcome of the last attempt, shown on the connect screen until the next action. */
    private var message: String? = null
    /** Name of the Mac on the cable, kept for the paused USB row while other Macs are tried. */
    private var usbMacName: String? = null
    private var macs: List<MacService> = emptyList()
    private var rows: List<MacRow> = emptyList()
    private var started = false
    private var pendingUsb = false
    /** Set when the user leaves a USB stream, so the USB probe does not pull them back in. */
    private var usbPaused = false
    /** Set when [recreate] (theme switch) restarts this screen, so [onStart] keeps [usbPaused]. */
    private var keepUsbPaused = false
    private var resumeTarget: Target? = null
    private var reconnectDeadline = 0L
    private var videoWidth = 0
    private var videoHeight = 0
    private var panelWidth = 0
    private var panelHeight = 0
    private var immersive = false
    private var backRegistered = false
    /** Night bits of the theme this instance was built with; see [onConfigurationChanged]. */
    private var themeNight = 0
    private var themeStale = false
    private val location = IntArray(2)

    private val backCallback = OnBackInvokedCallback { leave() }
    private val usbProbe = Runnable { probeUsb() }
    private val reconnect = Runnable { reconnectNow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        setContentView(R.layout.activity_main)
        streamRoot = findViewById(R.id.stream_root)
        video = findViewById(R.id.video)
        reconnecting = findViewById(R.id.reconnecting)
        connectRoot = findViewById(R.id.connect_root)
        status = findViewById(R.id.status)
        pairing = findViewById(R.id.pairing)
        pairCode = findViewById(R.id.pair_code)
        macList = findViewById(R.id.mac_list)
        addressSection = findViewById(R.id.address_section)
        addressField = findViewById(R.id.address_field)
        addressConnect = findViewById(R.id.address_connect)
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, TAG)
            .apply { setReferenceCounted(false) }

        prefs = Prefs(this)
        decoder = VideoDecoder(stats)
        input = InputMapper(this, findViewById(R.id.hover_dot))
        discovery = Discovery(this) {
            macs = it
            rebuildRows()
            render()
        }

        connectRoot.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            if (ime.bottom > 0 && addressField.hasFocus()) v.post { revealAddressSection() }
            WindowInsets.CONSUMED
        }
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = decoder.setSurface(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) = decoder.setSurface(null)
        })
        streamRoot.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (l != ol || t != ot || r != or || b != ob) streamRoot.post { layoutVideo() }
        }
        // The hover dot replaces the system stylus pointer over the video.
        streamRoot.pointerIcon = PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL)
        findViewById<Button>(R.id.pair_cancel).setOnClickListener { leave() }
        addressConnect.setOnClickListener { connectAddress() }
        addressField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_GO) return@setOnEditorActionListener false
            connectAddress()
            true
        }

        if (savedInstanceState == null) {
            pendingUsb = intent?.action == ACTION_CONNECT_USB
        } else {
            // Recreated after a theme switch: the launch intent was already handled.
            usbPaused = savedInstanceState.getBoolean(KEY_USB_PAUSED)
            keepUsbPaused = usbPaused
            message = savedInstanceState.getString(KEY_MESSAGE)
            usbMacName = savedInstanceState.getString(KEY_USB_MAC_NAME)
        }
        themeNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_USB_PAUSED, usbPaused)
        outState.putString(KEY_MESSAGE, message)
        outState.putString(KEY_USB_MAC_NAME, usbMacName)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action != ACTION_CONNECT_USB) return
        if (state == State.STREAMING && target?.usb == true) return
        if (started) {
            connectUsb()
        } else {
            pendingUsb = true
            resumeTarget = null
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (!keepUsbPaused) usbPaused = false
        keepUsbPaused = false
        rebuildRows()
        val resume = resumeTarget
        resumeTarget = null
        when {
            pendingUsb -> {
                pendingUsb = false
                connectUsb()
            }
            resume != null -> {
                target = resume
                state = State.RECONNECTING
                reconnectDeadline = SystemClock.uptimeMillis() + RECONNECT_WINDOW_MS
                render()
                reconnectNow()
            }
            else -> enterConnect(probeNow = true)
        }
    }

    override fun onStop() {
        started = false
        handler.removeCallbacks(usbProbe)
        handler.removeCallbacks(reconnect)
        if (state == State.STREAMING || state == State.RECONNECTING) resumeTarget = target
        endSession()
        state = State.IDLE
        discovery.stop()
        render()
        super.onStop()
    }

    override fun onDestroy() {
        endSession()
        setWifiLock(false)
        discovery.stop()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // uiMode is handled here so a light/dark switch does not tear down a live stream; the
        // connect screen is rebuilt with the new theme once no connection is active.
        if (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK != themeNight) {
            if (state == State.IDLE) {
                recreate()
                return
            }
            themeStale = true
        }
        val bounds = windowManager.maximumWindowMetrics.bounds
        if (bounds.width() == panelWidth && bounds.height() == panelHeight) return
        panelWidth = bounds.width()
        panelHeight = bounds.height()
        session?.resize(panelWidth, panelHeight)
    }

    // ---- Input ----

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!streaming()) return super.dispatchTouchEvent(ev)
        input.onTouch(ev)
        return true
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (streaming() && input.onGenericMotion(ev)) return true
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun streaming() = state == State.STREAMING || state == State.RECONNECTING

    // ---- Connecting ----

    private fun connectUsb() {
        handler.removeCallbacks(usbProbe)
        handler.removeCallbacks(reconnect)
        usbPaused = false
        message = null
        target = Target.usb()
        macName = null
        state = State.CONNECTING
        startSession(target!!, quiet = false, timeoutMs = USB_TIMEOUT_MS)
        rebuildRows()
        render()
    }

    private fun connectWifi(t: Target) {
        handler.removeCallbacks(usbProbe)
        message = null
        target = t
        macName = t.name
        state = State.CONNECTING
        startSession(t, quiet = false, timeoutMs = WIFI_TIMEOUT_MS)
        render()
    }

    /** "Connect by address": a Mac remembered at that address keeps its id, else pairing starts. */
    private fun connectAddress() {
        val address = addressField.text.toString().trim()
        if (address.isEmpty() || state != State.IDLE) {
            addressField.requestFocus()
            return
        }
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(addressField.windowToken, 0)
        val known = prefs.knownMacs().firstOrNull { address in it.addresses }
        connectWifi(
            Target(false, listOf(Endpoint(address, Target.WIFI_PORT)), known?.serverIdHex, known?.name ?: address, typed = true),
        )
    }

    private fun probeUsb() {
        if (!started || usbPaused || session != null || state != State.IDLE) return
        startSession(Target.usb(), quiet = true, timeoutMs = USB_PROBE_TIMEOUT_MS)
    }

    private fun reconnectNow() {
        val t = target
        if (!started || t == null || state != State.RECONNECTING) return
        if (SystemClock.uptimeMillis() >= reconnectDeadline) {
            fail(getString(R.string.status_lost, displayName()))
            return
        }
        startSession(t, quiet = false, timeoutMs = RECONNECT_TIMEOUT_MS)
    }

    private fun startSession(t: Target, quiet: Boolean, timeoutMs: Int) {
        endSession()
        val bounds = windowManager.maximumWindowMetrics.bounds
        panelWidth = bounds.width()
        panelHeight = bounds.height()
        val s = Session(
            t, prefs, deviceName(), panelWidth, panelHeight, resources.displayMetrics.densityDpi,
            timeoutMs, quiet, this, decoder, stats,
        )
        session = s
        decoder.session = s
        stats.reset()
        s.start()
    }

    private fun endSession() {
        session?.close()
        session = null
        decoder.session = null
        decoder.clear()
        input.session = null
        input.reset()
    }

    /** Back gesture or Cancel pairing: close the connection and show the connect screen. */
    private fun leave() {
        handler.removeCallbacks(reconnect)
        val wasStreaming = streaming()
        if (wasStreaming && target?.usb == true) {
            usbPaused = true
            usbMacName = macName
            rebuildRows()
        }
        endSession()
        message = if (wasStreaming) getString(R.string.status_left, displayName()) else null
        state = State.IDLE
        enterConnect(probeNow = false)
    }

    private fun fail(text: String) {
        handler.removeCallbacks(reconnect)
        endSession()
        message = text
        state = State.IDLE
        rebuildRows() // a pairing key may have been deleted
        enterConnect(probeNow = false)
    }

    private fun enterConnect(probeNow: Boolean) {
        if (themeStale) {
            recreate()
            return
        }
        rebuildRows() // SERVER_INFO or a handshake may have remembered a Mac
        render()
        if (!started) return
        discovery.start()
        handler.removeCallbacks(usbProbe)
        if (!usbPaused) handler.postDelayed(usbProbe, if (probeNow) 0 else USB_RETRY_MS)
    }

    // ---- Session callbacks (main thread) ----

    override fun onPairingCode(session: Session, code: Int) {
        if (session !== this.session) return
        val digits = String.format(Locale.ROOT, "%06d", code)
        pairCode.text = digits.substring(0, 3) + " " + digits.substring(3)
        pairCode.contentDescription = getString(R.string.pair_code_description, digits.toCharArray().joinToString(" "))
        state = State.PAIRING
        render()
    }

    override fun onServerInfo(session: Session, macName: String) {
        if (session !== this.session) return
        handler.removeCallbacks(usbProbe)
        handler.removeCallbacks(reconnect)
        target = session.target
        this.macName = macName
        message = null
        state = State.STREAMING
        input.session = session
        discovery.stop()
        render()
    }

    override fun onVideoSize(session: Session, width: Int, height: Int) {
        if (session !== this.session) return
        videoWidth = width
        videoHeight = height
        layoutVideo()
    }

    override fun onNotice(session: Session, code: Int, message: String) {
        if (session !== this.session) return
        fail(
            when (code) {
                1 -> getString(R.string.notice_permission)
                2 -> getString(R.string.notice_busy)
                3 -> getString(R.string.notice_display)
                else -> message
            },
        )
    }

    override fun onEnded(session: Session, end: Session.End) {
        if (session !== this.session) return
        this.session = null
        decoder.session = null
        input.session = null
        input.reset()
        val name = displayName()
        val retryable = end == Session.End.LOST || end == Session.End.UNREACHABLE
        when {
            streaming() && retryable -> {
                if (state == State.STREAMING) {
                    state = State.RECONNECTING
                    reconnectDeadline = SystemClock.uptimeMillis() + RECONNECT_WINDOW_MS
                    render()
                }
                handler.postDelayed(reconnect, RECONNECT_DELAY_MS)
            }
            session.quiet -> {
                decoder.clear()
                if (started && !usbPaused) handler.postDelayed(usbProbe, USB_RETRY_MS)
            }
            else -> fail(
                when (end) {
                    Session.End.UNREACHABLE ->
                        if (session.target.usb) getString(R.string.status_usb_failed)
                        else if (session.target.typed) getString(R.string.status_unreachable_address, name)
                        else getString(R.string.status_unreachable, name)
                    Session.End.REJECTED -> getString(R.string.status_rejected)
                    Session.End.KEYS_DISAGREE -> getString(R.string.status_keys_reset, name)
                    Session.End.LOST -> getString(R.string.status_lost, name)
                    Session.End.PROTOCOL -> getString(R.string.status_protocol, name)
                },
            )
        }
    }

    // ---- UI ----

    private fun displayName(): String = macName ?: target?.name ?: getString(R.string.your_mac)

    private fun render() {
        val streaming = streaming()
        streamRoot.visibility = if (streaming) View.VISIBLE else View.GONE
        connectRoot.visibility = if (streaming) View.GONE else View.VISIBLE
        setStreamWindow(streaming)
        setWifiLock(streaming && target?.usb == false)
        setBackHandled(state != State.IDLE)
        if (state == State.RECONNECTING) {
            reconnecting.text = getString(R.string.reconnecting, displayName())
            reconnecting.visibility = View.VISIBLE
        } else {
            reconnecting.visibility = View.GONE
        }
        if (streaming) return

        status.text = when (state) {
            State.CONNECTING -> getString(R.string.status_connecting, displayName())
            State.PAIRING -> getString(R.string.status_pairing, displayName())
            else -> message ?: getString(if (macList.childCount == 0) R.string.status_searching else R.string.status_pick)
        }
        pairing.visibility = if (state == State.PAIRING) View.VISIBLE else View.GONE
        macList.visibility = if (state == State.PAIRING) View.GONE else View.VISIBLE
        addressSection.visibility = macList.visibility
        setRowsEnabled()
    }

    /** Scrolls so the address field and its Connect button sit above the keyboard. */
    private fun revealAddressSection() {
        val scroll = connectRoot as ScrollView
        val rect = Rect(0, 0, addressSection.width, addressSection.height)
        scroll.offsetDescendantRectToMyCoords(addressSection, rect)
        val gap = (16 * resources.displayMetrics.density).roundToInt()
        val needed = rect.bottom + gap - (scroll.height - scroll.paddingBottom)
        if (needed > scroll.scrollY) scroll.smoothScrollTo(0, needed)
    }

    private fun setRowsEnabled() {
        val enabled = state == State.IDLE
        for (i in 0 until macList.childCount) macList.getChildAt(i).isEnabled = enabled
        addressField.isEnabled = enabled
        addressConnect.isEnabled = enabled
    }

    /** Holds Wi-Fi out of power save while streaming over it; power save adds 30-80 ms of ping. */
    private fun setWifiLock(on: Boolean) {
        if (on == wifiLock.isHeld) return
        if (on) wifiLock.acquire() else wifiLock.release()
    }

    private fun rebuildRows() {
        macList.removeAllViews()
        if (usbPaused) {
            addRow(usbMacName ?: getString(R.string.usb_row_title), getString(R.string.usb_row_subtitle)) { connectUsb() }
        }
        rows = mergeRows()
        for (row in rows) {
            val paired = row.serverIdHex != null && prefs.hasPairingKey(row.serverIdHex)
            addRow(row.name, getString(if (paired) R.string.mac_paired else R.string.mac_unpaired)) {
                connectWifi(Target(false, row.endpoints, row.serverIdHex, row.name))
            }
        }
        setRowsEnabled()
    }

    /** Bonjour results first (their address tried first), then remembered Macs Bonjour did not see. */
    private fun mergeRows(): List<MacRow> {
        val known = LinkedHashMap<String, KnownMac>()
        for (mac in prefs.knownMacs()) known[mac.serverIdHex] = mac
        val out = ArrayList<MacRow>()
        for (mac in macs) {
            val host = mac.host.hostAddress ?: continue
            val remembered = mac.serverIdHex?.let { known.remove(it) }
            val endpoints = ArrayList<Endpoint>()
            endpoints.add(Endpoint(host, mac.port))
            remembered?.addresses?.forEach { if (it != host) endpoints.add(Endpoint(it, Target.WIFI_PORT)) }
            out.add(MacRow(mac.name, mac.serverIdHex, endpoints))
        }
        for (mac in known.values) {
            if (mac.addresses.isEmpty()) continue
            out.add(MacRow(mac.name, mac.serverIdHex, mac.addresses.map { Endpoint(it, Target.WIFI_PORT) }))
        }
        return out
    }

    private fun addRow(title: String, subtitle: String, onClick: () -> Unit) {
        val row = layoutInflater.inflate(R.layout.item_mac, macList, false) as Button
        val text = SpannableStringBuilder(title).append('\n')
        val start = text.length
        // Smaller size only: the theme's secondary gray is under 4.5:1 on One UI button fills.
        text.append(subtitle)
        text.setSpan(RelativeSizeSpan(0.82f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        row.text = text
        row.setOnClickListener { onClick() }
        macList.addView(row)
    }

    private fun setStreamWindow(on: Boolean) {
        if (on == immersive) return
        immersive = on
        val controller = window.insetsController
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsets.Type.systemBars())
            // Deliver touch and pen samples as they arrive instead of batching them per frame.
            streamRoot.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_POINTER)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller?.show(WindowInsets.Type.systemBars())
            streamRoot.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_NONE)
            videoWidth = 0
            videoHeight = 0
        }
    }

    private fun setBackHandled(on: Boolean) {
        if (on == backRegistered) return
        backRegistered = on
        if (on) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback)
        } else {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        }
    }

    /** Aspect-fits the SurfaceView in the stream area and maps input to the displayed rect. */
    private fun layoutVideo() {
        val rw = streamRoot.width
        val rh = streamRoot.height
        if (rw == 0 || rh == 0) return
        var w = rw
        var h = rh
        if (videoWidth > 0 && videoHeight > 0) {
            val scale = minOf(rw.toFloat() / videoWidth, rh.toFloat() / videoHeight)
            w = (videoWidth * scale).roundToInt()
            h = (videoHeight * scale).roundToInt()
        }
        val lp = video.layoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            video.layoutParams = lp
        }
        streamRoot.getLocationInWindow(location)
        input.setVideoRect(location[0] + (rw - w) * 0.5f, location[1] + (rh - h) * 0.5f, w.toFloat(), h.toFloat())
    }

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private companion object {
        const val ACTION_CONNECT_USB = "com.azhardhiaulhaq.macpad.CONNECT_USB"
        const val USB_PROBE_TIMEOUT_MS = 300
        const val USB_TIMEOUT_MS = 2_000
        /** Per address; a Mac on the same LAN answers well within this. */
        const val WIFI_TIMEOUT_MS = 1_500
        const val RECONNECT_TIMEOUT_MS = 1_500
        const val USB_RETRY_MS = 3_000L
        const val RECONNECT_WINDOW_MS = 10_000L
        const val RECONNECT_DELAY_MS = 500L
        const val KEY_USB_PAUSED = "usb_paused"
        const val KEY_MESSAGE = "message"
        const val KEY_USB_MAC_NAME = "usb_mac_name"
    }
}
