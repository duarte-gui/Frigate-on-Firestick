package com.frigate.tv;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Rational;
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

    private static final String TAG = "FrigateTV";

    private static final String WEBRTC_PATH = "/live/webrtc/webrtc.html?src=";
    private static final String CONFIG_PATH = "/api/config";

    // Lido em onCreate de R.string.frigate_host
    private String frigateHost;

    // Fallback se a chamada à API falhar
    private static final List<String> FALLBACK_CAMERAS =
        Arrays.asList("rua", "frente_praia", "frente_rua");

    // Extras de intent para abrir direto em PIP (usado p/ ex. por Home Assistant):
    //   am start -n com.frigate.tv/.MainActivity --ez pip true --es cam garagem_sub
    private static final String EXTRA_PIP = "pip";
    private static final String EXTRA_CAM = "cam";

    private WebView webView;
    private TextView cameraLabel;
    private final Handler labelHandler = new Handler(Looper.getMainLooper());
    private final Handler pipHandler = new Handler(Looper.getMainLooper());

    // Lista de câmeras (nome amigável) na ordem em que aparecem no Frigate
    private List<String> cameras = new ArrayList<>();
    // Mapping câmera → nome do stream go2rtc a usar no WebRTC
    private Map<String, String> cameraToStream = new LinkedHashMap<>();
    private int currentCamera = 0;

    // Câmera pedida via extra (carregada quando a lista terminar de baixar)
    private String pendingCam = null;
    // Se true, entra em PIP automaticamente no próximo onResume
    private boolean enterPipOnResume = false;

    // Watchdog do PIP: no Fire TV Lite (Android 9) não há setAutoEnterEnabled, e
    // com janela translúcida o sistema às vezes descarta o PIP segundos depois
    // (traz o app de conteúdo de volta). Em vez de tentar uma vez, vigiamos por
    // uma janela de tempo e RE-SUBIMOS o PIP sempre que ele cair — enquanto a
    // Activity ainda estiver em foreground/PIP (de background não dá pra entrar).
    private static final long PIP_DELAY_MS = 200;       // 1ª tentativa após o resume
    private static final long PIP_WATCH_MS = 1200;      // intervalo de vigilância
    private static final long PIP_WATCH_WINDOW_MS = 15000; // por quanto tempo vigiar
    private static final int MAX_REENTRIES = 6;         // trava anti-flicker
    private boolean pipDesired = false;     // queremos estar em PIP?
    private boolean activityStarted = false; // entre onStart e onStop
    private long pipWatchUntil = 0;
    private int pipReentries = 0;

    // O PipManager do Fire TV SÓ mantém a janelinha de PIP para apps com uma
    // MediaSession ativa. Sem ela, o sistema fecha o PIP em ~2s
    // (log: PipManager onPipActivityClosed / token=null). Mantemos uma sessão
    // "tocando" enquanto a câmera está na tela.
    private MediaSession mediaSession;

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

        // Fundo transparente durante o flash de tela cheia (deixa ver o app
        // atrás). Ao firmar em PIP, volta a preto p/ a janelinha não vazar.
        webView.setBackgroundColor(0x00000000);

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

        setupMediaSession();

        readIntentExtras(getIntent());

        showCameraLabel("Carregando câmeras…");
        fetchCamerasAsync();
        // PIP é entrado no onResume (estado garantido como resumed).
    }

    // singleTask: quando o app já está aberto e recebe novo intent (ex.: HA
    // pedindo outra câmera em PIP), cai aqui em vez de criar nova Activity.
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readIntentExtras(intent);
        if (pendingCam != null && !cameras.isEmpty()) {
            loadCameraByName(pendingCam);
            pendingCam = null;
        }
        // onResume virá em seguida e trata o PIP.
    }

    private void readIntentExtras(Intent intent) {
        if (intent == null) return;
        boolean pip = intent.getBooleanExtra(EXTRA_PIP, false);
        String cam = intent.getStringExtra(EXTRA_CAM);
        if (cam != null && !cam.trim().isEmpty()) pendingCam = cam.trim();
        if (pip) enterPipOnResume = true;
        Log.i(TAG, "readIntentExtras: pip=" + pip + " cam=" + cam
                + " supportsPip=" + supportsPip());
    }

    // MediaSession "tocando" — exigida pelo Fire TV para o PIP não fechar.
    private void setupMediaSession() {
        try {
            mediaSession = new MediaSession(this, "FrigateTV");
            PlaybackState state = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                          | PlaybackState.ACTION_STOP)
                .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
                .build();
            mediaSession.setPlaybackState(state);
            mediaSession.setCallback(new MediaSession.Callback() {});
            mediaSession.setActive(true);
            Log.i(TAG, "MediaSession ativa");
        } catch (Throwable t) {
            Log.e(TAG, "setupMediaSession FAILED", t);
        }
    }

    private boolean supportsPip() {
        // Alguns Android TV (ex.: Xiaomi TV Stick / twilight) suportam PIP mas
        // NÃO declaram FEATURE_PICTURE_IN_PICTURE no pm. Em vez de confiar só na
        // feature, exigimos SDK>=O e deixamos o enterPictureInPictureMode tentar;
        // se o sistema recusar, ele lança/retorna false e tratamos lá.
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    private boolean isInPip() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode();
    }

    // Inicia o ciclo: pede PIP e arma o watchdog por PIP_WATCH_WINDOW_MS.
    private void startPipWatch() {
        pipDesired = true;
        pipReentries = 0;
        pipWatchUntil = android.os.SystemClock.uptimeMillis() + PIP_WATCH_WINDOW_MS;
        enterPipMode();
        pipHandler.removeCallbacks(pipWatchRunnable);
        pipHandler.postDelayed(pipWatchRunnable, PIP_WATCH_MS);
    }

    private void enterPipMode() {
        boolean inPip = isInPip();
        Log.i(TAG, "enterPipMode supportsPip=" + supportsPip() + " inPip=" + inPip
                + " started=" + activityStarted + " reentries=" + pipReentries);
        if (!supportsPip() || inPip || !activityStarted) return;
        try {
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(16, 9))
                .build();
            boolean ok = enterPictureInPictureMode(params);
            Log.i(TAG, "enterPictureInPictureMode returned " + ok);
        } catch (Throwable t) {
            Log.e(TAG, "enterPictureInPictureMode FAILED", t);
        }
    }

    private final Runnable pipWatchRunnable = new Runnable() {
        @Override public void run() {
            if (!pipDesired) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (now > pipWatchUntil) {
                Log.i(TAG, "watchdog: janela encerrada (inPip=" + isInPip() + ")");
                return;
            }
            if (!isInPip()) {
                if (!activityStarted) {
                    // Backgrounded: não dá pra re-entrar daqui; só continua vigiando.
                    Log.i(TAG, "watchdog: PIP caiu mas Activity em background; aguardando");
                } else if (pipReentries < MAX_REENTRIES) {
                    pipReentries++;
                    Log.i(TAG, "watchdog: PIP caiu; re-subindo (" + pipReentries + "/"
                            + MAX_REENTRIES + ")");
                    enterPipMode();
                } else {
                    Log.w(TAG, "watchdog: limite de re-subidas atingido; parando");
                    pipDesired = false;
                    return;
                }
            }
            pipHandler.postDelayed(this, PIP_WATCH_MS);
        }
    };

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPip, newConfig);
        if (isInPip) {
            // Na janelinha: fundo preto (sem vazar transparência) e sem rótulo.
            webView.setBackgroundColor(0xFF000000);
            labelHandler.removeCallbacksAndMessages(null);
            cameraLabel.setVisibility(View.INVISIBLE);
        } else {
            // Voltou a fullscreen: transparente de novo p/ o próximo flash.
            webView.setBackgroundColor(0x00000000);
        }
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
                if (pendingCam != null) {
                    loadCameraByName(pendingCam);
                    pendingCam = null;
                } else {
                    loadCamera(0);
                }
            });
        });
    }

    // Carrega o que foi pedido via extra "cam". Aceita:
    //  1. nome de câmera (ex.: "Garagem") -> usa o stream mapeado da câmera
    //  2. nome de stream go2rtc (ex.: "garagem_sub", "porta_frente_2") -> casa
    //     pelo stream mapeado; se não bater nenhum, carrega o stream DIRETO
    //     (o HA passa nomes de stream, e a câmera "Garagem" mapeia p/ _main,
    //      então pedir "garagem_sub" não casa pelo mapeamento e cairia no
    //      fallback errado — por isso o carregamento direto).
    private void loadCameraByName(String wanted) {
        if (cameras.isEmpty()) return;
        String w = wanted.toLowerCase();
        for (int i = 0; i < cameras.size(); i++) {
            String name = cameras.get(i);
            String stream = cameraToStream.get(name);
            if (name.toLowerCase().equals(w)
                    || (stream != null && stream.toLowerCase().equals(w))) {
                loadCamera(i);
                return;
            }
        }
        // Não casou nenhuma câmera/stream mapeado: trata "wanted" como nome de
        // stream go2rtc e carrega direto. Alinha currentCamera à câmera de mesmo
        // prefixo (p/ navegação ←/→ continuar coerente), se houver.
        loadStreamDirect(wanted);
    }

    private void loadStreamDirect(String stream) {
        String w = stream.toLowerCase();
        for (int i = 0; i < cameras.size(); i++) {
            String cam = cameras.get(i).toLowerCase();
            if (w.equals(cam) || w.startsWith(cam + "_")) { currentCamera = i; break; }
        }
        webView.loadUrl(frigateHost + WEBRTC_PATH + stream);
        showCameraLabel(prettyName(stream));
        Log.i(TAG, "loadStreamDirect: " + stream);
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

    @Override protected void onStart() { super.onStart(); activityStarted = true; }
    @Override protected void onStop()  { super.onStop();  activityStarted = false; }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        if (enterPipOnResume) {
            enterPipOnResume = false;
            // delay curto p/ minimizar o flash; o watchdog re-sobe se cair
            pipHandler.postDelayed(this::startPipWatch, PIP_DELAY_MS);
        }
    }
    @Override protected void onPause() {
        super.onPause();
        // Em PIP a Activity fica "paused" mas visível: NÃO pausar o WebView,
        // senão o vídeo da câmera congela na janelinha.
        if (!isInPip()) webView.onPause();
    }
    @Override protected void onDestroy() {
        labelHandler.removeCallbacksAndMessages(null);
        pipHandler.removeCallbacksAndMessages(null);
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        webView.destroy();
        super.onDestroy();
    }
}
