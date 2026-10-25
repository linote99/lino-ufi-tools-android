package top.linote.ufiinstaller;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * Client side of the scrcpy 5.0.1 protocol (tunnel_forward, video + control, no audio), as implemented by
 * the bundled scrcpy-server (Genymobile/scrcpy v5.0.1, Apache-2.0):
 * server/src/main/java/com/genymobile/scrcpy/{device/DesktopConnection,device/Streamer,control/ControlMessageReader}.java
 * No Android dependencies, so it is tested on the JVM against test/mock-adbd.py.
 */
final class ScrcpyProtocol {
    static final String VERSION = "5.0.1";
    static final String REMOTE_JAR = "/data/local/tmp/ufi-scrcpy-server.jar";
    static final int CODEC_H264 = 0x68323634;
    private static final long FLAG_CONFIG = 1L << 62, FLAG_KEY = 1L << 61;

    private ScrcpyProtocol() { }

    static String hex(int scid) { return String.format(Locale.ROOT, "%08x", scid & 0x7fffffff); }

    static String serverCommand(String hex, int maxSize, int bitRate) {
        return "CLASSPATH=" + REMOTE_JAR + " app_process / com.genymobile.scrcpy.Server " + VERSION
                + " scid=" + hex + " log_level=info audio=false control=true tunnel_forward=true cleanup=true"
                + " video_codec=h264 max_size=" + maxSize + " video_bit_rate=" + bitRate + " max_fps=30"
                + " send_device_meta=false clipboard_autosync=false power_on=true";
    }

    static String socketService(String hex) { return "localabstract:scrcpy_" + hex; }

    /** INJECT_TOUCH_EVENT (32 bytes). action: 0 down, 1 up, 2 move (the server derives POINTER_DOWN/UP). */
    static byte[] touch(int action, long pointerId, int x, int y, int w, int h) {
        ByteBuffer b = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN);
        b.put((byte) 2).put((byte) action).putLong(pointerId).putInt(x).putInt(y)
                .putShort((short) w).putShort((short) h).putShort((short) (action == 1 ? 0 : 0xffff))
                .putInt(0).putInt(0);
        return b.array();
    }

    /** INJECT_KEYCODE (14 bytes). */
    static byte[] key(int action, int keycode) {
        ByteBuffer b = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN);
        b.put((byte) 0).put((byte) action).putInt(keycode).putInt(0).putInt(0);
        return b.array();
    }

    interface VideoSink {
        /** New capture session (start, rotation): the decoder must be (re)created for w x h. */
        void session(int w, int h) throws IOException;
        void packet(byte[] data, boolean config, boolean keyFrame, long pts) throws IOException;
        boolean stopped();
    }

    /** Reads the video socket after the dummy byte: codec id, then session and media packets until stopped/EOF. */
    static void readVideo(DataInputStream in, VideoSink sink) throws IOException {
        int codec = in.readInt();
        if (codec == 0 || codec == 1) throw new IOException("设备无法采集或编码屏幕画面");
        if (codec != CODEC_H264) throw new IOException("不支持的视频编码 0x" + Integer.toHexString(codec));
        while (!sink.stopped()) {
            long head = in.readLong();
            int third = in.readInt();
            if (head < 0) {   // session packet: u32 flags (MSB set) | u32 width, u32 height
                int w = (int) head;
                if (w <= 0 || third <= 0 || w > 8192 || third > 8192) throw new IOException("画面尺寸异常 " + w + "x" + third);
                sink.session(w, third);
                continue;
            }
            if (third < 0 || third > (16 << 20)) throw new IOException("视频数据异常");
            byte[] data = new byte[third];
            in.readFully(data);
            boolean config = (head & FLAG_CONFIG) != 0;
            sink.packet(data, config, (head & FLAG_KEY) != 0, config ? 0 : head & (FLAG_KEY - 1));
        }
    }

    /** A started server: its shell stream (log), the video socket (after the dummy byte) and the control socket. */
    static final class Session {
        AdbConnection.MuxStream shell, video, control;
        DataInputStream videoIn;
        private final ArrayDeque<String> log = new ArrayDeque<>();

        void addLog(String l) {
            synchronized (log) {
                log.add(l);
                while (log.size() > 8) log.poll();
            }
        }

        /** Last server errors (or the last line) for error messages; empty if none. */
        String tail() {
            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
            StringBuilder b = new StringBuilder();
            synchronized (log) {
                for (String l : log) if (l.contains("ERROR") || l.contains("Exception") || l.contains("WARN")) b.append('\n').append(l);
                if (b.length() == 0 && !log.isEmpty()) b.append('\n').append(log.peekLast());
            }
            return b.length() == 0 ? "" : "：" + b.toString().trim();
        }

        void close() {
            for (AdbConnection.MuxStream m : new AdbConnection.MuxStream[]{control, video, shell}) if (m != null) m.close();
        }
    }

    /** Starts the pushed server over a mux and opens video + control (the server listens only after it started). */
    static Session start(AdbConnection.Mux mux, String hex, int maxSize, int bitRate, BooleanSupplier stopped) throws IOException {
        Session s = new Session();
        try {
            s.shell = mux.open("shell:" + serverCommand(hex, maxSize, bitRate), 10000);
            AdbConnection.MuxStream sh = s.shell;
            Thread lt = new Thread(() -> readLog(sh, s), "scrcpy-log");
            lt.setDaemon(true);
            lt.start();
            String svc = socketService(hex);
            long end = System.currentTimeMillis() + 10000;
            while (s.video == null) {
                if (stopped.getAsBoolean()) throw new Installer.Cancelled();
                try {
                    s.video = mux.open(svc, 4000);
                } catch (IOException e) {
                    if (!mux.alive()) throw e;
                    if (sh.isClosed()) throw new IOException("投屏服务端已退出" + s.tail());
                    if (System.currentTimeMillis() > end) throw new IOException("投屏服务端未能启动" + s.tail());
                    sleep(150);
                }
            }
            s.videoIn = new DataInputStream(new BufferedInputStream(s.video.in(), 1 << 16));
            if (s.videoIn.read() != 0) throw new IOException("投屏连接异常（握手失败）" + s.tail());
            for (int i = 0; s.control == null; i++) {
                try {
                    s.control = mux.open(svc, 4000);
                } catch (IOException e) {
                    if (i >= 20 || !mux.alive()) throw e;
                    sleep(100);
                }
            }
            return s;
        } catch (IOException | RuntimeException e) {
            s.close();
            throw e;
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private static void readLog(AdbConnection.MuxStream sh, Session s) {
        try {
            InputStream in = sh.in();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int r;
            while ((r = in.read()) >= 0) {
                if (r == '\n') {
                    String l = new String(line.toByteArray(), StandardCharsets.UTF_8).trim();
                    line.reset();
                    if (!l.isEmpty()) s.addLog(l);
                } else if (line.size() < 4096) {
                    line.write(r);
                }
            }
        } catch (IOException ignored) {
        }
    }
}
