package com.axion.tiktokview;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Mr Unknown v4.3 — thin UI, multi-path view engine (Android APK only).
 * Direct path: TikTok page + embed + mobile-style GET rotation for higher register rate.
 */
public class MainActivity extends Activity {

    private EditText urlInput, countInput, threadsInput, proxyInput;
    private Button startBtn, stopBtn, modeDirectBtn, modeProxyBtn;
    private TextView statusText, sentText, rpsText, failText, delayLabel, proxyLabel, logConsole, statusDot;
    private ProgressBar progress;
    private SeekBar delaySeek;

    private final AtomicInteger sentCount = new AtomicInteger(0);
    private final AtomicInteger failCount = new AtomicInteger(0);
    private final AtomicInteger okCount = new AtomicInteger(0);
    private final AtomicLong startTimeMs = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private final Random rng = new Random();

    private volatile int minDelayMs = 80;
    private volatile int maxDelayMs = 200;
    private boolean useProxyMode = false;

    private OkHttpClient baseClient;
    private final List<OkHttpClient> proxyClients = new ArrayList<>();
    private final Object clientLock = new Object();
    private final StringBuilder logBuf = new StringBuilder();
    private int logLines = 0;

    private static final int FG = 0xFFDDDDDD;
    private static final int DIM = 0xFF888888;
    private static final int MUTED = 0xFF666666;
    private static final int PANEL = 0xFF1A1A1A;
    private static final int ACTIVE = 0xFFCCCCCC;
    private static final int ACTIVE_FG = 0xFF111111;

    private static final String[] WEB_UA = {
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.3 Mobile/15E148 Safari/604.1"
    };

    private static final String[] APP_UA = {
        "com.zhiliaoapp.musically/2023405030 (Linux; U; Android 14; en_US; Pixel 8; Build/UQ1A.240205.004; Cronet/119.0.6045.66)",
        "com.zhiliaoapp.musically/2023405030 (Linux; U; Android 14; en_US; SM-S918B; Build/UP1A.231005.007; Cronet/119.0.6045.66)",
        "com.zhiliaoapp.musically/2023404030 (Linux; U; Android 13; en_GB; Pixel 7; Build/TQ3A.230805.001; Cronet/114.0.5735.61)"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bindViews();
        setupListeners();
        acquireWakeLock();
        buildBaseClient();
        setMode(false);
        log("online v4.3");
    }

    private void bindViews() {
        urlInput = findViewById(R.id.url_input);
        countInput = findViewById(R.id.count_input);
        threadsInput = findViewById(R.id.threads_input);
        proxyInput = findViewById(R.id.proxy_input);
        startBtn = findViewById(R.id.start_btn);
        stopBtn = findViewById(R.id.stop_btn);
        modeDirectBtn = findViewById(R.id.mode_direct_btn);
        modeProxyBtn = findViewById(R.id.mode_proxy_btn);
        statusText = findViewById(R.id.status_text);
        sentText = findViewById(R.id.sent_text);
        rpsText = findViewById(R.id.rps_text);
        failText = findViewById(R.id.fail_text);
        progress = findViewById(R.id.progress);
        delaySeek = findViewById(R.id.delay_seek);
        delayLabel = findViewById(R.id.delay_label);
        proxyLabel = findViewById(R.id.proxy_label);
        logConsole = findViewById(R.id.log_console);
        statusDot = findViewById(R.id.status_dot);
    }

