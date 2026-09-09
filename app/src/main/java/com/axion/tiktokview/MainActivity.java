package com.axion.tiktokview;

import android.app.Activity;
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

/**
 * TikTok Mass View Engine v3.0
 * OkHttp connection pool + authenticated proxy support.
 * High throughput, keep-alive, randomized headers.
 */
public class MainActivity extends Activity {

    private EditText urlInput;
    private EditText countInput;
    private EditText threadsInput;
    private EditText proxyInput;
    private Button startBtn;
    private Button stopBtn;
    private TextView statusText;
    private TextView sentText;
    private TextView rpsText;
    private TextView failText;
    private ProgressBar progress;
    private SeekBar delaySeek;
    private TextView delayLabel;

    private final AtomicInteger sentCount = new AtomicInteger(0);
    private final AtomicInteger failCount = new AtomicInteger(0);
    private final AtomicLong startTimeMs = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private final Random rng = new Random();

    private volatile int minDelayMs = 40;
    private volatile int maxDelayMs = 180;

    private OkHttpClient baseClient;
    private final List<OkHttpClient> proxyClients = new ArrayList<>();
    private final Object clientLock = new Object();

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final String[] USER_AGENTS = {
        "com.zhiliaoapp.musically/2023208030 (Linux; U; Android 13; en_US; Pixel 7; Build/TQ3A.230805.001; Cronet/98.0.4758.74)",
        "com.zhiliaoapp.musically/2023208030 (Linux; U; Android 14; en_US; SM-S918B; Build/UP1A.231005.007; Cronet/98.0.4758.74)",
        "com.zhiliaoapp.musically/2023207040 (Linux; U; Android 13; en_GB; Pixel 6a; Build/TQ3A.230705.001; Cronet/98.0.4758.74)",
        "com.zhiliaoapp.musically/2023207040 (Linux; U; Android 12; en_US; SM-G998B; Build/SP1A.210812.016; Cronet/98.0.4758.74)",
        "com.zhiliaoapp.musically/2023206050 (Linux; U; Android 14; en_US; Pixel 8; Build/UD1A.230803.041; Cronet/98.0.4758.74)",
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
    };

    private static final String[] DEVICE_IDS = {
        "7123456789012345678", "7234567890123456789", "7345678901234567890",
        "7456789012345678901", "7567890123456789012", "7678901234567890123",
        "7789012345678901234", "7890123456789012345", "7901234567890123456"
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
    }

    private void bindViews() {
        urlInput = findViewById(R.id.url_input);
        countInput = findViewById(R.id.count_input);
        threadsInput = findViewById(R.id.threads_input);
        proxyInput = findViewById(R.id.proxy_input);
        startBtn = findViewById(R.id.start_btn);
        stopBtn = findViewById(R.id.stop_btn);
        statusText = findViewById(R.id.status_text);
        sentText = findViewById(R.id.sent_text);
        rpsText = findViewById(R.id.rps_text);
        failText = findViewById(R.id.fail_text);
        progress = findViewById(R.id.progress);
        delaySeek = findViewById(R.id.delay_seek);
        delayLabel = findViewById(R.id.delay_label);
    }

