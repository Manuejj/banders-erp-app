package com.banderspotato.erp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;

/**
 * Banders Potato ERP: shows the web app in a full-screen window and gives it what a browser tab cannot:
 * saving PDF / CSV files in Downloads, sharing a file to WhatsApp, the camera for invoice photos.
 */
public class MainActivity extends AppCompatActivity {

    /** The web link of the system (the Google Apps Script web app). If you make a NEW deployment, change it here. */
    static final String APP_URL = "https://script.google.com/macros/s/AKfycbxsKdDVPhmhRI8hXnAmaMUL5s_isxBtVwILIWZbr84YUf-ShNu9kIsxtaeDGbJFbjC2/exec";

    private static final int REQ_FILE = 1001;
    private static final int REQ_CAMERA_PERMISSION = 1002;

    private WebView web;
    private View splash;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraUri;
    private long lastBack = 0;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        web = findViewById(R.id.web);
        splash = findViewById(R.id.splash);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new AppBridge(this), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if (scheme.equals("file")) return false;
                String host = u.getHost() == null ? "" : u.getHost();
                if (host.endsWith("google.com") || host.endsWith("googleusercontent.com")) return false;
                openOutside(u);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                splash.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    splash.setVisibility(View.GONE);
                    view.loadUrl("file:///android_asset/offline.html?url=" + Uri.encode(APP_URL));
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> callback, FileChooserParams params) {
                return chooseFile(callback, params);
            }

            // links that open a new window (for example the WhatsApp link) go to the phone
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                WebView temp = new WebView(MainActivity.this);
                temp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        openOutside(request.getUrl());
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(temp);
                resultMsg.sendToTarget();
                return true;
            }
        });

        // normal web downloads go to the browser; files made by the app use AndroidBridge.saveFile
        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url.startsWith("http")) {
                openOutside(Uri.parse(url));
            } else {
                Toast.makeText(MainActivity.this, "Use the Download button inside the app.", Toast.LENGTH_SHORT).show();
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(APP_URL);
        }
    }

    private void openOutside(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(this, "Could not open the link.", Toast.LENGTH_SHORT).show();
        }
    }

    /** Photo or file for an invoice copy, product upload, count file... */
    private boolean chooseFile(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (filePathCallback != null) filePathCallback.onReceiveValue(null);
        filePathCallback = callback;
        cameraUri = null;

        boolean imageAccepted = false;
        String[] types = params.getAcceptTypes();
        if (types != null) {
            for (String t : types) {
                if (t != null && t.startsWith("image/")) imageAccepted = true;
            }
        }
        boolean cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;

        Intent camera = null;
        if (imageAccepted) {
            if (cameraGranted) {
                camera = buildCameraIntent();
            } else if (params.isCaptureEnabled()) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
                Toast.makeText(this, "Allow the camera, then tap Take photo again.", Toast.LENGTH_LONG).show();
                filePathCallback.onReceiveValue(null);
                filePathCallback = null;
                return true;
            }
        }

        Intent toLaunch;
        if (camera != null && params.isCaptureEnabled()) {
            toLaunch = camera;   // "Take photo": go straight to the camera
        } else {
            toLaunch = Intent.createChooser(params.createIntent(), "Choose a file");
            if (camera != null) toLaunch.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
        }
        try {
            startActivityForResult(toLaunch, REQ_FILE);
        } catch (Exception e) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        return true;
    }

    private Intent buildCameraIntent() {
        try {
            File dir = new File(getCacheDir(), "images");
            if (!dir.exists()) dir.mkdirs();
            File photo = File.createTempFile("invoice_", ".jpg", dir);
            cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photo);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (i.resolveActivity(getPackageManager()) == null) {
                cameraUri = null;
                return null;
            }
            return i;
        } catch (Exception e) {
            cameraUri = null;
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || filePathCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK) {
            if (data != null && (data.getData() != null || data.getClipData() != null)) {
                results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            } else if (cameraUri != null) {
                File photo = new File(getCacheDir(), "images/" + cameraUri.getLastPathSegment());
                if (photo.exists() && photo.length() > 0) results = new Uri[]{cameraUri};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
        cameraUri = null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) {
            super.onBackPressed();
        } else {
            lastBack = now;
            Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("AndroidBridge");
            web.destroy();
        }
        super.onDestroy();
    }
}
