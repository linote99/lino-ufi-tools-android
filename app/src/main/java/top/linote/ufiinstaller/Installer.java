package top.linote.ufiinstaller;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The Windows "UFI-TOOLS 一键安装器" flow, step for step, with this phone as the ADB host:
 * get APK -> make sure ADB is reachable (USB OTG or Wi-Fi ADB; switch it on through the
 * stock web backend if needed) -> install -> grant permissions / keep-alive settings ->
 * start the app, check it runs -> reboot.
 */
final class Installer {
    static final String PKG = ApkSource.PACKAGE;
    static final String REMOTE_APK = "/data/local/tmp/UFI-TOOLS-install.apk";

    interface Host {
        void log(String s);
        /** Replaces the last progress line (download / push). */
        void progress(String s);
        boolean cancelled();
        /** Blocking yes/no question shown on the UI thread. */
        boolean confirm(String title, String message, String yes, String no);
        /** Blocking multiple choice; returns the new checked states, or null when cancelled. */
        default boolean[] choose(String title, String[] items, boolean[] checked) { return null; }
    }

    static final class Settings {
        String ip = "192.168.0.1";
        int webPort = 8080;
        String password = ZteWeb.DEFAULT_PASSWORD;
        int connMode;          // 0 auto (USB first, then Wi-Fi ADB), 1 USB only, 2 Wi-Fi ADB only
        int adbPort = 5555;
        int sourceMode;        // 0 latest from server, 1 direct link, 2 local file
        String server = ApkSource.DEFAULT_SERVER;
        String dir = ApkSource.DEFAULT_DIR;
        String customUrl = "";
        File localApk;         // imported copy of the picked file
        boolean autoReboot = true;
        // ADB tools (mirroring, backup, restore): own address, direct ADB only (never the web backend)
        String toolsIp = "192.168.0.1";
        int toolsPort = 5555;
        int toolsConn;         // 0 wireless ADB to toolsIp:toolsPort, 1 USB OTG

        /** The settings saved by the main screen (same keys as MainActivity.save()). */
        static Settings fromPrefs(android.content.SharedPreferences p) {
            Settings s = new Settings();
            s.ip = p.getString("ip", s.ip);
            if (s.ip.trim().isEmpty()) s.ip = "192.168.0.1";
            s.webPort = p.getInt("webPort", 8080);
            s.password = p.getString("pwd", ZteWeb.DEFAULT_PASSWORD);
            if (s.password.isEmpty()) s.password = ZteWeb.DEFAULT_PASSWORD;
            s.connMode = Math.max(0, Math.min(2, p.getInt("conn", 0)));
            s.autoReboot = p.getBoolean("reboot", true);
            s.sourceMode = Math.max(0, Math.min(2, p.getInt("src", 0)));
            s.server = p.getString("server", "").trim();
            if (s.server.isEmpty()) s.server = ApkSource.DEFAULT_SERVER;
            s.dir = p.getString("dir", "").trim();
            if (s.dir.isEmpty()) s.dir = ApkSource.DEFAULT_DIR;
            s.customUrl = p.getString("url", "").trim();
            String lp = p.getString("local", "");
            s.localApk = lp.isEmpty() ? null : new File(lp);
            if (s.webPort < 1 || s.webPort > 65535) s.webPort = 8080;
            s.toolsIp = p.getString("toolsIp", s.toolsIp).trim();
            if (s.toolsIp.isEmpty()) s.toolsIp = "192.168.0.1";
            s.toolsPort = p.getInt("toolsPort", 5555);
            if (s.toolsPort < 1 || s.toolsPort > 65535) s.toolsPort = 5555;
            s.toolsConn = Math.max(0, Math.min(1, p.getInt("toolsConn", 0)));
            return s;
        }
    }

    static final class Cancelled extends IOException { Cancelled() { super("已停止"); } }

    private final Context app;
    private final Host h;
    private final Settings s;
    private final File apkDir;

    Installer(Context app, Host host, Settings settings) {
        this.app = app;
        this.h = host;
        this.s = settings;
        apkDir = new File(app.getFilesDir(), "apk");
        apkDir.mkdirs();
    }

    void check() throws Cancelled { if (h.cancelled()) throw new Cancelled(); }

