package lat.sotspart.viewer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

class MainActivity : Activity() {

    companion object {
        private const val DEFAULT_URL = "https://sotspart.lat/"
        private const val KEY_HOME = "home_url"
        private const val KEY_PAGE_LANG = "page_lang"   // "es", "en" o "" (original)
        private const val KEY_SUB_LANG = "sub_lang"     // "es", "en", "off" o "auto"
        private const val KEY_ADBLOCK = "adblock"
        private const val KEY_IMMERSIVE = "immersive"
        private const val KEY_HOSTS = "allowed_hosts"

        private val AD_HOSTS = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "popads.net", "popcash.net", "propellerads.com",
            "propellerclick.com", "adsterra.com", "exoclick.com", "exosrv.com",
            "juicyads.com", "onclickads.net", "onclickperformance.com", "adcash.com",
            "hilltopads.net", "trafficjunky.net", "clickadu.com", "a-ads.com", "mgid.com",
            "taboola.com", "outbrain.com", "revcontent.com", "zeropark.com",
            "ad-maven.com", "admaven.com", "galaksion.com", "bidvertiser.com",
            "clickaine.com", "richpartners.co", "pushwhy.com", "dolohen.com"
        )
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var web: WebView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var controls: LinearLayout
    private lateinit var handle: TextView
    private lateinit var lockOverlay: FrameLayout
    private lateinit var unlockBtn: TextView
    private lateinit var rotBtn: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val hideUnlockHint = Runnable { unlockBtn.visibility = View.INVISIBLE }

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var scriptHandler: ScriptHandler? = null
    private var rotationLocked = false
    private var touchLocked = false

    // Se lee desde el hilo de red en shouldInterceptRequest.
    @Volatile
    private var adBlock = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("ajustes", MODE_PRIVATE)
        adBlock = prefs.getBoolean(KEY_ADBLOCK, true)

        web = findViewById(R.id.web)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
        controls = findViewById(R.id.controls)
        handle = findViewById(R.id.handle)
        lockOverlay = findViewById(R.id.lock_overlay)
        unlockBtn = findViewById(R.id.unlock_btn)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR

