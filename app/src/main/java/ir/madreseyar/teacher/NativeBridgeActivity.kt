package ir.madreseyar.teacher

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.text.InputType
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyPermanentlyInvalidatedException

class NativeBridgeActivity : AppCompatActivity() {
    companion object {
        private const val KEY_ALIAS = "madreseyar_teacher_biometric_v1"
        private const val PREFS = "madreseyar_teacher_secure_session"
        private const val PREF_CIPHER = "session_ciphertext"
        private const val PREF_IV = "session_iv"
        private const val PREF_ENABLED = "biometric_enabled"
        private const val MAX_BLOB_BYTES = 40 * 1024 * 1024

        const val EXTRA_RETURN_ON_READY = "return_on_teacher_ready"
        const val EXTRA_AUTH_ONLY = "auth_only"
        const val EXTRA_NATIVE_LOGIN = "native_login"
        const val EXTRA_RESTORE_ONLY = "restore_only"
        const val EXTRA_USERNAME = "native_username"
        const val EXTRA_PASSWORD = "native_password"
        const val EXTRA_FEATURE_MODE = "feature_mode"
        const val EXTRA_TARGET_ROUTE = "target_route"
        const val EXTRA_TARGET_TITLE = "target_title"
    }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var splash: LinearLayout
    private lateinit var errorPanel: LinearLayout
    private var nativeFeatureScroll: ScrollView? = null
    private var nativeFeatureContent: LinearLayout? = null
    private var mfaDialogVisible = false
    private lateinit var filePicker: ActivityResultLauncher<Intent>
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraCaptureUri: Uri? = null
    private var blobPreviewMode = false
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val allowedHost: String by lazy { URI(BuildConfig.WEB_APP_URL).host.lowercase(Locale.ROOT) }
    private val allowedPort: Int by lazy { URI(BuildConfig.WEB_APP_URL).port }
    private val effectiveAllowedPort: Int by lazy { if (allowedPort < 0) 443 else allowedPort }
    private val allowedOrigin: String by lazy {
        val uri = URI(BuildConfig.WEB_APP_URL)
        "${uri.scheme}://${uri.rawAuthority}"
    }
    private var documentStartSupported = false
    private var documentStartScriptHandler: ScriptHandler? = null
    @Volatile private var restoredSessionPayload: String? = null
    @Volatile private var restoredToken: String = ""
    @Volatile private var activeSessionToken: String = ""
    private var promptedTokenFingerprint: String = ""
    private var restoreFailureHandled = false
    private var blobName = "download"
    private var blobMime = "application/octet-stream"
    private var blobBuffer: ByteArrayOutputStream? = null
    private val blobLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoLoginProbeCount = 0
    private var autoLoginSubmitted = false
    private var autoLoginFallbackScheduled = false
    private var autoLoginCompleted = false
    private val returnOnReady by lazy { intent.getBooleanExtra(EXTRA_RETURN_ON_READY, false) }
    private val authOnly by lazy { intent.getBooleanExtra(EXTRA_AUTH_ONLY, false) }
    private val nativeLogin by lazy { intent.getBooleanExtra(EXTRA_NATIVE_LOGIN, false) }
    private val restoreOnly by lazy { intent.getBooleanExtra(EXTRA_RESTORE_ONLY, false) }
    private val autoUsername by lazy { intent.getStringExtra(EXTRA_USERNAME).orEmpty() }
    private val autoPassword by lazy { intent.getStringExtra(EXTRA_PASSWORD).orEmpty() }
    private val featureMode by lazy { intent.getBooleanExtra(EXTRA_FEATURE_MODE, false) }
    private val targetRoute by lazy { intent.getStringExtra(EXTRA_TARGET_ROUTE).orEmpty() }
    private val targetTitle by lazy { intent.getStringExtra(EXTRA_TARGET_TITLE).orEmpty() }
    private var targetProbeCount = 0
    private var targetOpened = false
    private var fallbackSessionReloaded = false
    private var runtimeBridgeInjected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.rgb(18, 59, 154)
        window.navigationBarColor = Color.WHITE
        if (Build.VERSION.SDK_INT >= 26) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            if (callback != null) {
                val uris = if (result.resultCode == RESULT_OK) {
                    extractUris(result.data) ?: cameraCaptureUri?.let { arrayOf(it) }
                } else null
                callback.onReceiveValue(uris)
            }
            filePathCallback = null
            cameraCaptureUri = null
        }

        if (featureMode) {
            NativeSessionCache.payload()?.let { cached ->
                restoredSessionPayload = cached
                restoredToken = try { JSONObject(cached).optString("token") } catch (_: Exception) { "" }
                activeSessionToken = restoredToken
            }
        }

        buildLayout()
        configureWebView()

        if (restoreOnly && !hasEncryptedSession()) {
            Toast.makeText(this, "نشست اثر انگشتی ذخیره نشده است.", Toast.LENGTH_SHORT).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        if (featureMode && !restoredSessionPayload.isNullOrBlank()) {
            loadWebsite(true)
        } else if (restoreOnly || (!nativeLogin && hasEncryptedSession())) {
            authenticateToRestoreSession()
        } else {
            loadWebsite(false)
        }
    }

    private fun buildLayout() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(246, 248, 252)) }
        webView = WebView(this).apply {
            // The legacy site is a background transport only. It is never rendered to the user.
            visibility = View.INVISIBLE
            alpha = 0f
        }
        val webParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            if (featureMode) topMargin = dp(62)
        }
        root.addView(webView, webParams)

        if (featureMode) {
            val toolbar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(12), 0)
                setBackgroundColor(Color.rgb(58, 13, 136))
                val back = Button(this@NativeBridgeActivity).apply {
                    text = "‹"
                    textSize = 24f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.TRANSPARENT)
                    setOnClickListener { finish() }
                }
                addView(back, LinearLayout.LayoutParams(dp(52), dp(52)))
                val title = TextView(this@NativeBridgeActivity).apply {
                    text = targetTitle.ifBlank { "مدرسه‌یار" }
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
                }
                addView(title, LinearLayout.LayoutParams(0, dp(62), 1f))
            }
            root.addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62), Gravity.TOP))

            nativeFeatureContent = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(28))
                setBackgroundColor(Color.rgb(246, 248, 252))
            }
            nativeFeatureScroll = ScrollView(this).apply {
                visibility = View.GONE
                isFillViewport = true
                setBackgroundColor(Color.rgb(246, 248, 252))
                addView(nativeFeatureContent, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            root.addView(nativeFeatureScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                topMargin = dp(62)
            })
        }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            setMax(100)
            setProgress(0)
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(21, 94, 239))
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.rgb(21, 94, 239))
            visibility = View.GONE
        }
        root.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP).apply {
            if (featureMode) topMargin = dp(62)
        })

        splash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(246, 248, 252))
            val logo = ImageView(this@NativeBridgeActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            addView(logo, LinearLayout.LayoutParams(dp(116), dp(116)))
            val title = TextView(this@NativeBridgeActivity).apply {
                text = "مدرسه‌یار معلم"
                textSize = 23f
                setTextColor(Color.rgb(18, 59, 154))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(5))
            }
            addView(title)
            val subtitle = TextView(this@NativeBridgeActivity).apply {
                text = "پنل هوشمند مدیریت کلاس و آموزش"
                textSize = 13f
                setTextColor(Color.rgb(88, 107, 135))
                gravity = Gravity.CENTER
            }
            addView(subtitle)
            val spinner = ProgressBar(this@NativeBridgeActivity).apply { isIndeterminate = true }
            val lp = LinearLayout.LayoutParams(dp(32), dp(32)).apply { topMargin = dp(22) }
            addView(spinner, lp)
        }
        root.addView(splash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(Color.rgb(246, 248, 252))
            visibility = View.GONE
            addView(TextView(this@NativeBridgeActivity).apply {
                text = "اتصال به سامانه برقرار نشد"
                textSize = 20f
                setTextColor(Color.rgb(24, 47, 82))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
            addView(TextView(this@NativeBridgeActivity).apply {
                text = "اتصال امن اینترنت و آدرس سامانه را بررسی کنید؛ سپس دوباره تلاش کنید."
                textSize = 14f
                setTextColor(Color.rgb(90, 108, 133))
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(18))
            })
            addView(Button(this@NativeBridgeActivity).apply {
                text = "تلاش دوباره"
                setTextColor(Color.WHITE)
                setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(21, 94, 239)))
                setOnClickListener { errorPanel.visibility = View.GONE; loadWebsite(restoredSessionPayload != null) }
            })
        }
        root.addView(errorPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    private fun configureWebView() {
        webView.setBackgroundColor(Color.WHITE)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = !authOnly
            blockNetworkImage = authOnly
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            allowFileAccess = false
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString MadreseyarTeacherAndroid/${BuildConfig.VERSION_NAME}"
            if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }
        val messageListenerSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && messageListenerSupported
        if (messageListenerSupported) {
            WebViewCompat.addWebMessageListener(webView, "MSYNative", setOf(allowedOrigin)) { _, message, sourceOrigin, isMainFrame, _ ->
                if (isMainFrame && isAllowedHttpsUri(sourceOrigin)) handleNativeMessage(message.data ?: "")
            }
        }
        if (documentStartSupported) installDocumentStartScript()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return routeUrl(request.url)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = routeUrl(Uri.parse(url))

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progress.visibility = View.VISIBLE
                progress.progress = 8
                errorPanel.visibility = View.GONE
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                super.onPageCommitVisible(view, url)
                if (authOnly && nativeLogin && !autoLoginCompleted) tryNativeAutoLogin()
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                progress.visibility = View.GONE

                // Older Android System WebView versions may not support DOCUMENT_START_SCRIPT.
                // In that case we first load the origin, restore sessionStorage, then reload once.
                // Without this fallback every feature Activity starts unauthenticated and no teacher
                // route can ever be found.
                if (restoreSessionForLegacyWebViewIfNeeded()) return
                installRuntimeBridgeIfNeeded()
                probeStoredTeacherSession()
                if (authOnly) {
                    if (nativeLogin && !autoLoginCompleted) {
                        splash.visibility = View.VISIBLE
                        webView.visibility = View.INVISIBLE
                        tryNativeAutoLogin()
                    } else if (!nativeLogin && !autoLoginCompleted) {
                        splash.visibility = View.GONE
                        setResult(RESULT_CANCELED)
                        Toast.makeText(this@NativeBridgeActivity, "ورود باید از فرم Native برنامه انجام شود.", Toast.LENGTH_LONG).show()
                        finish()
                    }
                } else if (featureMode) {
                    if (targetOpened) renderNativeFeature() else attemptOpenTargetFeature()
                } else {
                    splash.visibility = View.GONE
                    webView.visibility = View.INVISIBLE
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    progress.visibility = View.GONE
                    splash.visibility = View.GONE
                    errorPanel.visibility = View.VISIBLE
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                Toast.makeText(this@NativeBridgeActivity, "گواهی امنیتی سایت معتبر نیست؛ برای حفاظت از رمز عبور اتصال متوقف شد.", Toast.LENGTH_LONG).show()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                if (newProgress >= 100) progress.visibility = View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams
            ): Boolean {
                this@NativeBridgeActivity.filePathCallback?.onReceiveValue(null)
                this@NativeBridgeActivity.filePathCallback = filePathCallback
                val accepts = fileChooserParams.acceptTypes
                    .flatMap { it.split(',') }
                    .map { it.trim().lowercase(Locale.ROOT) }
                    .filter { it.isNotBlank() }
                val acceptsImages = accepts.isEmpty() || accepts.any { it.startsWith("image/") || it == "image/*" }
                val cameraIntent = if (acceptsImages) createCameraCaptureIntent() else null
                val pickerIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = choosePickerMimeType(accepts)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
                    val mimeTypes = acceptedMimeTypes(accepts)
                    if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                }
                return try {
                    if (fileChooserParams.isCaptureEnabled && cameraIntent != null) {
                        filePicker.launch(cameraIntent)
                    } else {
                        val chooser = Intent.createChooser(pickerIntent, "انتخاب فایل برای بارگذاری")
                        if (cameraIntent != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
                        filePicker.launch(chooser)
                    }
                    true
                } catch (_: Exception) {
                    cameraCaptureUri = null
                    this@NativeBridgeActivity.filePathCallback = null
                    false
                }
            }

            override fun onJsAlert(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean {
                AlertDialog.Builder(this@NativeBridgeActivity)
                    .setMessage(message)
                    .setPositiveButton("باشه") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean {
                AlertDialog.Builder(this@NativeBridgeActivity)
                    .setMessage(message)
                    .setPositiveButton("تأیید") { _, _ -> result.confirm() }
                    .setNegativeButton("انصراف") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // Camera/microphone access is denied by default. File uploads remain available via the system picker.
                request.deny()
            }
        }

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:", true)) return@DownloadListener
            enqueueDownload(url, userAgent, contentDisposition, mimeType)
        })

        if (!documentStartSupported) {
            Toast.makeText(this, "حالت سازگاری WebView فعال شد.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun routeAliases(route: String, title: String): List<String> {
        val aliases = when (route) {
            "myTeaching" -> listOf("کلاس‌ها و درس‌های من", "کلاس‌های من", "درس‌های من")
            "classroom" -> listOf("حضور و غیاب", "ثبت حضور", "حضور کلاس")
            "students" -> listOf("دانش‌آموزان من", "دانش آموزان من", "دانش‌آموزان")
            "classJournal" -> listOf("دفتر کلاسی", "دفتر کلاس")
            "weeklyScheduleV73" -> listOf("برنامه هفتگی", "برنامه تدریس")
            "gradeCenterT07" -> listOf("مرکز نمرات", "نمرات", "مرکز نمره")
            "termExamT06" -> listOf("آزمون پایانی", "نمره پایانی")
            "exams" -> listOf("آزمون‌ها", "آزمون ها", "مدیریت آزمون")
            "create" -> listOf("آزمون جدید", "ساخت آزمون", "ایجاد آزمون")
            "grading" -> listOf("تصحیح و نمره‌دهی", "تصحیح و نمره دهی", "تصحیح", "نمره‌دهی")
            "reports" -> listOf("گزارش‌ها", "گزارش ها", "گزارش")
            "examprint" -> listOf("مرکز چاپ آزمون", "چاپ آزمون")
            "continuousReport" -> listOf("گزارش مستمر", "کارنامه مستمر")
            "gradebookV75" -> listOf("دفتر نمره پیشرفته", "دفتر نمره")
            "gradeRegisterT07" -> listOf("دفتر نمره یکپارچه", "دفتر نمرات")
            "continuousDetailed" -> listOf("کارنامه تفصیلی آزمون", "کارنامه تفصیلی")
            "messages" -> listOf("پیام‌های درسی", "پیام ها", "پیام‌ها", "ارتباط درسی")
            "practice" -> listOf("بانک تمرین", "تمرین")
            "library" -> listOf("کتابخانه دیجیتال", "کتابخانه")
            "homework" -> listOf("مرکز تکالیف", "تکالیف", "تکلیف")
            "questionBank" -> listOf("بانک سؤال", "بانک سوال")
            "familyCenter" -> listOf("مرکز خانواده و اولیا", "خانواده و اولیا", "اولیا")
            "guardianMeeting" -> listOf("دعوت و ملاقات اولیا", "ملاقات اولیا", "دعوت به ملاقات")
            "accountSecurityV201" -> listOf("تنظیمات امنیتی", "امنیت حساب", "امنیت")
            "actionCenter" -> listOf("مرکز اقدام و پیگیری", "اقدام و پیگیری")
            "communicationsCenter" -> listOf("ارتباطات", "مرکز ارتباطات", "پیام‌ها و ارتباطات")
            "attendanceDiscipline" -> listOf("حضور و انضباط", "انضباط")
            "learningPlanner" -> listOf("برنامه آموزشی", "برنامه‌ریزی آموزشی", "برنامه ریزی آموزشی")
            else -> emptyList()
        }
        return (listOf(title) + aliases).filter { it.isNotBlank() }.distinct()
    }

    private fun attemptOpenTargetFeature() {
        if (!featureMode || targetOpened || targetRoute.isBlank()) {
            if (featureMode && targetRoute.isBlank()) {
                splash.visibility = View.GONE
                webView.visibility = View.INVISIBLE
                showNativeFeatureError("مسیر این بخش تعریف نشده است.")
            }
            return
        }
        if (targetProbeCount >= 50) {
            splash.visibility = View.GONE
            webView.visibility = View.INVISIBLE
            showNativeFeatureError("این بخش از پنل معلم بارگذاری نشد. اتصال یا نشست را بررسی کنید.")
            Toast.makeText(this, "بارگذاری «${targetTitle.ifBlank { targetRoute }}» انجام نشد.", Toast.LENGTH_LONG).show()
            return
        }
        targetProbeCount += 1
        val route = JSONObject.quote(targetRoute)
        val aliases = JSONArray(routeAliases(targetRoute, targetTitle)).toString()
        val attempt = targetProbeCount
        val script = """
            (function(){
              try {
                var route=$route, aliases=$aliases, attempt=$attempt;
                function norm(v){return String(v||'').replace(/[\u200c\u200f\u202a-\u202e]/g,' ').replace(/\s+/g,' ').trim();}
                function click(el){
                  if(!el) return false;
                  try {
                    var target=el.closest ? (el.closest('button,a,[role=button],[onclick],[data-route],[data-view],[data-page],[data-target],[data-screen],.menu-item,.nav-item,.card,.tile,.item')||el) : el;
                    target.click();
                    return true;
                  } catch(_){ return false; }
                }
                function bySelector(sel){try{var el=document.querySelector(sel);return el&&click(el);}catch(_){return false;}}

                // 1) Route-bearing attributes / inline handlers. Do not require visibility: the
                // Android transport WebView is intentionally invisible and menus may be collapsed.
                var safe=String(route).replace(/"/g,'\\"');
                var selectors=[
                  '[data-route="'+safe+'"]','[data-view="'+safe+'"]','[data-page="'+safe+'"]',
                  '[data-target="'+safe+'"]','[data-screen="'+safe+'"]','[href="#'+safe+'"]',
                  '[href*="'+safe+'"]','[onclick*="'+safe+'"]','#'+safe
                ];
                for(var i=0;i<selectors.length;i++) if(bySelector(selectors[i])) return 'opened-selector';

                // 2) Known navigation APIs used by different revisions of the SPA.
                var names=['showView','openView','navigate','navigateTo','goTo','openRoute','renderView','setView','switchView','showPage','openPage','nav','go','routeTo','teacherRoute'];
                for(var j=0;j<names.length;j++){
                  try {
                    if(typeof window[names[j]]==='function'){
                      var before=(document.getElementById('page')||{}).dataset||{};
                      window[names[j]](route);
                      return 'opened-function';
                    }
                  } catch(_){}
                }

                // 3) The web source exposes teacher views in some builds.
                try {
                  if(window.views && window.views.teacher && window.views.teacher[route]){
                    var v=window.views.teacher[route];
                    if(typeof v==='function'){v(); return 'opened-view-fn';}
                    if(v && typeof v.open==='function'){v.open(); return 'opened-view-open';}
                    if(v && typeof v.render==='function'){v.render(); return 'opened-view-render';}
                  }
                } catch(_){}

                // 4) Search actual menu labels and click their closest clickable ancestor. This
                // works for plugin modules that don't expose route data attributes.
                var wanted=(aliases||[]).map(norm).filter(Boolean);
                var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,[role=button],[onclick],[data-route],[data-view],[data-page],[data-target],[data-screen],.menu-item,.nav-item,.card,.tile,.item,li'));
                for(var a=0;a<wanted.length;a++){
                  var w=wanted[a];
                  var exact=nodes.find(function(el){return norm(el.innerText||el.textContent||el.getAttribute('aria-label'))===w;});
                  if(exact && click(exact)) return 'opened-title';
                }
                for(var b=0;b<wanted.length;b++){
                  var wp=wanted[b]; if(wp.length<3) continue;
                  var partial=nodes.find(function(el){var t=norm(el.innerText||el.textContent||el.getAttribute('aria-label')); return t && (t.indexOf(wp)>=0 || wp.indexOf(t)>=0);});
                  if(partial && click(partial)) return 'opened-title-partial';
                }

                // 5) Some menus put text in a child span while the click handler is on a parent.
                var allText=Array.prototype.slice.call(document.querySelectorAll('span,div,p,strong,h3,h4'));
                for(var c=0;c<wanted.length;c++){
                  var wt=wanted[c];
                  var textNode=allText.find(function(el){return norm(el.textContent)===wt;});
                  if(textNode && click(textNode)) return 'opened-text-parent';
                }

                // 6) Last-resort hash navigation for revisions that use location.hash.
                if(attempt>20){
                  try {
                    var desired='#'+route;
                    if(location.hash!==desired){
                      location.hash=desired;
                      window.dispatchEvent(new HashChangeEvent('hashchange'));
                      return 'opened-hash';
                    }
                  } catch(_){}
                }
                return 'pending';
              } catch(e){ return 'pending'; }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { raw ->
            val result = raw.orEmpty()
            if (result.contains("opened-")) {
                targetOpened = true
                mainHandler.postDelayed({
                    applyFeatureModeStyling()
                    renderNativeFeature()
                }, 850)
                // A second render catches modules that populate rows asynchronously.
                mainHandler.postDelayed({ renderNativeFeature() }, 1800)
            } else {
                mainHandler.postDelayed({ attemptOpenTargetFeature() }, 250)
            }
        }
    }

    private fun applyFeatureModeStyling() {
        if (!featureMode) return
        val script = """
            (function(){
              try {
                var id='msy-native-feature-style';
                if(document.getElementById(id)) return;
                var st=document.createElement('style'); st.id=id;
                st.textContent=`
                  html,body{background:#f6f7fc!important;overscroll-behavior:none}
                  body{margin-top:0!important;padding-top:0!important}
                  .app-header,.topbar,.top-bar,.global-header,.main-header,
                  .sidebar,.side-nav,.main-sidebar,.bottom-nav,.bottom-navigation,
                  #bottomNav,#sidebar,#appHeader{display:none!important}
                  .app-content,.main-content,.content,.page-content{margin:0!important;max-width:none!important;width:100%!important}
                `;
                document.documentElement.appendChild(st);
              }catch(_){}
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun showNativeFeatureError(message: String) {
        if (!featureMode) return
        splash.visibility = View.GONE
        webView.visibility = View.INVISIBLE
        nativeFeatureScroll?.visibility = View.VISIBLE
        nativeFeatureContent?.apply {
            removeAllViews()
            addView(TextView(this@NativeBridgeActivity).apply {
                text = targetTitle.ifBlank { "مدرسه‌یار" }
                textSize = 20f
                setTextColor(Color.rgb(24, 31, 45))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.RIGHT
                setPadding(0, dp(12), 0, dp(8))
            })
            addView(TextView(this@NativeBridgeActivity).apply {
                text = message
                textSize = 14f
                setTextColor(Color.rgb(107, 114, 128))
                gravity = Gravity.RIGHT
                setPadding(0, dp(8), 0, dp(18))
            })
            addView(Button(this@NativeBridgeActivity).apply {
                text = "تلاش دوباره"
                setTextColor(Color.WHITE)
                setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(91, 32, 214)))
                setOnClickListener { targetOpened = false; targetProbeCount = 0; attemptOpenTargetFeature() }
            })
        }
    }

    private fun renderNativeFeature() {
        if (!featureMode || isFinishing) return
        webView.visibility = View.INVISIBLE
        webView.alpha = 0f
        val script = """
            (function(){
              try{
                function visible(el){
                  if(!el) return false;
                  var st=getComputedStyle(el),r=el.getBoundingClientRect();
                  return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;
                }
                function norm(v){return String(v||'').replace(/\\s+/g,' ').trim();}
                var seq=0;
                function nid(el){
                  if(!el.dataset.msyNativeId) el.dataset.msyNativeId='native-'+Date.now()+'-'+(++seq);
                  return el.dataset.msyNativeId;
                }
                function labelFor(el){
                  try{
                    if(el.labels&&el.labels.length) return norm(el.labels[0].innerText||el.labels[0].textContent);
                    var aria=norm(el.getAttribute('aria-label')); if(aria) return aria;
                    var ph=norm(el.getAttribute('placeholder')); if(ph) return ph;
                    var parent=el.closest('label'); if(parent){var t=norm(parent.innerText||parent.textContent);if(t)return t;}
                    var prev=el.previousElementSibling; if(prev){var p=norm(prev.innerText||prev.textContent);if(p&&p.length<120)return p;}
                  }catch(_){}
                  return '';
                }
                var root=document.querySelector('[role=dialog],.modal.show,.modal.active,.dialog.open,.dialog.active,[data-view].active,[data-page].active,.view.active,.page.active,main,[role=main],.app-content,.main-content,.page-content,.content')||document.body;
                var texts=[];
                Array.prototype.slice.call(root.querySelectorAll('h1,h2,h3,h4,h5,p,.title,.subtitle,.summary,.alert,.message,.notice,.empty-state')).forEach(function(el){
                  if(!visible(el)) return; var t=norm(el.innerText||el.textContent); if(!t||t.length>500) return;
                  if(texts.indexOf(t)<0) texts.push(t);
                });
                texts=texts.slice(0,45);
                var fields=[];
                Array.prototype.slice.call(root.querySelectorAll('input,textarea,select')).forEach(function(el){
                  if(!visible(el)||el.disabled) return;
                  var type=(el.type||el.tagName||'text').toLowerCase();
                  if(type==='hidden'||type==='submit'||type==='button'||type==='reset') return;
                  var item={id:nid(el),label:labelFor(el),type:type,value:String(el.value||''),placeholder:String(el.placeholder||''),checked:!!el.checked,options:[]};
                  if(el.tagName==='SELECT') item.options=Array.prototype.slice.call(el.options||[]).map(function(o){return {text:norm(o.textContent),value:String(o.value||''),selected:!!o.selected};});
                  fields.push(item);
                });
                fields=fields.slice(0,60);
                var actions=[];
                Array.prototype.slice.call(root.querySelectorAll('button,input[type=submit],input[type=button],a,[role=button]')).forEach(function(el){
                  if(!visible(el)||el.disabled) return;
                  var text=norm(el.innerText||el.textContent||el.value||el.getAttribute('aria-label'));
                  if(!text||text.length>120) return;
                  if(/^(خانه|خروج|بیشتر|کلاس‌ها|پیام‌ها)$/i.test(text)) return;
                  actions.push({id:nid(el),text:text});
                });
                actions=actions.slice(0,50);
                var tables=[];
                Array.prototype.slice.call(root.querySelectorAll('table')).filter(visible).slice(0,12).forEach(function(table){
                  var rows=[];
                  Array.prototype.slice.call(table.querySelectorAll('tr')).slice(0,40).forEach(function(tr){
                    if(!visible(tr))return;
                    var cells=Array.prototype.slice.call(tr.querySelectorAll('th,td')).slice(0,10).map(function(c){return norm(c.innerText||c.textContent);});
                    if(cells.some(Boolean))rows.push(cells);
                  });
                  if(rows.length)tables.push(rows);
                });
                return JSON.stringify({title:document.title||'',texts:texts,fields:fields,actions:actions,tables:tables});
              }catch(e){return JSON.stringify({error:String(e)});}
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { raw ->
            runOnUiThread {
                try {
                    val decoded = org.json.JSONTokener(raw).nextValue() as? String ?: ""
                    val doc = JSONObject(decoded)
                    if (doc.has("error")) {
                        showNativeFeatureError("نمایش Native این بخش آماده نشد.")
                    } else {
                        buildNativeFeatureUi(doc)
                    }
                } catch (_: Exception) {
                    showNativeFeatureError("دریافت اطلاعات این بخش انجام نشد.")
                }
            }
        }
    }

    private fun buildNativeFeatureUi(doc: JSONObject) {
        val container = nativeFeatureContent ?: return
        container.removeAllViews()
        nativeFeatureScroll?.visibility = View.VISIBLE
        splash.visibility = View.GONE
        errorPanel.visibility = View.GONE
        webView.visibility = View.INVISIBLE

        fun card(block: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.rgb(232, 234, 240))
            }
            block()
        }
        fun addCard(v: View) {
            container.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
        }

        addCard(card {
            addView(TextView(this@NativeBridgeActivity).apply {
                text = targetTitle.ifBlank { doc.optString("title", "مدرسه‌یار") }
                textSize = 20f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(21, 27, 45))
                gravity = Gravity.RIGHT
            })
            addView(TextView(this@NativeBridgeActivity).apply {
                text = "نمای Native • اطلاعات زنده سامانه"
                textSize = 12f
                setTextColor(Color.rgb(107, 114, 128))
                gravity = Gravity.RIGHT
                setPadding(0, dp(4), 0, 0)
            })
        })

        val texts = doc.optJSONArray("texts") ?: JSONArray()
        for (i in 0 until texts.length()) {
            val text = texts.optString(i).trim()
            if (text.isBlank() || text == targetTitle) continue
            addCard(card {
                addView(TextView(this@NativeBridgeActivity).apply {
                    this.text = text
                    textSize = 14f
                    setTextColor(Color.rgb(45, 55, 72))
                    gravity = Gravity.RIGHT
                    setLineSpacing(0f, 1.25f)
                })
            })
        }

        val fields = doc.optJSONArray("fields") ?: JSONArray()
        for (i in 0 until fields.length()) {
            val f = fields.optJSONObject(i) ?: continue
            val id = f.optString("id")
            val type = f.optString("type").lowercase(Locale.ROOT)
            val label = f.optString("label").ifBlank { f.optString("placeholder").ifBlank { "فیلد" } }
            addCard(card {
                addView(TextView(this@NativeBridgeActivity).apply {
                    text = label
                    textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(55, 65, 81))
                    gravity = Gravity.RIGHT
                    setPadding(0, 0, 0, dp(8))
                })
                when {
                    type == "file" -> addView(Button(this@NativeBridgeActivity).apply {
                        text = "انتخاب / بارگذاری فایل"
                        setTextColor(Color.WHITE)
                        setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(91, 32, 214)))
                        setOnClickListener { clickNativeElement(id) }
                    })
                    type == "checkbox" || type == "radio" -> addView(CheckBox(this@NativeBridgeActivity).apply {
                        text = label
                        isChecked = f.optBoolean("checked")
                        setOnCheckedChangeListener { _, checked -> setNativeChecked(id, checked) }
                    })
                    type == "select-one" || type == "select-multiple" -> {
                        val opts = f.optJSONArray("options") ?: JSONArray()
                        val labels = mutableListOf<String>()
                        var selected = 0
                        for (j in 0 until opts.length()) {
                            val o = opts.optJSONObject(j) ?: continue
                            labels += o.optString("text")
                            if (o.optBoolean("selected")) selected = labels.lastIndex
                        }
                        addView(Spinner(this@NativeBridgeActivity).apply {
                            adapter = ArrayAdapter(this@NativeBridgeActivity, android.R.layout.simple_spinner_dropdown_item, labels)
                            if (labels.isNotEmpty()) setSelection(selected.coerceIn(0, labels.lastIndex))
                            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, itemId: Long) {
                                    if (position in 0 until opts.length()) setNativeValue(id, opts.optJSONObject(position)?.optString("value").orEmpty())
                                }
                                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                            }
                        })
                    }
                    else -> addView(EditText(this@NativeBridgeActivity).apply {
                        setText(f.optString("value"))
                        hint = f.optString("placeholder")
                        textDirection = View.TEXT_DIRECTION_RTL
                        gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                        inputType = when (type) {
                            "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                            "number", "tel" -> InputType.TYPE_CLASS_NUMBER
                            "email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                            else -> InputType.TYPE_CLASS_TEXT or if (type == "textarea") InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
                        }
                        if (type == "textarea") minLines = 3
                        setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) setNativeValue(id, text?.toString().orEmpty()) }
                    })
                }
            })
        }

        val tables = doc.optJSONArray("tables") ?: JSONArray()
        for (ti in 0 until tables.length()) {
            val rows = tables.optJSONArray(ti) ?: continue
            addCard(card {
                for (ri in 0 until rows.length()) {
                    val row = rows.optJSONArray(ri) ?: continue
                    val parts = (0 until row.length()).map { row.optString(it) }.filter { it.isNotBlank() }
                    if (parts.isEmpty()) continue
                    addView(TextView(this@NativeBridgeActivity).apply {
                        text = parts.joinToString("  •  ")
                        textSize = if (ri == 0) 13.5f else 12.5f
                        typeface = if (ri == 0) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                        setTextColor(Color.rgb(31, 41, 55))
                        gravity = Gravity.RIGHT
                        setPadding(0, dp(7), 0, dp(7))
                    })
                    if (ri < rows.length() - 1) addView(View(this@NativeBridgeActivity).apply { setBackgroundColor(Color.rgb(238, 240, 244)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                }
            })
        }

        val actions = doc.optJSONArray("actions") ?: JSONArray()
        if (actions.length() > 0) {
            addCard(card {
                addView(TextView(this@NativeBridgeActivity).apply {
                    text = "عملیات"
                    textSize = 15f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(21, 27, 45))
                    gravity = Gravity.RIGHT
                    setPadding(0, 0, 0, dp(8))
                })
                for (i in 0 until actions.length()) {
                    val a = actions.optJSONObject(i) ?: continue
                    addView(Button(this@NativeBridgeActivity).apply {
                        text = a.optString("text")
                        isAllCaps = false
                        setTextColor(Color.WHITE)
                        setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(91, 32, 214)))
                        setOnClickListener { clickNativeElement(a.optString("id")) }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(7) })
                }
            })
        }

        if (container.childCount <= 1) {
            addCard(card {
                addView(TextView(this@NativeBridgeActivity).apply {
                    text = "اطلاعاتی برای نمایش پیدا نشد."
                    textSize = 14f
                    setTextColor(Color.rgb(107, 114, 128))
                    gravity = Gravity.CENTER
                })
            })
        }
    }

    private fun clickNativeElement(id: String) {
        if (id.isBlank()) return
        val q = JSONObject.quote(id)
        webView.evaluateJavascript("(function(){var e=document.querySelector('[data-msy-native-id='+${q}+']');if(!e)return 'missing';e.click();return 'ok';})()", null)
        mainHandler.postDelayed({ renderNativeFeature() }, 350L)
        mainHandler.postDelayed({ renderNativeFeature() }, 950L)
    }

    private fun setNativeValue(id: String, value: String) {
        if (id.isBlank()) return
        val qId = JSONObject.quote(id)
        val qVal = JSONObject.quote(value)
        val js = """
            (function(){try{var e=document.querySelector('[data-msy-native-id='+${qId}+']');if(!e)return;
            var proto=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:(e.tagName==='SELECT'?HTMLSelectElement.prototype:HTMLInputElement.prototype);
            var d=Object.getOwnPropertyDescriptor(proto,'value');if(d&&d.set)d.set.call(e,${qVal});else e.value=${qVal};
            e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}catch(_){}})();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun setNativeChecked(id: String, checked: Boolean) {
        if (id.isBlank()) return
        val qId = JSONObject.quote(id)
        webView.evaluateJavascript("(function(){var e=document.querySelector('[data-msy-native-id='+${qId}+']');if(!e)return;e.checked=${checked};e.dispatchEvent(new Event('change',{bubbles:true}));})()", null)
    }

    private fun routeUrl(uri: Uri): Boolean {
        val scheme = (uri.scheme ?: "").lowercase(Locale.ROOT)
        val host = (uri.host ?: "").lowercase(Locale.ROOT)
        if (isAllowedHttpsUri(uri)) return false
        if (scheme == "http" && host == allowedHost) {
            Toast.makeText(this, "لینک ناامن HTTP مسدود شد.", Toast.LENGTH_SHORT).show()
            return true
        }
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: Exception) {
            true
        }
    }

    private fun isAllowedHttpsUri(uri: Uri): Boolean {
        val scheme = (uri.scheme ?: "").lowercase(Locale.ROOT)
        val host = (uri.host ?: "").lowercase(Locale.ROOT)
        val port = if (uri.port < 0) if (scheme == "https") 443 else 80 else uri.port
        return scheme == "https" && host == allowedHost && port == effectiveAllowedPort
    }

    private fun loadWebsite(restore: Boolean) {
        restoreFailureHandled = false
        splash.visibility = View.VISIBLE
        errorPanel.visibility = View.GONE
        if (!restore) {
            fallbackSessionReloaded = false
            runtimeBridgeInjected = false
        }
        val base = BuildConfig.WEB_APP_URL
        // With document-start support the session is injected before app.js executes, so we can
        // directly request the return path. Older WebView versions need a two-step restore: first
        // establish the origin, then write sessionStorage and reload (handled in onPageFinished).
        val canRestoreAtDocumentStart = restore && documentStartSupported
        val url = if (canRestoreAtDocumentStart && !base.contains("p635_return=1")) {
            base + (if (base.contains("?")) "&" else "?") + "p635_return=1"
        } else base
        webView.loadUrl(url)
    }

    private fun restoreSessionForLegacyWebViewIfNeeded(): Boolean {
        if (documentStartSupported || fallbackSessionReloaded) return false
        val payload = restoredSessionPayload ?: return false
        if (payload.isBlank()) return false
        val quoted = JSONObject.quote(payload)
        val js = """
            (function(){
              try {
                var p=JSON.parse($quoted);
                if(!p || !p.session || !p.token) return 'invalid';
                var sk=p.sessionKey||'madreseyar.session.v14';
                var tk=p.tokenKey||'madreseyar.staff.token.v14';
                sessionStorage.setItem(sk,p.session);
                sessionStorage.setItem(tk,p.token);
                return 'ok';
              } catch(e) { return 'error'; }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { raw ->
            runOnUiThread {
                if (raw.orEmpty().contains("ok")) {
                    fallbackSessionReloaded = true
                    runtimeBridgeInjected = false
                    val base = BuildConfig.WEB_APP_URL
                    val returnUrl = if (!base.contains("p635_return=1")) {
                        base + (if (base.contains("?")) "&" else "?") + "p635_return=1"
                    } else base
                    webView.loadUrl(returnUrl)
                } else {
                    splash.visibility = View.GONE
                    showNativeFeatureError("بازیابی نشست انجام نشد؛ یک‌بار از حساب خارج و دوباره وارد شوید.")
                }
            }
        }
        return true
    }

    private fun installRuntimeBridgeIfNeeded() {
        if (documentStartSupported || runtimeBridgeInjected) return
        try {
            val bridgeAsset = if (authOnly) "auth-bridge.js" else "native-bridge.js"
            val bridgeScript = assets.open(bridgeAsset).bufferedReader(Charsets.UTF_8).use { it.readText() }
            runtimeBridgeInjected = true
            webView.evaluateJavascript(bridgeScript, null)
        } catch (_: Exception) {
            runtimeBridgeInjected = false
        }
    }

    private fun hasEncryptedSession(): Boolean =
        prefs.getBoolean(PREF_ENABLED, false) && !prefs.getString(PREF_CIPHER, null).isNullOrBlank() && !prefs.getString(PREF_IV, null).isNullOrBlank()

    private fun authenticateToRestoreSession() {
        val can = BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            clearBiometricSession()
            Toast.makeText(this, "ورود اثر انگشتی در دسترس نیست؛ با رمز عبور وارد شوید.", Toast.LENGTH_LONG).show()
            loadWebsite(false)
            return
        }
        try {
            val cipher = newCipher()
            val iv = Base64.decode(prefs.getString(PREF_IV, ""), Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(128, iv))
            showBiometricPrompt(
                title = "ورود به پنل معلم",
                subtitle = "برای بازکردن نشست امن مدرسه‌یار، اثر انگشت خود را تأیید کنید.",
                negative = "ورود با رمز عبور",
                cipher = cipher,
                onSuccess = { authenticatedCipher ->
                    try {
                        val encrypted = Base64.decode(prefs.getString(PREF_CIPHER, ""), Base64.NO_WRAP)
                        val plain = authenticatedCipher.doFinal(encrypted).toString(Charsets.UTF_8)
                        val payload = JSONObject(plain)
                        val session = payload.optString("session")
                        val token = payload.optString("token")
                        if (session.isBlank() || token.isBlank()) throw IllegalStateException("نشست ذخیره‌شده ناقص است.")
                        val role = JSONObject(session).optJSONObject("authUser")?.optString("role")
                        if (role != "teacher") throw IllegalStateException("نشست ذخیره‌شده متعلق به معلم نیست.")
                        restoredSessionPayload = plain
                        restoredToken = token
                        activeSessionToken = token
                        NativeSessionCache.putPayload(plain)
                        if (returnOnReady && authOnly && restoreOnly) {
                            // Native home does not need the legacy website to render.
                            // Returning immediately makes fingerprint unlock feel instant;
                            // the saved server session will be validated when a server-backed
                            // feature is opened/refreshed.
                            finishNativeLoginSuccess()
                        } else {
                            installDocumentStartScript()
                            loadWebsite(true)
                        }
                    } catch (_: Exception) {
                        clearBiometricSession()
                        Toast.makeText(this, "نشست ذخیره‌شده معتبر نیست؛ دوباره وارد شوید.", Toast.LENGTH_LONG).show()
                        loadWebsite(false)
                    }
                },
                onFallback = {
                    clearBiometricSession()
                    restoredSessionPayload = null
                    loadWebsite(false)
                }
            )
        } catch (_: KeyPermanentlyInvalidatedException) {
            clearBiometricSession()
            loadWebsite(false)
        } catch (_: Exception) {
            clearBiometricSession()
            loadWebsite(false)
        }
    }

    private fun showBiometricPrompt(
        title: String,
        subtitle: String,
        negative: String,
        cipher: Cipher,
        onSuccess: (Cipher) -> Unit,
        onFallback: () -> Unit
    ) {
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                val authenticatedCipher = result.cryptoObject?.cipher
                if (authenticatedCipher != null) onSuccess(authenticatedCipher) else onFallback()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_LOCKOUT || errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) {
                    onFallback()
                } else if (errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_CANCELED) {
                    Toast.makeText(this@NativeBridgeActivity, "ورود اثر انگشتی لغو شد.", Toast.LENGTH_SHORT).show()
                    onFallback()
                } else {
                    Toast.makeText(this@NativeBridgeActivity, errString, Toast.LENGTH_LONG).show()
                    onFallback()
                }
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negative)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun showEnableBiometricDialog(payload: String, token: String) {
        if (!documentStartSupported) return
        val signature = token.take(18)
        if (signature.isBlank() || promptedTokenFingerprint == signature) return
        promptedTokenFingerprint = signature
        val can = BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "برای فعال‌سازی، ابتدا اثر انگشت یا زیست‌سنجی قوی را در تنظیمات گوشی ثبت کنید.", Toast.LENGTH_LONG).show()
            return
        }
        val title = if (hasEncryptedSession()) "به‌روزرسانی ورود اثر انگشتی" else "فعال‌سازی ورود با اثر انگشت"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("پس از اولین ورود با نام کاربری و رمز، می‌توانید دفعات بعد پنل معلم را با اثر انگشت باز کنید. رمز عبور شما ذخیره نمی‌شود؛ فقط نشست ورود به‌صورت رمزنگاری‌شده نگهداری می‌شود.")
            .setPositiveButton("فعال‌سازی") { _, _ -> beginBiometricEnrollment(payload) }
            .setNegativeButton("فعلاً نه", null)
            .show()
    }

    private fun beginBiometricEnrollment(payload: String) {
        try {
            val key = createSecretKey()
            val cipher = newCipher()
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    try {
                        val authenticatedCipher = result.cryptoObject?.cipher ?: throw IllegalStateException("رمزنگاری زیست‌سنجی آماده نیست.")
                        val encrypted = authenticatedCipher.doFinal(payload.toByteArray(Charsets.UTF_8))
                        prefs.edit()
                            .putString(PREF_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                            .putString(PREF_IV, Base64.encodeToString(authenticatedCipher.iv, Base64.NO_WRAP))
                            .putBoolean(PREF_ENABLED, true)
                            .apply()
                        restoredSessionPayload = null
                        restoredToken = JSONObject(payload).optString("token")
                        activeSessionToken = restoredToken
                        installDocumentStartScript()
                        Toast.makeText(this@NativeBridgeActivity, "ورود با اثر انگشت فعال شد.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        clearBiometricSession()
                        Toast.makeText(this@NativeBridgeActivity, "ذخیره امن نشست انجام نشد: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(this@NativeBridgeActivity, "فعال‌سازی اثر انگشت انجام نشد.", Toast.LENGTH_SHORT).show()
                }
            })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("تأیید برای فعال‌سازی اثر انگشت")
                .setSubtitle("اثر انگشت خود را برای رمزنگاری امن نشست تأیید کنید.")
                .setNegativeButtonText("انصراف")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        } catch (e: Exception) {
            Toast.makeText(this, "امکان فعال‌سازی اثر انگشت وجود ندارد: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun createSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= 24) builder.setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: throw IllegalStateException("کلید امن دستگاه پیدا نشد.")
    }

    private fun newCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding")

    private fun clearBiometricSession() {
        prefs.edit().clear().apply()
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {}
        restoredSessionPayload = null
        restoredToken = ""
        activeSessionToken = ""
        promptedTokenFingerprint = ""
        NativeSessionCache.clear()
        if (::webView.isInitialized && documentStartSupported) installDocumentStartScript()
    }

    private fun installDocumentStartScript() {
        if (!documentStartSupported) return
        try {
            documentStartScriptHandler?.remove()
            val bridgeAsset = if (authOnly) "auth-bridge.js" else "native-bridge.js"
            val bridgeScript = assets.open(bridgeAsset).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val payload = restoredSessionPayload
            val restorePrefix = if (!payload.isNullOrBlank()) {
                val quoted = JSONObject.quote(payload)
                "(function(){try{var p=JSON.parse($quoted);if(p&&p.session&&p.token){var sk=p.sessionKey||'madreseyar.session.v14';var tk=p.tokenKey||'madreseyar.staff.token.v14';Storage.prototype.setItem.call(sessionStorage,sk,p.session);Storage.prototype.setItem.call(sessionStorage,tk,p.token);}}catch(_){}})();\n"
            } else ""
            documentStartScriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, restorePrefix + bridgeScript, setOf(allowedOrigin))
        } catch (e: Exception) {
            Toast.makeText(this, "راه‌اندازی پل ارتباطی امن انجام نشد.", Toast.LENGTH_LONG).show()
        }
    }

    private fun tryNativeAutoLogin() {
        if (!nativeLogin || autoLoginCompleted || autoLoginSubmitted || autoUsername.isBlank() || autoPassword.isBlank()) return
        if (autoLoginProbeCount >= 24) {
            revealAuthenticationUi("فرم ورود خودکار شناسایی نشد؛ ورود را در همین صفحه کامل کنید.")
            return
        }
        autoLoginProbeCount += 1
        val username = JSONObject.quote(autoUsername)
        val password = JSONObject.quote(autoPassword)
        val script = """
            (function(){
              try {
                function visible(el){
                  if(!el) return false;
                  var s=getComputedStyle(el);
                  var r=el.getBoundingClientRect();
                  return s.display!=='none' && s.visibility!=='hidden' && !el.disabled && r.width>0 && r.height>0;
                }
                function setValue(el, value){
                  var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                  var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                  if(desc && desc.set) desc.set.call(el, value); else el.value = value;
                  el.dispatchEvent(new Event('input',{bubbles:true}));
                  el.dispatchEvent(new Event('change',{bubbles:true}));
                  el.dispatchEvent(new Event('blur',{bubbles:true}));
                }
                var inputs = Array.prototype.slice.call(document.querySelectorAll('input')).filter(visible);
                var pass = document.querySelector('input[type=password]') || inputs.find(function(x){
                  return /pass|password|رمز/i.test((x.name||'')+' '+(x.id||'')+' '+(x.placeholder||'')+' '+(x.getAttribute('aria-label')||''));
                });
                var user = document.querySelector('input[autocomplete=username],input[name=username],input[name=user],input[name=login],input[type=tel],input[inputmode=numeric],input[type=text]') || inputs.find(function(x){
                  return x!==pass && x.type!=='hidden' && x.type!=='submit' && x.type!=='button';
                });
                if(!user || !pass) return 'missing';
                setValue(user, $username);
                setValue(pass, $password);
                var form = pass.form || user.form || document.querySelector('form');
                var buttons = Array.prototype.slice.call(document.querySelectorAll('button,input[type=submit],input[type=button]')).filter(visible);
                var button = buttons.find(function(b){
                  var label = String((b.innerText||b.textContent||b.value||b.getAttribute('aria-label')||'')).trim();
                  return /^(ورود|ورود به سامانه|ادامه|تأیید|تایید|login|sign\s*in)$/i.test(label) || /ورود|login|sign\s*in/i.test(label);
                });
                if(!button && form) button = form.querySelector('button,input[type=submit],input[type=button]');
                if(button && visible(button)){ button.click(); return 'clicked'; }
                if(form){
                  if(form.requestSubmit) form.requestSubmit();
                  else form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));
                  return 'submitted';
                }
                pass.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));
                pass.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));
                return 'enter';
              } catch(e) { return 'error'; }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { raw ->
            val result = raw?.trim('"') ?: ""
            when (result) {
                "clicked", "submitted", "enter" -> {
                    autoLoginSubmitted = true
                    if (!autoLoginFallbackScheduled) {
                        autoLoginFallbackScheduled = true
                        mainHandler.postDelayed({
                            if (nativeLogin && !autoLoginCompleted && !isFinishing) {
                                revealAuthenticationUi("اگر تأیید دومرحله‌ای یا مرحله تکمیلی نمایش داده شد، آن را کامل کنید.")
                            }
                        }, 4500L)
                    }
                }
                "missing" -> mainHandler.postDelayed({ tryNativeAutoLogin() }, 140L)
                else -> revealAuthenticationUi("ورود خودکار کامل نشد؛ ورود را در همین صفحه ادامه دهید.")
            }
        }
    }

    private fun revealAuthenticationUi(message: String) {
        if (!authOnly || isFinishing || autoLoginCompleted || mfaDialogVisible) return
        webView.visibility = View.INVISIBLE
        webView.alpha = 0f
        splash.visibility = View.VISIBLE
        val script = """
            (function(){
              try {
                function visible(el){
                  if(!el) return false;
                  var s=getComputedStyle(el), r=el.getBoundingClientRect();
                  return s.display!=='none' && s.visibility!=='hidden' && !el.disabled && r.width>0 && r.height>0;
                }
                var all=Array.prototype.slice.call(document.querySelectorAll('input')).filter(visible);
                var input=all.find(function(x){
                  var sig=((x.name||'')+' '+(x.id||'')+' '+(x.placeholder||'')+' '+(x.getAttribute('aria-label')||'')).toLowerCase();
                  return /otp|totp|mfa|2fa|code|pin|verify|verification|کد|تأیید|تایید/.test(sig) && x.type!=='password';
                }) || all.find(function(x){ return x.type!=='password' && x.type!=='hidden' && x.value===''; });
                if(!input){var msg=document.getElementById('loginMessage')||document.querySelector('.login-message,.error,.alert-danger,.alert-error');return JSON.stringify({ok:false,error:msg?String(msg.innerText||msg.textContent||'').trim():''});}
                if(!input.dataset.msyNativeId) input.dataset.msyNativeId='mfa-'+Date.now();
                var label=(input.getAttribute('aria-label')||input.placeholder||'کد تأیید دومرحله‌ای');
                return JSON.stringify({ok:true,id:input.dataset.msyNativeId,label:label});
              } catch(e){ return JSON.stringify({ok:false}); }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { raw ->
            runOnUiThread {
                try {
                    val decoded = org.json.JSONTokener(raw).nextValue() as? String ?: ""
                    val obj = JSONObject(decoded)
                    if (!obj.optBoolean("ok")) {
                        splash.visibility = View.GONE
                        Toast.makeText(this, obj.optString("error").ifBlank { "مرحله تکمیلی ورود قابل شناسایی نیست؛ نام کاربری/رمز یا تنظیمات MFA را بررسی کنید." }, Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    showNativeMfaDialog(obj.optString("id"), obj.optString("label").ifBlank { message })
                } catch (_: Exception) {
                    splash.visibility = View.GONE
                    Toast.makeText(this, "مرحله تکمیلی ورود قابل شناسایی نیست.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showNativeMfaDialog(nativeId: String, label: String) {
        if (mfaDialogVisible || nativeId.isBlank() || isFinishing) return
        mfaDialogVisible = true
        splash.visibility = View.GONE
        val input = EditText(this).apply {
            hint = label
            inputType = InputType.TYPE_CLASS_NUMBER
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("تأیید دومرحله‌ای")
            .setMessage("کد امنیتی را وارد کنید. نسخه وب نمایش داده نمی‌شود.")
            .setView(input)
            .setNegativeButton("انصراف") { _, _ ->
                mfaDialogVisible = false
                setResult(RESULT_CANCELED)
                finish()
            }
            .setPositiveButton("تأیید", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = input.text?.toString()?.trim().orEmpty()
                if (code.isBlank()) return@setOnClickListener
                val qId = JSONObject.quote(nativeId)
                val qCode = JSONObject.quote(code)
                val js = """
                    (function(){
                      try{
                        var input=document.querySelector('[data-msy-native-id='+${qId}+']');
                        if(!input) return 'missing';
                        var proto=input.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
                        var d=Object.getOwnPropertyDescriptor(proto,'value');
                        if(d&&d.set)d.set.call(input,${qCode}); else input.value=${qCode};
                        input.dispatchEvent(new Event('input',{bubbles:true}));
                        input.dispatchEvent(new Event('change',{bubbles:true}));
                        var form=input.form||input.closest('form');
                        var buttons=Array.prototype.slice.call(document.querySelectorAll('button,input[type=submit],input[type=button]')).filter(function(b){
                          var st=getComputedStyle(b),r=b.getBoundingClientRect(); return st.display!=='none'&&st.visibility!=='hidden'&&!b.disabled&&r.width>0&&r.height>0;
                        });
                        var btn=buttons.find(function(b){var t=String(b.innerText||b.textContent||b.value||'');return /تأیید|تایید|ادامه|ورود|verify|confirm|continue/i.test(t);});
                        if(btn){btn.click();return 'clicked';}
                        if(form){if(form.requestSubmit)form.requestSubmit();else form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));return 'submitted';}
                        return 'missing-button';
                      }catch(e){return 'error';}
                    })();
                """.trimIndent()
                dialog.dismiss()
                mfaDialogVisible = false
                splash.visibility = View.VISIBLE
                webView.evaluateJavascript(js, null)
                mainHandler.postDelayed({ probeStoredTeacherSession() }, 700L)
                mainHandler.postDelayed({
                    if (!autoLoginCompleted && !isFinishing) revealAuthenticationUi("کد تأیید دوباره لازم است.")
                }, 2600L)
            }
        }
        dialog.setOnDismissListener { mfaDialogVisible = false }
        dialog.show()
    }

    private fun probeStoredTeacherSession() {
        if (!returnOnReady || restoreOnly || autoLoginCompleted || isFinishing) return
        webView.evaluateJavascript(
            """
            (function(){
              try {
                var preferredSession='madreseyar.session.v14', preferredToken='madreseyar.staff.token.v14';
                var sessionKey='', tokenKey='', session='', token='';
                function inspect(){
                  for(var i=0;i<sessionStorage.length;i++){
                    var k=sessionStorage.key(i), v=sessionStorage.getItem(k)||'';
                    if(!session && (k===preferredSession || /session/i.test(k))){
                      try{ var o=JSON.parse(v); if(o && o.authUser && o.authUser.role){ session=v; sessionKey=k; } }catch(_e){}
                    }
                    if(!token && (k===preferredToken || /token/i.test(k)) && v.length>=20){ token=v; tokenKey=k; }
                  }
                  if(!session){
                    var pv=sessionStorage.getItem(preferredSession)||'';
                    if(pv){ session=pv; sessionKey=preferredSession; }
                  }
                  if(!token){
                    var tv=sessionStorage.getItem(preferredToken)||'';
                    if(tv){ token=tv; tokenKey=preferredToken; }
                  }
                }
                inspect();
                return JSON.stringify({session:session,token:token,sessionKey:sessionKey,tokenKey:tokenKey});
              } catch(e) { return ''; }
            })();
            """.trimIndent()
        ) { raw ->
            try {
                if (raw.isNullOrBlank() || raw == "null" || raw == "\"\"") return@evaluateJavascript
                val decoded = org.json.JSONTokener(raw).nextValue() as? String ?: return@evaluateJavascript
                if (decoded.isBlank()) return@evaluateJavascript
                val obj = JSONObject(decoded)
                val session = obj.optString("session")
                val token = obj.optString("token")
                if (session.isNotBlank() && token.isNotBlank()) {
                    handleTeacherReady(session, token, obj.optString("sessionKey"), obj.optString("tokenKey"))
                }
            } catch (_: Exception) {}
        }
    }

    private fun handleNativeMessage(raw: String) {
        if (raw.isBlank()) return
        val message = try { JSONObject(raw) } catch (_: Exception) { return }
        when (message.optString("type")) {
            "sessionCaptured" -> {
                activeSessionToken = message.optString("token")
                if (!restoreOnly) {
                    handleTeacherReady(
                        message.optString("session"),
                        message.optString("token"),
                        message.optString("sessionKey"),
                        message.optString("tokenKey")
                    )
                }
            }
            "teacherReady" -> handleTeacherReady(
                message.optString("session"),
                message.optString("token"),
                message.optString("sessionKey"),
                message.optString("tokenKey")
            )
            "sessionCleared" -> runOnUiThread { clearBiometricSession() }
            "restoreFailed" -> handleRestoreFailed()
            "roleRejected" -> handleRoleRejected()
            "refreshDashboard" -> runOnUiThread {
                webView.evaluateJavascript("(function(){var u=new URL(location.href);u.searchParams.set('p635_return','1');location.replace(u.toString());})()", null)
            }
            "openUrl" -> runOnUiThread { openNativeUrl(message.optString("url")) }
            "openHtml" -> runOnUiThread { showHtmlPreview(message.optString("html"), false) }
            "printHtml" -> runOnUiThread { showHtmlPreview(message.optString("html"), true) }
            "printPage" -> runOnUiThread {
                try {
                    val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
                    printManager.print("گزارش مدرسه‌یار", webView.createPrintDocumentAdapter("Madreseyar"), PrintAttributes.Builder().build())
                } catch (_: Exception) {
                    Toast.makeText(this, "چاپ در این دستگاه در دسترس نیست.", Toast.LENGTH_LONG).show()
                }
            }
            "beginBlobPreview" -> synchronized(blobLock) {
                blobPreviewMode = true
                blobName = message.optString("name", "preview").take(180).ifBlank { "madreseyar-preview" }
                blobMime = message.optString("mime", "application/octet-stream").take(120).ifBlank { "application/octet-stream" }
                blobBuffer = ByteArrayOutputStream()
            }
            "beginBlobDownload" -> synchronized(blobLock) {
                blobPreviewMode = false
                blobName = message.optString("name", "download").take(180).ifBlank { "madreseyar-file" }
                blobMime = message.optString("mime", "application/octet-stream").take(120).ifBlank { "application/octet-stream" }
                blobBuffer = ByteArrayOutputStream()
            }
            "appendBlobDownloadChunk" -> {
                try {
                    val decoded = Base64.decode(message.optString("chunk"), Base64.DEFAULT)
                    synchronized(blobLock) {
                        val buffer = blobBuffer ?: return
                        if (buffer.size() + decoded.size > MAX_BLOB_BYTES) blobBuffer = null else buffer.write(decoded)
                    }
                } catch (_: Exception) {}
            }
            "finishBlobDownload" -> {
                val payload: Pair<Boolean, Triple<String, String, ByteArray>>? = synchronized(blobLock) {
                    val buffer = blobBuffer ?: return
                    val result = Pair(blobPreviewMode, Triple(blobName, blobMime, buffer.toByteArray()))
                    blobBuffer = null
                    blobPreviewMode = false
                    result
                }
                runOnUiThread {
                    payload?.let { (preview, file) ->
                        if (preview) previewBlob(file.first, file.second, file.third)
                        else saveBlobToDownloads(file.first, file.second, file.third)
                    }
                }
            }
        }
    }

    private fun handleTeacherReady(sessionJson: String, token: String, sessionKey: String = "", tokenKey: String = "") {
        try {
            val user = JSONObject(sessionJson).optJSONObject("authUser")
            if (user?.optString("role") != "teacher" || token.length < 24) {
                handleRoleRejected()
                return
            }
            activeSessionToken = token
            autoLoginCompleted = true
            val payload = JSONObject()
                .put("session", sessionJson)
                .put("token", token)
                .put("sessionKey", sessionKey.ifBlank { "madreseyar.session.v14" })
                .put("tokenKey", tokenKey.ifBlank { "madreseyar.staff.token.v14" })
                .toString()
            NativeSessionCache.put(sessionJson, token, sessionKey, tokenKey)
            if (featureMode) {
                runOnUiThread { attemptOpenTargetFeature() }
                return
            }
            if (returnOnReady) {
                runOnUiThread {
                    if (!hasEncryptedSession()) showEnableBiometricDialogForNativeReturn(payload)
                    else finishNativeLoginSuccess()
                }
                return
            }
            if (hasEncryptedSession() && restoredToken == token) {
                if (restoredSessionPayload != null) {
                    restoredSessionPayload = null
                    installDocumentStartScript()
                }
                return
            }
            runOnUiThread {
                showEnableBiometricDialog(payload, token)
            }
        } catch (_: Exception) {
            handleRoleRejected()
        }
    }

    private fun finishNativeLoginSuccess() {
        autoLoginCompleted = true
        if (::webView.isInitialized) webView.visibility = View.INVISIBLE
        if (::splash.isInitialized) splash.visibility = View.VISIBLE
        setResult(RESULT_OK, Intent().putExtra("teacher_ready", true))
        if (!isFinishing) finish()
    }

    private fun showEnableBiometricDialogForNativeReturn(payload: String) {
        val can = BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            finishNativeLoginSuccess()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("فعال‌سازی ورود با اثر انگشت")
            .setMessage("رمز عبور ذخیره نمی‌شود. فقط نشست ورود با کلید امن گوشی و اثر انگشت محافظت خواهد شد.")
            .setPositiveButton("فعال‌سازی") { _, _ -> beginBiometricEnrollmentForNativeReturn(payload) }
            .setNegativeButton("فعلاً نه") { _, _ -> finishNativeLoginSuccess() }
            .setOnCancelListener { finishNativeLoginSuccess() }
            .show()
    }

    private fun beginBiometricEnrollmentForNativeReturn(payload: String) {
        try {
            val key = createSecretKey()
            val cipher = newCipher()
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    try {
                        val authenticatedCipher = result.cryptoObject?.cipher ?: throw IllegalStateException("Biometric cipher unavailable")
                        val encrypted = authenticatedCipher.doFinal(payload.toByteArray(Charsets.UTF_8))
                        prefs.edit()
                            .putString(PREF_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                            .putString(PREF_IV, Base64.encodeToString(authenticatedCipher.iv, Base64.NO_WRAP))
                            .putBoolean(PREF_ENABLED, true)
                            .apply()
                        restoredToken = JSONObject(payload).optString("token")
                        activeSessionToken = restoredToken
                        Toast.makeText(this@NativeBridgeActivity, "ورود با اثر انگشت فعال شد.", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {
                        clearBiometricSession()
                    } finally {
                        finishNativeLoginSuccess()
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    finishNativeLoginSuccess()
                }
            })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("تأیید اثر انگشت")
                .setSubtitle("برای فعال‌سازی ورود سریع مدرسه‌یار")
                .setNegativeButtonText("انصراف")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        } catch (_: Exception) {
            finishNativeLoginSuccess()
        }
    }

    private fun handleRestoreFailed() {
        runOnUiThread {
            if (restoreFailureHandled) return@runOnUiThread
            restoreFailureHandled = true
            clearBiometricSession()
            Toast.makeText(this, "نشست قبلی منقضی شده است؛ دوباره با رمز عبور وارد شوید.", Toast.LENGTH_LONG).show()
            webView.evaluateJavascript("sessionStorage.removeItem('madreseyar.session.v14');sessionStorage.removeItem('madreseyar.staff.token.v14');history.replaceState(null,'',location.pathname+location.hash)", null)
        }
    }

    private fun handleRoleRejected() {
        runOnUiThread {
            clearBiometricSession()
            Toast.makeText(this, "این اپلیکیشن مخصوص حساب معلم است.", Toast.LENGTH_LONG).show()
            webView.evaluateJavascript("document.getElementById('logoutBtn')?.click()", null)
        }
    }

    private fun enqueueDownload(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        try {
            val downloadUri = Uri.parse(url)
            if (!isAllowedHttpsUri(downloadUri)) {
                Toast.makeText(this, "دانلود از آدرس ناامن یا خارج از سامانه مجاز نیست.", Toast.LENGTH_LONG).show()
                return
            }
            val request = DownloadManager.Request(downloadUri)
                .setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
                .setDescription("دانلود از مدرسه‌یار")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (Build.VERSION.SDK_INT >= 24) {
                request.setAllowedOverMetered(true)
                request.setAllowedOverRoaming(false)
            }
            if (!mimeType.isNullOrBlank()) request.setMimeType(mimeType)
            request.addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            if (activeSessionToken.isNotBlank()) request.addRequestHeader("Authorization", "Bearer $activeSessionToken")
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimeType))
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, "دانلود فایل آغاز شد.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "دانلود فایل شروع نشد: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveBlobToDownloads(name: String, mime: String, bytes: ByteArray) {
        try {
            val safeName = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").ifBlank { "madreseyar-file" }
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Madreseyar")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("ساخت فایل دانلود ممکن نشد.")
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("نوشتن فایل دانلود ممکن نشد.")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                val file = File(dir, safeName)
                FileOutputStream(file).use { it.write(bytes) }
            }
            Toast.makeText(this, "فایل در پوشه دانلودها ذخیره شد.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "ذخیره فایل انجام نشد: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun createCameraCaptureIntent(): Intent? {
        return try {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            val file = File.createTempFile("teacher-capture-", ".jpg", dir)
            val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
            cameraCaptureUri = uri
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }.takeIf { it.resolveActivity(packageManager) != null }
        } catch (_: Exception) {
            cameraCaptureUri = null
            null
        }
    }

    private fun acceptedMimeTypes(accepts: List<String>): Array<String> {
        if (accepts.isEmpty()) return emptyArray()
        return accepts.mapNotNull {
            when {
                it.startsWith(".") -> when (it) {
                    ".pdf" -> "application/pdf"
                    ".jpg", ".jpeg" -> "image/jpeg"
                    ".png" -> "image/png"
                    ".webp" -> "image/webp"
                    ".csv" -> "text/csv"
                    else -> null
                }
                it.contains("/") -> it
                else -> null
            }
        }.distinct().toTypedArray()
    }

    private fun choosePickerMimeType(accepts: List<String>): String {
        val mimes = acceptedMimeTypes(accepts)
        if (mimes.isEmpty()) return "*/*"
        if (mimes.size == 1) return mimes.first()
        val roots = mimes.map { it.substringBefore('/') }.distinct()
        return if (roots.size == 1) "${roots.first()}/*" else "*/*"
    }

    private fun openNativeUrl(raw: String) {
        if (raw.isBlank() || raw == "about:blank") return
        try {
            val uri = Uri.parse(raw)
            if (isAllowedHttpsUri(uri)) {
                webView.loadUrl(raw)
                return
            }
            if ((uri.scheme ?: "").equals("http", true) && (uri.host ?: "").equals(allowedHost, true)) {
                Toast.makeText(this, "لینک ناامن HTTP مسدود شد.", Toast.LENGTH_SHORT).show()
                return
            }
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            Toast.makeText(this, "باز کردن لینک ممکن نیست.", Toast.LENGTH_LONG).show()
        }
    }

    private fun configurePreviewWebView(view: WebView) {
        view.setBackgroundColor(Color.WHITE)
        view.settings.apply {
            javaScriptEnabled = false
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }
    }

    private fun showHtmlPreview(html: String, printable: Boolean) {
        if (html.isBlank()) return
        if (html.length > 2_500_000) {
            Toast.makeText(this, "گزارش برای پیش‌نمایش داخل اپ بسیار بزرگ است.", Toast.LENGTH_LONG).show()
            return
        }
        val preview = WebView(this)
        configurePreviewWebView(preview)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (printable) "پیش‌نمایش چاپ / PDF" else "پیش‌نمایش")
            .setView(preview)
            .setNegativeButton("بستن", null)
            .apply { if (printable) setPositiveButton("چاپ / ذخیره PDF", null) }
            .create()
        dialog.setOnDismissListener {
            try { preview.stopLoading(); preview.destroy() } catch (_: Exception) {}
        }
        dialog.setOnShowListener {
            if (printable) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    printWebView(preview, "گزارش مدرسه‌یار")
                }
            }
        }
        dialog.show()
        preview.loadDataWithBaseURL(allowedOrigin + "/", html, "text/html", "UTF-8", null)
    }

    private fun printWebView(view: WebView, name: String) {
        try {
            val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
            printManager.print(name, view.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
        } catch (_: Exception) {
            Toast.makeText(this, "چاپ در این دستگاه در دسترس نیست.", Toast.LENGTH_LONG).show()
        }
    }

    private fun previewBlob(name: String, mime: String, bytes: ByteArray) {
        try {
            if (mime.startsWith("image/", true)) {
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val safeMime = mime.takeIf { it.matches(Regex("image/(png|jpeg|jpg|webp|gif)", RegexOption.IGNORE_CASE)) } ?: "image/jpeg"
                showHtmlPreview("<html><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><body style=\"margin:0;background:#111;display:flex;align-items:center;justify-content:center;min-height:100vh\"><img alt=\"preview\" style=\"max-width:100%;height:auto\" src=\"data:$safeMime;base64,$encoded\"></body></html>", false)
                return
            }
            val dir = File(cacheDir, "preview").apply { mkdirs() }
            val ext = when {
                mime.contains("pdf", true) -> ".pdf"
                mime.contains("text", true) -> ".txt"
                else -> ""
            }
            val safe = name.replace(Regex("[^A-Za-z0-9._\\-\\u0600-\\u06FF]"), "_").take(80).ifBlank { "madreseyar-preview" }
            val file = File(dir, if (safe.contains('.')) safe else safe + ext)
            FileOutputStream(file).use { it.write(bytes) }
            val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime.ifBlank { "application/octet-stream" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            saveBlobToDownloads(name, mime, bytes)
            Toast.makeText(this, "نمایشگر مناسب پیدا نشد؛ فایل در Downloads ذخیره شد.", Toast.LENGTH_LONG).show()
        }
    }

    private fun extractUris(data: Intent?): Array<Uri>? {
        if (data == null) return null
        val result = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(result::add)
        }
        if (result.isEmpty()) data.data?.let(result::add)
        return result.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()


    override fun onBackPressed() {
        if (featureMode) finish() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
            webView.resumeTimers()
        }
    }

    override fun onPause() {
        if (::webView.isInitialized) {
            webView.onPause()
            webView.pauseTimers()
        }
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        intent.removeExtra(EXTRA_USERNAME)
        intent.removeExtra(EXTRA_PASSWORD)
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.removeJavascriptInterface("MSYNative")
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }
}
