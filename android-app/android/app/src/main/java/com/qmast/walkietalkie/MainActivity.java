package com.qmast.walkietalkie;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.WebView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 서버 화면(WebView)을 띄우는 메인 화면.
 * - 시작하면 백그라운드 유지 서비스(WalkieService)를 켜서 화면이 꺼져도 수신이 끊기지 않게 함
 * - 방에 들어가 있는 동안 볼륨 아래(-) 버튼을 누르고 있으면 송신(PTT)
 */
public class MainActivity extends BridgeActivity {
    private static final int REQ_PERMS = 1001;
    private static final int REQ_OFFLINE = 1002;
    private boolean volDown = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(WalkiePlugin.class);
        super.onCreate(savedInstanceState);

        WebView wv = getBridge().getWebView();
        // 백그라운드에서도 수신음이 재생되도록 '사용자 터치 후 재생' 제한 해제
        wv.getSettings().setMediaPlaybackRequiresUserGesture(false);

        // window.WalkieNative: 온라인·오프라인 화면 공통 연결(페이지가 바뀌어도 유지)
        wv.addJavascriptInterface(new WalkieJs(this), "WalkieNative");
        // 오프라인 무전 상태를 화면(offline.html)에 전달
        OfflineEngine.get(this).setListener(json -> runOnUiThread(() ->
                getBridge().getWebView().evaluateJavascript("window.offState&&window.offState(" + json + ")", null)));

        requestNeededPermissions();
        WalkieService.start(this, null);
        // 이어폰이 없으면 바깥 스피커로(통화 수화기로 나오지 않게)
        SpeakerRoute.get(this);
    }

    /** 오프라인 무전에 필요한 권한(근처 기기 찾기·블루투스·와이파이 다이렉트·위치) */
    String[] offlinePerms() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        p.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        p.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        return p.toArray(new String[0]);
    }

    boolean offlinePermsGranted() {
        for (String perm : offlinePerms()) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    void requestOfflinePerms() {
        ActivityCompat.requestPermissions(this, offlinePerms(), REQ_OFFLINE);
    }

    private void requestNeededPermissions() {
        List<String> need = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) {
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_OFFLINE) {
            boolean ok = offlinePermsGranted();
            getBridge().getWebView().evaluateJavascript("window.offPerms&&window.offPerms(" + ok + ")", null);
            WalkieService.start(this, null);
        }
        if (requestCode == REQ_PERMS) {
            // 마이크 권한을 받았으면 서비스를 '마이크 사용' 형태로 다시 시작(화면 꺼진 상태 송신 대비)
            WalkieService.start(this, null);
        }
    }

    @Override
    public void onDestroy() {
        // 화면만 닫힘: 무전 연결은 계속 유지(완전 종료는 quitApp)
        try { if (NativeSocket.exists()) NativeSocket.get(this).detach(getBridge().getWebView()); } catch (Exception ignored) {}
        super.onDestroy();
    }

    /** 완전 종료: 무전 연결·오프라인·서비스를 모두 끄고 앱 종료 */
    void quitApp() {
        try { NativeSocket.shutdown(); } catch (Exception ignored) {}
        try { OfflineEngine.get(this).stop(); } catch (Exception ignored) {}
        WalkieService.stop(this);
        finishAndRemoveTask();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    public void onPause() {
        super.onPause();
        keepWebViewRunning();
    }

    @Override
    public void onStop() {
        super.onStop();
        keepWebViewRunning();
    }

    /** 화면이 가려져도 WebView의 스크립트·타이머가 멈추지 않게 함(무전 수신 유지) */
    private void keepWebViewRunning() {
        try {
            WebView wv = getBridge().getWebView();
            wv.onResume();
            wv.resumeTimers();
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (WalkiePlugin.pttKeyEnabled && code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            boolean off = OfflineEngine.isActive();
            if (event.getAction() == KeyEvent.ACTION_DOWN && !volDown) {
                volDown = true;
                if (off) OfflineEngine.get(this).setTalking(true); else WalkiePlugin.emitPtt(true);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                volDown = false;
                if (off) OfflineEngine.get(this).setTalking(false); else WalkiePlugin.emitPtt(false);
            }
            return true; // 볼륨이 바뀌지 않게 소비
        }
        return super.dispatchKeyEvent(event);
    }
}
