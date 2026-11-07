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
    }

    static void emitPtt(boolean down) {
        Bridge b = sBridge;
        if (b == null) return;
        b.getActivity().runOnUiThread(() -> b.triggerWindowJSEvent("walkieptt", "{\"down\":" + down + "}"));
    }

    static void emitToggle() {
        Bridge b = sBridge;
        if (b == null) return;
        b.getActivity().runOnUiThread(() -> b.triggerWindowJSEvent("walkiepttToggle"));
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
        if (Build.VERSION.SDK_INT < 23 || isBatteryExempt(act)) return;
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + act.getPackageName()));
            act.startActivity(i);
        } catch (Exception e) {
            try {
                act.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }
}
