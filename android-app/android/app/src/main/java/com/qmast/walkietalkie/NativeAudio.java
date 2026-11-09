package com.qmast.walkietalkie;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.HandlerThread;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 온라인 무전의 소리 처리(앱 화면과 상관없이 동작).
 * - 송신: 마이크 48kHz → Opus 32kbps(안드로이드 MediaCodec) → 웹과 같은 묶음 형식('OP2'/'OPS')으로 전송
 *         Opus를 못 쓰는 경우 16kHz PCM
 * - 수신: Opus/PCM 풀기 → 크기 맞춤(깨끗한 리미터) → 48kHz 재생
 */
public class NativeAudio {
    public interface Sender { void send(byte[] data); }

    static final int RATE = 48000, FRAME = 960; // 20ms
    private static final byte[] MAGIC = {0x4f, 0x50, 0x53}, MAGIC2 = {0x4f, 0x50, 0x32};

    final boolean opusOk;
    private final Sender sender;
    private volatile float vol = 2f;

    // 재생
    private AudioTrack track;
    private final BlockingQueue<short[]> playQ = new ArrayBlockingQueue<>(200);
    private Thread playThread;
    private volatile boolean alive = true;
    private ToneGenerator tones;
    private final HandlerThread decThread = new HandlerThread("walkie-dec");
    private final Handler dec;
    private final Map<String, MediaCodec> decoders = new HashMap<>();
    private final Map<String, Float> rxGain = new HashMap<>();

    // 송신
    private volatile boolean capturing = false;
    private Thread capThread;
    private float txAgc = 1.5f;

    NativeAudio(Sender sender) {
        this.sender = sender;
        this.opusOk = codecAvailable(true) && codecAvailable(false);
        decThread.start();
        dec = new Handler(decThread.getLooper());
        startPlayback();
    }

    private static boolean codecAvailable(boolean encoder) {
        try {
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1);
            if (encoder) f.setInteger(MediaFormat.KEY_BIT_RATE, 32000);
            else { f.setByteBuffer("csd-0", opusHead()); f.setByteBuffer("csd-1", ns(6500000L)); f.setByteBuffer("csd-2", ns(80000000L)); }
            MediaCodecList l = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            return (encoder ? l.findEncoderForFormat(f) : l.findDecoderForFormat(f)) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static ByteBuffer opusHead() {
        ByteBuffer b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        b.put("OpusHead".getBytes()).put((byte) 1).put((byte) 1).putShort((short) 312).putInt(RATE).putShort((short) 0).put((byte) 0);
        b.flip();
        return b;
    }

    private static ByteBuffer ns(long v) {
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
        b.putLong(v);
        b.flip();
        return b;
    }

    void setVolume(float v) { vol = Math.max(0f, Math.min(4f, v)); }

    // ---------- 재생 ----------
    private void startPlayback() {
        try { tones = new ToneGenerator(AudioManager.STREAM_MUSIC, 70); } catch (Exception ignored) {}
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(min, RATE / 5 * 2)) // 약 0.2초
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();
        playThread = new Thread(() -> {
            while (alive) {
                try {
                    short[] s = playQ.poll(500, TimeUnit.MILLISECONDS);
                    if (s != null && track != null) track.write(s, 0, s.length);
                } catch (Exception ignored) {}
            }
        }, "walkie-play-online");
        playThread.start();
    }

    void beep(int tone) {
        try { if (tones != null) tones.startTone(tone, 150); } catch (Exception ignored) {}
    }

    /** 서버에서 받은 소리 묶음 처리 */
    void onAudio(String from, byte[] data) {
        if (data == null || data.length < 2 || capturing) return;
        dec.post(() -> {
            try {
                if (data.length >= 5 && data[0] == MAGIC2[0] && data[1] == MAGIC2[1] && data[2] == MAGIC2[2]) decodeOpus(from, data, 5);
                else if (data.length >= 4 && data[0] == MAGIC[0] && data[1] == MAGIC[1] && data[2] == MAGIC[2]) decodeOpus(from, data, 4);
                else playPcm16k(from, data);
            } catch (Exception ignored) {}
        });
    }

    /** 말하는 사람이 바뀌면 그 사람의 압축 풀기 상태를 새로 시작 */
    void resetDecoder(String from) {
        dec.post(() -> {
            MediaCodec c = decoders.remove(from);
            if (c != null) { try { c.stop(); c.release(); } catch (Exception ignored) {} }
        });
    }

