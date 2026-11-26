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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
        MIME_TYPES.put("bmp", "image/bmp");
        MIME_TYPES.put("heic", "image/heic");
        MIME_TYPES.put("heif", "image/heif");
        MIME_TYPES.put("avif", "image/avif");
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
    private String pendingNativeRequest;
    private final ExecutorService fileWorker = Executors.newSingleThreadExecutor();
    private final Map<String, File> pickedFiles = new ConcurrentHashMap<>();
    private static final String PICKED_PATH = "/_picked/";
    private static final long MAX_PICKED_BYTES = 64L * 1024 * 1024;
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
        view.addJavascriptInterface(new NativeFilesBridge(), "NativeFiles");
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
            if (path != null && path.startsWith(PICKED_PATH)) {
                return pickedFileResponse(path);
            }
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

    /** Only random tokens registered by the picker can be served, never arbitrary cache paths. */
    private WebResourceResponse pickedFileResponse(String path) {
        String token = path.substring(PICKED_PATH.length());
        File file = pickedFiles.get(token);
        if (file != null) {
            try {
                InputStream stream = new FilterInputStream(new FileInputStream(file)) {
                    @Override
                    public void close() throws IOException {
                        try {
                            super.close();
                        } finally {
                            pickedFiles.remove(token, file);
                            file.delete();
                        }
                    }
                };
                Map<String, String> headers = cacheHeaders("picked.html", "");
                headers.put("Content-Length", Long.toString(file.length()));
                return new WebResourceResponse(mimeForName(file.getName()), null, 200, "OK",
                        headers, stream);
            } catch (IOException error) {
                Log.w(TAG, "Cannot serve picked file", error);
            }
        }
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                cacheHeaders("picked.html", ""), new ByteArrayInputStream(new byte[0]));
    }

    private final class NativeFilesBridge {
        @JavascriptInterface
        public void pick(String requestId) {
            runOnUiThread(() -> {
                Uri current = web == null ? null : Uri.parse(web.getUrl() == null ? "" : web.getUrl());
                if (current == null || !"https".equals(current.getScheme())
                        || !HOST.equals(current.getHost()) || requestId == null
                        || requestId.length() > 100) {
                    return;
                }
                if (pendingNativeRequest != null || pendingFileCallback != null) {
                    deliverNativeFiles(requestId, new JSONArray(), "文件选择器正在使用中");
                    return;
                }
                pendingNativeRequest = requestId;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                if (intent.resolveActivity(getPackageManager()) == null) {
                    intent.setAction(Intent.ACTION_GET_CONTENT);
                }
                try {
                    startActivityForResult(intent, REQUEST_FILE);
                } catch (ActivityNotFoundException error) {
                    pendingNativeRequest = null;
                    deliverNativeFiles(requestId, new JSONArray(), "无法打开文件选择器");
                }
            });
        }
    }

    private void deliverNativeFiles(String requestId, JSONArray files, String error) {
        try {
            JSONObject detail = new JSONObject();
            detail.put("id", requestId);
            detail.put("files", files);
            if (error != null) {
                detail.put("error", error);
            }
            String script = "window.dispatchEvent(new CustomEvent('native-files-picked',{detail:"
                    + detail.toString() + "}));";
            runOnUiThread(() -> {
                if (web != null && !isFinishing() && !isDestroyed()) {
                    web.evaluateJavascript(script, null);
                }
            });
        } catch (JSONException errorBuildingResult) {
            Log.e(TAG, "Cannot deliver picked files", errorBuildingResult);
        }
    }

    private void receiveNativeFiles(String requestId, int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null) {
            deliverNativeFiles(requestId, new JSONArray(), null);
            return;
        }
        List<Uri> sources = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int index = 0; index < data.getClipData().getItemCount(); index++) {
                Uri source = data.getClipData().getItemAt(index).getUri();
                if (source != null) {
                    sources.add(source);
                }
            }
        } else if (data.getData() != null) {
            sources.add(data.getData());
        }
        if (sources.size() > 50) {
            deliverNativeFiles(requestId, new JSONArray(), "每次最多选择 50 个文件");
            return;
        }
        fileWorker.execute(() -> {
            JSONArray files = new JSONArray();
            try {
                for (Uri source : sources) {
                    files.put(prepareNativeFile(source));
                }
                deliverNativeFiles(requestId, files, null);
            } catch (IOException | JSONException | RuntimeException | OutOfMemoryError error) {
                for (int index = 0; index < files.length(); index++) {
                    JSONObject descriptor = files.optJSONObject(index);
                    if (descriptor != null) {
                        String path = Uri.parse(descriptor.optString("url")).getPath();
                        File file = path == null ? null
                                : pickedFiles.remove(path.substring(PICKED_PATH.length()));
                        if (file != null) {
                            file.delete();
                        }
                    }
                }
                Log.w(TAG, "Cannot read native picked file", error);
                deliverNativeFiles(requestId, new JSONArray(), "读取附件失败：" + error.getMessage());
            }
        });
    }

    private JSONObject prepareNativeFile(Uri source) throws IOException, JSONException {
        String name = sanitize(queryDisplayName(source));
        String mime = getContentResolver().getType(source);
        String extension = extensionOf(name).toLowerCase(Locale.ROOT);
        boolean image = (mime != null && mime.startsWith("image/")) || IMAGE_EXTS.contains(extension)
                || "heic".equals(extension) || "heif".equals(extension);
        File directory = PickedFileProvider.cacheDir(this);
        File target = File.createTempFile("picked-", ".tmp", directory);
        boolean registered = false;
        try {
            try (InputStream input = getContentResolver().openInputStream(source);
                 OutputStream output = new FileOutputStream(target)) {
                if (input == null) {
                    throw new IOException("无法读取所选文件");
                }
                byte[] buffer = new byte[64 * 1024];
                long size = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    size += count;
                    if (size > MAX_PICKED_BYTES) {
                        throw new IOException("单个文件不能超过 64 MB");
                    }
                    output.write(buffer, 0, count);
                }
            }
            if (target.length() == 0) {
                throw new IOException("所选文件为空");
            }
            String actual = sniffImageExt(target);
            image |= !actual.isEmpty();
            if (image) {
                // 统一经 BitmapFactory 重编码：系统能解、但 WebView 拒解的 JPEG（异常头信息、
                // 相机 RAW、HEIC 等）原来会卡在页面的 image.decode()，请求根本发不出去。
                File normalized = normalizePickedImage(target, directory);
                if (normalized != null) {
                    target.delete();
                    target = normalized;
                } else {
                    // 系统也解不出：原样交给页面，页面解码失败后会改为原样上传
                    String keep = isPageImageExt(actual) ? actual
                            : (isPageImageExt(extension) ? extension : "jpg");
                    target = renameInto(target, directory, "image", keep);
                }
                actual = extensionOf(target.getName()).toLowerCase(Locale.ROOT);
                name = baseNameOf(name) + "." + actual;
                mime = mimeForName(name);
            } else {
                if (extension.isEmpty()) {
                    extension = "bin";
                }
                target = renameInto(target, directory, "file", extension);
                if (mime == null || mime.isEmpty()) {
                    mime = mimeForName(name);
                }
            }
            String token = UUID.randomUUID().toString();
            JSONObject descriptor = new JSONObject();
            descriptor.put("url", "https://" + HOST + PICKED_PATH + token);
            descriptor.put("name", name);
            descriptor.put("type", mime);
            descriptor.put("size", target.length());
            pickedFiles.put(token, target);
            registered = true;
            return descriptor;
        } finally {
            if (!registered) {
                target.delete();
            }
        }
    }

    // ---------------------------------------------------------------- web chrome

    private final class ChromeClient extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (pendingNativeRequest != null) {
                callback.onReceiveValue(null);
                return true;
            }
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
                    // 通配类型（image/*）只用于页面的接受列表，不能拿来过滤选择器，
                    // 否则聊天框的附件按钮会变成只能选图片。
                    if (entry != null && entry.contains("/") && !entry.endsWith("/*")) {
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
            if (pendingNativeRequest != null) {
                String requestId = pendingNativeRequest;
                pendingNativeRequest = null;
                receiveNativeFiles(requestId, resultCode, data);
                return;
            }
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
            boolean image = mime.startsWith("image/") || IMAGE_EXTS.contains(extension);
            File directory = PickedFileProvider.cacheDir(this);
            String stamp = Long.toString(System.currentTimeMillis(), 36)
                    + Integer.toString((int) (Math.random() * 1296), 36);
            String base = baseNameOf(displayName);
            if (base.isEmpty()) {
                base = image ? "image" : "file";
            }
            if (image) {
                // 先原样落盘，再按文件头判断真实格式：很多相册返回的文件叫 .jpg 但内容是
                // HEIC，直接上传会让页面缩略图黑掉、模型也读不出图。
                File copy = new File(directory,
                        base + "-" + stamp + "." + (extension.isEmpty() ? "img" : extension));
                try (InputStream input = resolver.openInputStream(source);
                     OutputStream output = new FileOutputStream(copy)) {
                    if (input == null) {
                        return source;
                    }
                    copy(input, output);
                }
                if (copy.length() <= 0) {
                    //noinspection ResultOfMethodCallIgnored
                    copy.delete();
                    return source;
                }
                String actual = sniffImageExt(copy);
                if (isPageImageExt(actual)) {
                    File target = new File(directory, base + "-" + stamp + "." + actual);
                    if (copy.renameTo(target)) {
                        return pickedUri(target);
                    }
                }
                File target = normalizePickedImage(copy, directory);
                if (target != null) {
                    //noinspection ResultOfMethodCallIgnored
                    copy.delete();
                    return pickedUri(target);
                }
                //noinspection ResultOfMethodCallIgnored
                copy.delete();
                // 转码失败时退回原件：页面 accept 里带了 image/*，原件同样能被接收
                return source;
            }
            if (extension.isEmpty()) {
                extension = "bin";
            }
            File target = new File(directory, base + "-" + stamp + "." + extension);
            try (InputStream input = resolver.openInputStream(source);
                 OutputStream output = new FileOutputStream(target)) {
                if (input == null) {
                    return source;
                }
                copy(input, output);
            }
            if (target.length() <= 0) {
                // An empty copy would make the page show a broken attachment.
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                return source;
            }
            return pickedUri(target);
        } catch (IOException | RuntimeException | OutOfMemoryError error) {
            Log.w(TAG, "Cannot normalize picked file", error);
            return source;
        }
    }

    private static Uri pickedUri(File file) {
        return Uri.parse("content://" + PickedFileProvider.AUTHORITY + "/" + file.getName());
    }

    /** 页面自己会解码的图片格式，直接沿用原文件，不重新压缩 */
    private static boolean isPageImageExt(String extension) {
        return "jpg".equals(extension) || "png".equals(extension)
                || "gif".equals(extension) || "webp".equals(extension);
    }

    /**
     * 读文件头判断真实图片格式，返回 jpg / png / gif / webp / heic / avif，认不出时返回空串。
     * 相册给的 MIME 和后缀都不可靠，按字节判断才不会把 HEIC 当成 JPEG 传给服务端。
     */
    private static String sniffImageExt(File file) {
        try (InputStream input = new FileInputStream(file)) {
            byte[] head = new byte[12];
            int read = 0;
            while (read < head.length) {
                int count = input.read(head, read, head.length - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
            if (read >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8
                    && (head[2] & 0xFF) == 0xFF) {
                return "jpg";
            }
            if (read >= 4 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N'
                    && head[3] == 'G') {
                return "png";
            }
            if (read >= 3 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F') {
                return "gif";
            }
            if (read >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
                return "webp";
            }
            if (read >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
                String brand = new String(head, 8, 4, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
                if (brand.startsWith("hei") || brand.startsWith("hev") || brand.startsWith("mif")
                        || brand.startsWith("msf")) {
                    return "heic";
                }
                if (brand.startsWith("avi")) {
                    return "avif";
                }
            }
        } catch (IOException error) {
            Log.w(TAG, "Cannot sniff picked image", error);
        }
        return "";
    }

    /**
     * 用系统解码器重编码图片，返回带 .jpg/.png 后缀的临时文件，失败时返回 null。
     *
     * 有些相册图片 Android 能解、WebView 的 image.decode() 却报 "source image cannot be
     * decoded"；统一重编码后页面拿到的一定是标准 JPEG/PNG，不会再卡在本地解码。
     * 带透明通道的图保留 PNG，避免透明区域变黑。
     */
    private File normalizePickedImage(File source, File directory) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 4096) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
            if (bitmap == null) {
                return null;
            }
            boolean alpha = bitmap.hasAlpha();
            String extension = alpha ? "png" : "jpg";
            File target = File.createTempFile("image-", "." + extension, directory);
            boolean written;
            try (FileOutputStream output = new FileOutputStream(target)) {
                written = bitmap.compress(
                        alpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 92, output);
            } finally {
                bitmap.recycle();
            }
            if (!written || target.length() == 0) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                return null;
            }
            return target;
        } catch (IOException | RuntimeException | OutOfMemoryError error) {
            Log.w(TAG, "Cannot normalize picked image", error);
            return null;
        }
    }

    /** 把无后缀的临时文件改成带真实后缀的名字，保证页面拿到的 MIME 正确。 */
    private static File renameInto(File source, File directory, String prefix, String extension) {
        try {
            File target = File.createTempFile(prefix + "-", "." + extension, directory);
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (source.renameTo(target)) {
                return target;
            }
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = new FileOutputStream(target)) {
                copy(input, output);
            }
            //noinspection ResultOfMethodCallIgnored
            source.delete();
            return target;
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Cannot rename picked file", error);
            return source;
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
