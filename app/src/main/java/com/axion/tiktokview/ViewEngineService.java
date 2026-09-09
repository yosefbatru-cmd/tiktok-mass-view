package com.axion.tiktokview;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

/**
 * Foreground service so the engine keeps running when you leave the app / lock screen.
 * Shows persistent notification "Mr Unknown".
 */
public class ViewEngineService extends Service {

    public static final String CHANNEL_ID = "mr_unknown_engine";
    public static final int NOTIF_ID = 1001;

    public static final String ACTION_START = "com.axion.tiktokview.START";
    public static final String ACTION_STOP = "com.axion.tiktokview.STOP";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TARGET = "target";
    public static final String EXTRA_THREADS = "threads";
    public static final String EXTRA_PROXIES = "proxies";
    public static final String EXTRA_MIN_DELAY = "min_delay";
    public static final String EXTRA_MAX_DELAY = "max_delay";
    public static final String EXTRA_USE_PROXY = "use_proxy";

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        acquireWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        String url = intent.getStringExtra(EXTRA_URL);
        int target = intent.getIntExtra(EXTRA_TARGET, 10000);
        int threads = intent.getIntExtra(EXTRA_THREADS, 32);
        String proxies = intent.getStringExtra(EXTRA_PROXIES);
        int minDelay = intent.getIntExtra(EXTRA_MIN_DELAY, 40);
        int maxDelay = intent.getIntExtra(EXTRA_MAX_DELAY, 180);
        boolean useProxy = intent.getBooleanExtra(EXTRA_USE_PROXY, false);

        startForeground(NOTIF_ID, buildNotification("Running • " + threads + " threads"));

        // Engine is driven from MainActivity workers; service keeps process alive.
        // MainActivity binds stats via shared statics / broadcast if needed later.
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Mr Unknown Engine",
                    NotificationManager.IMPORTANCE_LOW
            );
            ch.setDescription("Keeps the view engine alive in background");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stop = new Intent(this, ViewEngineService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Mr Unknown")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .addAction(0, "Stop", stopPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MrUnknown::Engine");
            wakeLock.acquire(4 * 60 * 60 * 1000L); // up to 4 hours
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