    private MediaCodec decoderFor(String from) throws Exception {
        MediaCodec c = decoders.get(from);
        if (c != null) return c;
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1);
        f.setByteBuffer("csd-0", opusHead());
        f.setByteBuffer("csd-1", ns(6500000L));
        f.setByteBuffer("csd-2", ns(80000000L));
        c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
        c.configure(f, null, null, 0);
        c.start();
        decoders.put(from, c);
        return c;
    }

    private long pts = 0;

    private void decodeOpus(String from, byte[] u8, int head) throws Exception {
        if (!opusOk) return;
        int n = u8[head - 1] & 0xff;
        int o = head + n * 2;
        MediaCodec c = decoderFor(from);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        List<short[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int len = (u8[head + i * 2] & 0xff) | ((u8[head + 1 + i * 2] & 0xff) << 8);
            if (o + len > u8.length) break;
            int ii = c.dequeueInputBuffer(10000);
            if (ii >= 0) {
                ByteBuffer in = c.getInputBuffer(ii);
                in.clear();
                in.put(u8, o, len);
                c.queueInputBuffer(ii, 0, len, pts, 0);
                pts += 20000;
            }
            o += len;
            drain(c, info, out);
        }
        drain(c, info, out);
        for (short[] s : out) enqueue(from, s);
    }

    private void drain(MediaCodec c, MediaCodec.BufferInfo info, List<short[]> out) {
        while (true) {
            int oi = c.dequeueOutputBuffer(info, 0);
            if (oi < 0) break;
            ByteBuffer b = c.getOutputBuffer(oi);
            if (b != null && info.size > 0) {
                b.position(info.offset).limit(info.offset + info.size);
                short[] s = new short[info.size / 2];
                b.order(ByteOrder.nativeOrder()).asShortBuffer().get(s);
                out.add(s);
            }
            c.releaseOutputBuffer(oi, false);
        }
    }

    /** 무압축(16kHz Int16) → 48kHz로 늘려 재생 */
    private void playPcm16k(String from, byte[] data) {
        int n = data.length / 2;
        short[] s = new short[n * 3];
        short prev = 0;
        for (int i = 0; i < n; i++) {
            short v = (short) ((data[2 * i] & 0xff) | (data[2 * i + 1] << 8));
            s[3 * i] = (short) ((prev * 2 + v) / 3);
            s[3 * i + 1] = (short) ((prev + v * 2) / 3);
            s[3 * i + 2] = v;
            prev = v;
        }
        enqueue(from, s);
    }

    private void enqueue(String from, short[] s) {
        louden(from, s);
        if (!playQ.offer(s)) { playQ.clear(); playQ.offer(s); }
    }

    /** 받는 소리 크기 맞춤: 평균 크기를 유튜브 수준에 맞추고, 0.6 위만 부드럽게 눌러 찢어짐 방지(웹과 같은 방식) */
    private void louden(String from, short[] s) {
        double sum = 0;
        for (short v : s) { double x = v / 32768.0; sum += x * x; }
        double rms = Math.sqrt(sum / Math.max(1, s.length));
        float g = rxGain.containsKey(from) ? rxGain.get(from) : 3f;
        if (rms > 0.004) {
            float want = (float) Math.min(16, 0.22 / rms);
            g = want < g ? g * 0.6f + want * 0.4f : g * 0.95f + want * 0.05f;
            rxGain.put(from, g);
        }
        double gate = rms < 0.006 ? rms / 0.006 : 1, k = g * vol;
        for (int i = 0; i < s.length; i++) {
            double x = s[i] / 32768.0 * k, a = Math.abs(x);
            double y = a <= 0.6 ? a : 0.6 + 0.38 * Math.tanh((a - 0.6) / 0.38);
            s[i] = (short) Math.round((x < 0 ? -y : y) * gate * 32767);
        }
    }

    // ---------- 송신 ----------
    /** codec: "opus2"(고음질 묶음) / "opus"(기본 묶음) / "pcm" */
    synchronized void startCapture(String codec) {
        if (capturing) return;
        capturing = true;
        playQ.clear();
        final String mode = opusOk && ("opus2".equals(codec) || "opus".equals(codec)) ? codec : "pcm";
        capThread = new Thread(() -> captureLoop(mode), "walkie-cap-online");
        capThread.start();
    }

    /** 송신 끝: 남은 소리까지 보낸 뒤 done 실행 */
    void stopCapture(Runnable done) {
        Thread t;
        synchronized (this) {
            if (!capturing) { if (done != null) done.run(); return; }
            capturing = false;
            t = capThread;
        }
        new Thread(() -> {
            try { if (t != null) t.join(1500); } catch (InterruptedException ignored) {}
            if (done != null) done.run();
        }).start();
    }

    private void captureLoop(String mode) {
        boolean pcm = "pcm".equals(mode);
        AudioRecord rec = null;
        MediaCodec enc = null;
        List<byte[]> q = new ArrayList<>();
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 8));
            try { if (android.media.audiofx.NoiseSuppressor.isAvailable()) { android.media.audiofx.NoiseSuppressor ns = android.media.audiofx.NoiseSuppressor.create(rec.getAudioSessionId()); if (ns != null) ns.setEnabled(true); } } catch (Exception ignored) {}
            if (!pcm) {
                MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1);
                f.setInteger(MediaFormat.KEY_BIT_RATE, 32000);
                enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
                enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                enc.start();
            }
            rec.startRecording();
            short[] buf = new short[FRAME];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long ts = 0;
            while (capturing) {
                int n = 0;
                while (n < FRAME && capturing) {
                    int r = rec.read(buf, n, FRAME - n);
                    if (r <= 0) break;
                    n += r;
                }
                if (n < FRAME) continue;
                // 보내는 쪽 자동 음량: 작은 목소리만 부드럽게 키움(최대 3배), 큰 소리는 즉시 줄임
                int pk = 1;
                for (int i = 0; i < n; i++) pk = Math.max(pk, Math.abs((int) buf[i]));
                float want = pk > 650 ? Math.min(3f, 22900f / pk) : txAgc;
                txAgc = want < txAgc ? want : txAgc * 0.97f + want * 0.03f;
                byte[] frame = new byte[n * 2];
                for (int i = 0; i < n; i++) {
                    int v = Math.round(buf[i] * txAgc);
                    if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                    frame[2 * i] = (byte) v; frame[2 * i + 1] = (byte) (v >> 8);
                }
                if (pcm) {
                    // 48kHz → 16kHz(3개 평균)
                    byte[] out = new byte[n / 3 * 2];
                    for (int i = 0; i < n / 3; i++) {
                        int a = (short) ((frame[6 * i] & 0xff) | (frame[6 * i + 1] << 8));
                        int b = (short) ((frame[6 * i + 2] & 0xff) | (frame[6 * i + 3] << 8));
                        int c = (short) ((frame[6 * i + 4] & 0xff) | (frame[6 * i + 5] << 8));
                        int v = (a + b + c) / 3;
                        out[2 * i] = (byte) v; out[2 * i + 1] = (byte) (v >> 8);
                    }
                    sender.send(out);
                    continue;
                }
                int ii = enc.dequeueInputBuffer(10000);
                if (ii >= 0) {
                    ByteBuffer in = enc.getInputBuffer(ii);
                    in.clear();
                    in.put(frame);
                    enc.queueInputBuffer(ii, 0, frame.length, ts, 0);
                    ts += 20000;
                }
                collect(enc, info, q, mode);
            }
            if (enc != null) {
                // 남은 압축 결과 마저 보내기
                for (int i = 0; i < 5; i++) { collect(enc, info, q, mode); Thread.sleep(10); }
                flush(q, mode);
            }
        } catch (Exception ignored) {
        } finally {
            try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception ignored) {}
            try { if (enc != null) { enc.stop(); enc.release(); } } catch (Exception ignored) {}
            capturing = false;
        }
    }

    private void collect(MediaCodec enc, MediaCodec.BufferInfo info, List<byte[]> q, String mode) {
        while (true) {
            int oi = enc.dequeueOutputBuffer(info, 0);
            if (oi < 0) break;
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                ByteBuffer b = enc.getOutputBuffer(oi);
                if (b != null) {
                    byte[] pkt = new byte[info.size];
                    b.position(info.offset);
                    b.get(pkt);
                    q.add(pkt);
                }
            }
            enc.releaseOutputBuffer(oi, false);
            if (q.size() >= 5) flush(q, mode);
        }
    }

    /** 웹과 같은 묶음 형식으로 전송: 'OP2'+샘플레이트코드(0=48k)+개수+길이들+데이터 / 'OPS'+개수+... */
    private void flush(List<byte[]> q, String mode) {
        if (q.isEmpty()) return;
        boolean hq = "opus2".equals(mode);
        int head = hq ? 5 : 4, total = 0;
        for (byte[] p : q) total += p.length;
        byte[] out = new byte[head + q.size() * 2 + total];
        if (hq) { out[0] = MAGIC2[0]; out[1] = MAGIC2[1]; out[2] = MAGIC2[2]; out[3] = 0; out[4] = (byte) q.size(); }
        else { out[0] = MAGIC[0]; out[1] = MAGIC[1]; out[2] = MAGIC[2]; out[3] = (byte) q.size(); }
        int o = head;
        for (byte[] p : q) { out[o++] = (byte) p.length; out[o++] = (byte) (p.length >> 8); }
        for (byte[] p : q) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        q.clear();
        sender.send(out);
    }

    void release() {
        alive = false;
        capturing = false;
        try { if (track != null) { track.stop(); track.release(); } } catch (Exception ignored) {}
        track = null;
        if (tones != null) { tones.release(); tones = null; }
        dec.post(() -> {
            for (MediaCodec c : decoders.values()) { try { c.stop(); c.release(); } catch (Exception ignored) {} }
            decoders.clear();
            decThread.quitSafely();
        });
    }
}
