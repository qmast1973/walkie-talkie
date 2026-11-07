package com.qmast.walkietalkie;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import com.getcapacitor.Bridge;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 웹 화면 ↔ 안드로이드 연결.
 * 웹에서: Capacitor.nativePromise('Walkie', 'setStatus', {inRoom, text})
 * 안드로이드 → 웹: window 이벤트 'walkieptt' {down:true|false}, 'walkiepttToggle'
 */
@CapacitorPlugin(name = "Walkie")
public class WalkiePlugin extends Plugin {
    static volatile boolean pttKeyEnabled = false;
    private static volatile Bridge sBridge;

    @Override
    public void load() {
        sBridge = getBridge();
        // 페이지를 불러오기 전에 window.WalkieNative 를 붙여 둠
        getBridge().getWebView().addJavascriptInterface(new WalkieJs(getActivity()), "WalkieNative");
    }

    /** 웹 화면에 이벤트 전달(Capacitor 스크립트 없이도 동작) */
    private static void dispatch(String js) {
        Bridge b = sBridge;
        if (b == null) return;
        b.getActivity().runOnUiThread(() -> b.getWebView().evaluateJavascript(js, null));
    }

    static void emitPtt(boolean down) {
        dispatch("(function(){var e=new Event('walkieptt');e.down=" + down + ";window.dispatchEvent(e);})()");
    }

    static void emitToggle() {
        dispatch("window.dispatchEvent(new Event('walkiepttToggle'))");
    }

    /** 방 입장 여부와 알림에 보일 문구 갱신 */
    @PluginMethod
    public void setStatus(PluginCall call) {
        pttKeyEnabled = call.getBoolean("inRoom", false);
        String text = call.getString("text", "대기실");
        WalkieService.start(getContext(), text);
        call.resolve();
    }

    @PluginMethod
    public void info(PluginCall call) {
        JSObject r = new JSObject();
        r.put("batteryExempt", isBatteryExempt(getContext()));
        String ver = "";
        try {
            ver = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        r.put("version", ver);
        call.resolve(r);
    }

    @PluginMethod
    public void openBatterySettings(PluginCall call) {
        openBatterySettings(getActivity());
        call.resolve();
    }

    /** 앱 완전 종료(알림의 '종료'와 같음) */
    @PluginMethod
    public void quit(PluginCall call) {
        call.resolve();
        WalkieService.stop(getContext());
        getActivity().finishAndRemoveTask();
    }

    static boolean isBatteryExempt(Context ctx) {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
    }

    static void openBatterySettings(Activity act) {
        if (Build.VERSION.SDK_INT < 23) return;
        // 스토어 정책상 예외를 직접 요청하지 않고, 사용자가 고를 수 있는 설정 화면을 엶
        try {
            act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + act.getPackageName())));
        } catch (Exception e) {
            try {
                act.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }
}