        setupWebView()
        setupControls()
        setupLockOverlay()
        applyImmersive()

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            setTranslateCookie(homeUrl(), pageLang())
            web.loadUrl(homeUrl())
        }
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            // Con ventanas múltiples activadas y onCreateWindow devolviendo false,
            // las ventanas emergentes (publicidad) nunca se abren.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Algunos sitios rechazan los WebView; nos presentamos como Chrome normal.
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                val scheme = uri.scheme
                if (scheme != "http" && scheme != "https") {
                    toast("Bloqueado: enlace externo")
                    return true
                }
                val host = uri.host ?: return true
                if (!adBlock || isAllowedHost(host)) return false
                askOpenHost(uri, host)
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (adBlock && isAdHost(request.url.host)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageFinished(view: WebView, url: String) {
                // Respaldo por si el WebView no soporta scripts al inicio del documento.
                if (scriptHandler == null) view.evaluateJavascript(frameScript(), null)
                val lang = pageLang()
                if (lang.isNotEmpty()) view.evaluateJavascript(translateScript(lang), null)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                fullscreenContainer.visibility = View.VISIBLE
                web.visibility = View.INVISIBLE
                setSystemBarsHidden(true)
                if (!rotationLocked) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                if (adBlock) toast("Ventana emergente bloqueada")
                return false
            }
        }

        installFrameScript()
    }

    private fun exitFullscreen() {
        val view = customView ?: return
        customView = null
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        web.visibility = View.VISIBLE
        customCallback?.onCustomViewHidden()
        customCallback = null
        applyImmersive()
        if (!rotationLocked) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }

    /**
     * Script que corre en TODOS los marcos (incluidos los iframes del reproductor):
     * elige la pista de subtítulos del idioma preferido y anula window.open.
     */
    private fun frameScript(): String {
        val sub = prefs.getString(KEY_SUB_LANG, "es") ?: "es"
        return """
            (function(){
              if (window.__stSub) return; window.__stSub = 1;
              var SUB = '$sub', BLOCK = $adBlock;
              if (BLOCK) { try { window.open = function(){ return null; }; } catch(e) {} }
              if (SUB === 'auto') return;
              function matches(tr){
                var l = ((tr.language || '') + ' ' + (tr.label || '')).toLowerCase();
                if (SUB === 'es') return /(^|\s)es|spa|españ|espan|castell|latino/.test(l);
                if (SUB === 'en') return /(^|\s)en|eng|ingl/.test(l);
                return false;
              }
              function pick(v){
                var t = v.textTracks; if (!t || !t.length) return;
                var best = -1;
                if (SUB !== 'off') {
                  for (var i = 0; i < t.length; i++) { if (matches(t[i])) { best = i; break; } }
                  if (best < 0) return;
                }
                for (var j = 0; j < t.length; j++) {
                  var m = (j === best) ? 'showing' : 'disabled';
                  if (t[j].mode !== m) t[j].mode = m;
                }
              }
              setInterval(function(){
                var vs = document.getElementsByTagName('video');
                for (var k = 0; k < vs.length; k++) pick(vs[k]);
              }, 2000);
            })();
        """.trimIndent()
    }

    private fun installFrameScript() {
        scriptHandler?.remove()
        scriptHandler = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(web, frameScript(), setOf("*"))
        }
    }

    /** Inserta el traductor de Google en la página principal. */
    private fun translateScript(lang: String): String = """
        (function(l){
          if (window.top !== window || window.__stTr) return; window.__stTr = 1;
          var st = document.createElement('style');
          st.textContent = '.goog-te-banner-frame,.skiptranslate iframe,#goog-gt-tt{display:none!important}body{top:0!important}';
          document.head.appendChild(st);
          var d = document.createElement('div'); d.id = 'st_translate'; d.style.display = 'none';
          document.body.appendChild(d);
          function choose(){
            var s = document.querySelector('select.goog-te-combo');
            if (s && s.value !== l) { s.value = l; s.dispatchEvent(new Event('change')); }
          }
          window.stTranslateInit = function(){
            new google.translate.TranslateElement({pageLanguage: 'auto', autoDisplay: false}, 'st_translate');
            setTimeout(choose, 1000); setTimeout(choose, 3000);
          };
          var sc = document.createElement('script');
          sc.src = 'https://translate.google.com/translate_a/element.js?cb=stTranslateInit';
          document.head.appendChild(sc);
        })('$lang');
    """.trimIndent()

    private fun setTranslateCookie(url: String, lang: String) {
        val cm = CookieManager.getInstance()
        val host = Uri.parse(url).host ?: return
        val value = if (lang.isEmpty()) {
            "googtrans=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/"
        } else {
            "googtrans=/auto/$lang; path=/"
        }
        cm.setCookie(url, value)
        cm.setCookie(url, "$value; domain=$host")
        cm.setCookie(url, "$value; domain=.${baseHost(host)}")
        cm.flush()
    }

    // ------------------------------------------------------------- Controles

    private fun setupControls() {
        addControl("⟲", "Girar pantalla") { rotateNow() }
        rotBtn = addControl("🔓", "Fijar / liberar orientación") { toggleRotationLock() }
        addControl("✋", "Bloquear toques") { setTouchLock(true) }
        addControl("🌐", "Idioma de la página") { showTranslateDialog() }
        addControl("CC", "Subtítulos") { showSubtitleDialog() }
        addControl("⋮", "Más opciones") { showMoreDialog() }
        addControl("✕", "Ocultar controles") { showControls(false) }
        handle.setOnClickListener { showControls(true) }
    }

    private fun addControl(label: String, description: String, action: () -> Unit): TextView {
        val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 44f, resources.displayMetrics).toInt()
        val btn = TextView(this).apply {
            text = label
            contentDescription = description
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setOnClickListener { action() }
            setOnLongClickListener { toast(description); true }
        }
        controls.addView(btn, LinearLayout.LayoutParams(size, size))
        return btn
    }

    private fun showControls(show: Boolean) {
        controls.visibility = if (show) View.VISIBLE else View.GONE
        handle.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun rotateNow() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        rotationLocked = true
        updateRotBtn()
        toast(if (landscape) "Vertical (fijado)" else "Horizontal (fijado)")
    }

    private fun toggleRotationLock() {
        if (rotationLocked) {
            rotationLocked = false
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            toast("Giro automático activado")
        } else {
            rotationLocked = true
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            toast("Orientación fijada")
        }
        updateRotBtn()
    }

    private fun updateRotBtn() {
        rotBtn.text = if (rotationLocked) "🔒" else "🔓"
    }

    // --------------------------------------------------------- Bloqueo táctil

    @SuppressLint("ClickableViewAccessibility")
    private fun setupLockOverlay() {
        lockOverlay.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) flashUnlockHint()
            true
        }
        unlockBtn.setOnClickListener { toast("Mantén presionado para desbloquear") }
        unlockBtn.setOnLongClickListener {
            setTouchLock(false)
            true
        }
    }

    private fun setTouchLock(on: Boolean) {
        touchLocked = on
        lockOverlay.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            controls.visibility = View.GONE
            handle.visibility = View.GONE
            flashUnlockHint()
        } else {
            handler.removeCallbacks(hideUnlockHint)
            showControls(true)
            toast("Pantalla desbloqueada")
        }
    }

    private fun flashUnlockHint() {
        unlockBtn.visibility = View.VISIBLE
        handler.removeCallbacks(hideUnlockHint)
        handler.postDelayed(hideUnlockHint, 3000)
    }

    // --------------------------------------------------------------- Diálogos

    private fun showTranslateDialog() {
        val codes = arrayOf("es", "en", "")
        val names = arrayOf("Español", "English (inglés)", "Original (sin traducir)")
        val current = codes.indexOf(pageLang()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Traducir la página a…")
            .setSingleChoiceItems(names, current) { d, which ->
                d.dismiss()
                prefs.edit().putString(KEY_PAGE_LANG, codes[which]).apply()
                setTranslateCookie(web.url ?: homeUrl(), codes[which])
                web.reload()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showSubtitleDialog() {
        val codes = arrayOf("es", "en", "off", "auto")
        val names = arrayOf(
            "Subtítulos del video: Español",
            "Subtítulos del video: Inglés",
            "Subtítulos del video: Apagados",
            "Dejar que el reproductor decida"
        )
        val current = codes.indexOf(prefs.getString(KEY_SUB_LANG, "es")).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Subtítulos")
            .setSingleChoiceItems(names, current) { d, which ->
                d.dismiss()
                prefs.edit().putString(KEY_SUB_LANG, codes[which]).apply()
                installFrameScript()
                web.reload()
            }
            .setPositiveButton("Subtítulos en vivo (traducir audio)…") { _, _ -> showLiveCaptionHelp() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showLiveCaptionHelp() {
        AlertDialog.Builder(this)
            .setTitle("Audio en inglés con subtítulos en español")
            .setMessage(
                "Si el video no trae subtítulos en español, Android puede generarlos en vivo:\n\n" +
                    "1. Mientras se reproduce, presiona un botón de volumen.\n" +
                    "2. Toca el ícono de subtítulos (Subtítulos instantáneos / Live Caption).\n" +
                    "3. En los ajustes de Subtítulos instantáneos activa «Traducir» y elige Español.\n\n" +
                    "La traducción en vivo depende del teléfono (por ejemplo Google Pixel 6 o más nuevo). " +
                    "En otros modelos se muestran los subtítulos en el idioma original del audio."
            )
            .setPositiveButton("Abrir ajustes") { _, _ ->
                if (!tryStart(Intent("android.settings.CAPTIONING_SETTINGS"))) {
                    tryStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showMoreDialog() {
        val immersive = prefs.getBoolean(KEY_IMMERSIVE, false)
        val items = arrayOf(
            "Recargar",
            "Ir al inicio",
            "Pantalla completa: " + if (immersive) "activada" else "desactivada",
            "Bloqueo de anuncios: " + if (adBlock) "activado" else "desactivado",
            "Fijar app (anclar pantalla)",
            "Soltar app anclada",
            "Cambiar dirección del sitio"
        )
        AlertDialog.Builder(this)
            .setTitle("Opciones")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> web.reload()
                    1 -> web.loadUrl(homeUrl())
                    2 -> {
                        prefs.edit().putBoolean(KEY_IMMERSIVE, !immersive).apply()
                        applyImmersive()
                    }
                    3 -> {
                        adBlock = !adBlock
                        prefs.edit().putBoolean(KEY_ADBLOCK, adBlock).apply()
                        installFrameScript()
                        web.reload()
                    }
                    4 -> pinApp()
                    5 -> try { stopLockTask() } catch (_: Exception) {}
                    6 -> showHomeUrlDialog()
                }
            }
            .show()
    }

    private fun pinApp() {
        try {
            startLockTask()
            toast("Para soltar: mantén presionados Atrás y Recientes, o usa ⋮ → Soltar app")
        } catch (e: Exception) {
            toast("Activa «Fijar apps» en Ajustes → Seguridad")
        }
    }

    private fun showHomeUrlDialog() {
        val input = EditText(this).apply {
            setText(homeUrl())
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Dirección del sitio")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                var url = input.text.toString().trim()
                if (url.isEmpty()) url = DEFAULT_URL
                if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
                prefs.edit().putString(KEY_HOME, url).apply()
                setTranslateCookie(url, pageLang())
                web.loadUrl(url)
            }
            .setNeutralButton("Predeterminada") { _, _ ->
                prefs.edit().remove(KEY_HOME).apply()
                web.loadUrl(DEFAULT_URL)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun askOpenHost(uri: Uri, host: String) {
        AlertDialog.Builder(this)
            .setTitle("¿Abrir otro sitio?")
            .setMessage("La página quiere llevarte a:\n$host\n\nSuele ser publicidad.")
            .setPositiveButton("Bloquear", null)
            .setNeutralButton("Permitir siempre") { _, _ ->
                val hosts = HashSet(prefs.getStringSet(KEY_HOSTS, emptySet()) ?: emptySet())
                hosts.add(baseHost(host))
                prefs.edit().putStringSet(KEY_HOSTS, hosts).apply()
                web.loadUrl(uri.toString())
            }
            .show()
    }

    // --------------------------------------------------------------- Utilidades

    private fun homeUrl(): String = prefs.getString(KEY_HOME, DEFAULT_URL) ?: DEFAULT_URL

    private fun pageLang(): String = prefs.getString(KEY_PAGE_LANG, "") ?: ""

    private fun baseHost(host: String): String = host.removePrefix("www.")

    private fun isAllowedHost(host: String): Boolean {
        val allowed = HashSet(prefs.getStringSet(KEY_HOSTS, emptySet()) ?: emptySet())
        Uri.parse(homeUrl()).host?.let { allowed.add(baseHost(it)) }
        web.url?.let { Uri.parse(it).host }?.let { allowed.add(baseHost(it)) }
        return allowed.any { host == it || host.endsWith(".$it") }
    }

    private fun isAdHost(host: String?): Boolean {
        if (host == null) return false
        return AD_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun applyImmersive() {
        setSystemBarsHidden(customView != null || prefs.getBoolean(KEY_IMMERSIVE, false))
    }

    @Suppress("DEPRECATION")
    private fun setSystemBarsHidden(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(!on)
            val c = window.insetsController ?: return
            if (on) {
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                c.show(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = if (on) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    // ------------------------------------------------------------- Ciclo de vida

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            touchLocked -> flashUnlockHint()
            customView != null -> exitFullscreen()
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }
}