    void sleep(long ms) throws Cancelled {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            check();
            try { Thread.sleep(Math.min(200, Math.max(1, end - System.currentTimeMillis()))); } catch (InterruptedException e) { throw new Cancelled(); }
        }
    }

    // ------------------------------------------------------------ network
    /** The Wi-Fi network (the UFI hotspot), so device traffic does not go out over mobile data. */
    Network wifi() {
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private boolean tcpOpen(String host, int port, int ms) {
        Network n = wifi();
        try (Socket so = n != null ? n.getSocketFactory().createSocket() : new Socket()) {
            so.connect(new InetSocketAddress(host, port), ms);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------ step 1: APK
    File prepareApk() throws IOException {
        File f;
        if (s.sourceMode == 2) {
            if (s.localApk == null || !s.localApk.isFile()) throw new IOException("请先选择本地 APK 文件");
            f = s.localApk;
            h.log("📦 使用本地 APK：" + f.getName());
        } else {
            String url, name;
            long size = -1;
            if (s.sourceMode == 1) {
                url = s.customUrl.trim();
                if (!url.startsWith("https://") && !url.startsWith("http://")) throw new IOException("自定义下载链接必须以 http:// 或 https:// 开头");
                if (url.startsWith("http://") && !h.confirm("未加密的下载链接",
                        "该链接使用 HTTP，下载内容可能被网络中间方替换。仍要继续吗？", "继续", "取消")) throw new Cancelled();
                name = ApkSource.nameFromUrl(url);
                h.log("🔗 使用自定义下载链接：" + url);
            } else {
                h.log("🔍 正在获取最新版 APK 信息...（" + ApkSource.trimSlash(s.server) + "，目录 " + ApkSource.normDir(s.dir) + "）");
                ApkSource.Remote r;
                try {
                    r = ApkSource.latest(s.server, s.dir);
                } catch (IOException e) {
                    h.log("❌ 获取 APK 列表失败：" + e.getMessage());
                    File cached = newestCached();
                    if (cached == null) throw new IOException("获取 APK 列表失败，且本机没有已下载的安装包。可改用“自定义下载链接”或“本地 APK 文件”");
                    h.log("📦 改用本机已下载的安装包：" + cached.getName());
                    return checked(cached);
                }
                url = r.url;
                name = ApkSource.safeName(r.name);
                size = r.size;
                h.log("✅ 最新 APK：" + r.name + (size > 0 ? "（" + mb(size) + "）" : ""));
            }
            f = new File(apkDir, name);
            boolean download = true;
            if (f.isFile()) {
                boolean ok = true;
                try { ApkSource.verifyZip(f); } catch (IOException e) { ok = false; }
                if (ok && !h.confirm("文件已存在", "本机已下载过 " + name + "。\n重新下载并覆盖，还是使用已下载的安装包？", "重新下载", "使用已下载的")) download = false;
            }
            if (download) {
                check();
                h.log("⬇️ 开始下载 APK...");
                h.progress("进度: 0%");
                final long expect = size;
                ApkSource.download(url, f, new ApkSource.Progress() {
                    @Override public void update(long done, long total) {
                        long t = total > 0 ? total : expect;
                        h.progress(t > 0 ? "进度: " + Math.min(100, done * 100 / t) + "%（" + mb(done) + " / " + mb(t) + "）" : "已下载 " + mb(done));
                    }
                    @Override public boolean cancelled() { return h.cancelled(); }
                });
                h.log("✅ 下载完成：" + f.getName());
            }
        }
        return checked(f);
    }

    private File newestCached() {
        File[] l = apkDir.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".apk"));
        File best = null;
        if (l != null) for (File x : l) if (best == null || x.lastModified() > best.lastModified()) best = x;
        return best;
    }

    private File checked(File f) throws IOException {
        try {
            ApkSource.verifyZip(f);
        } catch (IOException e) {
            throw new IOException("APK 校验失败（文件损坏或不完整）：" + e.getMessage());
        }
        String[] info = ApkSource.packageInfo(app, f);
        String sha = ApkSource.sha256(f);
        h.log("APK：" + f.getName() + "，" + mb(f.length()) + (info != null ? "，包名 " + info[0] + "，版本 " + info[1] + "（" + info[2] + "）" : ""));
        h.log("SHA-256：" + sha);
        if (!ApkSource.isMinikano(info)) {
            String what = info == null ? "本机无法解析该 APK 的包名" : "该 APK 的包名是 " + info[0] + "，不是 UFI-TOOLS（" + PKG + "）";
            if (!h.confirm("安装包不是 UFI-TOOLS", what + "。\n后续的授权、保活和启动步骤都针对 " + PKG + "。仍要安装吗？", "仍要安装", "取消"))
                throw new Cancelled();
        }
        return f;
    }

    boolean autoReboot() { return s.autoReboot; }

    static String mb(long b) { return String.format(java.util.Locale.ROOT, "%.1f MB", b / 1048576.0); }

    // ------------------------------------------------------------ step 2: ADB
    private AdbKey key() throws IOException { return AdbKey.loadOrCreate(new File(app.getFilesDir(), "adbkey")); }

    private void onAuth(String fp) { h.log("请在设备上允许 USB 调试授权（本机密钥指纹 " + fp + "）……"); }

    private static final String ACTION_USB = "top.linote.ufiinstaller.USB_PERMISSION";

    private UsbManager usb() { return (UsbManager) app.getSystemService(Context.USB_SERVICE); }

    private boolean askUsbPermission(UsbDevice d) throws Cancelled {
        UsbManager um = usb();
        if (um.hasPermission(d)) return true;
        h.log("请在系统弹窗中允许本应用访问 USB 设备……");
        final boolean[] granted = {false};
        CountDownLatch latch = new CountDownLatch(1);
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                granted[0] = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                latch.countDown();
            }
        };
        IntentFilter f = new IntentFilter(ACTION_USB);
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(r, f);
        try {
            Intent pi = new Intent(ACTION_USB).setPackage(app.getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            um.requestPermission(d, PendingIntent.getBroadcast(app, 7, pi, flags | PendingIntent.FLAG_UPDATE_CURRENT));
            for (int i = 0; i < 300; i++) {   // up to 60 s
                check();
                try { if (latch.await(200, TimeUnit.MILLISECONDS)) break; } catch (InterruptedException e) { throw new Cancelled(); }
            }
        } finally {
            try { app.unregisterReceiver(r); } catch (RuntimeException ignored) { }
        }
        return granted[0] || um.hasPermission(d);
    }

    private AdbConnection connectUsb(UsbAdbTransport.Candidate c) throws IOException {
        if (!askUsbPermission(c.dev)) throw new IOException("未获得 USB 访问权限");
        AdbConnection.abort = h::cancelled;
        AdbConnection a = AdbConnection.connect(UsbAdbTransport.open(usb(), c.dev), "USB " + c.label, key(), "ufi-installer@android", this::onAuth);
        h.log("✅ 已通过 USB OTG 连接：" + c.label);
        return a;
    }

    private AdbConnection connectTcp() throws IOException { return connectTcp(s.ip, s.adbPort); }

    private AdbConnection connectTcp(String host, int port) throws IOException {
        Network n = wifi();
        AdbConnection.abort = h::cancelled;
        AdbConnection a = AdbConnection.connect(host, port, n != null ? n.getSocketFactory() : null, key(), "ufi-installer@android", this::onAuth);
        h.log("✅ 已连接无线 ADB：" + a.endpoint());
        return a;
    }

    /** One look for an ADB device that is already there (like "adb devices"); null when none. */
    private AdbConnection tryExisting(boolean quiet) throws IOException {
        if (s.connMode != 2) {
            List<UsbAdbTransport.Candidate> l = UsbAdbTransport.list(usb());
            if (!l.isEmpty()) {
                try {
                    return connectUsb(l.get(0));
                } catch (Cancelled e) {
                    throw e;
                } catch (IOException e) {
                    if (s.connMode == 1) throw e;
                    h.log("USB 连接失败：" + e.getMessage());
                }
            }
        }
        if (s.connMode != 1 && tcpOpen(s.ip, s.adbPort, 1500)) {
            try {
                return connectTcp();
            } catch (Cancelled e) {
                throw e;
            } catch (IOException e) {
                if (!quiet) h.log("无线 ADB 连接失败：" + e.getMessage());
            }
        }
        return null;
    }

    /**
     * ADB tools (mirroring, backup, restore): connect straight to their own address (default port 5555) or
     * over USB OTG. Never logs into the web backend, so no backend password is needed.
     */
    AdbConnection connectTools() throws IOException {
        check();
        if (s.toolsConn == 1) {
            h.log("🔌 正在查找 USB OTG 的 ADB 设备...");
            List<UsbAdbTransport.Candidate> l = UsbAdbTransport.list(usb());
            if (l.isEmpty()) throw new IOException("未检测到 USB OTG 的 ADB 设备。请用 OTG 线连接设备，并确认设备已开启 ADB（可先点“仅开启 ADB”）");
            return connectUsb(l.get(0));
        }
        String where = s.toolsIp.contains(":") ? "[" + s.toolsIp + "]:" + s.toolsPort : s.toolsIp + ":" + s.toolsPort;
        h.log("🔌 正在直连无线 ADB " + where + "（不登录后台，无需密码）...");
        if (!tcpOpen(s.toolsIp, s.toolsPort, 3000)) {
            throw new IOException("无法连接 " + where + "。请确认：手机与设备在同一网络（已连上设备热点）、地址和端口正确、设备已开启无线 ADB"
                    + "（可先点“仅开启 ADB”或完成一键安装）。");
        }
        check();
        return connectTcp(s.toolsIp, s.toolsPort);
    }

    AdbConnection connect() throws IOException {
        h.log("🔌 正在查找 ADB 设备（" + new String[]{"自动：先 USB OTG，后无线 ADB", "仅 USB OTG", "仅无线 ADB"}[s.connMode] + "）...");
        AdbConnection a = tryExisting(false);
        if (a != null) return a;
        check();
        h.log("⚠️ 未检测到任何 ADB 设备，正在打开设备的ADB...");
        boolean opened = false;
        try {
            enableAdbViaWeb();
            opened = true;
            h.log("✅ ADB打开成功，等待ADB端口出现...");
        } catch (Cancelled e) {
            throw e;
        } catch (IOException e) {
            h.log("⚠️ ADB请求打开失败：" + e.getMessage());
        }
        if (opened) {
            for (int i = 0; i < 20; i++) {   // ~30 s for adbd / USB re-enumeration
                sleep(1500);
                a = tryExisting(true);
                if (a != null) return a;
            }
            h.log("等待 ADB 端口超时。");
        }
        if (s.connMode != 1) {
            h.log("⚠️ 正在尝试使用 " + s.ip + ":" + s.adbPort + " 连接无线ADB...");
            try {
                return connectTcp();
            } catch (Cancelled e) {
                throw e;
            } catch (IOException e) {
                h.log("无线ADB尝试打开失败：" + e.getMessage());
            }
        }
        throw new IOException("程序无法打开ADB，请手动打开，然后重试：http://" + s.ip + "/index.html#usb_port"
                + (s.connMode != 2 ? "\n使用 USB 时请确认本机支持 OTG，且已用 OTG 线连接设备" : ""));
    }

    /** USB_PORT_SETTING usb_port_switch=1 through the stock web backend, as zreq.exe does. */
    void enableAdbViaWeb() throws IOException {
        Network n = wifi();
        if (n == null) h.log("提示：本机未连接 Wi-Fi，请先连接随身 WiFi 的热点");
        h.log("登录设备后台 http://" + s.ip + ":" + s.webPort + " 并开启 ADB...");
        String r = new ZteWeb(s.ip, s.webPort, n).enableAdb(s.password);
        h.log("后台返回：" + ZteWeb.brief(r));
    }

    // ------------------------------------------------------------ step 3+: install and configure
    String sh(AdbConnection a, String cmd) throws IOException {
        check();
        h.log("$ " + cmd);
        StringBuilder b = new StringBuilder();
        a.shell(cmd, l -> { b.append(l).append('\n'); if (!l.trim().isEmpty()) h.log("  " + l); }, 120000);
        return b.toString();
    }

    private void keepScreenAwake(AdbConnection a) throws IOException {
        sh(a, "input keyevent 224");
        sleep(300);
        sh(a, "svc power stayon true");
        sleep(300);
        sh(a, "svc power stayon usb");
        sleep(300);
    }

    void run() throws IOException {
        File apk = prepareApk();
        check();
        try (AdbConnection a = connect()) {
            String model = sh(a, "getprop ro.product.model; getprop ro.build.display.id; getprop ro.build.version.release").trim();
            if (!model.isEmpty()) h.log("设备：" + model.replace("\n", " / "));
            boolean installed = sh(a, "pm path " + PKG).contains("package:");
            h.log(installed ? "📱 正在更新 APK..." : "📱 正在安装 APK...");
            long total = apk.length();
            h.progress("推送: 0%");
            a.push(new File[]{apk}, new String[]{REMOTE_APK}, new int[]{0644}, new AdbConnection.Progress() {
                long last;
                @Override public void bytes(long done) {
                    long now = System.currentTimeMillis();
                    if (now - last > 300 || done == total) { last = now; h.progress("推送: " + (done * 100 / Math.max(1, total)) + "%（" + mb(done) + " / " + mb(total) + "）"); }
                }
                @Override public boolean cancelled() { return h.cancelled(); }
            });
            String out = sh(a, "pm install -r " + REMOTE_APK);
            sh(a, "rm -f " + REMOTE_APK);
            if (!out.contains("Success")) {
                throw new IOException("❌ 安装失败：" + ZteWeb.brief(out) + "\n❌ 可能的原因：\n1.系统版本过高，ADB功能被官方禁用，请尝试降级系统版本"
                        + "（例如F50需要降级到B15以下版本，U30亚太需要降级到B19以下版本，U30普通版需要B25以下版本，V50需要B21以下版本）"
                        + "\n2.安装包与设备上已装版本签名不同（可先在设备上卸载旧版）\n3.检查设备是否仍连接着本机？");
            }
            h.log("✅ 安装完成");
            configure(a);
        }
    }

    /** After installing UFI-TOOLS: permissions, keep-alive settings, start it, check it runs, reboot (as the desktop installer). */
    void configure(AdbConnection a) throws IOException {
        {
            keepScreenAwake(a);
            h.log("🔐 正在授权权限...");
            for (String p : new String[]{"READ_SMS", "RECEIVE_SMS", "SEND_SMS", "READ_EXTERNAL_STORAGE",
                    "WRITE_EXTERNAL_STORAGE", "READ_PHONE_STATE", "POST_NOTIFICATIONS"}) {
                sh(a, "pm grant " + PKG + " android.permission." + p);
            }
            for (String op : new String[]{"GET_USAGE_STATS", "android:get_usage_stats", "POST_NOTIFICATION"}) {
                sh(a, "appops set " + PKG + " " + op + " allow");
            }
            sh(a, "settings put secure enabled_notification_listeners " + PKG + "/" + PKG + ".MyListenerService");
            keepScreenAwake(a);
            sh(a, "dumpsys deviceidle whitelist +" + PKG);
            sh(a, "cmd app_hibernation set-state " + PKG + " false");
            sh(a, "cmd package set-hibernating " + PKG + " false");
            sh(a, "cmd package set-auto-revoke-permissions " + PKG + " false");
            sh(a, "cmd package set-auto-revoke-permissions-enabled " + PKG + " false");
            sh(a, "appops set " + PKG + " AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore");
            String uid = sh(a, "dumpsys package " + PKG + " 2>/dev/null | grep -m1 userId= | cut -d= -f2").trim();
            h.log("UID:" + uid);
            sh(a, "appops set --uid $(dumpsys package " + PKG + " 2>/dev/null | grep -m1 userId= | cut -d= -f2) AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore");
            sleep(500);
            h.log("🚀 启动应用...");
            String st = sh(a, "am start -n " + PKG + "/.MainActivity");
            if (st.contains("Error")) h.log("❌ 启动失败：" + ZteWeb.brief(st));
            h.log("同步缓存....");
            sh(a, "sync");
            h.log("dexopt缓存建立中....");
            sh(a, "cmd package compile -m speed-profile -f " + PKG);
            sh(a, "cmd package set-stopped-state " + PKG + " false");
            h.log("🚀 再次启动应用...");
            sh(a, "am start -n " + PKG + "/.MainActivity");
            h.log("检测应用是否已启动....");
            boolean running = false;
            for (int i = 0; i < 10 && !running; i++) {
                sleep(i == 0 ? 500 : 1000);
                StringBuilder b = new StringBuilder();
                a.shell("dumpsys package " + PKG, l -> b.append(l).append('\n'), 60000);
                running = b.indexOf("stopped=false") >= 0;
            }
            String web = "http://" + s.ip + ":2333";
            if (!running) throw new IOException("等待UFI-TOOLS启动失败，请手动访问" + web + "查看是否安装成功");
            if (!s.autoReboot) {
                h.log("✅ app启动成功，现在请连接WiFi使用" + web + "访问UFI-TOOLS（已按设置跳过自动重启，建议稍后在后台手动重启设备）");
                return;
            }
            h.log("app启动成功，现在请连接WiFi使用" + web + "访问UFI-TOOLS，15s后会自动重启设备应用更改~");
            sleep(15000);
            a.reboot();
            h.log("✅ 操作完成！设备自动重启中,重启后可使用" + web + "访问UFI-TOOLS~");
        }
    }
}
