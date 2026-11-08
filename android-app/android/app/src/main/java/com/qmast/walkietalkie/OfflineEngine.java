package com.qmast.walkietalkie;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 오프라인 무전: 인터넷 없이 근처 폰끼리(블루투스·와이파이 다이렉트, Google Nearby Connections) 직접 연결.
 * - 근처의 같은 앱 사용자와 자동 연결(여러 대 동시), 받은 메시지를 다른 폰에 다시 전달(최대 3단계)해 거리를 늘림
 * - 같은 팀 이름 + 같은 채널 번호인 사람의 소리만 재생
 * - 음성: 8kHz μ-law(64kbps), 0.1초 단위로 전송
 */
public class OfflineEngine {
    public interface Listener { void onState(String json); }

    private static final String SERVICE_ID = "com.qmast.walkietalkie.offline";
    private static final byte T_PRESENCE = 1, T_AUDIO = 2, T_TALK = 3;
    private static final int RATE = 8000, FRAME = 800; // 0.1초
    private static final int TTL = 3;

    private static OfflineEngine inst;
    public static synchronized OfflineEngine get(Context ctx) {
        if (inst == null) inst = new OfflineEngine(ctx.getApplicationContext());
        return inst;
    }
    public static boolean isActive() { return inst != null && inst.active; }

    private final Context ctx;
    private final ConnectionsClient client;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String myUuid;
    private final SecureRandom rnd = new SecureRandom();

    private volatile boolean active = false;
    private volatile boolean talking = false;
    private String nick = "", group = "", status = "";
    private int channel = 1;
    private Listener listener;

