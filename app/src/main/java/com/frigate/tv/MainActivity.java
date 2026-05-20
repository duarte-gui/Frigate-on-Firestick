package com.frigate.tv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String WEBRTC_PATH = "/live/webrtc/webrtc.html?src=";
    private static final String CONFIG_PATH = "/api/config";

    // Lido em onCreate de R.string.frigate_host
    private String frigateHost;

    // Fallback se a chamada à API falhar
    private static final List<String> FALLBACK_CAMERAS =
        Arrays.asList("rua", "frente_praia", "frente_rua");

    private WebView webView;
    private TextView cameraLabel;
    private final Handler labelHandler = new Handler(Looper.getMainLooper());

    // Lista de câmeras (nome amigável) na ordem em que aparecem no Frigate
    private List<String> cameras = new ArrayList<>();
    // Mapping câmera → nome do stream go2rtc a usar no WebRTC
    private Map<String, String> cameraToStream = new LinkedHashMap<>();
    private int currentCamera = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);
        cameraLabel = findViewById(R.id.cameraLabel);
        frigateHost = getString(R.string.frigate_host);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                view.loadData(
                    "<html><body style='background:#111;color:#fff;font-family:sans-serif;" +
                    "display:flex;align-items:center;justify-content:center;height:100vh;margin:0;'>" +
                    "<div style='text-align:center'>" +
                    "<h1 style='font-size:48px'>📷</h1>" +
                    "<h2>Frigate não encontrado</h2>" +
                    "<p style='color:#666;font-size:12px'>MENU para recarregar</p>" +
                    "</div></body></html>",
                    "text/html", "UTF-8"
                );
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        showCameraLabel("Carregando câmeras…");
        fetchCamerasAsync();
    }

    private void fetchCamerasAsync() {
        Executors.newSingleThreadExecutor().execute(() -> {
            final Map<String, String> result = fetchCamerasBlocking();
            runOnUiThread(() -> {
                if (result.isEmpty()) {
                    cameraToStream = new LinkedHashMap<>();
                    for (String c : FALLBACK_CAMERAS) cameraToStream.put(c, c);
                    showCameraLabel("Usando lista padrão (API offline)");
                } else {
                    cameraToStream = result;
                }
                cameras = new ArrayList<>(cameraToStream.keySet());
                loadCamera(0);
            });
        });
    }

    // Retorna map preservando ordem: nome_da_câmera → stream_go2rtc
    private Map<String, String> fetchCamerasBlocking() {
        Map<String, String> out = new LinkedHashMap<>();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(frigateHost + CONFIG_PATH);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) return out;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
            JSONObject camsObj = root.optJSONObject("cameras");
            if (camsObj == null) return out;

            // Conjunto de nomes de streams go2rtc
            Set<String> streams = new HashSet<>();
            JSONObject g2 = root.optJSONObject("go2rtc");
            if (g2 != null) {
                JSONObject ss = g2.optJSONObject("streams");
                if (ss != null) {
                    for (Iterator<String> it = ss.keys(); it.hasNext(); ) {
                        streams.add(it.next());
                    }
                }
            }

            for (Iterator<String> it = camsObj.keys(); it.hasNext(); ) {
                String name = it.next();
                JSONObject cam = camsObj.optJSONObject(name);
                if (cam != null && !cam.optBoolean("enabled", true)) continue;
                String stream = resolveStream(name, streams);
                if (stream != null) out.put(name, stream);
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return out;
    }

    // Escolhe o melhor stream go2rtc para uma câmera (case-insensitive):
    //  1. nome exato (case-insensitive)
    //  2. nome + "_main"
    //  3. nome + "_sub"
    //  4. primeiro stream que comece com "nome_"
    private static String resolveStream(String camera, Set<String> streams) {
        String cam = camera.toLowerCase();
        String match = null, mainMatch = null, subMatch = null, prefMatch = null;
        for (String s : streams) {
            String low = s.toLowerCase();
            if (low.equals(cam))            { match = s; break; }
            if (low.equals(cam + "_main"))  mainMatch = s;
            else if (low.equals(cam + "_sub")) subMatch = s;
            else if (prefMatch == null && low.startsWith(cam + "_")) prefMatch = s;
        }
        if (match != null)    return match;
        if (mainMatch != null) return mainMatch;
        if (subMatch != null)  return subMatch;
        return prefMatch;
    }

    private void loadCamera(int idx) {
        if (cameras.isEmpty()) return;
        int n = cameras.size();
        currentCamera = ((idx % n) + n) % n;
        String name = cameras.get(currentCamera);
        String stream = cameraToStream.get(name);
        if (stream == null) stream = name;
        webView.loadUrl(frigateHost + WEBRTC_PATH + stream);
        showCameraLabel(prettyName(name) + "  (" + (currentCamera + 1) + "/" + n + ")");
    }

    private String prettyName(String raw) {
        String[] parts = raw.split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(parts[i].charAt(0)));
            if (parts[i].length() > 1) sb.append(parts[i].substring(1));
        }
        return sb.toString();
    }

    private void showCameraLabel(String text) {
        cameraLabel.setText(text);
        cameraLabel.clearAnimation();
        cameraLabel.setAlpha(1f);
        cameraLabel.setVisibility(View.VISIBLE);
        labelHandler.removeCallbacksAndMessages(null);
        labelHandler.postDelayed(() -> {
            AlphaAnimation fade = new AlphaAnimation(1f, 0f);
            fade.setDuration(400);
            fade.setFillAfter(true);
            cameraLabel.startAnimation(fade);
            labelHandler.postDelayed(
                () -> cameraLabel.setVisibility(View.INVISIBLE), 400);
        }, 2000);
    }

    // dispatchKeyEvent intercepta ANTES do WebView consumir os D-pad
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    loadCamera(currentCamera - 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    loadCamera(currentCamera + 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (!cameras.isEmpty()) {
                        showCameraLabel(prettyName(cameras.get(currentCamera))
                            + "  (" + (currentCamera + 1) + "/" + cameras.size() + ")");
                    }
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    fetchCamerasAsync();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    finish();
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onResume()  { super.onResume();  webView.onResume(); }
    @Override protected void onPause()   { super.onPause();   webView.onPause(); }
    @Override protected void onDestroy() {
        labelHandler.removeCallbacksAndMessages(null);
        webView.destroy();
        super.onDestroy();
    }
}
