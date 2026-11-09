package com.qmast.walkietalkie;

import android.content.Context;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.net.URI;

import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;

/**
 * 온라인 무전의 서버 연결을 앱 화면 밖(백그라운드 서비스와 같은 프로세스)에서 유지.
 * - 화면(웹)은 window.__wtSock 으로 이 연결을 빌려 씀: 화면을 닫아도 연결·수신·이어폰 송신이 계속됨
 * - 소리(송수신)는 NativeAudio가 처리, 화면에는 소리를 넘기지 않음
 * - 화면이 없을 때 다시 연결되면 마지막 hello(닉네임·재접속 키)로 스스로 원래 방에 복귀
 */
public class NativeSocket {
    private static NativeSocket inst;

    public static synchronized NativeSocket get(Context ctx) {
        if (inst == null) inst = new NativeSocket(ctx.getApplicationContext());
        return inst;
    }

    public static synchronized boolean exists() { return inst != null; }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Socket sock;
    private volatile WebView web;
    private JSONObject lastHello;
    private final NativeAudio audio;
    private volatile boolean talking = false, startPending = false;

    private NativeSocket(Context ctx) {
        this.ctx = ctx;
        this.audio = new NativeAudio(data -> { Socket s = sock; if (s != null && s.connected()) s.emit("audio", (Object) data); });
    }

    private synchronized void ensure() {
        if (sock != null) return;
        IO.Options o = IO.Options.builder()
                .setTransports(new String[]{"websocket"})
                .setReconnection(true)
                .setReconnectionDelay(1000)
                .setReconnectionDelayMax(5000)
                .build();
        sock = IO.socket(URI.create(WalkieJs.ONLINE_URL), o);
        sock.on(Socket.EVENT_CONNECT, a -> {
            if (web != null) toJs("connect", null);
            else if (lastHello != null) autoHello();
        });
        sock.on(Socket.EVENT_DISCONNECT, a -> {
            if (talking) { talking = false; audio.stopCapture(null); }
            toJs("disconnect", null);
        });
        sock.onAnyIncoming(args -> {
            if (args.length == 0 || !(args[0] instanceof String)) return;
            String ev = (String) args[0];
            Object d = args.length > 1 ? args[1] : null;
            handle(ev, d);
        });
        sock.connect();
    }

    private void handle(String ev, Object d) {
        switch (ev) {
            case "audio":
                if (d instanceof JSONObject) {
                    JSONObject m = (JSONObject) d;
                    Object data = m.opt("data");
                    if (data instanceof byte[]) audio.onAudio(m.optString("from"), (byte[]) data);
                }
                return; // 소리는 화면에 넘기지 않음
            case "talk:start":
                if (d instanceof JSONObject) audio.resetDecoder(((JSONObject) d).optString("userId"));
                audio.beep(ToneGenerator.TONE_PROP_BEEP2);
                break;
            case "talk:end":
                main.postDelayed(() -> audio.beep(ToneGenerator.TONE_PROP_ACK), 300);
                break;
            default:
                break;
        }
        toJs(ev, d);
    }

    /** 화면이 없을 때 다시 연결되면 스스로 hello(원래 방으로 복귀) */
    private void autoHello() {
        try {
            JSONObject h = new JSONObject(lastHello.toString());
            h.put("caps", caps());
            sock.emit("hello", new Object[]{h}, args -> {
                if (args.length > 0 && args[0] instanceof JSONObject) {
                    JSONObject r = (JSONObject) args[0];
                    if (r.optBoolean("ok") && r.has("key")) try { lastHello.put("key", r.optString("key")); } catch (Exception ignored) {}
                    JSONObject room = r.optJSONObject("room");
                    WalkiePlugin.pttKeyEnabled = room != null;
                }
            });
        } catch (Exception ignored) {}
    }

    private JSONObject caps() {
        JSONObject c = new JSONObject();
        try { c.put("opus", audio.opusOk).put("opus2", audio.opusOk); } catch (Exception ignored) {}
        return c;
    }

    // ---------- 화면(웹)과 연결 ----------
    public void attach(WebView w) {
        web = w;
        ensure();
        if (sock.connected()) toJs("connect", null);
    }

