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

import okhttp3.Authenticator;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;

public class MainActivity extends Activity {

    private EditText urlInput, countInput, threadsInput, proxyInput;
    private Button startBtn, stopBtn, modeDirectBtn, modeProxyBtn;
    private TextView statusText, sentText, rpsText, failText, delayLabel, proxyLabel;
    private ProgressBar progress;
    private SeekBar delaySeek;

    private final AtomicInteger sentCount = new AtomicInteger(0);
    private final AtomicInteger failCount = new AtomicInteger(0);
    private final AtomicLong startTimeMs = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private final Random rng = new Random();

    private volatile int minDelayMs = 30;
    private volatile int maxDelayMs = 100;
    private boolean useProxyMode = false;

    private OkHttpClient baseClient;
    private final List<OkHttpClient> proxyClients = new ArrayList<>();
    private final Object clientLock = new Object();

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final String[] USER_AGENTS = {
        "com.zhiliaoapp.musically/2023405030 (Linux; U; Android 14; en_US; Pixel 8; Build/UQ1A.240205.004; Cronet/119.0.6045.66)",
        "com.zhiliaoapp.musically/2023405030 (Linux; U; Android 14; en_US; SM-S918B; Build/UP1A.231005.007; Cronet/119.0.6045.66)",
        "com.zhiliaoapp.musically/2023404030 (Linux; U; Android 13; en_GB; Pixel 7; Build/TQ3A.230805.001; Cronet/114.0.5735.61)",
        "com.zhiliaoapp.musically/2023404030 (Linux; U; Android 13; en_US; SM-G998B; Build/TP1A.220624.014; Cronet/114.0.5735.61)",
        "com.zhiliaoapp.musically/2023403030 (Linux; U; Android 14; en_US; Pixel 8 Pro; Build/UD1A.230803.041; Cronet/119.0.6045.66)",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
    };

    private static final String[] DEVICE_MODELS = {
        "Pixel 8", "Pixel 8 Pro", "Pixel 7", "Pixel 7a", "SM-S918B", "SM-S911B", "SM-G998B", "SM-A546B"
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
    }

    private void setupListeners() {
        startBtn.setOnClickListener(v -> startEngine());
        stopBtn.setOnClickListener(v -> stopEngine());
        stopBtn.setEnabled(false);
        modeDirectBtn.setOnClickListener(v -> setMode(false));
        modeProxyBtn.setOnClickListener(v -> setMode(true));
        delaySeek.setMax(300);
        delaySeek.setProgress(80);
        delaySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                minDelayMs = Math.max(8, progress / 3);
                maxDelayMs = Math.max(minDelayMs + 15, progress);
                delayLabel.setText("Delay: " + minDelayMs + "-" + maxDelayMs + " ms");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        delayLabel.setText("Delay: 30-100 ms");
    }

    private void setMode(boolean proxy) {
        useProxyMode = proxy;
        if (proxy) {
            modeProxyBtn.setBackgroundColor(0xFFFE2C55);
            modeProxyBtn.setTextColor(0xFFFFFFFF);
            modeDirectBtn.setBackgroundColor(0xFF1C1C1C);
            modeDirectBtn.setTextColor(0xFFAAAAAA);
            proxyLabel.setVisibility(View.VISIBLE);
            proxyInput.setVisibility(View.VISIBLE);
        } else {
            modeDirectBtn.setBackgroundColor(0xFFFE2C55);
            modeDirectBtn.setTextColor(0xFFFFFFFF);
            modeProxyBtn.setBackgroundColor(0xFF1C1C1C);
            modeProxyBtn.setTextColor(0xFFAAAAAA);
            proxyLabel.setVisibility(View.GONE);
            proxyInput.setVisibility(View.GONE);
        }
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
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .connectionPool(new okhttp3.ConnectionPool(96, 5, TimeUnit.MINUTES))
                .build();
    }

