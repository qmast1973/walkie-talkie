package com.qmast.walkietalkie;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 소리 출력 경로: 이어폰(유선·블루투스)이 있으면 이어폰, 없으면 바깥 스피커.
 * 마이크를 켜면 안드로이드가 '통화 모드'로 바꿔 수화기(귀에 대는 스피커)로 내보내는데, 이를 바깥 스피커로 돌림.
 */
public class SpeakerRoute {
    private static SpeakerRoute inst;
    private final AudioManager am;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static synchronized SpeakerRoute get(Context ctx) {
        if (inst == null) inst = new SpeakerRoute(ctx.getApplicationContext());
        return inst;
    }

    private SpeakerRoute(Context ctx) {
        am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        // 이어폰을 꽂거나 빼면 다시 판단
        am.registerAudioDeviceCallback(new AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { apply(); }
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { apply(); }
        }, main);
        // 통화 모드로 바뀔 때마다 다시 적용(웹 화면이 마이크를 켤 때)
        if (Build.VERSION.SDK_INT >= 31) {
            Executor ex = main::post;
            am.addOnModeChangedListener(ex, mode -> apply());
        }
        apply();
    }

    private boolean headsetConnected() {
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || t == AudioDeviceInfo.TYPE_USB_HEADSET || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return true;
            if (Build.VERSION.SDK_INT >= 31 && (t == AudioDeviceInfo.TYPE_BLE_HEADSET || t == AudioDeviceInfo.TYPE_BLE_SPEAKER)) return true;
        }
        return false;
    }

    /** 지금 상태에 맞게 출력 경로 적용 */
    public void apply() {
        if (am == null) return;
        try {
            boolean headset = headsetConnected();
            if (Build.VERSION.SDK_INT >= 31) {
                if (headset) {
                    am.clearCommunicationDevice(); // 이어폰은 시스템이 알아서 선택
                } else {
                    List<AudioDeviceInfo> devs = am.getAvailableCommunicationDevices();
                    for (AudioDeviceInfo d : devs) {
                        if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { am.setCommunicationDevice(d); break; }
                    }
                }
            } else {
                am.setSpeakerphoneOn(!headset);
            }
        } catch (Exception ignored) {
        }
    }
}
