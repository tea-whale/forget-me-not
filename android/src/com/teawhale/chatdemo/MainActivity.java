package com.teawhale.chatdemo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * WebView host for the bundled chat-demo build.
 *
 * Pages are served from {@link #HOST} instead of file:// so the app runs in a secure
 * HTTPS origin: IndexedDB, localStorage and fetches to the DeepSeek API behave exactly
 * as they do in a browser. Assets are read straight from the APK.
 */
public class MainActivity extends Activity {
    private static final String TAG = "ChatDemo";
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/index.html";
    private static final int REQUEST_FILE = 1001;
    private static final int REQUEST_MIC = 1002;

    private static final Map<String, String> MIME_TYPES = new HashMap<>();

    static {
        MIME_TYPES.put("html", "text/html");
        MIME_TYPES.put("js", "text/javascript");
        MIME_TYPES.put("mjs", "text/javascript");
        MIME_TYPES.put("css", "text/css");
        MIME_TYPES.put("json", "application/json");
        MIME_TYPES.put("map", "application/json");
        MIME_TYPES.put("svg", "image/svg+xml");
        MIME_TYPES.put("png", "image/png");
        MIME_TYPES.put("jpg", "image/jpeg");
        MIME_TYPES.put("jpeg", "image/jpeg");
        MIME_TYPES.put("gif", "image/gif");
        MIME_TYPES.put("webp", "image/webp");
        MIME_TYPES.put("ico", "image/x-icon");
        MIME_TYPES.put("woff", "font/woff");
        MIME_TYPES.put("woff2", "font/woff2");
        MIME_TYPES.put("ttf", "font/ttf");
        MIME_TYPES.put("otf", "font/otf");
        MIME_TYPES.put("txt", "text/plain");
        MIME_TYPES.put("xml", "application/xml");
    }

    private WebView web;
    private ValueCallback<Uri[]> pendingFileCallback;
    private PermissionRequest pendingMicRequest;
    private OutputStream downloadStream;
    private String downloadLabel;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView view = new WebView(this);
        web = view;
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        view.setBackgroundColor(0xFFFFFFFF);
        view.setWebViewClient(new AssetClient());
        view.setWebChromeClient(new ChromeClient());
        view.addJavascriptInterface(new DownloadBridge(), "NativeDownload");
        view.setDownloadListener((url, userAgent, contentDisposition, mime, contentLength) ->
                enqueueSystemDownload(url, contentDisposition, mime));
        setContentView(view);
        if (savedInstanceState == null) {
            view.loadUrl(START_URL);
        } else {
            view.restoreState(savedInstanceState);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) {
            web.saveState(outState);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ---------------------------------------------------------------- asset hosting

    private final class AssetClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (!HOST.equals(url.getHost())) {
                return null; // API calls and other hosts go to the network.
            }
            String path = url.getPath();
            if (path == null || "/".equals(path)) {
                path = "/index.html";
            }
            String assetPath = "app" + path;
            String mime = mimeType(assetPath);
            try {
                InputStream stream = getAssets().open(assetPath);
                return new WebResourceResponse(mime, charset(mime), 200, "OK",
                        cacheHeaders(assetPath, mime), stream);
            } catch (IOException error) {
                byte[] body = ("Not found: " + path).getBytes(StandardCharsets.UTF_8);
                return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                        cacheHeaders(assetPath, "text/plain"), new ByteArrayInputStream(body));
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (HOST.equals(url.getHost())) {
                return false;
            }
            String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
            if ("http".equals(scheme) || "https".equals(scheme)) {
                openExternally(url);
                return true;
            }
            if ("mailto".equals(scheme) || "tel".equals(scheme)) {
                openExternally(url);
                return true;
            }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            view.evaluateJavascript(DOWNLOAD_BRIDGE_JS, null);
        }
    }

    private void openExternally(Uri url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, url));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show();
        }
    }

    private static String mimeType(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) {
            return "application/octet-stream";
        }
        String extension = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mime = MIME_TYPES.get(extension);
        return mime == null ? "application/octet-stream" : mime;
    }

    private static String charset(String mime) {
        return mime.startsWith("text/") || "application/json".equals(mime)
                || "application/xml".equals(mime) ? "utf-8" : null;
    }

    private static Map<String, String> cacheHeaders(String path, String mime) {
        Map<String, String> headers = new HashMap<>();
        boolean entry = path.endsWith(".html");
        headers.put("Cache-Control", entry ? "no-cache" : "public, max-age=31536000, immutable");
        headers.put("X-Content-Type-Options", "nosniff");
        return headers;
    }

    // ---------------------------------------------------------------- web chrome

    private final class ChromeClient extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (pendingFileCallback != null) {
                pendingFileCallback.onReceiveValue(null);
            }
            pendingFileCallback = callback;
            try {
                Intent intent = params.createIntent();
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, REQUEST_FILE);
                return true;
            } catch (ActivityNotFoundException error) {
                pendingFileCallback = null;
                return false;
            }
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            boolean wantsAudio = false;
            for (String resource : request.getResources()) {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                    wantsAudio = true;
                }
            }
            if (!wantsAudio) {
                request.deny();
                return;
            }
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingMicRequest = request;
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_FILE) {
            if (pendingFileCallback == null) {
                super.onActivityResult(requestCode, resultCode, data);
                return;
            }
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int index = 0; index < count; index++) {
                        results[index] = data.getClipData().getItemAt(index).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            pendingFileCallback.onReceiveValue(results);
            pendingFileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_MIC) {
            PermissionRequest request = pendingMicRequest;
            pendingMicRequest = null;
            if (request != null) {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    request.deny();
                }
            }
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    // ---------------------------------------------------------------- downloads

    /** Receives chunked base64 data for blob downloads (chat export, memory backup). */
    private final class DownloadBridge {
        @JavascriptInterface
        public void start(String name, String mime) {
            runOnUiThread(() -> {
                try {
                    openDownload(name, mime);
                } catch (IOException | SecurityException error) {
                    Log.w(TAG, "Cannot start download", error);
                    closeDownload();
                    Toast.makeText(MainActivity.this, "无法保存文件：" + error.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void chunk(String base64) {
            runOnUiThread(() -> {
                if (downloadStream == null) {
                    return;
                }
                try {
                    downloadStream.write(Base64.decode(base64, Base64.DEFAULT));
                } catch (IOException error) {
                    Log.w(TAG, "Write failed", error);
                    closeDownload();
                }
            });
        }

        @JavascriptInterface
        public void finish() {
            runOnUiThread(() -> {
                String label = downloadLabel;
                closeDownload();
                if (label != null) {
                    Toast.makeText(MainActivity.this, "已保存到下载目录：" + label,
                            Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void abort() {
            runOnUiThread(MainActivity.this::closeDownload);
        }
    }

    private void openDownload(String name, String mime) throws IOException {
        closeDownload();
        String safeName = sanitize(name);
        String safeMime = mime == null || mime.isEmpty() ? "application/octet-stream" : mime;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, safeName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, safeMime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("无法创建下载条目");
            }
            downloadStream = getContentResolver().openOutputStream(uri);
        } else {
            File directory;
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                directory = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
            } else {
                directory = getExternalFilesDir(null);
            }
            if (directory == null) {
                throw new IOException("存储不可用");
            }
            if (!directory.exists()) {
                //noinspection ResultOfMethodCallIgnored
                directory.mkdirs();
            }
            File target = new File(directory, safeName);
            downloadStream = new FileOutputStream(target);
        }
        downloadLabel = safeName;
    }

    private void closeDownload() {
        OutputStream stream = downloadStream;
        downloadStream = null;
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException error) {
                Log.w(TAG, "Close failed", error);
            }
        }
    }

    private static String sanitize(String name) {
        String base = name == null || name.trim().isEmpty() ? "download.bin" : name.trim();
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (base.length() > 120) {
            base = base.substring(base.length() - 120);
        }
        return base;
    }

    /** DownloadListener fallback for plain http(s) links (for example attachments). */
    private void enqueueSystemDownload(String url, String contentDisposition, String mime) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (mime != null) {
                request.setMimeType(mime);
            }
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    sanitize("download-" + System.currentTimeMillis()));
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            manager.enqueue(request);
            Toast.makeText(this, "已开始下载", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException error) {
            Log.w(TAG, "Download enqueue failed", error);
            openExternally(Uri.parse(url));
        }
    }

    /**
     * Patches object URLs so the page's blob exports reach the native bridge: the web app
     * downloads chat exports and memory backups through an <a download> trigger.
     */
    private static final String DOWNLOAD_BRIDGE_JS =
            "(function(){"
                    + "if(window.__dshDownloadBridge){return;}"
                    + "window.__dshDownloadBridge=true;"
                    + "var blobs=new Map();var seq=0;"
                    + "var create=URL.createObjectURL.bind(URL);"
                    + "URL.createObjectURL=function(blob){var id='blob:native/'+(++seq);blobs.set(id,blob);return id;};"
                    + "var revoke=URL.revokeObjectURL.bind(URL);"
                    + "URL.revokeObjectURL=function(url){blobs.delete(url);try{revoke(url);}catch(e){}};"
                    + "function toBase64(buffer){var bytes=new Uint8Array(buffer);var out='';var step=0x8000;"
                    + "for(var i=0;i<bytes.length;i+=step){out+=String.fromCharCode.apply(null,bytes.subarray(i,i+step));}"
                    + "return btoa(out);}"
                    + "function readSlice(blob){return new Promise(function(resolve,reject){"
                    + "var reader=new FileReader();reader.onload=function(){resolve(reader.result);};"
                    + "reader.onerror=function(){reject(reader.error);};reader.readAsArrayBuffer(blob);});}"
                    + "document.addEventListener('click',function(event){"
                    + "var target=event.target;var anchor=target&&target.closest?target.closest('a[download]'):null;"
                    + "if(!anchor){return;}var key=anchor.getAttribute('href')||'';var blob=blobs.get(key)||blobs.get(anchor.href);"
                    + "if(!blob){return;}event.preventDefault();"
                    + "var name=anchor.getAttribute('download')||'download.bin';"
                    + "var mime=blob.type||'application/octet-stream';"
                    + "(async function(){try{NativeDownload.start(name,mime);var chunk=256*1024;"
                    + "for(var offset=0;offset<blob.size;offset+=chunk){"
                    + "var buffer=await readSlice(blob.slice(offset,offset+chunk));"
                    + "NativeDownload.chunk(toBase64(buffer));}"
                    + "NativeDownload.finish();}catch(e){NativeDownload.abort();}})();"
                    + "},true);"
                    + "})();";
}