    private void setupListeners() {
        startBtn.setOnClickListener(v -> startEngine());
        stopBtn.setOnClickListener(v -> stopEngine());
        stopBtn.setEnabled(false);

        delaySeek.setMax(400);
        delaySeek.setProgress(120);
        delaySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                minDelayMs = Math.max(10, progress / 3);
                maxDelayMs = Math.max(minDelayMs + 20, progress);
                delayLabel.setText("Delay: " + minDelayMs + "-" + maxDelayMs + " ms");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        delayLabel.setText("Delay: 40-120 ms");
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TikTokView::Engine");
            wakeLock.acquire(60 * 60 * 1000L);
        } catch (Exception ignored) {}
    }

    private void buildBaseClient() {
        baseClient = new OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .writeTimeout(6, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .connectionPool(new okhttp3.ConnectionPool(64, 5, TimeUnit.MINUTES))
                .build();
    }

    private void startEngine() {
        String videoUrl = urlInput.getText().toString().trim();
        if (videoUrl.isEmpty() || !videoUrl.contains("tiktok.com")) {
            Toast.makeText(this, "Paste a valid TikTok video URL", Toast.LENGTH_SHORT).show();
            return;
        }

        int target = parseIntSafe(countInput.getText().toString(), 10000);
        int threads = parseIntSafe(threadsInput.getText().toString(), 32);
        threads = Math.max(4, Math.min(128, threads));

        rebuildProxyClients(proxyInput.getText().toString());

        running.set(true);
        sentCount.set(0);
        failCount.set(0);
        startTimeMs.set(System.currentTimeMillis());

        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        progress.setVisibility(View.VISIBLE);
        int proxyCount = proxyClients.size();
        statusText.setText("Engine • " + threads + " threads • " + proxyCount + " proxies");
        updateStats(target);

        final int poolSize = threads;
        executor = Executors.newFixedThreadPool(poolSize, new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "view-worker-" + n.getAndIncrement());
                t.setPriority(Thread.NORM_PRIORITY + 1);
                t.setDaemon(true);
                return t;
            }
        });

        final String finalUrl = videoUrl;
        final int finalTarget = target;

        for (int i = 0; i < poolSize; i++) {
            executor.submit(() -> worker(finalUrl, finalTarget));
        }

        mainHandler.post(statsTicker);
    }

    private final Runnable statsTicker = new Runnable() {
        @Override
        public void run() {
            if (!running.get()) return;
            int target = parseIntSafe(countInput.getText().toString(), 10000);
            updateStats(target);
            mainHandler.postDelayed(this, 350);
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

        if (sent >= target) {
            stopEngine();
            statusText.setText("Target reached.");
        }
    }

    private void worker(String videoUrl, int target) {
        while (running.get() && sentCount.get() < target) {
            boolean ok = false;
            try {
                ok = sendView(videoUrl);
            } catch (Exception ignored) {}

            if (ok) {
                sentCount.incrementAndGet();
            } else {
                failCount.incrementAndGet();
            }

            int sleep = minDelayMs + rng.nextInt(Math.max(1, maxDelayMs - minDelayMs));
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private boolean sendView(String videoUrl) {
        OkHttpClient client = pickClient();
        String ua = USER_AGENTS[rng.nextInt(USER_AGENTS.length)];
        String did = DEVICE_IDS[rng.nextInt(DEVICE_IDS.length)];

        String bodyJson = "{\"url\":\"" + escapeJson(videoUrl) +
                "\",\"action\":\"view\",\"device_id\":\"" + did + "\"}";

        RequestBody body = RequestBody.create(bodyJson, JSON);

        // --- SWAP THIS URL FOR LIVE ENDPOINT ---
        String targetUrl = "https://httpbin.org/post";
        // ---------------------------------------

        Request request = new Request.Builder()
                .url(targetUrl)
                .post(body)
                .header("User-Agent", ua)
                .header("Accept", "application/json")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Connection", "keep-alive")
                .header("X-Device-Id", did)
                .header("X-Requested-With", "com.zhiliaoapp.musically")
                .build();

        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful();
        } catch (IOException e) {
            return false;
        }
    }

    private OkHttpClient pickClient() {
        synchronized (clientLock) {
            if (!proxyClients.isEmpty()) {
                return proxyClients.get(rng.nextInt(proxyClients.size()));
            }
        }
        return baseClient;
    }

    private void rebuildProxyClients(String raw) {
        synchronized (clientLock) {
            proxyClients.clear();
            if (raw == null || raw.trim().isEmpty()) return;

            String[] lines = raw.trim().split("\\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                try {
                    ParsedProxy pp = parseProxyLine(line);
                    if (pp == null) continue;

                    OkHttpClient.Builder builder = baseClient.newBuilder()
                            .proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(pp.host, pp.port)));

                    if (pp.user != null && pp.pass != null) {
                        final String user = pp.user;
                        final String pass = pp.pass;
                        builder.proxyAuthenticator(new Authenticator() {
                            @Override
                            public Request authenticate(Route route, Response response) {
                                String credential = Credentials.basic(user, pass);
                                return response.request().newBuilder()
                                        .header("Proxy-Authorization", credential)
                                        .build();
                            }
                        });
                    }

                    proxyClients.add(builder.build());
                } catch (Exception ignored) {}
            }
        }
    }

    private static class ParsedProxy {
        String host;
        int port;
        String user;
        String pass;
    }

    private ParsedProxy parseProxyLine(String line) {
        ParsedProxy p = new ParsedProxy();

        if (line.contains("@")) {
            String[] at = line.split("@", 2);
            String[] creds = at[0].split(":", 2);
            String[] hostPort = at[1].split(":");
            if (creds.length == 2 && hostPort.length >= 2) {
                p.user = creds[0];
                p.pass = creds[1];
                p.host = hostPort[0];
                p.port = Integer.parseInt(hostPort[1].replaceAll("[^0-9].*", ""));
                return p;
            }
        }

        String[] parts = line.split(":");
        if (parts.length == 4) {
            p.user = parts[0];
            p.pass = parts[1];
            p.host = parts[2];
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
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
        }
        mainHandler.removeCallbacks(statsTicker);
        mainHandler.post(() -> {
            startBtn.setEnabled(true);
            stopBtn.setEnabled(false);
            progress.setVisibility(View.GONE);
            statusText.setText("Stopped.");
        });
    }

    private int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    protected void onDestroy() {
        stopEngine();
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        if (baseClient != null) {
            baseClient.dispatcher().executorService().shutdown();
            baseClient.connectionPool().evictAll();
        }
        super.onDestroy();
    }
}