    private void setupListeners() {
        startBtn.setOnClickListener(v -> startEngine());
        stopBtn.setOnClickListener(v -> stopEngine());
        stopBtn.setEnabled(false);
        modeDirectBtn.setOnClickListener(v -> setMode(false));
        modeProxyBtn.setOnClickListener(v -> setMode(true));
        delaySeek.setMax(400);
        delaySeek.setProgress(140);
        delaySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                minDelayMs = Math.max(40, progress / 2);
                maxDelayMs = Math.max(minDelayMs + 40, progress);
                delayLabel.setText("delay  " + minDelayMs + "-" + maxDelayMs + " ms");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void setMode(boolean proxy) {
        useProxyMode = proxy;
        if (proxy) {
            modeProxyBtn.setBackgroundColor(ACTIVE);
            modeProxyBtn.setTextColor(ACTIVE_FG);
            modeDirectBtn.setBackgroundColor(PANEL);
            modeDirectBtn.setTextColor(DIM);
            proxyLabel.setVisibility(View.VISIBLE);
            proxyInput.setVisibility(View.VISIBLE);
            log("mode proxy");
        } else {
            modeDirectBtn.setBackgroundColor(ACTIVE);
            modeDirectBtn.setTextColor(ACTIVE_FG);
            modeProxyBtn.setBackgroundColor(PANEL);
            modeProxyBtn.setTextColor(DIM);
            proxyLabel.setVisibility(View.GONE);
            proxyInput.setVisibility(View.GONE);
            log("mode direct");
        }
    }

    private void log(String line) {
        mainHandler.post(() -> {
            if (logLines > 35) {
                int cut = logBuf.indexOf("\n");
                if (cut > 0) { logBuf.delete(0, cut + 1); logLines--; }
            }
            logBuf.append(line).append("\n");
            logLines++;
            if (logConsole != null) logConsole.setText(logBuf.toString());
        });
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MrUnknown::UI");
            wakeLock.acquire(60 * 60 * 1000L);
        } catch (Exception ignored) {}
    }

    private void buildBaseClient() {
        baseClient = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .connectionPool(new okhttp3.ConnectionPool(48, 5, TimeUnit.MINUTES))
                .build();
    }

    private void startEngine() {
        String videoUrl = urlInput.getText().toString().trim();
        if (videoUrl.isEmpty() || !(videoUrl.contains("tiktok.com") || videoUrl.contains("vm.tiktok.com"))) {
            Toast.makeText(this, "need tiktok url", Toast.LENGTH_SHORT).show();
            log("error: bad url");
            return;
        }
        int target = parseIntSafe(countInput.getText().toString(), 5000);
        int threads = parseIntSafe(threadsInput.getText().toString(), 16);
        if (!useProxyMode) threads = Math.max(4, Math.min(24, threads));
        else threads = Math.max(4, Math.min(128, threads));

        if (useProxyMode) {
            rebuildProxyClients(proxyInput.getText().toString());
            if (proxyClients.isEmpty()) {
                Toast.makeText(this, "no proxies", Toast.LENGTH_SHORT).show();
                log("error: empty proxy list");
                return;
            }
        } else {
            synchronized (clientLock) { proxyClients.clear(); }
        }

        running.set(true);
        sentCount.set(0);
        failCount.set(0);
        okCount.set(0);
        startTimeMs.set(System.currentTimeMillis());
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        progress.setVisibility(View.VISIBLE);
        if (statusDot != null) { statusDot.setText("live"); statusDot.setTextColor(FG); }

        String modeLabel = useProxyMode ? ("proxy " + proxyClients.size()) : "direct";
        statusText.setText("running  " + threads + "t  " + modeLabel);
        log("start " + threads + "t " + modeLabel);
        updateStats(target);

        Intent svc = new Intent(this, ViewEngineService.class);
        svc.setAction(ViewEngineService.ACTION_START);
        svc.putExtra(ViewEngineService.EXTRA_URL, videoUrl);
        svc.putExtra(ViewEngineService.EXTRA_TARGET, target);
        svc.putExtra(ViewEngineService.EXTRA_THREADS, threads);
        svc.putExtra(ViewEngineService.EXTRA_PROXIES, useProxyMode ? proxyInput.getText().toString() : "");
        svc.putExtra(ViewEngineService.EXTRA_MIN_DELAY, minDelayMs);
        svc.putExtra(ViewEngineService.EXTRA_MAX_DELAY, maxDelayMs);
        svc.putExtra(ViewEngineService.EXTRA_USE_PROXY, useProxyMode);
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);

