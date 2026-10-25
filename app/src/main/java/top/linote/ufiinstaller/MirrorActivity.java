package top.linote.ufiinstaller;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * ADB 投屏：通过 ADB 在设备上运行 scrcpy 服务端（scrcpy v5.0.1，Apache-2.0，随应用打包），
 * 接收 H.264 画面用手机硬件解码显示，触摸与按键回传到设备。
 * 若设备不支持（服务端启动失败、无法编码画面等），自动改用“截图模式”：循环 screencap + input 命令。
 */
public class MirrorActivity extends Activity implements SurfaceHolder.Callback {
    static final String SERVER_VERSION = ScrcpyProtocol.VERSION;
    static final String SERVER_ASSET = "scrcpy-server-v5.0.1";
    static final long SERVER_SIZE = 733930;   // scrcpy-server-v5.0.1, SHA-256 764eb6f7...bc536
    static final String REMOTE_SERVER = ScrcpyProtocol.REMOTE_JAR;
    private static final int[][] QUALITY = {{720, 2_000_000}, {1024, 4_000_000}, {1600, 8_000_000}};
    private static final String[] QUALITY_NAME = {"流畅", "标准", "高清"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Ui ui;
    private FrameLayout stage;
    private SurfaceView surfaceView;
    private ImageView shotView;
    private TextView status, modeBtn, qualityBtn;
    private final Object surfaceLock = new Object();
    private Surface surface;
    private Session session;
    private boolean shotMode;
    private int quality;
    private boolean started;   // between onStart and onStop

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(0xFF101214);
            getWindow().setNavigationBarColor(0xFF101214);
        }
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        shotMode = prefs.getBoolean("mirrorShot", false);
        quality = Math.max(0, Math.min(2, prefs.getInt("mirrorQuality", 1)));
        ui = new Ui(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101214);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(ui.dp(4), ui.dp(4), ui.dp(8), ui.dp(4));
        TextView back = barButton("‹ 返回");
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView title = new TextView(this);
        title.setText("ADB 投屏");
        title.setTextColor(0xFFE8ECEB);
        title.setTextSize(16);
        title.getPaint().setFakeBoldText(true);
        title.setPadding(ui.dp(4), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        qualityBtn = barButton("");
        qualityBtn.setOnClickListener(v -> {
            quality = (quality + 1) % QUALITY.length;
            prefs.edit().putInt("mirrorQuality", quality).apply();
            refreshBar();
            restart();
        });
        top.addView(qualityBtn);
        modeBtn = barButton("");
        modeBtn.setOnClickListener(v -> {
            shotMode = !shotMode;
            prefs.edit().putBoolean("mirrorShot", shotMode).apply();
            refreshBar();
            restart();
        });
        top.addView(modeBtn);
        root.addView(top);

        status = new TextView(this);
        status.setTextColor(0xFF9AA6A3);
        status.setTextSize(12);
        status.setMaxLines(3);
        status.setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(6));
        root.addView(status);

