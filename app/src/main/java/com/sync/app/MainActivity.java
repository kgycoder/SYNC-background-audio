package com.sync.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "SYNC";
    private WebView webView;
    private WebViewAssetLoader assetLoader;

    // ★ BG: 100ms 마다 resumeTimers() 강제 호출 — 시스템 pauseTimers() 대응
    private final Handler     resumeHandler  = new Handler(Looper.getMainLooper());
    private final Runnable    resumeRunnable = new Runnable() {
        @Override public void run() {
            try { if (webView != null) webView.resumeTimers(); }
            catch (Exception ignored) {}
            resumeHandler.postDelayed(this, 100);
        }
    };

    private final OkHttpClient http = new OkHttpClient.Builder()
            .followRedirects(true)
            .build();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }

        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webView);

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/",
                        new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/res/",
                        new WebViewAssetLoader.ResourcesPathHandler(this))
                .build();

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setDefaultTextEncodingName("UTF-8");
        ws.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36");

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setBackgroundColor(Color.parseColor("#08080D"));

        // ★ BG: 렌더러 우선순위 최상위 — 백그라운드에서도 스로틀링 안 함
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(
                    WebView.RENDERER_PRIORITY_IMPORTANT,
                    false  // false = 앱 백그라운드에서도 우선순위 유지
            );
        }

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebChromeClient(new WebChromeClient());

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                WebResourceResponse response =
                        assetLoader.shouldInterceptRequest(request.getUrl());
                if (response == null) return null;

                String mime = response.getMimeType();
                if (mime == null) mime = "text/plain";
                if (mime.contains(";")) mime = mime.substring(0, mime.indexOf(";")).trim();

                return new WebResourceResponse(mime, "UTF-8", response.getData());
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                return false;
            }

            // ★ BG: 페이지 로드 완료 후 Page Visibility 스푸핑 JS 주입
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Page Visibility 스푸핑은 app.js 첫 줄에서 처리
            }
        });

        webView.loadUrl(
                "https://appassets.androidplatform.net/assets/www/index.html");

        // ★ BG: Foreground Service 시작 (백그라운드 재생 유지)
        startMusicService();

        // ★ BG: resumeTimers 루프 시작
        resumeHandler.post(resumeRunnable);

        // ★ BG: 알림 권한 요청 (Android 13+)
        requestNotificationPermission();
    }

    // ★ BG: Page Visibility API 스푸핑 — YouTube가 백그라운드 인식 못 하게 차단
    private void injectVisibilitySpoof(WebView view) {
        String js =
            "(function(){"
            + "try{"
            // document.hidden 항상 false
            + "Object.defineProperty(document,'hidden',"
            + "{get:function(){return false;},configurable:true});"
            // document.visibilityState 항상 'visible'
            + "Object.defineProperty(document,'visibilityState',"
            + "{get:function(){return 'visible';},configurable:true});"
            // visibilitychange 이벤트 등록 차단
            + "var _origAdd=document.addEventListener.bind(document);"
            + "document.addEventListener=function(type,fn,opts){"
            + "if(type==='visibilitychange')return;"
            + "return _origAdd(type,fn,opts);"
            + "};"
            // pagehide / freeze 이벤트 차단
            + "window.addEventListener('pagehide',function(e){e.stopImmediatePropagation();},true);"
            + "window.addEventListener('freeze',function(e){e.stopImmediatePropagation();},true);"
            + "}catch(e){}"
            + "})();";
        view.evaluateJavascript(js, null);
    }

    // ★ BG: Foreground Service 시작
    private void startMusicService() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 알림 채널 미리 생성 (서비스보다 먼저)
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        MusicKeepAliveService.CHANNEL_ID,
                        "SYNC 음악 재생",
                        android.app.NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                android.app.NotificationManager nm =
                        (android.app.NotificationManager)
                                getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.createNotificationChannel(ch);
            }
            Intent i = new Intent(this, MusicKeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            Log.e(TAG, "startMusicService error", e);
        }
    }

    private void stopMusicService() {
        try { stopService(new Intent(this, MusicKeepAliveService.class)); }
        catch (Exception ignored) {}
    }

    // ★ BG: 알림 권한 (Android 13+)
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        1002);
            }
        }
    }

    // ★ BG: setTitle 메시지 처리 → 알림 제목 업데이트
    private void updateNotificationTitle(String title) {
        try {
            sendBroadcast(new Intent(MusicKeepAliveService.ACTION_UPDATE_TITLE)
                    .putExtra("title", title));
        } catch (Exception ignored) {}
    }

    // ──────────────────────────────────────────────────────────────
    //  생명주기 — onPause에서 WebView.onPause() 호출 제거 (★ BG)
    // ──────────────────────────────────────────────────────────────
    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        webView.resumeTimers();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // ★ BG: webView.onPause() 호출하지 않음 — 렌더러 pause 차단
        // (기존 코드의 webView.onPause() 제거)
    }

    @Override
    protected void onDestroy() {
        // ★ BG: resumeTimers 루프 정지
        resumeHandler.removeCallbacks(resumeRunnable);
        stopMusicService();
        executor.shutdown();
        webView.destroy();
        super.onDestroy();
    }

    private void sendToJs(JSONObject payload) {
        String b64 = Base64.encodeToString(
                payload.toString().getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP);
        String js =
            "(function(){" +
            "  var b=atob('" + b64 + "');" +
            "  var bytes=new Uint8Array(b.length);" +
            "  for(var i=0;i<b.length;i++) bytes[i]=b.charCodeAt(i);" +
            "  var s=new TextDecoder('utf-8').decode(bytes);" +
            "  window.__sync && window.__sync(s);" +
            "})();";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private void setOrientation(String mode) {
        if ("landscape".equals(mode)) {
            setRequestedOrientation(
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            hideSystemUI();
        } else {
            setRequestedOrientation(
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            showSystemUI();
        }
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    private void showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null)
                c.show(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.navigationBars());
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void postMessage(String json) {
            try {
                JSONObject msg = new JSONObject(json);
                String type = msg.optString("type");
                switch (type) {
                    case "search":
                        executor.submit(() -> doSearch(msg)); break;
                    case "suggest":
                        executor.submit(() -> doSuggest(msg)); break;
                    case "fetchLyrics":
                        executor.submit(() -> doFetchLyrics(msg)); break;
                    case "orientation":
                        String orient = msg.optString("value", "sensor");
                        runOnUiThread(() -> setOrientation(orient)); break;
                    // ★ BG: 재생 중인 곡 제목 → 알림 업데이트
                    case "setTitle":
                        String title = msg.optString("title", "SYNC");
                        runOnUiThread(() -> updateNotificationTitle(title)); break;
                    default: break;
                }
            } catch (JSONException e) {
                Log.e(TAG, "postMessage parse error", e);
            }
        }
    }

    // ─── 이하 doSearch / doSuggest / doFetchLyrics 등은 원본 그대로 ───

    private void doSearch(JSONObject msg) {
        String query = msg.optString("query");
        String id    = msg.optString("id", "0");
        try {
            String KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
            String URL = "https://www.youtube.com/youtubei/v1/search?key="
                       + KEY + "&prettyPrint=false";

            JSONObject client = new JSONObject();
            client.put("clientName", "WEB");
            client.put("clientVersion", "2.20240101.00.00");
            client.put("hl", "ko");
            client.put("gl", "KR");
            JSONObject context = new JSONObject();
            context.put("client", client);
            JSONObject body = new JSONObject();
            body.put("context", context);
            body.put("query", query);
            body.put("params", "EgIQAQ==");

            Request req = new Request.Builder()
                    .url(URL)
                    .post(RequestBody.create(
                            body.toString(),
                            MediaType.parse("application/json; charset=utf-8")))
                    .addHeader("X-YouTube-Client-Name", "1")
                    .addHeader("X-YouTube-Client-Version", "2.20240101.00.00")
                    .addHeader("Origin", "https://www.youtube.com")
                    .addHeader("Referer", "https://www.youtube.com/")
                    .addHeader("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
                    .addHeader("User-Agent",
                            "Mozilla/5.0 (Linux; Android 14) " +
                            "AppleWebKit/537.36 Chrome/124.0 Safari/537.36")
                    .build();

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null)
                    throw new IOException("HTTP " + resp.code());
                JSONArray tracks = parseSearchResults(readUtf8(resp));
                JSONObject result = new JSONObject();
                result.put("type", "searchResult");
                result.put("id", id);
                result.put("success", true);
                result.put("tracks", tracks);
                sendToJs(result);
            }
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("type", "searchResult");
                err.put("id", id);
                err.put("success", false);
                err.put("error", e.getMessage());
                sendToJs(err);
            } catch (JSONException ignored) {}
        }
    }

    private JSONArray parseSearchResults(String json) throws JSONException {
        JSONArray list = new JSONArray();
        JSONObject doc = new JSONObject(json);
        if (!doc.has("contents")) return list;

        JSONArray sections;
        try {
            sections = doc
                    .getJSONObject("contents")
                    .getJSONObject("twoColumnSearchResultsRenderer")
                    .getJSONObject("primaryContents")
                    .getJSONObject("sectionListRenderer")
                    .getJSONArray("contents");
        } catch (JSONException e) { return list; }

        for (int s = 0; s < sections.length() && list.length() < 20; s++) {
            JSONObject sec = sections.getJSONObject(s);
            if (!sec.has("itemSectionRenderer")) continue;
            JSONArray items = sec.getJSONObject("itemSectionRenderer")
                               .getJSONArray("contents");

            for (int k = 0; k < items.length() && list.length() < 20; k++) {
                JSONObject item = items.getJSONObject(k);
                if (!item.has("videoRenderer")) continue;
                JSONObject vr = item.getJSONObject("videoRenderer");
                String vid = vr.optString("videoId", "");
                if (vid.isEmpty()) continue;

                String title = extractText(vr, "title");
                String ch = extractText(vr, "ownerText");
                if (ch.isEmpty()) ch = extractText(vr, "shortBylineText");

                String durStr = "";
                try {
                    if (vr.has("lengthText"))
                        durStr = vr.getJSONObject("lengthText").optString("simpleText", "");
                } catch (JSONException ignored2) {}

                int dur = parseDur(durStr);
                if (!isMusicVideo(title, ch, dur)) continue;

                JSONObject t = new JSONObject();
                t.put("id", vid);
                t.put("title", title);
                t.put("channel", ch);
                t.put("dur", dur);
                t.put("thumb", "https://i.ytimg.com/vi/" + vid + "/mqdefault.jpg");
                list.put(t);
            }
        }
        return list;
    }

    private String extractText(JSONObject vr, String key) {
        try {
            if (!vr.has(key)) return "";
            JSONObject obj = vr.getJSONObject(key);
            if (obj.has("runs"))
                return obj.getJSONArray("runs").getJSONObject(0).optString("text", "");
            return obj.optString("simpleText", "");
        } catch (JSONException e) { return ""; }
    }

    private int parseDur(String s) {
        if (s == null || s.isEmpty()) return 0;
        String[] p = s.split(":");
        try {
            if (p.length == 3) return Integer.parseInt(p[0]) * 3600
                    + Integer.parseInt(p[1]) * 60 + Integer.parseInt(p[2]);
            if (p.length == 2) return Integer.parseInt(p[0]) * 60
                    + Integer.parseInt(p[1]);
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    private boolean isMusicVideo(String title, String channel, int durSec) {
        String tl = title.toLowerCase(), cl = channel.toLowerCase();
        for (String kw : new String[]{
                "vevo","topic","music","records","entertainment",
                "sound","audio","official","label","studio"})
            if (cl.contains(kw)) return true;
        for (String kw : new String[]{
                "official","mv","m/v","music video","audio","lyrics",
                "lyric","visualizer","live","performance","concert","feat",
                "뮤직비디오","음원","공식","노래"})
            if (tl.contains(kw)) return true;
        return durSec >= 60 || durSec == 0;
    }

    private void doSuggest(JSONObject msg) {
        String query = msg.optString("query");
        String id    = msg.optString("id", "0");
        try {
            String url = "https://suggestqueries.google.com/complete/search"
                    + "?client=firefox&ds=yt&q="
                    + java.net.URLEncoder.encode(query, "UTF-8") + "&hl=ko";
            Request req = new Request.Builder().url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 Firefox/124.0")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.body() == null) throw new IOException("empty");
                String json = readUtf8(resp);
                if (json.startsWith("window."))
                    json = json.replaceFirst("^[^(]+\\(", "")
                               .replaceFirst("\\)\\s*$", "");
                JSONArray arr  = new JSONArray(json);
                JSONArray sugs = new JSONArray();
                if (arr.length() > 1) {
                    JSONArray inner = arr.getJSONArray(1);
                    for (int i = 0; i < inner.length() && sugs.length() < 8; i++) {
                        Object o = inner.get(i);
                        String sv = (o instanceof JSONArray)
                                ? ((JSONArray) o).optString(0, "") : o.toString();
                        if (!sv.isEmpty()) sugs.put(sv);
                    }
                }
                JSONObject result = new JSONObject();
                result.put("type", "suggestResult");
                result.put("id", id);
                result.put("success", true);
                result.put("suggestions", sugs);
                sendToJs(result);
            }
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("type", "suggestResult");
                err.put("id", id);
                err.put("success", false);
                err.put("suggestions", new JSONArray());
                sendToJs(err);
            } catch (JSONException ignored) {}
        }
    }

    private void doFetchLyrics(JSONObject msg) {
        String rawTitle = msg.optString("title");
        String channel  = msg.optString("channel");
        double dur      = msg.optDouble("duration", 0);
        String id       = msg.optString("id", "0");

        JSONArray lines = tryLrclib(rawTitle, channel, dur);
        if (lines == null) lines = tryNetEase(rawTitle, channel, dur);

        try {
            JSONObject result = new JSONObject();
            result.put("type", "lyricsResult");
            result.put("id", id);
            if (lines != null) {
                result.put("success", true);
                result.put("lines", lines);
            } else {
                result.put("success", false);
                result.put("lines", new JSONArray());
            }
            sendToJs(result);
        } catch (JSONException ignored) {}
    }

    private JSONArray tryLrclib(String rawTitle, String channel, double ytDur) {
        try {
            String ct = cleanTitle(rawTitle);
            String ca = cleanArtist(channel);

            String stripped = stripBrackets(ct);
            List<String> queries = new ArrayList<>();
            queries.add(ct + " " + ca);
            queries.add(ct);
            if (!stripped.equals(ct)) queries.add(stripped);
            if (!ca.isEmpty()) queries.add(ca + " " + ct);
            if (!ca.isEmpty() && !stripped.equals(ct)) queries.add(stripped + " " + ca);

            JSONArray results = new JSONArray();
            for (String q : queries) {
                JSONArray r = searchLrclib(q);
                if (hasSyncedResults(r)) { results = r; break; }
                if (results.length() == 0 && r.length() > 0) results = r;
            }

            if (results.length() == 0) return null;

            String bestLrc   = null;
            double bestScore = Double.NEGATIVE_INFINITY;

            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.getJSONObject(i);
                String lrcText = item.optString("syncedLyrics", "");
                if (lrcText.isEmpty()) lrcText = item.optString("plainLyrics", "");
                if (lrcText.isEmpty()) continue;

                double lrcDur = getLrcLastTimestamp(lrcText);
                if (lrcDur <= 0) lrcDur = item.optDouble("duration", 0);

                double score = 0;
                if (ytDur > 0 && lrcDur > 0) {
                    double diff = Math.abs(lrcDur - ytDur);
                    if      (diff <= 3)  score += 50;
                    else if (diff <= 10) score += 35;
                    else if (diff <= 30) score += 15;
                    else if (diff <= 60) score += 5;
                    else                 score -= 25;
                }
                score += titleSim(ct, item.optString("trackName",  "")) * 30;
                score += titleSim(ca, item.optString("artistName", "")) * 20;
                if (!item.optString("syncedLyrics", "").isEmpty()) score += 10;

                if (score > bestScore) { bestScore = score; bestLrc = lrcText; }
            }
            return bestLrc != null ? parseLrc(bestLrc) : null;
        } catch (Exception e) { return null; }
    }

    private JSONArray searchLrclib(String query) {
        try {
            String url = "https://lrclib.net/api/search?q="
                    + java.net.URLEncoder.encode(query, "UTF-8");
            Request req = new Request.Builder().url(url)
                    .addHeader("User-Agent", "SYNCApp/1.0 (https://github.com/sync)")
                    .addHeader("Accept", "application/json")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.body() == null) return new JSONArray();
                Object parsed = new org.json.JSONTokener(readUtf8(resp)).nextValue();
                return parsed instanceof JSONArray ? (JSONArray) parsed : new JSONArray();
            }
        } catch (Exception e) { return new JSONArray(); }
    }

    private boolean hasSyncedResults(JSONArray arr) {
        if (arr == null) return false;
        try {
            for (int i = 0; i < arr.length(); i++)
                if (!arr.getJSONObject(i).optString("syncedLyrics", "").isEmpty())
                    return true;
        } catch (JSONException ignored) {}
        return false;
    }

    private JSONArray tryNetEase(String rawTitle, String channel, double ytDur) {
        try {
            String ct = cleanTitle(rawTitle);
            String ca = cleanArtist(channel);

            String stripped = stripBrackets(ct);
            List<String> queryList = new ArrayList<>();
            queryList.add(ct + " " + ca);
            queryList.add(ct);
            if (!stripped.equals(ct)) queryList.add(stripped);
            if (!ca.isEmpty()) queryList.add(ca + " " + ct);

            List<long[]>  ids    = new ArrayList<>();
            List<Double>  scores = new ArrayList<>();

            for (String q : queryList) {
                List<long[]>  tmpIds    = new ArrayList<>();
                List<Double>  tmpScores = new ArrayList<>();
                searchNetEase(q, ct, ca, ytDur, tmpIds, tmpScores);
                if (!tmpIds.isEmpty()) { ids = tmpIds; scores = tmpScores; break; }
            }
            if (ids.isEmpty()) return null;

            Integer[] idx = new Integer[ids.size()];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            final List<Double> fs = scores;
            Arrays.sort(idx, (a, b) -> Double.compare(fs.get(b), fs.get(a)));

            for (int i = 0; i < Math.min(5, idx.length); i++) {
                if (fs.get(idx[i]) < 20) break;
                JSONArray lines = fetchNetEaseLrc(ids.get(idx[i])[0]);
                if (lines != null && lines.length() > 0) return lines;
            }
            return null;
        } catch (Exception e) { return null; }
    }

    private void searchNetEase(String query, String ct, String ca,
            double ytDur, List<long[]> ids, List<Double> scores) {
        try {
            String url = "https://music.163.com/api/search/get?s="
                    + java.net.URLEncoder.encode(query, "UTF-8")
                    + "&type=1&limit=10";
            Request req = new Request.Builder().url(url)
                    .addHeader("Referer", "https://music.163.com")
                    .addHeader("Cookie",  "appver=8.0.0")
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.body() == null) return;
                JSONObject doc = new JSONObject(readUtf8(resp));
                if (!doc.has("result")) return;
                JSONArray songs = doc.getJSONObject("result").optJSONArray("songs");
                if (songs == null) return;
                for (int i = 0; i < songs.length(); i++) {
                    JSONObject song = songs.getJSONObject(i);
                    long   sid      = song.optLong("id");
                    String st       = song.optString("name", "");
                    double duration = song.optDouble("duration", 0) / 1000.0;
                    StringBuilder ab = new StringBuilder();
                    JSONArray art = song.optJSONArray("artists");
                    if (art != null)
                        for (int j = 0; j < art.length(); j++)
                            ab.append(art.getJSONObject(j).optString("name",""))
                              .append(" ");
                    double score = titleSim(ct, st) * 40
                                 + titleSim(ca, ab.toString().trim()) * 25;
                    if (ytDur > 0 && duration > 0) {
                        double diff = Math.abs(duration - ytDur);
                        if      (diff <= 3)  score += 30;
                        else if (diff <= 10) score += 18;
                        else if (diff <= 30) score += 8;
                        else                 score -= 15;
                    }
                    ids.add(new long[]{sid});
                    scores.add(score);
                }
            }
        } catch (Exception ignored) {}
    }

    private JSONArray fetchNetEaseLrc(long songId) {
        try {
            String url = "https://music.163.com/api/song/lyric?id="
                    + songId + "&lv=1&kv=1&tv=-1";
            Request req = new Request.Builder().url(url)
                    .addHeader("Referer", "https://music.163.com")
                    .addHeader("Cookie",  "appver=8.0.0")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.body() == null) return null;
                JSONObject doc = new JSONObject(readUtf8(resp));
                String lrc = null;
                if (doc.has("klyric"))
                    lrc = doc.getJSONObject("klyric").optString("lyric", null);
                if (lrc == null || lrc.isEmpty())
                    if (doc.has("lrc"))
                        lrc = doc.getJSONObject("lrc").optString("lyric", null);
                return (lrc != null && !lrc.isEmpty()) ? parseLrc(lrc) : null;
            }
        } catch (Exception e) { return null; }
    }

    private static final Pattern CREDIT_RX = Pattern.compile(
            "^\\s*(?:作词|作曲|编曲|混音|制作人|出品|录音|母带" +
            "|OP|SP|厂牌|发行|监制|制作|ISRC|专辑|歌手)\\s*[：:].{0,80}$");

    private static final Pattern TS_RX = Pattern.compile(
            "\\[(\\d+):(\\d{2})[.:](\\d{2,3})\\]");

    private JSONArray parseLrc(String lrc) throws JSONException {
        List<double[]> times = new ArrayList<>();
        List<String>   texts = new ArrayList<>();

        for (String line : lrc.split("\n")) {
            String trimmed = line.trim();
            String textPart = trimmed
                    .replaceAll("\\[\\d+:\\d{2}[.:]\\d{2,3}\\]", "").trim();
            if (textPart.isEmpty() || CREDIT_RX.matcher(textPart).matches())
                continue;

            Matcher scanner = TS_RX.matcher(trimmed);
            while (scanner.find()) {
                String msStr = scanner.group(3);
                double ms = msStr.length() == 2
                        ? Integer.parseInt(msStr) / 100.0
                        : Integer.parseInt(msStr) / 1000.0;
                double t = Integer.parseInt(scanner.group(1)) * 60.0
                         + Integer.parseInt(scanner.group(2)) + ms;
                times.add(new double[]{t});
                texts.add(textPart);
            }
        }

        Integer[] idx = new Integer[times.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) ->
                Double.compare(times.get(a)[0], times.get(b)[0]));

        JSONArray result = new JSONArray();
        for (int i = 0; i < idx.length; i++) {
            int    ci    = idx[i];
            double start = times.get(ci)[0];
            double end   = (i + 1 < idx.length)
                    ? times.get(idx[i + 1])[0] : start + 5.0;
            if (end - start < 0.1) end = start + 0.5;
            JSONObject obj = new JSONObject();
            obj.put("start", start);
            obj.put("end",   end);
            obj.put("text",  texts.get(ci));
            result.put(obj);
        }
        return result;
    }

    private double titleSim(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        a = a.toLowerCase(); b = b.toLowerCase();
        if (a.equals(b)) return 1.0;
        if (b.contains(a) || a.contains(b)) return 0.85;
        String[] wa = a.split("[^\\w]+"), wb = b.split("[^\\w]+");
        Set<String> sa = new HashSet<>(), sb2 = new HashSet<>();
        for (String w : wa) if (w.length() > 1) sa.add(w);
        for (String w : wb) if (w.length() > 1) sb2.add(w);
        if (sa.isEmpty() || sb2.isEmpty()) return 0;
        long inter = sa.stream().filter(sb2::contains).count();
        return (double) inter / Math.max(sa.size(), sb2.size());
    }

    private String stripBrackets(String t) {
        return t.replaceAll("\\([^)]*\\)", "")
                .replaceAll("\\[[^\\]]*\\]", "")
                .replaceAll("\\s{2,}", " ").trim();
    }

    private double getLrcLastTimestamp(String lrc) {
        double last = 0;
        for (String line : lrc.split("\n")) {
            Matcher m = TS_RX.matcher(line.trim());
            while (m.find()) {
                String msStr = m.group(3);
                double ms = msStr.length() == 2
                        ? Integer.parseInt(msStr) / 100.0
                        : Integer.parseInt(msStr) / 1000.0;
                double t = Integer.parseInt(m.group(1)) * 60.0
                         + Integer.parseInt(m.group(2)) + ms;
                if (t > last) last = t;
            }
        }
        return last;
    }

    private String cleanTitle(String t) {
        final String TAG_RX =
            "(?i)official\\s*(?:music\\s*)?(?:video|audio|mv|lyric(?:s)?|visualizer)?" +
            "|\\bm/?v\\b" +
            "|\\bmusic\\s*video\\b" +
            "|\\baudio(?:\\s*only)?\\b" +
            "|\\blyrics?(?:\\s*(?:video|ver(?:sion)?))?\\b" +
            "|\\bvisualizer\\b" +
            "|\\blive(?:\\s+(?:performance|version|session))?\\b" +
            "|\\bperformance(?:\\s+video)?\\b" +
            "|\\b(?:hd|4k|1080p|720p)\\b" +
            "|\\bremaster(?:ed)?(?:\\s+version)?\\b" +
            "|\\bre-?upload\\b" +
            "|\\beng(?:lish)?\\s*(?:ver\\.?|version|sub(?:title)?s?)?\\b" +
            "|\\bkor(?:ean)?\\s*(?:ver\\.?|version)?\\b" +
            "|\\bjp(?:n)?\\s*(?:ver\\.?|version)?\\b";

        t = t.replaceAll("\\(\\s*(?:" + TAG_RX + ")[^)]*\\)", "").trim();
        t = t.replaceAll("\\[\\s*(?:" + TAG_RX + ")[^\\]]*\\]", "").trim();
        t = t.replaceAll("(?i)\\s*[-|]\\s*(?:" + TAG_RX + ")\\s*$", "").trim();
        t = t.replaceAll("(?i)\\s+(?:feat\\.?|ft\\.?)\\s+.+$", "").trim();
        t = t.replaceAll("[\\u2013\\u2014]+", "-").trim();
        return t.replaceAll("\\s{2,}", " ").trim();
    }

    private String cleanArtist(String c) {
        c = c.replaceAll("(?i)\\s*[-–]\\s*Topic\\s*$", "").trim();
        c = c.replaceAll("(?i)VEVO$", "").trim();
        c = c.replaceAll(
                "(?i)\\s*(?:Records|Entertainment|Music|Official|Label|Studios?)\\s*$",
                "").trim();
        return c.replaceAll("\\s{2,}", " ").trim();
    }

    private String readUtf8(Response resp) throws IOException {
        byte[] bytes = resp.body().bytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void onBackPressed() {
        webView.evaluateJavascript(
                "window.__onAndroidBack && window.__onAndroidBack()", null);
    }
}
