package com.qmast.walkietalkie;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.webkit.JavascriptInterface;

/**
 * 웹 화면에서 바로 부르는 안드로이드 기능(window.WalkieNative).
 * 온라인 화면(서버)과 오프라인 화면(앱 안의 offline.html) 모두에서 사용.
 */
public class WalkieJs {
    static final String ONLINE_URL = "https://walkie-talkie-1zpg.onrender.com";
    static final String OFFLINE_URL = "https://localhost/offline.html";

    private final MainActivity act;

    WalkieJs(MainActivity act) {
        this.act = act;
    }

    // ---------- 공통 ----------
    /** 방 입장 여부(볼륨 버튼 송신 켜기/끄기)와 알림 문구 */
    @JavascriptInterface
    public void setStatus(boolean inRoom, String text) {
        WalkiePlugin.pttKeyEnabled = inRoom;
        WalkieService.start(act.getApplicationContext(), text == null ? "대기실" : text);
        // 송신 시작·끝마다 출력 경로 다시 확인(마이크를 켜면 수화기로 바뀌는 기기 대비)
        act.runOnUiThread(() -> SpeakerRoute.get(act).apply());
    }

    @JavascriptInterface
    public boolean isBatteryExempt() {
        return WalkiePlugin.isBatteryExempt(act);
    }

    @JavascriptInterface
    public void openBatterySettings() {
        act.runOnUiThread(() -> WalkiePlugin.openBatterySettings(act));
    }

    @JavascriptInterface
    public String version() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- 화면 전환 ----------
    @JavascriptInterface
    public void openOffline() {
        act.runOnUiThread(() -> act.getBridge().getWebView().loadUrl(OFFLINE_URL));
    }

    @JavascriptInterface
    public void openOnline() {
        OfflineEngine.get(act).stop();
        WalkiePlugin.pttKeyEnabled = false;
        act.runOnUiThread(() -> act.getBridge().getWebView().loadUrl(ONLINE_URL));
    }

    // ---------- 오프라인 무전 ----------
    @JavascriptInterface
    public boolean offPermsOk() {
        return act.offlinePermsGranted();
    }

    @JavascriptInterface
    public void offRequestPerms() {
        act.runOnUiThread(act::requestOfflinePerms);
    }

    @JavascriptInterface
    public boolean btEnabled() {
        try {
            BluetoothManager bm = (BluetoothManager) act.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter a = bm == null ? null : bm.getAdapter();
            return a != null && a.isEnabled();
        } catch (Exception e) {
            return true;
        }
    }

    /** 위치(GPS) 켜짐 여부: 근처 기기 찾기에 필요 */
    @JavascriptInterface
    public boolean locationEnabled() {
        try {
            android.location.LocationManager lm = (android.location.LocationManager) act.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return true;
            if (android.os.Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
            return lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return true;
        }
    }

    @JavascriptInterface
    public void offRescan() {
        OfflineEngine.get(act).restartNearby();
    }

    @JavascriptInterface
    public void offStart(String nick, String group, int channel) {
        OfflineEngine.get(act).start(nick, group, channel);
        WalkiePlugin.pttKeyEnabled = true;
        WalkieService.start(act.getApplicationContext(), "오프라인 · " + group + " · CH " + channel);
    }

    @JavascriptInterface
    public void offSet(String nick, String group, int channel) {
        OfflineEngine.get(act).set(nick, group, channel);
        WalkieService.start(act.getApplicationContext(), "오프라인 · " + group + " · CH " + channel);
    }

    @JavascriptInterface
    public void offStop() {
        OfflineEngine.get(act).stop();
        WalkiePlugin.pttKeyEnabled = false;
        WalkieService.start(act.getApplicationContext(), "대기실");
    }

    /** 오프라인 수신 소리 증폭(0~2400 mB) */
    @JavascriptInterface
    public void offBoost(int mb) {
        OfflineEngine.get(act).setBoost(mb);
    }

    @JavascriptInterface
    public boolean offPtt(boolean on) {
        return OfflineEngine.get(act).setTalking(on);
    }
}
