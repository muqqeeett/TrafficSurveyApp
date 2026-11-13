package com.trafficsurvey.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;

/**
 * Native shell around the Traffic Volume Survey web app.
 * The web app is served from the APK (assets/www) over https://appassets.androidplatform.net,
 * which is a secure origin, so localStorage, clipboard and the rest behave as in the browser.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/www/index.html";
    private static final int REQ_FILE = 1001;
    private static final int BG_DARK = 0xFF0E120F;

    private FrameLayout root;
    private WebView web;
    private LinearLayout reportOverlay;
    private WebView reportWeb;
    private WebViewAssetLoader assetLoader;
    private String shim = "";
    private ValueCallback<Uri[]> filePathCallback;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        shim = readAsset("android-shim.js");
        assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", this::serveAsset)
                .build();

        root = new FrameLayout(this);
        root.setBackgroundColor(BG_DARK);
        setContentView(root);

        web = new WebView(this);
        web.setBackgroundColor(BG_DARK);
        configure(web);
        web.addJavascriptInterface(new AppBridge(), "AndroidBridge");
        web.setWebViewClient(new ShellClient(true));
        web.setWebChromeClient(new MainChrome());
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Run the shim before the page's own scripts when the WebView supports it.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, shim,
                    Collections.singleton("https://" + HOST));
        }

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        closeReport();
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** Back works like the browser: close the report, then step back through the app's tabs, then exit. */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (reportOverlay != null) {
            closeReport();
        } else if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------------ web view setup

    private void configure(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);   // needed to read CSV files picked through the system picker
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setTextZoom(100);              // keep the layout identical whatever the system font size
    }

    private WebResourceResponse serveAsset(String path) {
        try {
            InputStream in = getAssets().open(path);
            return new WebResourceResponse(mimeFor(path), "UTF-8", in);
        } catch (IOException e) {
            return null;
        }
    }

    private static String mimeFor(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "text/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".webmanifest") || p.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    /** Serves the bundled app, keeps everything else out, and sends real web links to the browser. */
    private class ShellClient extends WebViewClient {
        private final boolean isMain;

        ShellClient(boolean isMain) {
            this.isMain = isMain;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (HOST.equals(u.getHost())) {
                return assetLoader.shouldInterceptRequest(u);
            }
            String scheme = u.getScheme();
            if ("http".equals(scheme) || "https".equals(scheme)) {
                // The app is offline-first and has no network permission; skip the Google Fonts link quietly.
                return new WebResourceResponse("text/plain", "UTF-8", 404, "Blocked", null,
                        new ByteArrayInputStream(new byte[0]));
            }
            return null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (HOST.equals(u.getHost())) return false;
            String scheme = u.getScheme();
            if ("about".equals(scheme) || "blob".equals(scheme) || "data".equals(scheme)) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (Exception ignored) { }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (isMain) {
                // Fallback for WebViews without document-start scripts.
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view.evaluateJavascript(shim, null);
                }
            } else {
                view.evaluateJavascript(
                        "window.print=function(){PopupBridge.print();};", null);
            }
        }
    }

    private class MainChrome extends WebChromeClient {

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (filePathCallback != null) filePathCallback.onReceiveValue(null);
            filePathCallback = callback;

            // CSV files are labelled differently by different file managers, so accept all the usual types.
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "text/csv", "text/comma-separated-values", "application/csv",
                    "application/vnd.ms-excel", "text/plain", "application/octet-stream"});
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                    params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
            try {
                startActivityForResult(i, REQ_FILE);
            } catch (Exception e) {
                filePathCallback = null;
                callback.onReceiveValue(null);
                return false;
            }
            return true;
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      Message resultMsg) {
            closeReport();
            reportWeb = new WebView(MainActivity.this);
            configure(reportWeb);
            reportWeb.setBackgroundColor(Color.WHITE);
            reportWeb.addJavascriptInterface(new PopupBridge(), "PopupBridge");
            reportWeb.setWebViewClient(new ShellClient(false));
            reportWeb.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onCloseWindow(WebView window) {
                    closeReport();
                }

                @Override
                public boolean onJsAlert(WebView v, String url, String msg, JsResult r) {
                    return showAlert(msg, r, false);
                }
            });

            reportOverlay = new LinearLayout(MainActivity.this);
            reportOverlay.setOrientation(LinearLayout.VERTICAL);
            reportOverlay.setBackgroundColor(BG_DARK);
            reportOverlay.setClickable(true);

            LinearLayout bar = new LinearLayout(MainActivity.this);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setBackgroundColor(BG_DARK);
            int pad = dp(8);
            bar.setPadding(pad, pad, pad, pad);
            Button close = new Button(MainActivity.this);
            close.setText("\u2190 Back to app");
            close.setAllCaps(false);
            close.setTextColor(0xFF163300);
            close.setBackgroundColor(0xFF9FE870);
            close.setOnClickListener(v -> closeReport());
            bar.addView(close, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            reportOverlay.addView(bar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            reportOverlay.addView(reportWeb, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            root.addView(reportOverlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
            t.setWebView(reportWeb);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public boolean onJsAlert(WebView v, String url, String msg, JsResult r) {
            return showAlert(msg, r, false);
        }

        @Override
        public boolean onJsConfirm(WebView v, String url, String msg, JsResult r) {
            return showAlert(msg, r, true);
        }
    }

    private boolean showAlert(String message, final JsResult result, boolean withCancel) {
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm());
        if (withCancel) b.setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel());
        b.show();
        return true;
    }

    private void closeReport() {
        if (reportOverlay != null) {
            root.removeView(reportOverlay);
            reportOverlay = null;
        }
        if (reportWeb != null) {
            reportWeb.destroy();
            reportWeb = null;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ------------------------------------------------------------------ JS bridges

    /** Methods the page can call (through the shim). Only the bundled app can reach these. */
    private class AppBridge {

        @JavascriptInterface
        public void saveFile(String name, String mime, String base64) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                ContentResolver cr = getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                v.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IOException("insert failed");
                try (OutputStream out = cr.openOutputStream(uri)) {
                    if (out == null) throw new IOException("no stream");
                    out.write(bytes);
                }
                v.clear();
                v.put(MediaStore.Downloads.IS_PENDING, 0);
                cr.update(uri, v, null, null);
                toast("Saved to Downloads: " + safe);
            } catch (Exception e) {
                toast("Could not save the file.");
            }
        }

        @JavascriptInterface
        public void toast(String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
        }

        @JavascriptInterface
        public void printPopup() {
            runOnUiThread(() -> printView(reportWeb));
        }

        @JavascriptInterface
        public void printMain() {
            runOnUiThread(() -> printView(reportWeb != null ? reportWeb : web));
        }

        @JavascriptInterface
        public void setBarColor(String css) {
            runOnUiThread(() -> applyBarColor(css));
        }
    }

    private class PopupBridge {
        @JavascriptInterface
        public void print() {
            runOnUiThread(() -> printView(reportWeb));
        }
    }

    private void printView(WebView v) {
        if (v == null) return;
        PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
        String job = "Traffic Volume Survey Report";
        PrintDocumentAdapter adapter = v.createPrintDocumentAdapter(job);
        pm.print(job, adapter, new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .build());
    }

    private void applyBarColor(String css) {
        int c;
        try {
            c = Color.parseColor(css.trim());
        } catch (Exception e) {
            return;
        }
        getWindow().setStatusBarColor(c);
        getWindow().setNavigationBarColor(c);
        if (root != null) root.setBackgroundColor(c);
        boolean light = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) > 150;
        WindowInsetsController ic = getWindow().getInsetsController();
        if (ic != null) {
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            ic.setSystemBarsAppearance(light ? mask : 0, mask);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