    private final Set<String> endpoints = new HashSet<>();            // 연결된 기기
    private final Map<String, String> discovered = new HashMap<>();    // endpointId → uuid
    private final Map<String, Peer> peers = new HashMap<>();           // uuid → 정보
    private final LinkedHashMap<Long, Boolean> seen = new LinkedHashMap<Long, Boolean>(512, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Boolean> e) { return size() > 3000; }
    };
    private int seq = 0;
    // 진단용(화면에 표시): 알리기·찾기 상태, 발견 수, 최근 오류
    private String advState = "-", discState = "-", lastError = "";
    private int attempts = 0;

    private AudioTrack track;
    private final BlockingQueue<short[]> playQ = new ArrayBlockingQueue<>(100);
    private Thread playThread, recThread;
    private ToneGenerator tones;
    /** 소리 키우기(mB, 100 = 1dB). 기본 +12dB, 최대 +24dB */
    private int boostMb = 1200;
    private String rxUuid = null;
    private long rxAt = 0;

    static class Peer {
        String nick = "", group = "";
        int channel = 1;
        boolean talking;
        long seenAt, audioAt;
    }

    private OfflineEngine(Context ctx) {
        this.ctx = ctx;
        this.client = Nearby.getConnectionsClient(ctx);
        String u = ctx.getSharedPreferences("walkie", Context.MODE_PRIVATE).getString("offUuid", null);
        if (u == null) {
            u = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            ctx.getSharedPreferences("walkie", Context.MODE_PRIVATE).edit().putString("offUuid", u).apply();
        }
        myUuid = u;
    }

    public void setListener(Listener l) { this.listener = l; }
    public boolean isTalking() { return talking; }

    // ---------- 시작/종료 ----------
    public synchronized void start(String nick, String group, int channel) {
        this.nick = nick; this.group = group; this.channel = channel;
        if (active) { sendPresence(); push(); return; }
        active = true;
        status = "주변 기기를 찾는 중…";
        startAudio();
        startNearby();
        main.post(ticker);
        push();
    }

    private void startNearby() {
        advState = "시작 중"; discState = "시작 중";
        String name = "WT|" + myUuid;
        AdvertisingOptions ao = new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build();
        client.startAdvertising(name, SERVICE_ID, lifecycle, ao)
                .addOnSuccessListener(v -> { advState = "켜짐"; push(); })
                .addOnFailureListener(e -> { advState = "실패 " + code(e); status = "알리기 실패: 블루투스·위치를 켜 주세요"; lastError = "알리기 " + code(e); push(); });
        DiscoveryOptions dopt = new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build();
        client.startDiscovery(SERVICE_ID, discovery, dopt)
                .addOnSuccessListener(v -> { discState = "켜짐"; push(); })
                .addOnFailureListener(e -> { discState = "실패 " + code(e); status = "찾기 실패: 블루투스·위치를 켜 주세요"; lastError = "찾기 " + code(e); push(); });
    }

    /** 다시 찾기: 알리기·찾기를 껐다 켬(화면의 '다시 찾기') */
    public synchronized void restartNearby() {
        if (!active) return;
        try { client.stopAdvertising(); client.stopDiscovery(); } catch (Exception ignored) {}
        discovered.clear();
        lastError = "";
        main.postDelayed(this::startNearby, 500);
        push();
    }

    /** Nearby 오류 코드를 사람이 읽을 수 있게 */
    private static String code(Exception e) {
        if (e instanceof ApiException) {
            int c = ((ApiException) e).getStatusCode();
            return ConnectionsStatusCodes.getStatusCodeString(c) + "(" + c + ")";
        }
        return e.getClass().getSimpleName();
    }

    public synchronized void stop() {
        if (!active) return;
        active = false;
        talking = false;
        main.removeCallbacks(ticker);
        try { client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints(); } catch (Exception ignored) {}
        endpoints.clear(); discovered.clear(); peers.clear(); pending.clear();
        advState = "-"; discState = "-"; lastError = ""; attempts = 0;
        stopAudio();
        status = "";
        push();
    }

    public synchronized void set(String nick, String group, int channel) {
        this.nick = nick; this.group = group; this.channel = channel;
        sendPresence();
        push();
    }

    // ---------- 송신 ----------
    /** 송신 시작/끝. 같은 채널에서 다른 사람이 말하는 중이면 false */
    public synchronized boolean setTalking(boolean on) {
        if (!active) return false;
        if (on == talking) return true;
        if (on && busyBy() != null) { beep(ToneGenerator.TONE_PROP_NACK); push(); return false; }
        talking = on;
        broadcast(T_TALK, header().toString(), null);
        if (on) beep(ToneGenerator.TONE_PROP_BEEP);
        push();
        return true;
    }

    private String busyBy() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Peer> e : peers.entrySet()) {
            Peer p = e.getValue();
            if (same(p) && (p.talking || now - p.audioAt < 1500) && now - p.seenAt < 15000) return e.getKey();
        }
        return null;
    }

    private boolean same(Peer p) { return p.group.equals(group) && p.channel == channel; }

    // ---------- 연결 ----------
    private final EndpointDiscoveryCallback discovery = new EndpointDiscoveryCallback() {
        @Override public void onEndpointFound(@NonNull String id, @NonNull DiscoveredEndpointInfo info) {
            String n = info.getEndpointName();
            if (n == null || !n.startsWith("WT|")) return;
            String uuid = n.substring(3);
            synchronized (OfflineEngine.this) { discovered.put(id, uuid); }
            push();
            maybeConnect(id, uuid, 0);
        }
        @Override public void onEndpointLost(@NonNull String id) {
            synchronized (OfflineEngine.this) { discovered.remove(id); }
            push();
        }
    };

    private final Set<String> pending = new HashSet<>();

    /** 둘이 동시에 요청해 충돌하지 않도록 id가 작은 쪽이 먼저, 큰 쪽은 4초 뒤에도 연결이 안 됐을 때 요청 */
    private void maybeConnect(String id, String uuid, int attempt) {
        if (!active || endpoints.contains(id) || pending.contains(id)) return;
        long delay = myUuid.compareTo(uuid) < 0 ? 0 : 4000;
        if (attempt > 0) delay = 2000L * attempt;
        main.postDelayed(() -> {
            synchronized (OfflineEngine.this) {
                if (!active || endpoints.contains(id) || pending.contains(id) || !discovered.containsKey(id)) return;
                pending.add(id);
                attempts++;
            }
            client.requestConnection("WT|" + myUuid, id, lifecycle)
                    .addOnSuccessListener(v -> push())
                    .addOnFailureListener(e -> {
                        synchronized (OfflineEngine.this) { pending.remove(id); }
                        int c = e instanceof ApiException ? ((ApiException) e).getStatusCode() : -1;
                        if (c == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) return;
                        lastError = "연결 " + code(e);
                        push();
                        if (attempt < 6) maybeConnect(id, uuid, attempt + 1);
                    });
        }, delay);
    }

    private final ConnectionLifecycleCallback lifecycle = new ConnectionLifecycleCallback() {
        @Override public void onConnectionInitiated(@NonNull String id, @NonNull ConnectionInfo info) {
            String n = info.getEndpointName();
            if (n != null && n.startsWith("WT|")) client.acceptConnection(id, payloads);
            else client.rejectConnection(id);
        }
        @Override public void onConnectionResult(@NonNull String id, @NonNull ConnectionResolution r) {
            synchronized (OfflineEngine.this) { pending.remove(id); }
            if (r.getStatus().isSuccess()) {
                synchronized (OfflineEngine.this) { endpoints.add(id); status = ""; lastError = ""; }
                sendPresence();
                push();
            } else {
                lastError = "연결 거절/실패 " + ConnectionsStatusCodes.getStatusCodeString(r.getStatus().getStatusCode()) + "(" + r.getStatus().getStatusCode() + ")";
                push();
            }
        }
        @Override public void onDisconnected(@NonNull String id) {
            synchronized (OfflineEngine.this) { endpoints.remove(id); pending.remove(id); }
            push();
            // 아직 보이면 다시 연결
            String uuid;
            synchronized (OfflineEngine.this) { uuid = discovered.get(id); }
            if (uuid != null) maybeConnect(id, uuid, 1);
        }
    };

    private final PayloadCallback payloads = new PayloadCallback() {
        @Override public void onPayloadReceived(@NonNull String from, @NonNull Payload p) {
            if (p.getType() == Payload.Type.BYTES && p.asBytes() != null) onMessage(from, p.asBytes());
        }
        @Override public void onPayloadTransferUpdate(@NonNull String from, @NonNull PayloadTransferUpdate u) {}
    };

    // ---------- 메시지 ----------
    // [1 type][8 msgId][1 ttl][2 jsonLen][json][payload]
    private byte[] pack(byte type, long id, int ttl, String json, byte[] payload) {
        byte[] j = json.getBytes(StandardCharsets.UTF_8);
        int pl = payload == null ? 0 : payload.length;
        ByteBuffer b = ByteBuffer.allocate(12 + j.length + pl);
        b.put(type).putLong(id).put((byte) ttl).putShort((short) j.length).put(j);
        if (payload != null) b.put(payload);
        return b.array();
    }

    private void broadcast(byte type, String json, byte[] payload) {
        long id = rnd.nextLong();
        synchronized (this) { seen.put(id, true); }
        send(pack(type, id, TTL, json, payload), null);
    }

    private void send(byte[] bytes, String except) {
        List<String> to;
        synchronized (this) {
            to = new ArrayList<>(endpoints);
        }
        if (except != null) to.remove(except);
        if (to.isEmpty()) return;
        try { client.sendPayload(to, Payload.fromBytes(bytes)); } catch (Exception ignored) {}
    }

    private void onMessage(String from, byte[] data) {
        try {
            ByteBuffer b = ByteBuffer.wrap(data);
            byte type = b.get();
            long id = b.getLong();
            int ttl = b.get();
            int jl = b.getShort() & 0xffff;
            byte[] jb = new byte[jl];
            b.get(jb);
            synchronized (this) {
                if (seen.containsKey(id)) return;
                seen.put(id, true);
            }
            // 다른 폰에도 전달(거리 늘리기)
            if (ttl > 0) {
                data[9] = (byte) (ttl - 1);
                send(data, from);
            }
            JSONObject h = new JSONObject(new String(jb, StandardCharsets.UTF_8));
            String u = h.optString("u");
            if (u.equals(myUuid)) return;
            Peer p;
            synchronized (this) {
                p = peers.get(u);
                if (p == null) { p = new Peer(); peers.put(u, p); }
                p.seenAt = System.currentTimeMillis();
                p.group = h.optString("g", p.group);
                p.channel = h.optInt("c", p.channel);
                if (h.has("n")) p.nick = h.optString("n");
            }
            if (type == T_PRESENCE) {
                p.talking = h.optBoolean("t", false);
                push();
            } else if (type == T_TALK) {
                boolean was = p.talking;
                p.talking = h.optBoolean("t", false);
                if (same(p)) {
                    if (p.talking && !was) { beep(ToneGenerator.TONE_PROP_BEEP2); rxUuid = u; }
                    if (!p.talking && was) { main.postDelayed(() -> beep(ToneGenerator.TONE_PROP_ACK), 250); }
                }
                push();
            } else if (type == T_AUDIO) {
                p.audioAt = System.currentTimeMillis();
                if (same(p) && !talking) {
                    byte[] mu = new byte[b.remaining()];
                    b.get(mu);
                    short[] pcm = new short[mu.length];
                    for (int i = 0; i < mu.length; i++) pcm[i] = ulawToLinear(mu[i]);
                    if (!playQ.offer(pcm)) { playQ.clear(); playQ.offer(pcm); }
                    boolean changed = !u.equals(rxUuid) || System.currentTimeMillis() - rxAt > 1000;
                    rxUuid = u; rxAt = System.currentTimeMillis();
                    if (changed) push();
                }
            }
        } catch (Exception ignored) {}
    }

    private JSONObject header() {
        JSONObject h = new JSONObject();
        try { h.put("u", myUuid).put("g", group).put("c", channel).put("n", nick).put("t", talking); } catch (Exception ignored) {}
        return h;
    }

    private void sendPresence() { if (active) broadcast(T_PRESENCE, header().toString(), null); }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!active) return;
            sendPresence();
            long now = System.currentTimeMillis();
            synchronized (OfflineEngine.this) {
                peers.values().removeIf(p -> now - p.seenAt > 20000);
                // 놓친 연결 다시 시도
                for (Map.Entry<String, String> e : discovered.entrySet()) if (!endpoints.contains(e.getKey())) maybeConnect(e.getKey(), e.getValue(), 0);
            }
            push();
            main.postDelayed(this, 5000);
        }
    };

    // ---------- 소리 ----------
    private void startAudio() {
        try { tones = new ToneGenerator(AudioManager.STREAM_MUSIC, 70); } catch (Exception ignored) {}
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(min, RATE * 2 / 2)) // 약 0.5초
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();
        SpeakerRoute.get(ctx).apply();
        // 소리 키우기는 재생 직전 louden()에서 처리(유튜브 수준 평균 크기 + 부드러운 리미터)
        playThread = new Thread(() -> {
            while (active) {
                try {
                    short[] s = playQ.poll(500, TimeUnit.MILLISECONDS);
                    if (s != null && track != null) { louden(s); track.write(s, 0, s.length); }
                } catch (Exception ignored) {}
            }
        }, "walkie-play");
        playThread.start();
        recThread = new Thread(this::recordLoop, "walkie-rec");
        recThread.start();
    }

    private float agc = 2f;

    private void recordLoop() {
        AudioRecord rec = null;
        short[] buf = new short[FRAME];
        while (active) {
            try {
                if (!talking) {
                    if (rec != null) { rec.stop(); rec.release(); rec = null; }
                    Thread.sleep(30);
                    continue;
                }
                if (rec == null) {
                    int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    // 통화 모드(수화기 출력)로 바뀌지 않도록 일반 마이크 사용 + 가능하면 잡음 제거·자동 음량
                    rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 4));
                    try { if (android.media.audiofx.NoiseSuppressor.isAvailable()) { android.media.audiofx.NoiseSuppressor ns = android.media.audiofx.NoiseSuppressor.create(rec.getAudioSessionId()); if (ns != null) ns.setEnabled(true); } } catch (Exception ignored) {}
                    try { if (android.media.audiofx.AutomaticGainControl.isAvailable()) { android.media.audiofx.AutomaticGainControl agc = android.media.audiofx.AutomaticGainControl.create(rec.getAudioSessionId()); if (agc != null) agc.setEnabled(true); } } catch (Exception ignored) {}
                    rec.startRecording();
                    main.post(() -> SpeakerRoute.get(ctx).apply());
                }
                int n = 0;
                while (n < FRAME && talking) {
                    int r = rec.read(buf, n, FRAME - n);
                    if (r <= 0) break;
                    n += r;
                }
                if (n <= 0) continue;
                // 보내는 쪽 자동 음량: 말소리 크기를 일정하게 크게(최대 8배), 큰 소리는 바로 줄여 찢어짐 방지
                int peak = 1;
                for (int i = 0; i < n; i++) peak = Math.max(peak, Math.abs((int) buf[i]));
                float want = peak > 300 ? Math.min(8f, 26000f / peak) : agc;
                agc = want < agc ? want : agc * 0.7f + want * 0.3f;
                byte[] mu = new byte[n];
                for (int i = 0; i < n; i++) {
                    int v = Math.round(buf[i] * agc);
                    if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                    mu[i] = linearToUlaw((short) v);
                }
                JSONObject h = header();
                h.put("s", seq++);
                broadcast(T_AUDIO, h.toString(), mu);
            } catch (SecurityException se) {
                status = "마이크 권한이 필요해요";
                talking = false;
                push();
            } catch (Exception ignored) {
            }
        }
        if (rec != null) { try { rec.stop(); rec.release(); } catch (Exception ignored) {} }
    }

    /** 소리 키우기(화면 슬라이더, mB): +6dB = 유튜브 수준, 기본 +12dB(2배), 최대 +24dB */
    public synchronized void setBoost(int mb) {
        boostMb = Math.max(0, Math.min(2400, mb));
    }

    /** 받는 쪽 소리 키우기: 말소리 평균 크기를 유튜브 수준(약 -10dBFS)에 맞추고 tanh 리미터로 찢어짐 방지, 말 사이 잡음은 키우지 않음 */
    private float rxGain = 4f;
    private void louden(short[] s) {
        double sum = 0;
        for (short v : s) { double x = v / 32768.0; sum += x * x; }
        double rms = Math.sqrt(sum / Math.max(1, s.length));
        if (rms > 0.004) {
            float want = (float) Math.min(20, 0.30 / rms);
            rxGain = want < rxGain ? rxGain * 0.5f + want * 0.5f : rxGain * 0.9f + want * 0.1f;
        }
        double vol = Math.pow(10, (boostMb - 600) / 2000.0);
        double gate = rms < 0.006 ? rms / 0.006 : 1;
        double k = 1.3 * rxGain * vol;
        for (int i = 0; i < s.length; i++) {
            double y = 0.97 * Math.tanh(k * (s[i] / 32768.0)) * gate;
            s[i] = (short) Math.round(y * 32767);
        }
    }

    private void stopAudio() {
        try { if (track != null) { track.stop(); track.release(); } } catch (Exception ignored) {}
        track = null;
        playQ.clear();
        if (tones != null) { tones.release(); tones = null; }
    }

    private void beep(int tone) {
        try { if (tones != null) tones.startTone(tone, 150); } catch (Exception ignored) {}
    }

    // ---------- 화면에 상태 알리기 ----------
    private long lastPush = 0;
    private void push() {
        Listener l = listener;
        if (l == null) return;
        long now = System.currentTimeMillis();
        try {
            JSONObject s = new JSONObject();
            s.put("active", active).put("talking", talking).put("status", status);
            s.put("connected", endpoints.size());
            s.put("diag", new JSONObject().put("adv", advState).put("disc", discState).put("found", discovered.size())
                    .put("attempts", attempts).put("error", lastError).put("id", myUuid));
            s.put("me", new JSONObject().put("nick", nick).put("group", group).put("channel", channel));
            JSONArray arr = new JSONArray();
            int others = 0;
            synchronized (this) {
                for (Peer p : peers.values()) {
                    if (!p.group.equals(group)) { others++; continue; }
                    boolean talk = p.talking || now - p.audioAt < 1500;
                    arr.put(new JSONObject().put("nick", p.nick).put("channel", p.channel).put("talking", talk));
                }
            }
            s.put("peers", arr).put("otherGroups", others);
            String busy = busyBy();
            Peer bp = busy == null ? null : peers.get(busy);
            s.put("rx", bp == null ? JSONObject.NULL : bp.nick);
            l.onState(s.toString());
        } catch (Exception ignored) {}
        lastPush = now;
    }

    // ---------- μ-law (G.711) ----------
    private static byte linearToUlaw(short sample) {
        final int BIAS = 0x84, CLIP = 32635;
        int s = sample;
        int sign = (s >> 8) & 0x80;
        if (sign != 0) s = -s;
        if (s > CLIP) s = CLIP;
        s += BIAS;
        int exp = 7;
        for (int mask = 0x4000; (s & mask) == 0 && exp > 0; exp--, mask >>= 1) {}
        int mant = (s >> (exp + 3)) & 0x0F;
        return (byte) ~(sign | (exp << 4) | mant);
    }

    private static short ulawToLinear(byte b) {
        int u = ~b & 0xff;
        int sign = u & 0x80, exp = (u >> 4) & 0x07, mant = u & 0x0F;
        int s = ((mant << 3) + 0x84) << exp;
        s -= 0x84;
        return (short) (sign != 0 ? -s : s);
    }
}
