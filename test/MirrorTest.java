package top.linote.ufiinstaller;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** JVM test of AdbConnection.pull / mux and ScrcpyProtocol against test/mock-adbd.py (127.0.0.1 only). */
public class MirrorTest {
    static int pass, fail;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("PASS: " + what); }
        else { fail++; System.out.println("FAIL: " + what); }
    }

    static String sha(byte[] d) throws Exception {
        StringBuilder b = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(d)) b.append(String.format("%02x", x));
        return b.toString();
    }

    public static void main(String[] a) throws Exception {
        int port = Integer.parseInt(a[0]);
        File work = new File(a[1]), root = new File(a[2]), mockLog = new File(a[3]), serverJar = new File(a[4]);
        AdbKey key = AdbKey.loadOrCreate(new File(work, "adbkey"));
        AdbConnection c = AdbConnection.connect("127.0.0.1", port, key, "ufi-test@pc", fp -> { });

        // pull (sync RECV) of an "installed APK"
        byte[] apk = new byte[700_000];
        new Random(7).nextBytes(apk);
        File src = new File(root, "data/app/~~x/com.example-1/base.apk");
        src.getParentFile().mkdirs();
        Files.write(src.toPath(), apk);
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        final long[] prog = {0};
        long n = c.pull("/data/app/~~x/com.example-1/base.apk", got, new AdbConnection.Progress() {
            @Override public void bytes(long done) { prog[0] = done; }
            @Override public boolean cancelled() { return false; }
        });
        check("pull returns size", n == apk.length && prog[0] == apk.length);
        check("pull content sha256", sha(got.toByteArray()).equals(sha(apk)));
        boolean failed = false;
        try { c.pull("/data/app/missing.apk", new ByteArrayOutputStream(), null); } catch (IOException e) { failed = e.getMessage().contains("失败"); }
        check("pull of missing file fails cleanly", failed);
        List<String> lines = new ArrayList<>();
        check("connection usable after FAIL", c.shell("echo still-ok", lines::add) == 0 && lines.contains("still-ok"));

        // push the bundled server, then switch to concurrent streams
        c.push(new File[]{serverJar}, new String[]{ScrcpyProtocol.REMOTE_JAR}, new int[]{0644}, new AdbConnection.Progress() {
            @Override public void bytes(long done) { }
            @Override public boolean cancelled() { return false; }
        });
        File pushed = new File(root, "data/local/tmp/ufi-scrcpy-server.jar");
        check("server jar pushed intact", pushed.isFile() && sha(Files.readAllBytes(pushed.toPath())).equals(sha(Files.readAllBytes(serverJar.toPath()))));
        AdbConnection.Mux mux = c.mux();
        String hex = ScrcpyProtocol.hex(new Random().nextInt());
        check("scid hex is 8 chars, 31-bit", hex.length() == 8 && Long.parseLong(hex, 16) < (1L << 31));
        ScrcpyProtocol.Session ss = ScrcpyProtocol.start(mux, hex, 1024, 4_000_000, () -> false);
        check("video + control sockets opened after server start (2 refusals retried)", ss.video != null && ss.control != null);

        List<String> events = new ArrayList<>();
        final int[] packets = {0};
        Thread vt = new Thread(() -> {
            try {
                ScrcpyProtocol.readVideo(ss.videoIn, new ScrcpyProtocol.VideoSink() {
                    @Override public void session(int w, int h) { synchronized (events) { events.add("session " + w + "x" + h); } }
                    @Override public void packet(byte[] d, boolean config, boolean kf, long pts) {
                        synchronized (events) { events.add((config ? "config" : kf ? "key" : "frame") + " " + d.length + " pts=" + pts); }
                        packets[0]++;
                    }
                    @Override public boolean stopped() { return packets[0] >= 6; }
                });
            } catch (IOException e) {
                synchronized (events) { events.add("error " + e.getMessage()); }
            }
        });
        vt.start();
        // a second shell stream while video is streaming
        String echo = mux.shellText("echo concurrent", 5000);
        check("concurrent shell stream during video", echo.trim().equals("concurrent"));
        vt.join(10000);
        String ev = String.join(" | ", events);
        System.out.println("  video events: " + ev);
        check("video stream parsed: sessions, config, key and delta frames", ev.equals(
                "session 720x1280 | config 16 pts=0 | key 300005 pts=1000 | frame 5005 pts=34000 | frame 5005 pts=67000"
                        + " | session 1280x720 | config 9 pts=0 | key 1005 pts=100000"));

        ss.control.out().write(ScrcpyProtocol.touch(0, 0, 100, 200, 720, 1280));
        ss.control.out().write(ScrcpyProtocol.touch(2, 1, 719, 1279, 720, 1280));
        ss.control.out().write(ScrcpyProtocol.touch(1, 0, 100, 200, 720, 1280));
        ss.control.out().write(ScrcpyProtocol.key(0, 4));
        ss.control.out().write(ScrcpyProtocol.key(1, 4));
        Thread.sleep(800);
        String log = new String(Files.readAllBytes(mockLog.toPath()), "UTF-8");
        check("server command accepted by mock", log.contains("scrcpy-server: CLASSPATH=/data/local/tmp/ufi-scrcpy-server.jar app_process"));
        check("two refusals before listening", log.split("refused localabstract:scrcpy_" + hex, -1).length - 1 == 2);
        check("touch down decoded", log.contains("ctrl touch action=0 id=0 x=100 y=200 size=720x1280 pressure=65535"));
        check("touch move decoded", log.contains("ctrl touch action=2 id=1 x=719 y=1279 size=720x1280 pressure=65535"));
        check("touch up decoded (pressure 0)", log.contains("ctrl touch action=1 id=0 x=100 y=200 size=720x1280 pressure=0"));
        check("key back down/up decoded", log.contains("ctrl key action=0 code=4") && log.contains("ctrl key action=1 code=4"));
        check("no protocol errors", !log.contains("PROTOCOL-ERROR") && !log.contains("Traceback"));
        c.close();
        Thread.sleep(500);
        log = new String(Files.readAllBytes(mockLog.toPath()), "UTF-8");
        check("server shell closed when the connection closes", log.contains("scrcpy-server: shell closed by client") || log.contains("client closed"));
        boolean dead = false;
        try { mux.open("shell:echo x", 1000); } catch (IOException e) { dead = true; }
        check("mux reports closed connection", dead && !mux.alive());
        System.out.println(pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
