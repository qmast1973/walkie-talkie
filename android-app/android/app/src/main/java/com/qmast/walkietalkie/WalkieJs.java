package com.qmast.walkietalkie;

import android.app.Activity;
import android.webkit.JavascriptInterface;

/**
 * 웹 화면에서 바로 부르는 안드로이드 기능(window.WalkieNative).
 * Capacitor 연결 스크립트가 붙지 않는 경우에도 동작하도록 안드로이드 기본 방식으로 연결.
 */
public class WalkieJs {
    private final Activity act;

    WalkieJs(Activity act) {
        this.act = act;
    }

    /** 방 입장 여부(볼륨 버튼 송신 켜기/끄기)와 알림 문구 */
    @JavascriptInterface
    public void setStatus(boolean inRoom, String text) {
        WalkiePlugin.pttKeyEnabled = inRoom;
        WalkieService.start(act.getApplicationContext(), text == null ? "대기실" : text);
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
}
