package top.linote.ufiinstaller;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class MainActivity extends Activity {
    private static final int PICK_RESTORE = 42, PICK_TREE = 43;
    private static final int MAX_LOG_LINES = 600;

    private Ui ui;
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    private CheckBox rebootBox;
    private TextView logView, statusView;
    private TextView startBtn, adbBtn, stopBtn, mirrorBtn, backupBtn, restoreBtn, backupLabel;
    private TextView toolsSummary;
    private int titleTaps;
    private long lastTitleTap;
    private Toast tapToast;
    private boolean pendingBackup;
    private ScrollView scroll;

    private final List<String> lines = new ArrayList<>();
    private boolean lastIsProgress;
    private volatile boolean cancel;
    private volatile Thread worker;

    // ------------------------------------------------------------ lifecycle
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Ui(this);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        getWindow().getDecorView().setBackgroundColor(ui.bg);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(ui.bg);
            if (!ui.dark && Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        setContentView(build());
        load();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        log(getString(R.string.app_name) + " " + BuildInfo.VERSION_NAME + " 已就绪。手机请先连上随身 WiFi 的热点；用 USB 时再用 OTG 线连接设备。");
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    // ------------------------------------------------------------ layout
    private View build() {
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = ui.col();
        root.setPadding(ui.dp(16), ui.dp(20), ui.dp(16), ui.dp(28));
        scroll.addView(root);

        LinearLayout head = ui.row();
        head.setGravity(Gravity.TOP);
        LinearLayout ht = ui.col();
        ht.addView(ui.overline("UFI-TOOLS · ANDROID"));
        TextView title = ui.h1(getString(R.string.app_name));
        title.setOnClickListener(v -> onTitleTap());
        ht.addView(title);
        head.addView(ht, ui.weight(1));
        TextView about = ui.button("ⓘ", 0, v -> startActivity(new Intent(this, AboutActivity.class)));
        about.setPadding(0, 0, 0, 0);
        about.setContentDescription("关于与使用说明");
        head.addView(about, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(40)));
        root.addView(head);
        root.addView(ui.sub("在手机上完成 UFI-TOOLS 一键安装（开启 ADB、安装、授权、保活、启动并重启），并提供 ADB 投屏与安装包备份/恢复。版本 " + BuildInfo.VERSION_NAME));

        root.addView(ui.notice("i", "连接方式",
                "手机连上随身 WiFi 的热点（用于登录后台开启 ADB、无线 ADB 和访问 2333 端口）。"
                        + "无线 ADB 不通时，用 OTG 线把随身 WiFi 接到手机，走 USB 调试。", 0), ui.mw(14));

        rebootBox = check("完成后 15 秒自动重启设备（与电脑版相同）", true);
        root.addView(rebootBox, ui.mw(10));

        startBtn = ui.button("开始一键安装UFI-TOOLS", 1, v -> start(false));
        root.addView(startBtn, ui.mw(10));
        LinearLayout r2 = ui.row();
        adbBtn = ui.button("仅开启 ADB", 0, v -> start(true));
        stopBtn = ui.button("停止", 2, v -> stop());
        stopBtn.setEnabled(false);
        r2.addView(adbBtn, ui.weight(1));
        LinearLayout.LayoutParams sp = ui.weight(1);
        sp.leftMargin = ui.dp(10);
        r2.addView(stopBtn, sp);
        root.addView(r2, ui.mw(10));
        LinearLayout r3 = ui.row();
        TextView openWeb = ui.button("打开 UFI-TOOLS", 0, v -> openUrl("http://" + ip() + ":2333"));
        TextView openUsb = ui.button("后台 USB 设置", 0, v -> openUrl("http://" + ip() + "/index.html#usb_port"));
        r3.addView(openWeb, ui.weight(1));
        LinearLayout.LayoutParams op = ui.weight(1);
        op.leftMargin = ui.dp(10);
        r3.addView(openUsb, op);
        root.addView(r3, ui.mw(10));

        // ADB tools card
        LinearLayout tools = ui.card();
        tools.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(14));
        LinearLayout th = ui.row();
        th.setGravity(Gravity.CENTER_VERTICAL);
        th.addView(ui.text("ADB 工具", 15, ui.text, true), ui.weight(1));
        TextView gear = ui.button("⚙ 设置", 3, v -> startActivity(SettingsActivity.intent(this, SettingsActivity.MODE_TOOLS)));
        gear.setMinHeight(ui.dp(32));
        gear.setPadding(ui.dp(10), ui.dp(4), ui.dp(4), ui.dp(4));
        gear.setTextColor(ui.primary);
        gear.setContentDescription("ADB 工具设置");
        th.addView(gear);
        tools.addView(th);
        toolsSummary = ui.sub("");
        toolsSummary.setTextSize(12.5f);
        tools.addView(toolsSummary, ui.mw(2));
        mirrorBtn = ui.button("ADB 投屏（查看并操作设备屏幕）", 0, v -> openMirror());
        tools.addView(mirrorBtn, ui.mw(10));
        LinearLayout r4 = ui.row();
        backupBtn = ui.button("备份安装包", 0, v -> backup());
        restoreBtn = ui.button("恢复安装包", 0, v -> pickRestore());
        r4.addView(backupBtn, ui.weight(1));
        LinearLayout.LayoutParams bp = ui.weight(1);
        bp.leftMargin = ui.dp(10);
        r4.addView(restoreBtn, bp);
        tools.addView(r4, ui.mw(10));
        LinearLayout r5 = ui.row();
        r5.setGravity(Gravity.CENTER_VERTICAL);
        backupLabel = ui.sub("");
        backupLabel.setTextSize(12);
        r5.addView(backupLabel, ui.weight(1));
        TextView chg = ui.button("更改备份位置", 3, v -> { pendingBackup = false; pickTree(); });
        chg.setMinHeight(ui.dp(32));
        chg.setPadding(ui.dp(8), ui.dp(4), 0, ui.dp(4));
        chg.setTextColor(ui.primary);
        r5.addView(chg);
        tools.addView(r5, ui.mw(6));
        root.addView(tools, ui.mw(14));

        // Log card
        LinearLayout logCard = ui.card();
        logCard.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        LinearLayout lh = ui.row();
        lh.addView(ui.text("运行日志", 15, ui.text, true), ui.weight(1));
        TextView copy = ui.button("复制", 3, v -> copyLog());
        copy.setMinHeight(ui.dp(32));
        copy.setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4));
        copy.setTextColor(ui.primary);
        lh.addView(copy);
        logCard.addView(lh);
        statusView = ui.dot("空闲", ui.muted);
        logCard.addView(statusView, ui.mw(4));
        logView = ui.mono("");
        logView.setEllipsize(null);
        logView.setBackground(ui.round(ui.logBg, 0, 8));
        logView.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        logView.setMinHeight(ui.dp(160));
        logCard.addView(logView, ui.mw(8));
        root.addView(logCard, ui.mw(16));

        TextView aboutLink = ui.button("关于 · 使用说明与注意事项", 3, v -> startActivity(new Intent(this, AboutActivity.class)));
        aboutLink.setTextColor(ui.primary);
        root.addView(aboutLink, ui.mw(10));
        TextView foot = ui.sub("UFI-TOOLS 开源地址：https://github.com/kanoqwq/UFI-TOOLS（作者 MiniKano）。本应用的一键安装为其电脑版一键安装器的安卓移植；ADB 投屏使用开源项目 scrcpy " + MirrorActivity.SERVER_VERSION + " 的设备端组件（Apache-2.0）。");
        foot.setTextSize(11.5f);
        root.addView(foot, ui.mw(14));
        return scroll;
    }




    private CheckBox check(String s, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setTextSize(13.5f);
        c.setTextColor(ui.text);
        c.setChecked(on);
        if (Build.VERSION.SDK_INT >= 21) c.setButtonTintList(android.content.res.ColorStateList.valueOf(ui.primary));
        return c;
    }



    // ------------------------------------------------------------ settings
    private void load() {
        rebootBox.setChecked(prefs.getBoolean("reboot", true));
        refreshBackupLabel();
        refreshToolsSummary();
    }

    private void save() {
        prefs.edit().putBoolean("reboot", rebootBox.isChecked()).apply();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshToolsSummary();
    }

    private void refreshToolsSummary() {
        Installer.Settings s = Installer.Settings.fromPrefs(prefs);
        String where = s.toolsConn == 1 ? "USB OTG"
                : "无线 ADB " + (s.toolsIp.contains(":") ? "[" + s.toolsIp + "]" : s.toolsIp) + ":" + s.toolsPort;
        toolsSummary.setText("直连 " + where + "，不登录后台、无需密码。设备需已开启 ADB（可先点“仅开启 ADB”）。");
    }

    private String ip() { return Installer.Settings.fromPrefs(prefs).ip.trim(); }

    /** Saved settings (advanced page + ADB tools page), validated for a task. */
    private Installer.Settings settings() throws IOException {
        Installer.Settings s = Installer.Settings.fromPrefs(prefs);
        s.ip = s.ip.trim();
        if (!s.ip.matches("[A-Za-z0-9.\\-]{1,253}")) throw new IOException("设备地址格式不正确：" + s.ip + "（连续点击标题 5 次进入高级设置修改）");
        if (s.sourceMode == 0 && !s.server.startsWith("https://") && !s.server.startsWith("http://")) throw new IOException("下载服务器必须以 http:// 或 https:// 开头（在高级设置中修改）");
        if (s.sourceMode == 2 && (s.localApk == null || !s.localApk.isFile())) s.localApk = null;
        s.autoReboot = rebootBox.isChecked();
        return s;
    }

    // ------------------------------------------------------------ actions
    interface Task { void run(Installer inst) throws IOException; }

    private void start(boolean adbOnly) {
        runTask(adbOnly ? "正在开启 ADB" : "正在安装", adbOnly ? "正在开启 ADB" : "正在安装 UFI-TOOLS", (inst) -> {
            if (adbOnly) {
                log("—— 仅开启 ADB ——");
                inst.enableAdbViaWeb();
                log("✅ ADB 已开启。用 OTG 线连接时，设备会以 USB 调试设备出现；也可尝试无线 ADB " + ip() + ":5555");
            } else {
                log("—— 开始一键安装UFI-TOOLS ——");
                inst.run();
            }
        });
    }

    private void runTask(String busy, String notif, Task task) {
        if (worker != null) { toast("请等待当前任务结束"); return; }
        Installer.Settings s;
        try {
            s = settings();
        } catch (IOException e) {
            toast(e.getMessage());
            return;
        }
        save();
        cancel = false;
        setBusy(true, busy);
        InstallService.start(this, notif);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Installer inst = new Installer(getApplicationContext(), host, s);
        Thread t = new Thread(() -> {
            String end;
            int color;
            try {
                task.run(inst);
                end = "完成";
                color = ui.primary;
            } catch (Installer.Cancelled e) {
                log("已停止。");
                end = "已停止";
                color = ui.muted;
            } catch (IOException | RuntimeException e) {
                String m = e.getMessage() == null ? e.toString() : e.getMessage();
                log(m.startsWith("❌") ? m : "❌ " + m);
                end = "失败";
                color = ui.danger;
            }
            final String fe = end;
            final int fc = color;
            main.post(() -> {
                worker = null;
                setBusy(false, null);
                setStatus(fe, fc);
                InstallService.stop(this);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }, "installer");
        worker = t;
        t.start();
    }

    private void stop() {
        cancel = true;
        setStatus("正在停止…", ui.muted);
    }

    private void setBusy(boolean busy, String what) {
        startBtn.setEnabled(!busy);
        adbBtn.setEnabled(!busy);
        for (View v : new View[]{mirrorBtn, backupBtn, restoreBtn}) v.setEnabled(!busy);
        stopBtn.setEnabled(busy);
        rebootBox.setEnabled(!busy);
        if (busy) setStatus(what, ui.primary);
    }

    private void setStatus(String s, int color) {
        TextView t = ui.dot(s, color);
        statusView.setText(t.getText());
    }

    private final Installer.Host host = new Installer.Host() {
        @Override public void log(String s) { MainActivity.this.log(s); }
        @Override public void progress(String s) { MainActivity.this.progress(s); }
        @Override public boolean cancelled() { return cancel; }
        @Override public boolean confirm(String title, String message, String yes, String no) {
            final boolean[] r = {false};
            CountDownLatch l = new CountDownLatch(1);
            main.post(() -> {
                if (isFinishing()) { l.countDown(); return; }
                new AlertDialog.Builder(MainActivity.this).setTitle(title).setMessage(message).setCancelable(false)
                        .setPositiveButton(yes, (d, w) -> { r[0] = true; l.countDown(); })
                        .setNegativeButton(no, (d, w) -> l.countDown())
                        .show();
            });
            try {
                while (!l.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) if (cancel) return false;
            } catch (InterruptedException e) {
                return false;
            }
            return r[0];
        }
        @Override public boolean[] choose(String title, String[] items, boolean[] checked) {
            final boolean[] sel = checked.clone();
            final boolean[] ok = {false};
            CountDownLatch l = new CountDownLatch(1);
            main.post(() -> {
                if (isFinishing()) { l.countDown(); return; }
                new AlertDialog.Builder(MainActivity.this).setTitle(title).setCancelable(false)
                        .setMultiChoiceItems(items, sel, (d, w, on) -> sel[w] = on)
                        .setPositiveButton("确定", (d, w) -> { ok[0] = true; l.countDown(); })
                        .setNegativeButton("取消", (d, w) -> l.countDown())
                        .show();
            });
            try {
                while (!l.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) if (cancel) return null;
            } catch (InterruptedException e) {
                return null;
            }
            return ok[0] ? sel : null;
        }
    };

    // ------------------------------------------------------------ log
    void log(String s) {
        main.post(() -> {
            lastIsProgress = false;
            for (String l : s.split("\n")) lines.add(l);
            refreshLog();
        });
    }

    void progress(String s) {
        main.post(() -> {
            if (lastIsProgress && !lines.isEmpty()) lines.set(lines.size() - 1, s);
            else lines.add(s);
            lastIsProgress = true;
            refreshLog();
        });
    }

    private void refreshLog() {
        while (lines.size() > MAX_LOG_LINES) lines.remove(0);
        StringBuilder b = new StringBuilder();
        for (String l : lines) { if (b.length() > 0) b.append('\n'); b.append(l); }
        logView.setText(b);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Lino-UFI工具 日志", logView.getText()));
        toast("日志已复制");
    }

    // ------------------------------------------------------------ local APK
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK_TREE) onTreePicked(res, data);
        else if (req == PICK_RESTORE) onRestorePicked(res, data);
    }

    // ------------------------------------------------------------ advanced settings (title x5)
    private void onTitleTap() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTitleTap > 1500) titleTaps = 0;
        lastTitleTap = now;
        titleTaps++;
        if (tapToast != null) tapToast.cancel();
        tapToast = null;
        if (titleTaps >= 5) {
            titleTaps = 0;
            if (worker != null) { toast("请等待当前任务结束"); return; }
            startActivity(SettingsActivity.intent(this, SettingsActivity.MODE_ADVANCED));
        } else if (titleTaps >= 2) {
            tapToast = Toast.makeText(this, "再点击 " + (5 - titleTaps) + " 次进入高级设置", Toast.LENGTH_SHORT);
            tapToast.show();
        }
    }

    // ------------------------------------------------------------ ADB tools
    private void openMirror() {
        if (worker != null) { toast("请等待当前任务结束"); return; }
        try {
            settings();
        } catch (IOException e) {
            toast(e.getMessage());
            return;
        }
        save();
        startActivity(new Intent(this, MirrorActivity.class));
    }

    private Uri backupTree() {
        String t = prefs.getString("backupTree", "");
        if (t.isEmpty()) return null;
        Uri u = Uri.parse(t);
        for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(u) && p.isWritePermission()) return u;
        }
        return null;
    }

    private void refreshBackupLabel() {
        Uri u = backupTree();
        String where = "未设置（首次备份时选择文件夹）";
        if (u != null) {
            try {
                String id = android.provider.DocumentsContract.getTreeDocumentId(u);
                int c = id.indexOf(':');
                String vol = c >= 0 ? id.substring(0, c) : "";
                String path = c >= 0 ? id.substring(c + 1) : id;
                where = ("primary".equals(vol) ? "内部存储" : vol) + (path.isEmpty() ? "" : "/" + path);
            } catch (RuntimeException e) {
                where = u.toString();
            }
        }
        backupLabel.setText("备份位置：" + where);
    }

    private void pickTree() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(i, PICK_TREE);
        } catch (RuntimeException e) {
            toast("无法打开文件夹选择器");
        }
    }

    private void onTreePicked(int res, Intent data) {
        boolean go = pendingBackup;
        pendingBackup = false;
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (RuntimeException e) {
            toast("无法获得该文件夹的写入权限，请换一个文件夹");
            return;
        }
        prefs.edit().putString("backupTree", u.toString()).apply();
        refreshBackupLabel();
        if (go) backup();
    }

    private void backup() {
        if (worker != null) { toast("请等待当前任务结束"); return; }
        Uri tree = backupTree();
        if (tree == null) {
            toast("请选择保存备份的文件夹");
            pendingBackup = true;
            pickTree();
            return;
        }
        runTask("正在备份安装包", "正在备份安装包", inst -> {
            log("—— 备份安装包 ——");
            new ApkBackup(getApplicationContext(), inst, host).backup(tree);
        });
    }

    private void pickRestore() {
        if (worker != null) { toast("请等待当前任务结束"); return; }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        Uri tree = backupTree();
        if (tree != null && Build.VERSION.SDK_INT >= 26) {
            try {
                i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        tree, android.provider.DocumentsContract.getTreeDocumentId(tree)));
            } catch (RuntimeException ignored) { }
        }
        try {
            startActivityForResult(i, PICK_RESTORE);
            toast("选择要恢复的 .apk / .apks 备份（可多选）");
        } catch (RuntimeException e) {
            toast("无法打开文件选择器");
        }
    }

    private void onRestorePicked(int res, Intent data) {
        if (res != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData cd = data.getClipData();
        if (cd != null) for (int k = 0; k < cd.getItemCount(); k++) if (cd.getItemAt(k).getUri() != null) uris.add(cd.getItemAt(k).getUri());
        if (uris.isEmpty() && data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        runTask("正在恢复安装包", "正在恢复安装包", inst -> {
            log("—— 恢复安装包（" + uris.size() + " 个文件）——");
            new ApkBackup(getApplicationContext(), inst, host).restore(uris);
        });
    }

    // ------------------------------------------------------------ misc
    private void openUrl(String u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
        } catch (RuntimeException e) {
            toast("没有可以打开链接的浏览器");
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override
    public void onBackPressed() {
        if (worker != null) {
            new AlertDialog.Builder(this).setMessage("任务仍在进行，确定要退出吗？退出不会停止后台任务。")
                    .setPositiveButton("退出", (d, w) -> moveTaskToBack(true)).setNegativeButton("取消", null).show();
            return;
        }
        super.onBackPressed();
    }
}