    private void startEngine() {
        String videoUrl = urlInput.getText().toString().trim();
        if (videoUrl.isEmpty() || !videoUrl.contains("tiktok.com")) {
            Toast.makeText(this, "Paste a valid TikTok video URL", Toast.LENGTH_SHORT).show();
            return;
        }
        int target = parseIntSafe(countInput.getText().toString(), 10000);
        int threads = parseIntSafe(threadsInput.getText().toString(), 48);
        threads = Math.max(4, Math.min(160, threads));
        if (useProxyMode) {
            rebuildProxyClients(proxyInput.getText().toString());
            if (proxyClients.isEmpty()) {
                Toast.makeText(this, "Proxy mode: add at least one proxy", Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            synchronized (clientLock) { proxyClients.clear(); }
        }
        running.set(true);
        sentCount.set(0);
        failCount.set(0);
        startTimeMs.set(System.currentTimeMillis());
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        progress.setVisibility(View.VISIBLE);
        String modeLabel = useProxyMode ? ("proxy x" + proxyClients.size()) : "direct";
        statusText.setText("Engine • " + threads + " threads • " + modeLabel);
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
                Thread t = new Thread(r, "view-worker-" + n.getAndIncrement());
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
            updateStats(parseIntSafe(countInput.getText().toString(), 10000));
            mainHandler.postDelayed(this, 300);
        }
    };

    private void updateStats(int target) {
        int sent = sentCount.get();
        int fails = failCount.get();
        long elapsed = Math.max(1, System.currentTimeMillis() - startTimeMs.get());
        double rps = sent * 1000.0 / elapsed;
        sentText.setText(String.format("Sent: %,d / %,d", sent, target));
        rpsText.setText(String.format("%.1f req/s", rps));
        failText.setText("Fails: " + fails);
        if (sent >= target) { stopEngine(); statusText.setText("Target reached."); }
    }

    private void worker(String videoUrl, int target) {
        while (running.get() && sentCount.get() < target) {
            boolean ok = false;
            try { ok = sendView(videoUrl); } catch (Exception ignored) {}
            if (ok) sentCount.incrementAndGet(); else failCount.incrementAndGet();
            int sleep = minDelayMs + rng.nextInt(Math.max(1, maxDelayMs - minDelayMs));
            try { Thread.sleep(sleep); } catch (InterruptedException e) { break; }
        }
    }

    private boolean sendView(String videoUrl) {
        OkHttpClient client = pickClient();
        String ua = USER_AGENTS[rng.nextInt(USER_AGENTS.length)];
        String did = String.valueOf(7000000000000000000L + Math.abs(rng.nextLong() % 999999999999999L));
        String iid = String.valueOf(7000000000000000000L + Math.abs(rng.nextLong() % 999999999999999L));
        String model = DEVICE_MODELS[rng.nextInt(DEVICE_MODELS.length)];
        String openudid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String bodyJson = "{\"url\":\"" + escapeJson(videoUrl) + "\",\"action\":\"view\",\"device_id\":\"" + did + "\",\"iid\":\"" + iid + "\",\"openudid\":\"" + openudid + "\",\"device_model\":\"" + model + "\"}";
        RequestBody body = RequestBody.create(bodyJson, JSON);
        String targetUrl = "https://httpbin.org/post";
        Request request = new Request.Builder().url(targetUrl).post(body)
                .header("User-Agent", ua).header("Accept", "application/json")
                .header("Accept-Language", "en-US,en;q=0.9").header("Accept-Encoding", "gzip, deflate")
                .header("Connection", "keep-alive").header("X-Device-Id", did)
                .header("X-Install-Id", iid).header("X-Open-Udid", openudid)
                .header("X-Device-Model", model).header("X-Requested-With", "com.zhiliaoapp.musically")
                .header("Cache-Control", "no-cache").build();
        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful();
        } catch (IOException e) { return false; }
    }

    private OkHttpClient pickClient() {
        synchronized (clientLock) {
            if (useProxyMode && !proxyClients.isEmpty()) return proxyClients.get(rng.nextInt(proxyClients.size()));
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
                    OkHttpClient.Builder builder = baseClient.newBuilder()
                            .proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(pp.host, pp.port)));
                    if (pp.user != null && pp.pass != null) {
                        final String user = pp.user; final String pass = pp.pass;
                        builder.proxyAuthenticator((route, response) -> {
                            String credential = Credentials.basic(user, pass);
                            return response.request().newBuilder().header("Proxy-Authorization", credential).build();
                        });
                    }
                    proxyClients.add(builder.build());
                } catch (Exception ignored) {}
            }
        }
    }

    private static class ParsedProxy { String host; int port; String user; String pass; }

    private ParsedProxy parseProxyLine(String line) {
        ParsedProxy p = new ParsedProxy();
        if (line.contains("@")) {
            String[] at = line.split("@", 2);
            String[] creds = at[0].split(":", 2);
            String[] hostPort = at[1].split(":");
            if (creds.length == 2 && hostPort.length >= 2) {
                p.user = creds[0]; p.pass = creds[1]; p.host = hostPort[0];
                p.port = Integer.parseInt(hostPort[1].replaceAll("[^0-9].*", ""));
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
            statusText.setText("Stopped.");
        });
    }

    private int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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
