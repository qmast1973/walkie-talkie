package com.qmast.walkietalkie;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.KeyEvent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

/**
 * 백그라운드 유지 서비스.
 * - 상단 알림('무전기 대기 중')을 띄워 안드로이드가 앱을 멈추거나 종료하지 않게 함
 * - 화면이 꺼져도 CPU·와이파이가 잠들지 않게 잠금 유지
 * - 블루투스 이어폰의 재생/일시정지 버튼 = 송신 시작/끝(토글)
 */
public class WalkieService extends Service {
    static final String CHANNEL = "walkie";
    static final int NOTI_ID = 7;
    static final String ACTION_STOP = "com.qmast.walkietalkie.STOP";
    static final String EXTRA_TEXT = "text";

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private MediaSessionCompat session;
    private String text = "대기실";

    static void start(Context ctx, String text) {
        Intent i = new Intent(ctx, WalkieService.class);
        if (text != null) i.putExtra(EXTRA_TEXT, text);
        try {
            ContextCompat.startForegroundService(ctx, i);
        } catch (Exception ignored) {
            // 백그라운드에서 시작이 막히는 경우 등: 다음 화면 진입 때 다시 시도됨
        }
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, WalkieService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "walkie:standby");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "walkie:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
        setupMediaSession();
    }

    private void setupMediaSession() {
        try {
            session = new MediaSessionCompat(this, "walkie");
            session.setCallback(new MediaSessionCompat.Callback() {
                @Override
                public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
                    KeyEvent ev = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (ev != null && ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                        int c = ev.getKeyCode();
                        if (c == KeyEvent.KEYCODE_HEADSETHOOK || c == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                                || c == KeyEvent.KEYCODE_MEDIA_PLAY || c == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                            if (OfflineEngine.isActive()) {
                                OfflineEngine e = OfflineEngine.get(WalkieService.this);
                                e.setTalking(!e.isTalking());
                            } else if (WalkiePlugin.pttKeyEnabled && NativeSocket.exists()) {
                                NativeSocket.get(WalkieService.this).toggle(); // 화면을 닫아도 이어폰 버튼 송신
                            } else if (WalkiePlugin.pttKeyEnabled) WalkiePlugin.emitToggle();
                            return true;
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent);
                }
            });
            session.setPlaybackState(new PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE)
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f)
                    .build());
            session.setActive(true);
        } catch (Exception ignored) {
            session = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            try { NativeSocket.shutdown(); } catch (Exception ignored) {}
            try { OfflineEngine.get(this).stop(); } catch (Exception ignored) {}
            stopForeground(true);
            stopSelf();
            // 앱 전체 종료(화면 포함)
            android.os.Process.killProcess(android.os.Process.myPid());
            return START_NOT_STICKY;
        }
        if (intent != null && intent.hasExtra(EXTRA_TEXT)) text = intent.getStringExtra(EXTRA_TEXT);
        Notification n = buildNotification();
        int types = 0;
        if (Build.VERSION.SDK_INT >= 29) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            // 오프라인 무전 중에는 근처 기기 연결(블루투스·와이파이 다이렉트) 유지
            if (OfflineEngine.isActive() && Build.VERSION.SDK_INT >= 29) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        }
        try {
            ServiceCompat.startForeground(this, NOTI_ID, n, types);
        } catch (Exception e) {
            // 마이크 형태로 시작이 막히면 재생 형태로만 시작
            try {
                ServiceCompat.startForeground(this, NOTI_ID, n, Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : 0);
            } catch (Exception ignored) {
            }
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        int pf = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, pf | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stopPi = PendingIntent.getService(this, 1, new Intent(this, WalkieService.class).setAction(ACTION_STOP), pf);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_walkie)
                .setContentTitle("무전기 대기 중")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(openPi)
                .addAction(0, "완전 종료", stopPi)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "무전기 대기", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("무전기가 백그라운드에서 수신 대기 중임을 표시합니다.");
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (session != null) {
            session.setActive(false);
            session.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