        final int poolSize = threads;
        executor = Executors.newFixedThreadPool(poolSize, new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger(1);
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "w" + n.getAndIncrement());
                t.setPriority(Thread.NORM_PRIORITY + 1);
                t.setDaemon(true);
                return t;
            }
        });
        final String finalUrl = videoUrl;
        final int finalTarget = target;
        for (int i = 0; i < poolSize; i++) executor.submit(() -> worker(finalUrl, finalTarget));
        mainHandler.post(statsTicker);
    }

    private final Runnable statsTicker = new Runnable() {
        @Override public void run() {
            if (!running.get()) return;
            updateStats(parseIntSafe(countInput.getText().toString(), 5000));
            mainHandler.postDelayed(this, 400);
        }
    };

    private void updateStats(int target) {
        int sent = sentCount.get();
        int fails = failCount.get();
        int ok = okCount.get();
        long elapsed = Math.max(1, System.currentTimeMillis() - startTimeMs.get());
        double rps = sent * 1000.0 / elapsed;
        double pct = sent > 0 ? (ok * 100.0 / sent) : 0;
        sentText.setText(String.format("%,d / %,d", sent, target));
        rpsText.setText(String.format("%.1f rps", rps));
        failText.setText(String.format("ok %.0f%%", pct));
        if (sent > 0 && sent % 250 == 0) log("chk " + sent + "  " + String.format("%.0f", pct) + "%");
        if (sent >= target) {
            stopEngine();
            statusText.setText("done  " + String.format("%.0f", pct) + "%");
            log("done " + String.format("%.0f", pct) + "%");
        }
    }

    private void worker(String videoUrl, int target) {
        while (running.get() && sentCount.get() < target) {
            boolean ok = false;
            try { ok = sendView(videoUrl); } catch (Exception ignored) {}
            sentCount.incrementAndGet();
            if (ok) okCount.incrementAndGet();
            else failCount.incrementAndGet();
            int sleep = minDelayMs + rng.nextInt(Math.max(1, maxDelayMs - minDelayMs));
            if (!useProxyMode) sleep += rng.nextInt(60);
            try { Thread.sleep(sleep); } catch (InterruptedException e) { break; }
        }
    }

    private boolean sendView(String videoUrl) {
        OkHttpClient client = pickClient();
        // Multi-hit path for higher chance a play registers
        boolean a = hitPage(client, videoUrl, WEB_UA[rng.nextInt(WEB_UA.length)]);
        boolean b = hitEmbed(client, videoUrl);
        boolean c = hitMobileStyle(client, videoUrl);
        return a || b || c;
    }

    private boolean hitPage(OkHttpClient client, String url, String ua) {
        try {
            Request req = new Request.Builder().url(url).get()
                    .header("User-Agent", ua)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Accept-Encoding", "gzip, deflate")
                    .header("Connection", "keep-alive")
                    .header("Upgrade-Insecure-Requests", "1")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .header("Cache-Control", "max-age=0")
                    .build();
            try (Response res = client.newCall(req).execute()) {
                int c = res.code();
                return c >= 200 && c < 400;
            }
        } catch (IOException e) { return false; }
    }

    private boolean hitEmbed(OkHttpClient client, String videoUrl) {
        String id = extractVideoId(videoUrl);
        if (id == null) return false;
        String embed = "https://www.tiktok.com/embed/v2/" + id;
        try {
            Request req = new Request.Builder().url(embed).get()
                    .header("User-Agent", WEB_UA[rng.nextInt(WEB_UA.length)])
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", "https://www.tiktok.com/")
                    .header("Connection", "keep-alive")
                    .build();
            try (Response res = client.newCall(req).execute()) {
                int c = res.code();
                return c >= 200 && c < 400;
            }
        } catch (IOException e) { return false; }
    }

    private boolean hitMobileStyle(OkHttpClient client, String videoUrl) {
        String id = extractVideoId(videoUrl);
        if (id == null) return false;
        // Mobile web item path often used by in-app browsers
        String mobile = "https://m.tiktok.com/v/" + id + ".html";
        try {
            Request req = new Request.Builder().url(mobile).get()
                    .header("User-Agent", APP_UA[rng.nextInt(APP_UA.length)])
                    .header("Accept", "*/*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Connection", "keep-alive")
                    .header("X-Requested-With", "com.zhiliaoapp.musically")
                    .build();
            try (Response res = client.newCall(req).execute()) {
                int c = res.code();
                return c >= 200 && c < 500; // 403 still may touch CDN counters sometimes
            }
        } catch (IOException e) { return false; }
    }

    private String extractVideoId(String url) {
        // /video/1234567890 or vm short links resolved via final URL pattern
        Matcher m = Pattern.compile("/video/(\\d+)").matcher(url);
        if (m.find()) return m.group(1);
        m = Pattern.compile("[?&]item_id=(\\d+)").matcher(url);
        if (m.find()) return m.group(1);
        // bare digits at end
        m = Pattern.compile("/(\\d{15,})(?:\\?|$|/)").matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    private OkHttpClient pickClient() {
        synchronized (clientLock) {
            if (useProxyMode && !proxyClients.isEmpty())
                return proxyClients.get(rng.nextInt(proxyClients.size()));
        }
        return baseClient;
    }

    private void rebuildProxyClients(String raw) {
        synchronized (clientLock) {
            proxyClients.clear();
            if (raw == null || raw.trim().isEmpty()) return;
            for (String line : raw.trim().split("\\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                try {
                    ParsedProxy pp = parseProxyLine(line);
                    if (pp == null) continue;
                    OkHttpClient.Builder b = baseClient.newBuilder()
                            .proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(pp.host, pp.port)));
                    if (pp.user != null && pp.pass != null) {
                        final String u = pp.user, p = pp.pass;
                        b.proxyAuthenticator((route, response) -> response.request().newBuilder()
                                .header("Proxy-Authorization", Credentials.basic(u, p)).build());
                    }
                    proxyClients.add(b.build());
                } catch (Exception ignored) {}
            }
        }
        log("proxies " + proxyClients.size());
    }

    private static class ParsedProxy { String host; int port; String user; String pass; }

    private ParsedProxy parseProxyLine(String line) {
        ParsedProxy p = new ParsedProxy();
        if (line.contains("@")) {
            String[] at = line.split("@", 2);
            String[] creds = at[0].split(":", 2);
            String[] hp = at[1].split(":");
            if (creds.length == 2 && hp.length >= 2) {
                p.user = creds[0]; p.pass = creds[1]; p.host = hp[0];
                p.port = Integer.parseInt(hp[1].replaceAll("[^0-9].*", ""));
                return p;
            }
        }
        String[] parts = line.split(":");
        if (parts.length == 4) {
            p.user = parts[0]; p.pass = parts[1]; p.host = parts[2];
            p.port = Integer.parseInt(parts[3].replaceAll("[^0-9].*", ""));
            return p;
        }
        if (parts.length >= 2) {
            p.host = parts[0];
            p.port = Integer.parseInt(parts[1].replaceAll("[^0-9].*", ""));
            return p;
        }
        return null;
    }

    private void stopEngine() {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
            try { executor.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
        mainHandler.removeCallbacks(statsTicker);
        Intent stop = new Intent(this, ViewEngineService.class);
        stop.setAction(ViewEngineService.ACTION_STOP);
        try { startService(stop); } catch (Exception ignored) {}
        mainHandler.post(() -> {
            startBtn.setEnabled(true);
            stopBtn.setEnabled(false);
            progress.setVisibility(View.GONE);
            statusText.setText("stopped");
            if (statusDot != null) { statusDot.setText("idle"); statusDot.setTextColor(MUTED); }
            log("stop");
        });
    }

    private int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    @Override
    protected void onDestroy() {
        stopEngine();
        if (wakeLock != null && wakeLock.isHeld()) try { wakeLock.release(); } catch (Exception ignored) {}
        if (baseClient != null) {
            baseClient.dispatcher().executorService().shutdown();
            baseClient.connectionPool().evictAll();
        }
        super.onDestroy();
    }
}
