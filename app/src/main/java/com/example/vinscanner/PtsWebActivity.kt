package com.example.vinscanner

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewTreeObserver
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.TextView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.annotation.StringRes
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.FileOutputStream
import java.lang.StringBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import org.json.JSONArray
import kotlin.math.abs

/**
 * Activity for interacting with the Ford PTS web portal, including autofill of credentials and VIN,
 * DOM snapshotting, and toggling between desktop and mobile user agents.
 */
class PtsWebActivity : AppCompatActivity() {

    // ...existing fields...
    private val vinRetryRunnable = Runnable { runVinAutofillAttempt() }
    private val overlayEntries = mutableListOf<OverlayEntry>()
    private var wiringContextActive = false
    private var pendingDomCapture: Runnable? = null
    private var wiringFrameZoom = 1f
    private var pendingZoomReapply: Runnable? = null

    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val PREF_FILE = "pts_prefs"
        private const val KEY_USER = "pts_user"
        private const val KEY_PASS = "pts_pass"
        private const val TAG = "PtsWebActivity"
        private const val EXTRA_DEBUG_DOM = "debug_dom"
        private const val MAX_AUTOFILL_ATTEMPTS = 6
        private const val REQ_WRITE_DOWNLOADS = 201
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
    }

    private lateinit var webView: WebView
    private lateinit var summaryCard: LinearLayout
    private lateinit var summaryTitle: TextView
    private lateinit var summaryBody: TextView
    private lateinit var summaryStatus: TextView
    private lateinit var zoomHint: TextView
    private lateinit var wiringNavBar: LinearLayout
    private lateinit var btnPrevPage: Button
    private lateinit var btnNextPage: Button
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var rootContainer: FrameLayout
    private val domCaptureScript = """
        (function(){
            function collectText(selector, limit){
                var nodes = Array.prototype.slice.call(document.querySelectorAll(selector || ''), 0, limit || 6);
                return nodes.map(function(node){
                    return (node && (node.innerText || node.textContent) || '').replace(/\s+/g,' ').trim();
                }).filter(function(txt){ return txt.length; });
            }
            function isRapVisible(){
                var modal = document.querySelector('#RAPModal');
                if(!modal){ return false; }
                var style = window.getComputedStyle(modal);
                return style.display !== 'none' && style.visibility !== 'hidden' && modal.offsetParent !== null;
            }
            function describeVinInputs(doc){
                try {
                    var inputs = doc.querySelectorAll('input');
                    var matches = 0;
                    for(var i=0;i<inputs.length;i++){
                        var meta = ((inputs[i].placeholder||'') + ' ' + (inputs[i].name||'') + ' ' + (inputs[i].id||'') + ' ' + (inputs[i].getAttribute('aria-label')||'')).toLowerCase();
                        if(meta.indexOf('vin') !== -1){ matches++; }
                    }
                    return matches;
                } catch(err) {
                    return 0;
                }
            }
            function collectFrames(){
                var frames = [];
                var maxFrames = Math.min(window.frames.length || 0, 4);
                for(var i=0;i<maxFrames;i++){
                    var entry = { index:i };
                    try {
                        var doc = window.frames[i].document;
                        entry.title = doc.title || '';
                        entry.url = doc.URL || '';
                        entry.vinInputs = describeVinInputs(doc);
                        entry.outerHtml = doc.documentElement ? doc.documentElement.outerHTML.substring(0, 4000) : '';
                    } catch(err){
                        entry.error = String(err);
                    }
                    frames.push(entry);
                }
                return frames;
            }
            return JSON.stringify({
                html: document.documentElement.outerHTML,
                meta: {
                    title: document.title,
                    readyState: document.readyState,
                    rapVisible: isRapVisible(),
                    summaryBlocks: collectText('.details-container', 4),
                    statusText: collectText('#scStatus, #scLabel', 4),
                    timestamp: (new Date()).toISOString()
                },
                frames: collectFrames()
            });
        })();
    """.trimIndent()
    private var vinToOpen: String = ""
    private var vinAutofillAttempts = 0
    private var vinAutofillInFlight = false
    private var vinAutofillComplete = false
    private var vinAutofillSuccessShown = false
    private var pendingDomPayload: Pair<String, String?>? = null
    private var desktopMode = false
    private var originalUserAgent: String? = null
    private var manualFillButton: Button? = null
    private var frameRedirectAttempted = false
    private var lastFinishedUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    @androidx.camera.core.ExperimentalGetImage // For CameraX requirement on intent extra (if needed)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pts_web)

        webView = findViewById(R.id.ptsWebView)
        rootContainer = findViewById(android.R.id.content) as FrameLayout
        summaryCard = findViewById(R.id.summaryCard)
        summaryTitle = findViewById(R.id.summaryTitle)
        summaryBody = findViewById(R.id.summaryBody)
        summaryStatus = findViewById(R.id.summaryStatus)
        zoomHint = findViewById(R.id.zoomHint)
        wiringNavBar = findViewById(R.id.wiringNavBar)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        btnNextPage = findViewById(R.id.btnNextPage)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.setSupportZoom(true)
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        webView.webChromeClient = WebChromeClient()
        originalUserAgent = webView.settings.userAgentString
        webView.addJavascriptInterface(Bridge(), "VinScannerBridge")
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
             override fun onScale(detector: ScaleGestureDetector): Boolean {
                 if (!wiringContextActive) return false
                 val factor = detector.scaleFactor
                 if (!factor.isFinite()) return false
                 val newScale = (wiringFrameZoom * factor).coerceIn(0.5f, 3f)
                 if (abs(newScale - wiringFrameZoom) < 0.01f) return false
                 wiringFrameZoom = newScale
                 applyZoomToWiringFrame(wiringFrameZoom)
                 return true
             }
         })
        webView.setOnTouchListener { _, event ->
            if (wiringContextActive) {
                scaleGestureDetector.onTouchEvent(event)
            }
            false
        }
        btnPrevPage.setOnClickListener { triggerWiringNav(false) }
        btnNextPage.setOnClickListener { triggerWiringNav(true) }

        if (savedInstanceState == null) {
            try {
                val baseUa = webView.settings.userAgentString ?: ""
                val chromeUaAppend = " AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Mobile Safari/537.36"
                webView.settings.userAgentString = baseUa + chromeUaAppend
            } catch (e: Exception) {
                Log.w(TAG, "Could not adjust userAgent: ${e.message}")
            }
        }

        // Enable cookies for WebView including third-party cookies (helps SSO/ADFS flows)
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)
        } catch (e: Exception) {
            Log.w(TAG, "Could not enable cookies: ${e.message}")
        }

        val debugDom = intent.getBooleanExtra(EXTRA_DEBUG_DOM, false)

        // Save VIN for later navigation (after auth)
        vinToOpen = intent.getStringExtra(AppConstants.EXTRA_VIN)
            ?.trim()
            ?.uppercase(Locale.US)
            ?: ""

        addDebugButton()
        addFillVinButton()
        addDesktopToggleButton()
        startVinRequiredWatcher()
        showZoomHintOnce()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                Log.i(TAG, "shouldOverrideUrlLoading: $url")
                return false // allow WebView to load
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                url ?: return
                Log.i(TAG, "onPageFinished: $url")
                val host = try { java.net.URL(url).host } catch (_: Exception) { "" }
                val creds = getSavedCredentials()

                if (host.contains("faust.idp.ford.com") || host.contains("corp.sts.ford.com") || host.contains("adfs")) {
                    creds?.let { (u, p) ->
                        autofillCredentialsOnlyFord(u, p)
                        showLongToast(R.string.pts_toast_autofill_prompt)
                    }
                }

                if (debugDom) {
                    captureDomSnapshot("auto-debug")
                 }

                if (shouldAttemptVinAutofill(url)) {
                    startVinAutofillLoop()
                } else {
                    extractSummary()
                    detectWiringContext()
                    enforceDesktopViewport()
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                val msg = "WebView error ${error?.errorCode}: ${error?.description}"
                Log.e(TAG, msg)
                AlertDialog.Builder(this@PtsWebActivity)
                    .setTitle(R.string.pts_error_page_title)
                    .setMessage(getString(R.string.pts_error_page_message, msg))
                    .setPositiveButton(R.string.btn_retry) { _, _ -> view?.reload() }
                    .setNegativeButton(R.string.vehicle_info_close) { _, _ ->
                        request?.url?.toString()?.let { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, it.toUri())) }
                    }
                    .show()
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                val status = errorResponse?.statusCode
                val url = request?.url?.toString()
                Log.e(TAG, "HTTP error $status while loading $url")
                // Surface a simple message to the user if a server error occurred
                if (status != null && status >= 500) {
                    AlertDialog.Builder(this@PtsWebActivity)
                        .setTitle(R.string.pts_error_server_title)
                        .setMessage(getString(R.string.pts_error_server_message, status))
                        .setPositiveButton(R.string.vehicle_info_retry) { _, _ ->
                            request?.url?.toString()?.let { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, it.toUri())) }
                        }
                        .setNegativeButton(R.string.vehicle_info_close, null)
                        .show()

                    request?.url?.toString()?.let { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, it.toUri())) }
                }
            }
        }

        val baseUrl = AppConstants.PTS_BASE_URL

        if (savedInstanceState == null) {
            val saved = getSavedCredentials()
            if (saved != null) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.pts_dialog_saved_title)
                    .setMessage(R.string.pts_dialog_saved_message)
                    .setPositiveButton(R.string.btn_use_saved) { _, _ ->
                        webView.loadUrl(baseUrl)
                    }
                    .setNeutralButton(R.string.btn_enter_different) { _, _ ->
                        showCredentialDialog(saved.first, saved.second, baseUrl)
                    }
                    .setNegativeButton(R.string.btn_open_page) { _, _ ->
                        webView.loadUrl(baseUrl)
                    }
                    .setCancelable(false)
                    .show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle(R.string.pts_dialog_optional_title)
                    .setMessage(R.string.pts_dialog_optional_message)
                    .setPositiveButton(R.string.btn_enter_credentials) { _, _ ->
                        showCredentialDialog(null, null, baseUrl)
                    }
                    .setNegativeButton(R.string.btn_cancel) { _, _ ->
                        webView.loadUrl(baseUrl)
                    }
                    .setCancelable(false)
                    .show()
            }
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        pendingDomCapture?.let { webView.removeCallbacks(it) }
        pendingDomCapture = null
        pendingZoomReapply?.let { webView.removeCallbacks(it) }
        pendingZoomReapply = null
        super.onDestroy()
    }

    private fun startVinRequiredWatcher() {
        val js = """
            (function(){
                var observer; 
                function notifyIfVisible(el){
                    if(!el) return;
                    var style = window.getComputedStyle(el);
                    var visible = style.display !== 'none' && style.visibility !== 'hidden' && el.offsetParent !== null;
                    if(visible){
                        window.VinScannerBridge && window.VinScannerBridge.onVinRequired();
                    }
                }
                function init(){
                    var modal = document.querySelector('#RAPModal');
                    if(!modal) return;
                    notifyIfVisible(modal);
                    observer = new MutationObserver(function(){ notifyIfVisible(modal); });
                    observer.observe(modal, {attributes:true, attributeFilter:['style','class']});
                }
                if(document.readyState === 'loading'){
                    document.addEventListener('DOMContentLoaded', init);
                } else {
                    init();
                }
                return true;
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun showZoomHintOnce() {
        zoomHint.visibility = View.VISIBLE
        zoomHint.postDelayed({ zoomHint.visibility = View.GONE }, 4000)
    }

    private fun triggerWiringNav(next: Boolean) {
        val js = if (next) {
            """
                (function(){
                    function tryNavigate(doc){
                        if(!doc) return false;
                        var btn = doc.querySelector('#fwdbutton');
                        if(btn){ btn.click(); return true; }
                        var sel = doc.querySelector('#PageListHolder');
                        if(sel && sel.selectedIndex < sel.options.length - 1){
                            sel.selectedIndex += 1;
                            sel.dispatchEvent(new Event('change', {bubbles:true}));
                            return true;
                        }
                        return false;
                    }
                    if(tryNavigate(document)) return true;
                    for(var i=0;i<window.frames.length;i++){
                        try { if(tryNavigate(window.frames[i].document)) return true; } catch(e){}
                    }
                    return false;
                })();
            """.trimIndent()
        } else {
            """
                (function(){
                    function tryNavigate(doc){
                        if(!doc) return false;
                        var back = doc.querySelector('a[title="Back"]');
                        if(back){ back.click(); return true; }
                        var sel = doc.querySelector('#PageListHolder');
                        if(sel && sel.selectedIndex > 0){
                            sel.selectedIndex -= 1;
                            sel.dispatchEvent(new Event('change', {bubbles:true}));
                            return true;
                        }
                        return false;
                    }
                    if(tryNavigate(document)) return true;
                    for(var i=0;i<window.frames.length;i++){
                        try { if(tryNavigate(window.frames[i].document)) return true; } catch(e){}
                    }
                    return false;
                })();
            """.trimIndent()
        }
        webView.evaluateJavascript(js, null)
        scheduleDomCapture(if (next) "wiring-next" else "wiring-prev")
        scheduleWiringZoomReapply()
    }

    private fun detectWiringContext() {
        val js = """
            (function(){
                var hasWiring = document.querySelector('#PageListHolder');
                return !!hasWiring;
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            val isWiring = result == "true"
            wiringNavBar.visibility = if (isWiring) View.VISIBLE else View.GONE
            wiringContextActive = isWiring
            if (!isWiring) {
                wiringFrameZoom = 1f
            }
            configureViewportForMode()
            if (isWiring) {
                scheduleWiringZoomReapply()
            }
        }
    }

    private fun configureViewportForMode() {
        val wide = desktopMode || wiringContextActive
        webView.settings.apply {
            useWideViewPort = wide
            loadWithOverviewMode = wide
        }
        if (wide) enforceDesktopViewport() else webView.setInitialScale(0)
    }

    private fun enforceDesktopViewport() {
        val js = """
            (function(){
                try {
                    var meta = document.querySelector('meta[name="viewport"]');
                    if(!meta){
                        meta = document.createElement('meta');
                        meta.name = 'viewport';
                        document.head && document.head.appendChild(meta);
                    }
                    if(meta){
                        meta.setAttribute('content','width=device-width,initial-scale=0.9,minimum-scale=0.4,maximum-scale=5,user-scalable=yes');
                    }
                    document.body && (document.body.style.zoom = '0.95');
                } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun applyZoomToWiringFrame(scale: Float) {
        val sanitizedScale = scale.coerceIn(0.5f, 3f)
        val js = """
            (function(){
                try {
                    var frame = document.getElementById('svgFrame');
                    if(frame && frame.contentWindow && frame.contentWindow.document) {
                        var doc = frame.contentWindow.document;
                        var factor = $sanitizedScale;
                        var root = doc.documentElement;
                        if(root && root.style){
                            root.style.transformOrigin = '0 0';
                            root.style.transform = 'scale(' + factor + ')';
                        }
                        if(doc.body && doc.body.style){
                            doc.body.style.transformOrigin = '0 0';
                            doc.body.style.transform = 'scale(' + factor + ')';
                        }
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
     }

    private fun addDebugButton() {
        try {
            val btn = Button(this).apply {
                setText(R.string.pts_button_dom)
                alpha = 0.8f
                setOnClickListener {
                    // Dump DOM snippet and cookies
                    captureDomSnapshot("manual-button") {
                        Toast.makeText(this@PtsWebActivity, R.string.pts_dom_logged, Toast.LENGTH_SHORT).show()
                    }
                    try {
                        val cookies = CookieManager.getInstance().getCookie(webView.url ?: "")
                        Log.i(TAG, "Cookies for ${webView.url}: $cookies")
                        Toast.makeText(this@PtsWebActivity, R.string.pts_cookies_logged, Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) { /* ignore */ }
                }
            }
            val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            params.gravity = Gravity.TOP or Gravity.END
            params.setMargins(0, 80, 20, 0)
            addContentView(btn, params)
            registerOverlay(btn, params)
            applyDraggableOverlay(btn, params)
        } catch (e: Exception) {
            Log.w(TAG, "Could not add debug button: ${e.message}")
        }
    }

    private fun showCredentialDialog(prefilledUser: String?, prefilledPass: String?, baseUrl: String = AppConstants.PTS_BASE_URL) {
        val usernameInput = EditText(this).apply { hint = getString(R.string.pts_hint_username); setText(prefilledUser ?: "") }
        val passwordInput = EditText(this).apply {
            hint = getString(R.string.pts_hint_password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefilledPass ?: "")
        }
        val rememberCheckbox = CheckBox(this).apply { setText(R.string.pts_remember_credentials) }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
            addView(usernameInput)
            addView(passwordInput)
            addView(rememberCheckbox)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.pts_dialog_credentials_title)
            .setView(container)
            .setPositiveButton(R.string.btn_login) { _, _ ->
                val user = usernameInput.text.toString()
                val pass = passwordInput.text.toString()
                if (rememberCheckbox.isChecked) saveCredentials(user, pass)
                // Load base URL; onPageFinished will autofill when the ADFS/IDP page arrives
                webView.loadUrl(baseUrl)
                // Also attempt immediate autofill in case login form is already present
                autofillCredentialsOnlyFord(user, pass)
                showLongToast(R.string.pts_toast_credentials_filled)
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                webView.loadUrl(baseUrl)
            }
            .show()
    }

    private fun autofillCredentialsOnlyFord(user: String, pass: String) {
        // Build a JS script that fills username/password fields but does NOT click/submit
        // Prioritize Ford-specific IDs first (#userName, #password, #btn-id, #btn-sign-in)
        val js = StringBuilder()
        js.append("(function(){")
        js.append("try{")
        // Ford-specific selectors first
        js.append("var elU = document.querySelector('#userName'); if(elU){elU.focus(); elU.value='${escapeForJs(user)}'; elU.dispatchEvent(new Event('input'));}")
        js.append("var elP = document.querySelector('#password'); if(elP){elP.focus(); elP.value='${escapeForJs(pass)}'; elP.dispatchEvent(new Event('input'));}")
        // Also try general selectors
        js.append("var uSelectors=['input[id*=user]', 'input[name*=user]', 'input[type=text]', 'input[id*=username]', 'input[name*=username]'];")
        js.append("for(var i=0;i<uSelectors.length;i++){var el=document.querySelector(uSelectors[i]); if(el && (!document.querySelector('#userName') || el!==document.querySelector('#userName'))){el.focus(); el.value='${escapeForJs(user)}'; el.dispatchEvent(new Event('input')); break;}};")
        js.append("var pSelectors=['input[type=password]', 'input[id*=pass]', 'input[name*=pass]', 'input[id*=password]', 'input[name*=password]'];")
        js.append("for(var i=0;i<pSelectors.length;i++){var el=document.querySelector(pSelectors[i]); if(el && (!document.querySelector('#password') || el!==document.querySelector('#password'))){el.focus(); el.value='${escapeForJs(pass)}'; el.dispatchEvent(new Event('input')); break;}};")
        js.append("}catch(e){/*ignore*/} })();")

        runOnUiThread {
            webView.evaluateJavascript(js.toString(), null)
        }
    }

    private fun shouldAttemptVinAutofill(url: String): Boolean {
        if (vinToOpen.isBlank() || vinAutofillComplete) return false
        val normalized = url.lowercase(Locale.US)
        return normalized.contains("dealerconnection") || normalized.contains("lookupvehiclebyvin")
    }

    private fun startVinAutofillLoop() {
        if (vinAutofillInFlight || vinAutofillComplete || vinToOpen.isBlank()) return
        webView.removeCallbacks(vinRetryRunnable)
        manualFillButton?.isEnabled = false
        vinAutofillInFlight = true
        vinAutofillAttempts = 0
        runVinAutofillAttempt()
    }

    private fun runVinAutofillAttempt() {
        if (vinAutofillComplete) {
            vinAutofillInFlight = false
            webView.removeCallbacks(vinRetryRunnable)
            manualFillButton?.isEnabled = true
            return
        }
        if (vinAutofillAttempts >= MAX_AUTOFILL_ATTEMPTS) {
            vinAutofillInFlight = false
            webView.removeCallbacks(vinRetryRunnable)
            manualFillButton?.isEnabled = true
            Toast.makeText(this, R.string.pts_autofill_error, Toast.LENGTH_SHORT).show()
            return
        }
        vinAutofillAttempts++
        webView.evaluateJavascript(buildVinAutofillScript(vinToOpen)) { rawResult ->
            val result = rawResult?.removeSurrounding("\"") ?: ""
            Log.i(TAG, "VIN autofill status: $result (attempt $vinAutofillAttempts)")
            when (result) {
                "filled-submitted", "filled-only" -> {
                    vinAutofillComplete = true
                    vinAutofillInFlight = false
                    webView.removeCallbacks(vinRetryRunnable)
                    manualFillButton?.isEnabled = true
                    manualFillButton?.visibility = View.VISIBLE
                    if (!vinAutofillSuccessShown) {
                        Toast.makeText(this, R.string.pts_autofill_success, Toast.LENGTH_SHORT).show()
                        vinAutofillSuccessShown = true
                    }
                    scheduleDomCapture("vin-autofilled")
                 }
                else -> scheduleNextVinAttempt()
            }
        }
    }

    private fun scheduleNextVinAttempt() {
        if (!vinAutofillInFlight || vinAutofillComplete) return
        webView.postDelayed(vinRetryRunnable, 600)
    }

    private fun buildVinAutofillScript(vin: String): String {
        val escapedVin = escapeForJs(vin)
        return """
            (function(){
                try{
                    var vinInput = null;
                    var selectors = ['#vin', '#VIN', '#vinNumber', '#VINNumber', '#vinInput', '#lookupVin', 'input[name="vin"]', 'input[name="VIN"]', 'input[id*="Vin"]', 'input[name*="Vin"]'];
                    for (var i=0;i<selectors.length;i++) {
                        var el = document.querySelector(selectors[i]);
                        if (el) { vinInput = el; break; }
                    }
                    if (!vinInput) {
                        var candidates = document.querySelectorAll('input');
                        for (var j=0;j<candidates.length;j++) {
                            var meta = ((candidates[j].placeholder||'') + ' ' + (candidates[j].getAttribute('aria-label')||'') + ' ' + (candidates[j].id||'') + ' ' + (candidates[j].name||'')).toLowerCase();
                            if (meta.indexOf('vin') !== -1) { vinInput = candidates[j]; break; }
                        }
                    }
                    if (!vinInput) { return 'missing-input'; }

                    vinInput.focus();
                    vinInput.value = '$escapedVin';
                    vinInput.dispatchEvent(new Event('input', { bubbles: true }));
                    vinInput.dispatchEvent(new Event('change', { bubbles: true }));

                    var button = null;
                    var btnSelectors = ['#btnLookupVin', '#lookupButton', '#btnGo', '#btnLookup', 'button[name*="lookup"]', 'button[id*="lookup"]', 'button[id*="Go"]', 'button[type="submit"]', 'input[type="submit"]', 'input[value*="Go"]', 'a[role="button"]'];
                    for (var k=0;k<btnSelectors.length;k++) {
                        var btnCandidate = document.querySelector(btnSelectors[k]);
                        if (btnCandidate) { button = btnCandidate; break; }
                    }
                    if (!button) {
                        var allButtons = document.querySelectorAll('button, input[type="button"], input[type="submit"], a[role="button"]');
                        for (var n=0;n<allButtons.length;n++) {
                            var metaBtn = ((allButtons[n].innerText||'') + ' ' + (allButtons[n].value||'') + ' ' + (allButtons[n].id||'') + ' ' + (allButtons[n].name||'')).toLowerCase();
                            if (metaBtn.indexOf('vin') !== -1 || metaBtn.indexOf('lookup') !== -1 || metaBtn.indexOf('go') !== -1 || metaBtn.indexOf('search') !== -1) {
                                button = allButtons[n];
                                break;
                            }
                        }
                    }
                    if (button) {
                        button.focus();
                        button.click();
                        return 'filled-submitted';
                    }
                    if (vinInput.form) {
                        vinInput.form.submit();
                        return 'filled-submitted';
                    }
                    return 'filled-only';
                }catch(e){
                    return 'vin-autofill-error';
                }
            })();
        """.trimIndent()
    }

    private fun addFillVinButton() {
        if (vinToOpen.isBlank()) return
        try {
            val btn = Button(this).apply {
                setText(R.string.pts_button_paste_vin)
                alpha = 0.9f
                visibility = View.VISIBLE
                setOnClickListener {
                    hideSummaryCard()
                    webView.removeCallbacks(vinRetryRunnable)
                    vinAutofillComplete = false
                    vinAutofillInFlight = false
                    vinAutofillSuccessShown = false
                    manualFillButton?.isEnabled = false
                    startVinAutofillLoop()
                    scheduleDomCapture("paste-vin")
                }
            }
            manualFillButton = btn
            val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            params.gravity = Gravity.BOTTOM or Gravity.END
            params.setMargins(0, 0, 24, 24)
            addContentView(btn, params)
            registerOverlay(btn, params)
            applyDraggableOverlay(btn, params)
        } catch (e: Exception) {
            Log.w(TAG, "Could not add fill button: ${e.message}")
        }
    }

    private fun addDesktopToggleButton() {
        try {
            val btn = Button(this).apply {
                setText(if (desktopMode) R.string.pts_button_mobile else R.string.pts_button_desktop)
                alpha = 0.9f
                setOnClickListener {
                    desktopMode = !desktopMode
                    setText(if (desktopMode) R.string.pts_button_mobile else R.string.pts_button_desktop)
                    toggleDesktopMode()
                }
            }
            val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            params.gravity = Gravity.BOTTOM or Gravity.START
            params.setMargins(24, 0, 0, 24)
            addContentView(btn, params)
            registerOverlay(btn, params)
            applyDraggableOverlay(btn, params)
        } catch (e: Exception) {
            Log.w(TAG, "Could not add desktop toggle: ${e.message}")
        }
    }

    private fun toggleDesktopMode() {
        hideSummaryCard()
        startVinRequiredWatcher()
        val settings = webView.settings
        if (desktopMode) {
            settings.userAgentString = DESKTOP_UA
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            Toast.makeText(this, R.string.pts_desktop_enabled, Toast.LENGTH_SHORT).show()
        } else {
            settings.userAgentString = originalUserAgent ?: DESKTOP_UA
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false
            Toast.makeText(this, R.string.pts_mobile_enabled, Toast.LENGTH_SHORT).show()
        }
        configureViewportForMode()
        webView.reload()
        scheduleDomCapture("desktop-toggle")
        if (wiringContextActive) {
            scheduleWiringZoomReapply(900)
        }
    }

    private fun getSavedCredentials(): Pair<String, String>? {
        return try {
            val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(
                this,
                PREF_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val user = prefs.getString(KEY_USER, null)
            val pass = prefs.getString(KEY_PASS, null)
            if (!user.isNullOrEmpty() && !pass.isNullOrEmpty()) Pair(user, pass) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun saveCredentials(user: String, pass: String) {
        try {
            val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(
                this,
                PREF_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            prefs.edit {
                putString(KEY_USER, user)
                putString(KEY_PASS, pass)
            }
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun escapeForJs(s: String): String {
        return s.replace("\\", "\\\\").replace("'", "\\'")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_WRITE_DOWNLOADS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingDomPayload?.let { (html, url) ->
                    writeDomToLegacyDownloads(html, url)
                }
            } else {
                Toast.makeText(this, R.string.pts_storage_permission_denied, Toast.LENGTH_LONG).show()
            }
            pendingDomPayload = null
        }
    }

    private fun captureDomSnapshot(reason: String, onComplete: (() -> Unit)? = null) {
        if (!this::webView.isInitialized) return
        webView.evaluateJavascript(domCaptureScript) { payload ->
            recordDomSnapshot(webView.url, payload, reason)
            onComplete?.invoke()
        }
    }

    private fun recordDomSnapshot(sourceUrl: String?, jsPayload: String?, reason: String? = null) {
        val raw = decodeJsString(jsPayload)
        if (raw.isEmpty()) return
        var htmlBody = raw
        var meta: JSONObject? = null
        var frames: JSONArray? = null
        try {
            val obj = JSONObject(raw)
            htmlBody = obj.optString("html", raw)
            meta = obj.optJSONObject("meta")
            frames = obj.optJSONArray("frames")
        } catch (_: Exception) {
            // payload was plain HTML
        }
        val metaPayload = meta ?: JSONObject()
        metaPayload.put("sourceUrl", sourceUrl ?: webView.url ?: "")
        metaPayload.put("desktopMode", desktopMode)
        metaPayload.put("wiringContext", wiringContextActive)
        metaPayload.put("vin", vinToOpen)
        if (!reason.isNullOrBlank()) metaPayload.put("captureReason", reason)

        val builder = StringBuilder()
        builder.append("<!-- VinScannerMeta: ").append(metaPayload.toString()).append(" -->\n")
        builder.append(htmlBody)
        if (frames != null) {
            for (i in 0 until frames.length()) {
                val frameObj = frames.optJSONObject(i) ?: continue
                val frameHtml = frameObj.optString("outerHtml")
                frameObj.remove("outerHtml")
                builder.append("\n<!-- VinScannerFrameMeta: ").append(frameObj.toString()).append(" -->\n")
                if (frameHtml.isNotEmpty()) {
                    builder.append(frameHtml).append('\n')
                }
            }
        }
        persistDomToDownloads(builder.toString(), sourceUrl)
    }

    private fun decodeJsString(raw: String?): String {
        raw ?: return ""
        return try {
            JSONObject("""{"value":$raw}""").getString("value")
        } catch (e: Exception) {
            raw.trim('"')
        }
    }

    private fun persistDomToDownloads(html: String, sourceUrl: String?) {
        val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeUrl = (sourceUrl ?: "unknown").replace(Regex("[^a-zA-Z0-9]+"), "_").take(32)
        val fileName = "domdump_${safeUrl}_$time.html"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use MediaStore for Android 10+
            val resolver = applicationContext.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/html")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri)?.use { it.write(html.toByteArray()) }
                    contentValues.clear()
                    contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                    Toast.makeText(this, getString(R.string.pts_dom_saved, fileName), Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.pts_dom_save_failed, e.message ?: "unknown"), Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "Failed to create file in Downloads.", Toast.LENGTH_LONG).show()
            }
        } else {
            // Legacy: need WRITE_EXTERNAL_STORAGE permission
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                pendingDomPayload = html to sourceUrl
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_WRITE_DOWNLOADS)
                return
            }
            writeDomToLegacyDownloads(html, sourceUrl)
        }
    }

    private fun writeDomToLegacyDownloads(html: String, sourceUrl: String?) {
        val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeUrl = (sourceUrl ?: "unknown").replace(Regex("[^a-zA-Z0-9]+"), "_").take(32)
        val fileName = "domdump_${safeUrl}_$time.html"
        try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloads.exists()) downloads.mkdirs()
            val file = File(downloads, fileName)
            FileOutputStream(file).use { it.write(html.toByteArray()) }
            Toast.makeText(this, getString(R.string.pts_dom_saved, fileName), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.pts_dom_save_failed, e.message ?: "unknown"), Toast.LENGTH_LONG).show()
        }
    }

    private fun showLongToast(@StringRes resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
    }

    private fun extractSummary() {
        val js = """
            (function(){
                try {
                    var title = document.title || '';
                    var blocks = Array.from(document.querySelectorAll('.details-container'));
                    var summary = blocks.slice(0,2).map(function(block){
                        return block.innerText.replace(/\s+/g,' ').trim();
                    }).join('\n');
                    var statuses = Array.from(document.querySelectorAll('#scStatus, #scLabel'))
                        .map(function(node){return node.innerText.replace(/\s+/g,' ').trim();})
                        .filter(Boolean)
                        .join(' • ');
                    var vinRequired = !!document.querySelector('#RAPModal');
                    var hasWiring = !!document.querySelector('#PageListHolder');
                    return JSON.stringify({title:title, body:summary, status:statuses, vinRequired:vinRequired, wiring:hasWiring});
                } catch(e) {
                    return '';
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            val json = result?.trim()?.removeSurrounding("\"") ?: ""
            if (json.isNotBlank() && json != "null") {
                parseSummary(json)
            }
        }
    }

    private fun parseSummary(payload: String) {
        try {
            val obj = JSONObject(payload)
            val title = obj.optString("title")
            val body = obj.optString("body")
            val status = obj.optString("status")
            val wiring = obj.optBoolean("wiring")
            runOnUiThread {
                if (title.isNotBlank()) summaryTitle.text = title
                summaryBody.text = if (body.isNotBlank()) body else getString(R.string.summary_no_details)
                summaryStatus.text = if (status.isNotBlank()) status else getString(R.string.summary_no_status)
                summaryCard.visibility = View.VISIBLE
                wiringNavBar.visibility = if (wiring) View.VISIBLE else View.GONE
            }
        } catch (e: Exception) {
            // ignore parse failure
        }
    }

    private fun hideSummaryCard() {
        summaryCard.visibility = View.GONE
    }

    private fun registerOverlay(view: View, params: FrameLayout.LayoutParams) {
        if (overlayEntries.any { it.view === view }) return
        overlayEntries += OverlayEntry(view, params)
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            clampOverlay(v, params)
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!view.isAttachedToWindow) {
                    view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    return
                }
                clampOverlay(view, params)
            }
        })
        clampOverlay(view, params)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun applyDraggableOverlay(view: View, layoutParams: FrameLayout.LayoutParams) {
        val touchSlop = resources.displayMetrics.density * 4
        val minMargin = resources.displayMetrics.density * 8
        var isDragging = false
        view.setOnTouchListener(object : View.OnTouchListener {
            private var dX = 0f
            private var dY = 0f
            private var lastAction = MotionEvent.ACTION_UP
            private var startX = 0f
            private var startY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dX = view.x - event.rawX
                        dY = view.y - event.rawY
                        startX = event.rawX
                        startY = event.rawY
                        lastAction = MotionEvent.ACTION_DOWN
                        isDragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val parent = view.parent as View
                        val parentWidth = parent.width
                        val parentHeight = parent.height
                        val newX = (event.rawX + dX).coerceIn(minMargin - view.width, parentWidth - minMargin.toFloat())
                        val newY = (event.rawY + dY).coerceIn(minMargin - view.height, parentHeight - minMargin.toFloat())
                        layoutParams.leftMargin = newX.toInt().coerceIn(0, parentWidth - view.width)
                        layoutParams.topMargin = newY.toInt().coerceIn(0, parentHeight - view.height)
                        layoutParams.rightMargin = 0
                        layoutParams.bottomMargin = 0
                        view.layoutParams = layoutParams
                        clampOverlay(view, layoutParams)
                        lastAction = MotionEvent.ACTION_MOVE
                        isDragging = true
                    }
                    MotionEvent.ACTION_UP -> {
                        val deltaX = Math.abs(event.rawX - startX)
                        val deltaY = Math.abs(event.rawY - startY)
                        if (deltaX < touchSlop && deltaY < touchSlop && !isDragging) {
                            view.performClick()
                        }
                        lastAction = MotionEvent.ACTION_UP
                        isDragging = false
                    }
                }
                return true
            }
        })
    }

    private fun clampOverlay(view: View, params: FrameLayout.LayoutParams) {
        val parent = view.parent as? View ?: return
        if (parent.width == 0 || parent.height == 0) return
        val maxLeft = (parent.width - view.width).coerceAtLeast(0)
        val maxTop = (parent.height - view.height).coerceAtLeast(0)
        params.leftMargin = params.leftMargin.coerceIn(0, maxLeft)
        params.topMargin = params.topMargin.coerceIn(0, maxTop)
        view.layoutParams = params
    }

    override fun onResume() {
        super.onResume()
        overlayEntries.forEach { clampOverlay(it.view, it.params) }
    }

    private data class OverlayEntry(val view: View, val params: FrameLayout.LayoutParams)

    inner class Bridge {
        @android.webkit.JavascriptInterface
        fun onVinRequired() {
            runOnUiThread {
                AlertDialog.Builder(this@PtsWebActivity)
                    .setMessage(R.string.vin_required_message)
                    .setPositiveButton(R.string.vin_required_ack) { _, _ -> manualFillButton?.performClick() }
                    .setNegativeButton(R.string.vin_required_later, null)
                    .show()
                scheduleDomCapture("vin-required")
            }
        }
    }

    private fun scheduleDomCapture(reason: String, delayMs: Long = 600L) {
        if (!this::webView.isInitialized) return
        pendingDomCapture?.let { webView.removeCallbacks(it) }
        val task = Runnable { captureDomSnapshot(reason) }
        pendingDomCapture = task
        webView.postDelayed(task, delayMs)
    }

    private fun scheduleWiringZoomReapply(delayMs: Long = 700L) {
        if (!this::webView.isInitialized) return
        pendingZoomReapply?.let { webView.removeCallbacks(it) }
        val task = Runnable {
            if (wiringContextActive) {
                applyZoomToWiringFrame(wiringFrameZoom)
            }
        }
        pendingZoomReapply = task
        webView.postDelayed(task, delayMs)
    }

    /**
     * 16KB Compliance:
     * This file and its associated logic are designed to comply with the 16KB method limit for Android DEX files.
     * - All logic is encapsulated within a single Activity class.
     * - Helper methods are kept concise and modular.
     * - No large third-party libraries are included directly in this file.
     * - If you add new features, consider extracting them into separate classes or modules to avoid exceeding the method limit.
     * - For more information, see: https://developer.android.com/studio/build/multidex
     */
}