        stage = new FrameLayout(this);
        stage.setBackgroundColor(0xFF000000);
        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        stage.addView(surfaceView, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        shotView = new ImageView(this);
        shotView.setScaleType(ImageView.ScaleType.FIT_XY);
        shotView.setBackgroundColor(0xFF000000);
        shotView.setVisibility(View.GONE);
        stage.addView(shotView, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        stage.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> layoutStage());
        surfaceView.setOnTouchListener(this::onTouch);
        shotView.setOnTouchListener(this::onTouch);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4));
        String[] labels = {"◁ 返回", "○ 主页", "▢ 多任务"};
        int[] codes = {4, 3, 187};
        for (int i = 0; i < labels.length; i++) {
            TextView k = barButton(labels[i]);
            k.setGravity(Gravity.CENTER);
            final int code = codes[i];
            k.setOnClickListener(v -> { Session s = session; if (s != null) s.key(code); });
            nav.addView(k, new LinearLayout.LayoutParams(0, ui.dp(44), 1));
        }
        root.addView(nav);
        setContentView(root);
        refreshBar();
    }

    private TextView barButton(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFFE8ECEB);
        t.setTextSize(13.5f);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(ui.dp(40));
        t.setPadding(ui.dp(10), 0, ui.dp(10), 0);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0x00000000);
        t.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, g));
        return t;
    }

    private void refreshBar() {
        modeBtn.setText(shotMode ? "截图模式" : "流畅模式");
        qualityBtn.setText("画质：" + QUALITY_NAME[quality]);
        qualityBtn.setVisibility(shotMode ? View.GONE : View.VISIBLE);
        shotView.setVisibility(shotMode ? View.VISIBLE : View.GONE);
    }

    private void setStatus(String s) {
        main.post(() -> status.setText(s));
    }

    // ------------------------------------------------------------ lifecycle
    @Override protected void onStart() {
        super.onStart();
        started = true;
        restart();
    }

    @Override protected void onStop() {
        started = false;
        stopSession();
        super.onStop();
    }

    private void restart() {
        stopSession();
        if (!started) return;
        session = new Session(shotMode);
        session.start();
    }

    private void stopSession() {
        Session s = session;
        session = null;
        if (s != null) s.stop();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        synchronized (surfaceLock) { surface = holder.getSurface(); surfaceLock.notifyAll(); }
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        synchronized (surfaceLock) { surface = holder.getSurface(); surfaceLock.notifyAll(); }
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        synchronized (surfaceLock) { surface = null; }
        Session s = session;
        if (s != null && !s.shot) s.dropDecoder();
    }

    /** Sizes the picture to the device aspect ratio inside the stage. */
    private void layoutStage() {
        Session s = session;
        int vw = s != null ? s.vw : 0, vh = s != null ? s.vh : 0;
        int W = stage.getWidth(), H = stage.getHeight();
        if (vw <= 0 || vh <= 0 || W <= 0 || H <= 0) return;
        float sc = Math.min(W / (float) vw, H / (float) vh);
        int w = Math.max(1, Math.round(vw * sc)), h = Math.max(1, Math.round(vh * sc));
        for (View v : new View[]{surfaceView, shotView}) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            if (lp.width != w || lp.height != h) {
                lp.width = w;
                lp.height = h;
                v.setLayoutParams(lp);
            }
        }
    }

    // ------------------------------------------------------------ touch
    private float downX, downY;
    private long downT;

    private boolean onTouch(View v, MotionEvent e) {
        Session s = session;
        if (s == null || s.vw <= 0 || v.getWidth() <= 0) return true;
        if (s.shot) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX(); downY = e.getY(); downT = e.getEventTime();
                    break;
                case MotionEvent.ACTION_UP: {
                    int x0 = s.mapX(downX, v), y0 = s.mapY(downY, v), x1 = s.mapX(e.getX(), v), y1 = s.mapY(e.getY(), v);
                    long dt = e.getEventTime() - downT;
                    boolean still = Math.hypot(e.getX() - downX, e.getY() - downY) < ui.dp(12);
                    if (still && dt < 400) s.shell("input tap " + x0 + " " + y0);
                    else if (still) s.shell("input swipe " + x0 + " " + y0 + " " + x0 + " " + y0 + " " + Math.min(dt, 3000));
                    else s.shell("input swipe " + x0 + " " + y0 + " " + x1 + " " + y1 + " " + Math.max(100, Math.min(dt, 3000)));
                    break;
                }
                default:
                    break;
            }
            return true;
        }
        int am = e.getActionMasked();
        switch (am) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                s.touch(0, e, e.getActionIndex(), v);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                s.touch(1, e, e.getActionIndex(), v);
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) s.touch(2, e, i, v);
                break;
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < e.getPointerCount(); i++) s.touch(1, e, i, v);
                break;
            default:
                break;
        }
        return true;
    }

    // ------------------------------------------------------------ server file
    private File serverFile() throws IOException {
        File f = new File(getFilesDir(), SERVER_ASSET + ".jar");
        AssetManager am = getAssets();
        if (f.isFile() && f.length() == SERVER_SIZE) return f;
        File t = new File(getFilesDir(), SERVER_ASSET + ".tmp");
        try (InputStream in = am.open(SERVER_ASSET); OutputStream o = new FileOutputStream(t)) {
            byte[] b = new byte[65536];
            int r;
            while ((r = in.read(b)) > 0) o.write(b, 0, r);
        }
        if (t.length() != SERVER_SIZE || !t.renameTo(f)) throw new IOException("无法准备投屏服务端文件");
        return f;
    }

    // ------------------------------------------------------------ one mirroring session
    private final class Session implements Installer.Host {
        final boolean shot;
        volatile boolean stopped;
        volatile AdbConnection conn;
        volatile AdbConnection.Mux mux;
        volatile AdbConnection.MuxStream control;
        final LinkedBlockingQueue<byte[]> ctrl = new LinkedBlockingQueue<>();
        final ExecutorService inputExec = Executors.newSingleThreadExecutor();
        volatile int vw, vh;
        private volatile MediaCodec codec;
        private Thread outThread;
        private volatile boolean gotFrame;
        private Thread worker;
        private boolean shotActive;

        Session(boolean shot) { this.shot = shot; }

        void start() {
            worker = new Thread(this::run, "mirror");
            worker.start();
        }

        void stop() {
            stopped = true;
            AdbConnection c = conn;
            if (c != null) c.close();
            ctrl.offer(new byte[0]);
            inputExec.shutdownNow();
            if (worker != null) worker.interrupt();
        }

        @Override public void log(String s) { if (!stopped) setStatus(s); }
        @Override public void progress(String s) { if (!stopped) setStatus(s); }
        @Override public boolean cancelled() { return stopped; }
        @Override public boolean confirm(String title, String message, String yes, String no) { return false; }

        int mapX(float x, View v) { return Math.max(0, Math.min(vw - 1, Math.round(x * vw / v.getWidth()))); }
        int mapY(float y, View v) { return Math.max(0, Math.min(vh - 1, Math.round(y * vh / v.getHeight()))); }

        private void run() {
            try {
                Installer inst = new Installer(getApplicationContext(), this, Installer.Settings.fromPrefs(prefs));
                AdbConnection a = inst.connectTools();
                conn = a;
                if (stopped) { a.close(); return; }
                int api = 0;
                try { api = Integer.parseInt(inst.sh(a, "getprop ro.build.version.sdk").trim()); } catch (NumberFormatException ignored) { }
                String model = inst.sh(a, "getprop ro.product.model").trim();
                boolean useShot = shot;
                if (!useShot && api > 0 && api < 21) {
                    useShot = true;
                    setStatus("设备 Android 版本过低（API " + api + "），使用截图模式");
                }
                if (!useShot) {
                    setStatus("正在推送投屏服务端（scrcpy " + SERVER_VERSION + "）...");
                    a.push(new File[]{serverFile()}, new String[]{REMOTE_SERVER}, new int[]{0644}, new AdbConnection.Progress() {
                        @Override public void bytes(long done) { }
                        @Override public boolean cancelled() { return stopped; }
                    });
                }
                mux = a.mux();
                if (!useShot) {
                    try {
                        runScrcpy(model);
                        return;
                    } catch (IOException | RuntimeException e) {
                        if (stopped) return;
                        if (gotFrame || !mux.alive()) throw e;
                        String why = e.getMessage();
                        dropDecoder();
                        main.post(() -> shotView.setVisibility(View.VISIBLE));
                        setStatus("流畅模式启动失败（" + why + "），已改用截图模式。");
                        sleepQuiet(1500);
                    }
                }
                runShots(model);
            } catch (Throwable e) {
                if (!stopped) {
                    String m = e.getMessage();
                    setStatus("❌ 投屏已断开：" + (m == null ? e.getClass().getSimpleName() : m) + "\n可点右上角按钮切换模式重试，或返回后重新进入。");
                }
            } finally {
                stopped = true;
                dropDecoder();
                AdbConnection c = conn;
                if (c != null) c.close();
                inputExec.shutdownNow();
                ctrl.offer(new byte[0]);
            }
        }

        private void sleepQuiet(long ms) {
            try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
        }

        // ---------------- scrcpy
        private void runScrcpy(String model) throws IOException {
            String hex = ScrcpyProtocol.hex(new SecureRandom().nextInt());
            int[] q = QUALITY[quality];
            setStatus("正在启动投屏服务端...");
            ScrcpyProtocol.Session ss = ScrcpyProtocol.start(mux, hex, q[0], q[1], () -> stopped);
            control = ss.control;
            startControl(ss.control);
            setStatus("已连接" + (model.isEmpty() ? "" : " " + model) + "，等待画面...");
            try {
                ScrcpyProtocol.readVideo(ss.videoIn, new ScrcpyProtocol.VideoSink() {
                    @Override public void session(int w, int h) throws IOException { configure(w, h); }
                    @Override public void packet(byte[] d, boolean config, boolean key, long pts) {
                        feed(d, config ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0, pts);
                    }
                    @Override public boolean stopped() { return stopped; }
                });
            } catch (IOException e) {
                if (stopped) throw e;
                throw new IOException(e.getMessage() + ss.tail(), e);
            }
        }

        private void configure(int w, int h) throws IOException {
            dropDecoder();
            vw = w;
            vh = h;
            main.post(MirrorActivity.this::layoutStage);
            Surface sf;
            synchronized (surfaceLock) {
                while ((sf = surface) == null && !stopped) {
                    try { surfaceLock.wait(500); } catch (InterruptedException e) { throw new Installer.Cancelled(); }
                }
            }
            if (stopped) throw new Installer.Cancelled();
            MediaCodec c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
            try {
                if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                c.configure(f, sf, null, 0);
            } catch (RuntimeException e) {
                c.release();
                c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                c.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h), sf, null, 0);
            }
            c.start();
            codec = c;
            final MediaCodec mc = c;
            outThread = new Thread(() -> drain(mc), "decoder-out");
            outThread.setDaemon(true);
            outThread.start();
        }

        private void drain(MediaCodec c) {
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            while (!stopped && codec == c) {
                try {
                    int o = c.dequeueOutputBuffer(bi, 20000);
                    if (o >= 0) {
                        c.releaseOutputBuffer(o, true);
                        if (!gotFrame) {
                            gotFrame = true;
                            setStatus("投屏中 " + vw + "×" + vh + "（scrcpy " + SERVER_VERSION + "，画质：" + QUALITY_NAME[quality] + "）。点击画面即可操作设备。");
                        }
                    }
                } catch (RuntimeException e) {
                    return;
                }
            }
        }

        private void feed(byte[] data, int flags, long pts) {
            MediaCodec c = codec;
            if (c == null) return;
            try {
                int idx = c.dequeueInputBuffer(200000);
                if (idx < 0) return;   // decoder busy: drop
                ByteBuffer b = c.getInputBuffer(idx);
                if (b == null || b.capacity() < data.length) {
                    c.queueInputBuffer(idx, 0, 0, pts, 0);
                    return;
                }
                b.clear();
                b.put(data);
                c.queueInputBuffer(idx, 0, data.length, pts, flags);
            } catch (RuntimeException ignored) {
                // decoder was released (surface gone / new session)
            }
        }

        void dropDecoder() {
            MediaCodec c = codec;
            codec = null;
            Thread t = outThread;
            outThread = null;
            if (t != null) {
                try { t.join(300); } catch (InterruptedException ignored) { }
            }
            if (c != null) {
                try { c.stop(); } catch (RuntimeException ignored) { }
                try { c.release(); } catch (RuntimeException ignored) { }
            }
        }

        private void startControl(AdbConnection.MuxStream c) {
            Thread w = new Thread(() -> {
                try {
                    OutputStream o = c.out();
                    while (!stopped) {
                        byte[] m = ctrl.poll(1, TimeUnit.SECONDS);
                        if (m == null) continue;
                        if (m.length == 0) break;
                        o.write(m);
                    }
                } catch (IOException | InterruptedException ignored) {
                }
            }, "scrcpy-control");
            w.setDaemon(true);
            w.start();
            Thread d = new Thread(() -> {   // device messages (clipboard etc.): read and ignore
                try {
                    InputStream in = c.in();
                    byte[] buf = new byte[4096];
                    while (in.read(buf) >= 0) { /* ignore */ }
                } catch (IOException ignored) {
                }
            }, "scrcpy-devmsg");
            d.setDaemon(true);
            d.start();
        }

        void touch(int action, MotionEvent e, int i, View v) {
            if (control == null || vw <= 0) return;
            ctrl.offer(ScrcpyProtocol.touch(action, e.getPointerId(i), mapX(e.getX(i), v), mapY(e.getY(i), v), vw, vh));
        }

        void key(int code) {
            if (stopped) return;
            if (shotActive || control == null) {
                shell("input keyevent " + code);
                return;
            }
            ctrl.offer(ScrcpyProtocol.key(0, code));
            ctrl.offer(ScrcpyProtocol.key(1, code));
        }

        void shell(String cmd) {
            AdbConnection.Mux m = mux;
            if (m == null || stopped) return;
            try {
                inputExec.execute(() -> {
                    try { m.shellText(cmd, 5000); } catch (IOException ignored) { }
                });
            } catch (RuntimeException ignored) {
            }
        }

        // ---------------- screenshot mode
        private void runShots(String model) throws IOException {
            shotActive = true;
            boolean exec = true;
            long frames = 0, t0 = System.currentTimeMillis();
            while (!stopped) {
                Bitmap bm = null;
                if (exec) {
                    try {
                        bm = rawShot();
                    } catch (IOException e) {
                        if (!mux.alive()) throw e;
                        exec = false;   // no exec: service (Android < 7) or unknown pixel format: use PNG files
                    }
                }
                if (bm == null) bm = pngShot();
                if (bm == null) throw new IOException("无法获取设备截图");
                vw = bm.getWidth();
                vh = bm.getHeight();
                final Bitmap show = bm;
                main.post(() -> {
                    shotView.setImageBitmap(show);
                    layoutStage();
                });
                frames++;
                long dt = System.currentTimeMillis() - t0;
                if (frames == 1 || frames % 5 == 0) {
                    setStatus("截图模式" + (model.isEmpty() ? "" : " · " + model) + " · " + vw + "×" + vh
                            + String.format(Locale.ROOT, " · 约 %.1f 帧/秒", frames * 1000.0 / Math.max(1, dt))
                            + "。点击/滑动画面操作设备（每次操作后需等待下一帧刷新）。");
                }
            }
        }

        private byte[] readAll(AdbConnection.MuxStream s, int max) throws IOException {
            try {
                ByteArrayOutputStream o = new ByteArrayOutputStream(1 << 20);
                InputStream in = s.in();
                byte[] b = new byte[65536];
                int r;
                while ((r = in.read(b)) > 0) {
                    o.write(b, 0, r);
                    if (o.size() > max) throw new IOException("截图数据过大");
                }
                return o.toByteArray();
            } finally {
                s.close();
            }
        }

        private Bitmap rawShot() throws IOException {
            byte[] raw = readAll(mux.open("exec:screencap", 8000), 64 << 20);
            if (raw.length < 16) throw new IOException("截图数据异常");
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            int w = bb.getInt(), h = bb.getInt(), f = bb.getInt();
            int bpp = f == 4 ? 2 : (f == 1 || f == 2 || f == 5) ? 4 : 0;
            if (bpp == 0 || w <= 0 || h <= 0 || w > 8192 || h > 8192) throw new IOException("不支持的截图格式 " + f);
            long px = (long) w * h * bpp;
            long hdr = raw.length - px;
            if (hdr != 12 && hdr != 16) throw new IOException("截图数据长度异常");
            if (f == 5) {
                for (int i = (int) hdr; i + 3 < raw.length; i += 4) { byte t = raw[i]; raw[i] = raw[i + 2]; raw[i + 2] = t; }
            }
            Bitmap bm = Bitmap.createBitmap(w, h, bpp == 2 ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888);
            bm.copyPixelsFromBuffer(ByteBuffer.wrap(raw, (int) hdr, (int) px));
            if (bpp == 4) bm.setHasAlpha(false);
            return bm;
        }

        private Bitmap pngShot() throws IOException {
            String path = "/data/local/tmp/ufi-mirror-shot.png";
            mux.shellText("screencap -p " + path, 15000);
            byte[] png = pullMux(path);
            return BitmapFactory.decodeByteArray(png, 0, png.length);
        }

        /** sync RECV over a mux stream (works on every Android version). */
        private byte[] pullMux(String path) throws IOException {
            AdbConnection.MuxStream s = mux.open("sync:", 8000);
            try {
                byte[] p = path.getBytes(StandardCharsets.UTF_8);
                ByteBuffer req = ByteBuffer.allocate(8 + p.length).order(ByteOrder.LITTLE_ENDIAN);
                req.putInt(0x56434552).putInt(p.length).put(p);
                s.out().write(req.array());
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.in(), 1 << 16));
                ByteArrayOutputStream o = new ByteArrayOutputStream(1 << 20);
                byte[] hb = new byte[8];
                while (true) {
                    in.readFully(hb);
                    ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
                    int id = h.getInt(), len = h.getInt();
                    if (id == 0x454e4f44) break;                       // DONE
                    if (len < 0 || len > (1 << 20)) throw new IOException("截图拉取异常");
                    byte[] d = new byte[len];
                    in.readFully(d);
                    if (id == 0x4c494146) throw new IOException("截图拉取失败：" + new String(d, StandardCharsets.UTF_8));  // FAIL
                    if (id != 0x41544144) throw new IOException("截图拉取异常");   // DATA
                    o.write(d);
                    if (o.size() > (64 << 20)) throw new IOException("截图数据过大");
                }
                ByteBuffer quit = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(0x54495551).putInt(0);
                s.out().write(quit.array());
                return o.toByteArray();
            } finally {
                s.close();
            }
        }
    }
}