    public void detach(WebView w) {
        if (web == w) web = null;
    }

    private void toJs(String ev, Object d) {
        WebView w = web;
        if (w == null) return;
        String json = d == null ? "null" : (d instanceof JSONObject || d instanceof JSONArray) ? d.toString() : JSONObject.quote(String.valueOf(d));
        if (d instanceof Number || d instanceof Boolean) json = String.valueOf(d);
        String js = "window.__wtSock&&window.__wtSock.dispatch(" + JSONObject.quote(ev) + "," + json + ")";
        main.post(() -> { try { w.evaluateJavascript(js, null); } catch (Exception ignored) {} });
    }

    private void ackToJs(int id, Object r) {
        WebView w = web;
        if (w == null) return;
        String json = r == null ? "null" : r.toString();
        String js = "window.__wtSock&&window.__wtSock.ack(" + id + "," + json + ")";
        main.post(() -> { try { w.evaluateJavascript(js, null); } catch (Exception ignored) {} });
    }

    /** 화면에서 보낸 이벤트를 서버로 전달(ackId>0이면 응답을 화면으로 돌려줌) */
    public void emit(String ev, String json, int ackId) {
        ensure();
        Object payload;
        try {
            Object v = json == null ? null : new JSONTokener(json).nextValue();
            payload = (v == null || v == JSONObject.NULL) ? new JSONObject() : v;
        } catch (Exception e) {
            payload = new JSONObject();
        }
        if ("hello".equals(ev) && payload instanceof JSONObject) {
            try { ((JSONObject) payload).put("caps", caps()); } catch (Exception ignored) {}
            lastHello = (JSONObject) payload;
        }
        if ("room:leave".equals(ev)) WalkiePlugin.pttKeyEnabled = false;
        final Object p = payload;
        if (ackId > 0) {
            sock.emit(ev, new Object[]{p}, (Ack) args -> {
                Object r = args.length > 0 ? args[0] : null;
                if ("hello".equals(ev) && r instanceof JSONObject && ((JSONObject) r).has("key") && lastHello != null) {
                    try { lastHello.put("key", ((JSONObject) r).optString("key")); } catch (Exception ignored) {}
                }
                ackToJs(ackId, r);
            });
        } else {
            sock.emit(ev, p);
        }
    }

    public boolean connected() { return sock != null && sock.connected(); }

    // ---------- 송신 ----------
    public synchronized void ptt(boolean on) {
        ensure();
        if (on) {
            if (talking || startPending) return;
            startPending = true;
            sock.emit("talk:start", new Object[]{new JSONObject()}, (Ack) args -> {
                startPending = false;
                JSONObject r = args.length > 0 && args[0] instanceof JSONObject ? (JSONObject) args[0] : new JSONObject();
                JSONObject st = new JSONObject();
                try {
                    if (r.optBoolean("ok")) {
                        talking = true;
                        audio.startCapture(r.optString("codec", "pcm"));
                        audio.beep(ToneGenerator.TONE_PROP_BEEP);
                        st.put("talking", true);
                    } else {
                        audio.beep(ToneGenerator.TONE_PROP_NACK);
                        st.put("talking", false).put("error", r.optString("error", "지금은 송신할 수 없어요."));
                    }
                } catch (Exception ignored) {}
                toJs("native:talk", st);
            });
        } else {
            if (!talking) return;
            talking = false;
            audio.stopCapture(() -> { Socket s = sock; if (s != null) s.emit("talk:stop", new JSONObject()); });
            try { toJs("native:talk", new JSONObject().put("talking", false)); } catch (Exception ignored) {}
        }
    }

    public void toggle() { ptt(!talking); }

    public boolean isTalking() { return talking; }

    public void setVolume(float v) { audio.setVolume(v); }

    /** 완전 종료 */
    public static synchronized void shutdown() {
        if (inst == null) return;
        try {
            if (inst.sock != null) { inst.sock.disconnect(); inst.sock.off(); }
        } catch (Exception ignored) {}
        inst.audio.release();
        inst = null;
    }
}
