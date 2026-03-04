package com.retropricer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var loadingOverlay: LinearLayout
    private lateinit var loadingStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnRetry: Button

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private var pageLoaded = false

    private val handler = Handler(Looper.getMainLooper())

    // After TIMEOUT_MS, show "Taking longer than usual..." message + retry button
    private val TIMEOUT_MS = 20_000L

    private val timeoutRunnable = Runnable {
        if (!pageLoaded) {
            loadingStatus.text = "Server is waking up… this can take a minute."
            btnRetry.visibility = View.VISIBLE
        }
    }

    // ── Permission launchers ───────────────────────────────────────────────
    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, "Camera permission needed for game scanning", Toast.LENGTH_SHORT).show()
    }

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* WebView geolocation callback handles the result */ }

    // ── File chooser launcher (handles camera + gallery) ──────────────────
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileUploadCallback ?: return@registerForActivityResult
        fileUploadCallback = null

        if (result.resultCode == Activity.RESULT_OK) {
            val uri = cameraImageUri?.takeIf { result.data?.data == null } ?: result.data?.data
            if (uri != null) {
                callback.onReceiveValue(arrayOf(uri))
            } else {
                callback.onReceiveValue(null)
            }
        } else {
            callback.onReceiveValue(null)
        }
        cameraImageUri = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        swipeRefresh  = findViewById(R.id.swipe_refresh)
        webView       = findViewById(R.id.webview)
        loadingOverlay = findViewById(R.id.loading_overlay)
        loadingStatus  = findViewById(R.id.loading_status)
        progressBar    = findViewById(R.id.progress_bar)
        btnRetry       = findViewById(R.id.btn_retry)

        ViewCompat.setOnApplyWindowInsetsListener(swipeRefresh) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        swipeRefresh.setColorSchemeColors(android.graphics.Color.parseColor("#00e5a0"))
        swipeRefresh.setProgressBackgroundColorSchemeColor(android.graphics.Color.parseColor("#1c1c21"))
        swipeRefresh.setOnRefreshListener {
            retryLoad()
        }

        btnRetry.setOnClickListener { retryLoad() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        setupWebView()
        startLoad()
    }

    private fun startLoad() {
        pageLoaded = false
        loadingOverlay.visibility = View.VISIBLE
        loadingStatus.text = "Starting up…"
        btnRetry.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
        webView.loadUrl(APP_URL)
    }

    private fun retryLoad() {
        handler.removeCallbacks(timeoutRunnable)
        if (!pageLoaded) {
            loadingStatus.text = "Retrying…"
            btnRetry.visibility = View.GONE
            progressBar.visibility = View.VISIBLE
        }
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
        webView.reload()
    }

    private fun onPageReady() {
        pageLoaded = true
        swipeRefresh.isRefreshing = false
        handler.removeCallbacks(timeoutRunnable)
        loadingOverlay.visibility = View.GONE
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled        = true
            domStorageEnabled        = true
            allowFileAccess          = true
            allowContentAccess       = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls      = false
            displayZoomControls      = false
            useWideViewPort          = true
            loadWithOverviewMode     = true
            cacheMode                = WebSettings.LOAD_DEFAULT
            setGeolocationEnabled(true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Only hide overlay if the page actually loaded content (not a blank/error page)
                if (url != null && url != "about:blank") {
                    onPageReady()
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    swipeRefresh.isRefreshing = false
                    handler.removeCallbacks(timeoutRunnable)
                    loadingStatus.text = "No connection. Check your internet."
                    btnRetry.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val cameraIntent = buildCameraIntent()
                val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }

                val chooser = Intent.createChooser(galleryIntent, "Select or take photo").apply {
                    if (cameraIntent != null) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
                    }
                }

                fileChooserLauncher.launch(chooser)
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                callback.invoke(origin, true, false)
            }
        }
    }

    private fun buildCameraIntent(): Intent? {
        return try {
            val photoFile = createImageFile()
            val uri = FileProvider.getUriForFile(this, "com.retropricer.fileprovider", photoFile)
            cameraImageUri = uri
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun createImageFile(): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("GAME_${ts}_", ".jpg", dir)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    companion object {
        const val APP_URL = "https://retro-pricer.onrender.com"
    }
}
