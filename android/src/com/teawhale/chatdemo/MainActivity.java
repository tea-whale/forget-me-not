package com.teawhale.chatdemo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    /** Extensions the web app lists in its accept attribute; anything else is normalized. */
    private static final Set<String> IMAGE_EXTS = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "svgz", "avif", "apng",
            "tif", "tiff", "ico", "xbm", "dib", "pjp", "pjpeg", "jfif"));

    static String mimeForName(String name) {
        String extension = extensionOf(name).toLowerCase(Locale.ROOT);
        String mime = MIME_TYPES.get(extension);
        if (mime != null) {
            return mime;
        }
        String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return guessed == null ? "application/octet-stream" : guessed;
    }

    static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1) : "";
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
        // The file chooser hands back content:// URIs; blocking content access makes
        // <input type="file"> silently produce empty reads, so uploads fail.
        settings.setAllowContentAccess(true);
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
            view.loadUrl(startUrl());
        } else {
            view.restoreState(savedInstanceState);
        }
    }

    /**
     * Adds the APK version to the entry URL so a freshly installed build cannot be served
     * from a WebView cache populated by the previous version.
     */
    private String startUrl() {
        try {
            String version = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            return START_URL + (START_URL.contains("?") ? "&" : "?") + "v=" + version;
        } catch (PackageManager.NameNotFoundException error) {
            return START_URL;
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
        headers.put("Cache-Control",
                entry ? "no-store, no-cache, must-revalidate" : "public, max-age=31536000, immutable");
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
                startActivityForResult(pickerIntent(params), REQUEST_FILE);
                return true;
            } catch (ActivityNotFoundException error) {
                pendingFileCallback = null;
                return false;
            }
        }

        /**
         * The page accepts a long list of extensions, which the framework cannot express as
         * MIME filters. Open a permissive picker and let the page validate afterwards.
         */
        private Intent pickerIntent(FileChooserParams params) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            List<String> mimes = new ArrayList<>();
            String[] accept = params.getAcceptTypes();
            if (accept != null) {
                for (String entry : accept) {
                    if (entry != null && entry.contains("/")) {
                        mimes.add(entry);
                    }
                }
            }
            if (mimes.size() == 1) {
                intent.setType(mimes.get(0));
            } else {
                intent.setType("*/*");
                if (!mimes.isEmpty()) {
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
                }
            }
            if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            if (intent.resolveActivity(getPackageManager()) == null) {
                intent.setAction(Intent.ACTION_GET_CONTENT);
            }
            return intent;
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
                List<Uri> picked = new ArrayList<>();
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    for (int index = 0; index < count; index++) {
                        Uri item = data.getClipData().getItemAt(index).getUri();
                        if (item != null) {
                            picked.add(normalizePickedUri(item));
                        }
                    }
                } else if (data.getData() != null) {
                    picked.add(normalizePickedUri(data.getData()));
                }
                if (!picked.isEmpty()) {
                    results = picked.toArray(new Uri[0]);
                }
            }
            pendingFileCallback.onReceiveValue(results);
            pendingFileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * Copies the picked file into the cache and serves it through PickedFileProvider.
     *
     * The WebView filters chooser results against the page's accept list. Picker results
     * often have no extension or an unsupported one (HEIC photos, cloud providers), so they
     * get dropped without any error. Normalizing here guarantees a matching name and MIME.
     */
    private Uri normalizePickedUri(Uri source) {
        try {
            ContentResolver resolver = getContentResolver();
            String mime = resolver.getType(source);
            if (mime == null) {
                mime = "";
            }
            String displayName = queryDisplayName(source);
            String extension = extensionOf(displayName).toLowerCase(Locale.ROOT);
            if (extension.isEmpty()) {
                String guessed = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                extension = guessed == null ? "" : guessed.toLowerCase(Locale.ROOT);
            }
            boolean imageByMime = mime.startsWith("image/");
            boolean heic = extension.equals("heic") || extension.equals("heif")
                    || mime.contains("heic") || mime.contains("heif");
            boolean supportedImage = IMAGE_EXTS.contains(extension);
            File directory = PickedFileProvider.cacheDir(this);
            String stamp = Long.toString(System.currentTimeMillis(), 36)
                    + Integer.toString((int) (Math.random() * 1296), 36);
            String base = baseNameOf(displayName);
            if (base.isEmpty()) {
                base = imageByMime || supportedImage ? "image" : "file";
            }
            File target;
            if ((imageByMime || supportedImage) && (heic || !supportedImage)) {
                target = new File(directory, base + "-" + stamp + ".jpg");
                if (!transcodeToJpeg(source, target)) {
                    return source;
                }
            } else {
                if (extension.isEmpty()) {
                    extension = "bin";
                }
                target = new File(directory, base + "-" + stamp + "." + extension);
                try (InputStream input = resolver.openInputStream(source);
                     OutputStream output = new FileOutputStream(target)) {
                    if (input == null) {
                        return source;
                    }
                    copy(input, output);
                }
            }
            if (target.length() <= 0) {
                // An empty copy would make the page show a broken attachment.
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                return source;
            }
            return Uri.parse("content://" + PickedFileProvider.AUTHORITY + "/" + target.getName());
        } catch (IOException | RuntimeException | OutOfMemoryError error) {
            Log.w(TAG, "Cannot normalize picked file", error);
            return source;
        }
    }

    /** Re-encodes an unsupported image (for example HEIC) as a JPEG the page accepts. */
    private boolean transcodeToJpeg(Uri source, File target) {
        ContentResolver resolver = getContentResolver();
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = resolver.openInputStream(source)) {
                BitmapFactory.decodeStream(input, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return false;
            }
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 4096) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap bitmap;
            try (InputStream input = resolver.openInputStream(source)) {
                bitmap = BitmapFactory.decodeStream(input, null, options);
            }
            if (bitmap == null) {
                return false;
            }
            try (FileOutputStream output = new FileOutputStream(target)) {
                return bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output);
            } finally {
                bitmap.recycle();
            }
        } catch (IOException | RuntimeException | OutOfMemoryError error) {
            Log.w(TAG, "Cannot transcode picked image", error);
            return false;
        }
    }

    private String queryDisplayName(Uri source) {
        try (Cursor cursor = getContentResolver().query(
                source, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Fall through to the URI segment below.
        }
        String segment = source.getLastPathSegment();
        return segment == null ? "" : segment;
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    /** File name without extension, limited to characters that are safe in a URI segment. */
    private static String baseNameOf(String name) {
        String base = name == null ? "" : name;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = base.replaceAll("[^\\p{L}\\p{N}_-]", "_");
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        return base;
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
