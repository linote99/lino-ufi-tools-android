package top.linote.ufiinstaller;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Separate settings page, two modes:
 *  - MODE_ADVANCED (title tapped 5 times on the main screen): device web backend + ADB mode for the
 *    one-click install, and the APK source;
 *  - MODE_TOOLS (gear on the "ADB 工具" card): the ADB tools' own address, port 5555 by default, no password.
 * Everything is stored in the "settings" SharedPreferences (same keys as Installer.Settings.fromPrefs).
 */
public class SettingsActivity extends Activity {
    static final String EXTRA_MODE = "mode";
    static final String MODE_ADVANCED = "advanced", MODE_TOOLS = "tools";
    private static final int PICK_APK = 41;

    private Ui ui;
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean tools;

    // advanced
    private EditText ipEdit, portEdit, pwdEdit, serverEdit, dirEdit, urlEdit;
    private RadioGroup connGroup, srcGroup;
    private LinearLayout onlineBox, urlBox, localBox;
    private TextView localLabel;
    private File localApk;
    // tools
    private EditText toolsIpEdit, toolsPortEdit;
    private RadioGroup toolsConnGroup;
    private LinearLayout toolsAddrBox;

    static Intent intent(Activity a, String mode) { return new Intent(a, SettingsActivity.class).putExtra(EXTRA_MODE, mode); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Ui(this);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        tools = MODE_TOOLS.equals(getIntent().getStringExtra(EXTRA_MODE));
        getWindow().getDecorView().setBackgroundColor(ui.bg);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(ui.bg);
            if (!ui.dark && Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        setContentView(build());
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    // ------------------------------------------------------------ layout
    private View build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = ui.col();
        root.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(28));
        scroll.addView(root);

        TextView back = ui.button("‹ 返回", 3, v -> finish());
        back.setTextColor(ui.primary);
        back.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        back.setPadding(0, 0, ui.dp(12), 0);
        root.addView(back, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ui.dp(40)));
        root.addView(ui.overline(tools ? "ADB TOOLS" : "ADVANCED"), ui.mw(12));
        root.addView(ui.h1(tools ? "ADB 工具设置" : "高级设置"));
        if (tools) buildTools(root);
        else buildAdvanced(root);

        TextView done = ui.button("完成", 1, v -> finish());
        root.addView(done, ui.mw(18));
        return scroll;
    }

    private void buildTools(LinearLayout root) {
        root.addView(ui.sub("ADB 投屏、备份和恢复安装包使用这里的地址，直接连接设备的 ADB：不登录设备后台，不需要后台密码。"
                + "设备需已开启 ADB（没开启时在主页面点“仅开启 ADB”或完成一键安装）。"));
        LinearLayout c = ui.card();
        c.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(14));
        c.addView(ui.text("连接方式", 15, ui.text, true));
        toolsConnGroup = radios(new String[]{"无线 ADB（直连下面的地址）", "USB OTG（用 OTG 线连接设备）"});
        c.addView(toolsConnGroup, ui.mw(4));
        toolsAddrBox = ui.col();
        LinearLayout r = ui.row();
        toolsIpEdit = edit("192.168.0.1", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        toolsPortEdit = edit("5555", InputType.TYPE_CLASS_NUMBER);
        r.addView(field("设备 IP", toolsIpEdit), ui.weight(2));
        LinearLayout.LayoutParams tp = ui.weight(1);
        tp.leftMargin = ui.dp(10);
        r.addView(field("ADB 端口", toolsPortEdit), tp);
        toolsAddrBox.addView(r, ui.mw(8));
        TextView tip = ui.sub("默认 192.168.0.1:5555。IP 栏也可直接填“地址:端口”，会自动拆分。");
        tip.setTextSize(12);
        toolsAddrBox.addView(tip, ui.mw(4));
        TextView reset = ui.button("恢复默认（192.168.0.1:5555）", 3, v -> { toolsIpEdit.setText("192.168.0.1"); toolsPortEdit.setText("5555"); });
        reset.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        reset.setTextColor(ui.primary);
        reset.setPadding(0, ui.dp(8), 0, 0);
        toolsAddrBox.addView(reset);
        c.addView(toolsAddrBox);
        toolsConnGroup.setOnCheckedChangeListener((g, id) -> toolsAddrBox.setVisibility(idx(toolsConnGroup) == 0 ? View.VISIBLE : View.GONE));
        root.addView(c, ui.mw(14));
    }

    private void buildAdvanced(LinearLayout root) {
        root.addView(ui.sub("这些设置只用于“开始一键安装UFI-TOOLS”和“仅开启 ADB”：登录设备后台开启 ADB，以及获取 UFI-TOOLS 安装包。"
                + "默认值适用于大多数设备。ADB 投屏和备份/恢复的连接在“ADB 工具”卡片右上角的设置里。"));

        LinearLayout dev = ui.card();
        dev.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(14));
        dev.addView(ui.text("设备", 15, ui.text, true));
        ipEdit = edit("192.168.0.1", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        portEdit = edit("8080", InputType.TYPE_CLASS_NUMBER);
        LinearLayout r1 = ui.row();
        r1.addView(field("设备地址", ipEdit), ui.weight(2));
        LinearLayout.LayoutParams pp = ui.weight(1);
        pp.leftMargin = ui.dp(10);
        r1.addView(field("后台端口", portEdit), pp);
        dev.addView(r1, ui.mw(6));
        pwdEdit = edit(ZteWeb.DEFAULT_PASSWORD, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        dev.addView(field("后台密码（默认值即安装器内置密码；改过后台密码请填写新密码）", pwdEdit), ui.mw(8));
        CheckBox show = check("显示密码");
        show.setOnCheckedChangeListener((v, on) -> {
            pwdEdit.setInputType(InputType.TYPE_CLASS_TEXT | (on ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            pwdEdit.setSelection(pwdEdit.getText().length());
        });
        dev.addView(show);
        dev.addView(label("ADB 连接（一键安装）"), ui.mw(6));
        connGroup = radios(new String[]{"自动（先 USB OTG，再无线 ADB 5555）", "仅 USB OTG", "仅无线 ADB（5555 端口）"});
        dev.addView(connGroup);
        TextView rd = ui.button("恢复默认设备设置", 3, v -> {
            ipEdit.setText("192.168.0.1");
            portEdit.setText("8080");
            pwdEdit.setText(ZteWeb.DEFAULT_PASSWORD);
            connGroup.check(1000);
        });
        rd.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        rd.setTextColor(ui.primary);
        rd.setPadding(0, ui.dp(8), 0, 0);
        dev.addView(rd);
        root.addView(dev, ui.mw(14));

        LinearLayout src = ui.card();
        src.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(14));
        src.addView(ui.text("安装包", 15, ui.text, true));
        srcGroup = radios(new String[]{"在线最新版（与电脑版安装器相同的下载源）", "自定义下载链接", "本地 APK 文件"});
        src.addView(srcGroup);
        onlineBox = ui.col();
        serverEdit = edit(ApkSource.DEFAULT_SERVER, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        dirEdit = edit(ApkSource.DEFAULT_DIR, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        onlineBox.addView(field("下载服务器（AList）", serverEdit), ui.mw(4));
        onlineBox.addView(field("目录", dirEdit), ui.mw(8));
        TextView reset = ui.button("恢复默认下载源", 3, v -> { serverEdit.setText(ApkSource.DEFAULT_SERVER); dirEdit.setText(ApkSource.DEFAULT_DIR); });
        reset.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        reset.setTextColor(ui.primary);
        reset.setPadding(0, ui.dp(8), 0, 0);
        onlineBox.addView(reset);
        src.addView(onlineBox);
        urlBox = ui.col();
        urlEdit = edit("https://example.com/UFI-TOOLS.apk", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlBox.addView(field("APK 完整下载链接（http:// 或 https://）", urlEdit), ui.mw(4));
        src.addView(urlBox);
        localBox = ui.col();
        localLabel = ui.sub("未选择文件");
        localBox.addView(localLabel, ui.mw(6));
        localBox.addView(ui.button("选择 APK 文件", 0, v -> pickApk()), ui.mw(8));
        src.addView(localBox);
        srcGroup.setOnCheckedChangeListener((g, id) -> showSource());
        root.addView(src, ui.mw(14));
    }

    private EditText edit(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextSize(14);
        e.setTextColor(ui.text);
        e.setHintTextColor(ui.disabled);
        e.setBackground(ui.round(ui.surface, ui.border, 8));
        e.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        return e;
    }

    private TextView label(String s) { return ui.text(s, 12.5f, ui.muted, false); }

    private View field(String name, EditText e) {
        LinearLayout l = ui.col();
        l.addView(label(name));
        l.addView(e, ui.mw(4));
        return l;
    }

    private CheckBox check(String s) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setTextSize(13.5f);
        c.setTextColor(ui.text);
        if (Build.VERSION.SDK_INT >= 21) c.setButtonTintList(android.content.res.ColorStateList.valueOf(ui.primary));
        return c;
    }

    private RadioGroup radios(String[] items) {
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.VERTICAL);
        for (int i = 0; i < items.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setId(1000 + i);
            r.setText(items[i]);
            r.setTextSize(13.5f);
            r.setTextColor(ui.text);
            if (Build.VERSION.SDK_INT >= 21) r.setButtonTintList(android.content.res.ColorStateList.valueOf(ui.primary));
            g.addView(r, new RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return g;
    }

    private static int idx(RadioGroup g) {
        int i = g.getCheckedRadioButtonId() - 1000;
        return i < 0 ? 0 : i;
    }

    private void showSource() {
        int m = idx(srcGroup);
        onlineBox.setVisibility(m == 0 ? View.VISIBLE : View.GONE);
        urlBox.setVisibility(m == 1 ? View.VISIBLE : View.GONE);
        localBox.setVisibility(m == 2 ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------ load / save
    private void load() {
        if (tools) {
            toolsIpEdit.setText(prefs.getString("toolsIp", "192.168.0.1"));
            toolsPortEdit.setText(String.valueOf(prefs.getInt("toolsPort", 5555)));
            toolsConnGroup.check(1000 + Math.max(0, Math.min(1, prefs.getInt("toolsConn", 0))));
            toolsAddrBox.setVisibility(idx(toolsConnGroup) == 0 ? View.VISIBLE : View.GONE);
            return;
        }
        ipEdit.setText(prefs.getString("ip", "192.168.0.1"));
        portEdit.setText(String.valueOf(prefs.getInt("webPort", 8080)));
        pwdEdit.setText(prefs.getString("pwd", ZteWeb.DEFAULT_PASSWORD));
        connGroup.check(1000 + Math.max(0, Math.min(2, prefs.getInt("conn", 0))));
        srcGroup.check(1000 + Math.max(0, Math.min(2, prefs.getInt("src", 0))));
        serverEdit.setText(prefs.getString("server", ApkSource.DEFAULT_SERVER));
        dirEdit.setText(prefs.getString("dir", ApkSource.DEFAULT_DIR));
        urlEdit.setText(prefs.getString("url", ""));
        String lp = prefs.getString("local", "");
        if (!lp.isEmpty() && new File(lp).isFile()) setLocal(new File(lp));
        showSource();
    }

    private void save() {
        SharedPreferences.Editor e = prefs.edit();
        if (tools) {
            String ip = toolsIpEdit.getText().toString().trim();
            String pt = toolsPortEdit.getText().toString().trim();
            Matcher hp = Pattern.compile("^([^:\\[\\]]+):(\\d{1,5})$").matcher(ip);
            if (hp.matches()) {   // "192.168.0.1:5555" typed into the IP field
                ip = hp.group(1);
                pt = hp.group(2);
                toolsIpEdit.setText(ip);
                toolsPortEdit.setText(pt);
            }
            if (ip.startsWith("[") && ip.endsWith("]")) ip = ip.substring(1, ip.length() - 1);
            if (ip.isEmpty()) ip = "192.168.0.1";
            int port = parsePort(pt, 5555);
            e.putString("toolsIp", ip).putInt("toolsPort", port).putInt("toolsConn", idx(toolsConnGroup));
        } else {
            e.putString("ip", ipEdit.getText().toString().trim()).putInt("webPort", parsePort(portEdit.getText().toString().trim(), 8080))
                    .putString("pwd", pwdEdit.getText().toString())
                    .putInt("conn", idx(connGroup)).putInt("src", idx(srcGroup))
                    .putString("server", serverEdit.getText().toString().trim()).putString("dir", dirEdit.getText().toString().trim())
                    .putString("url", urlEdit.getText().toString().trim())
                    .putString("local", localApk == null ? "" : localApk.getPath());
        }
        e.apply();
    }

    private static int parsePort(String s, int def) {
        try {
            int p = Integer.parseInt(s);
            return p >= 1 && p <= 65535 ? p : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Warn about input that save() had to replace with a default before leaving the page. */
    private String problem() {
        if (tools) {
            if (idx(toolsConnGroup) == 1) return null;
            String ip = toolsIpEdit.getText().toString().trim();
            String pt = toolsPortEdit.getText().toString().trim();
            Matcher hp = Pattern.compile("^([^:\\[\\]]+):(\\d{1,5})$").matcher(ip);
            if (hp.matches()) { ip = hp.group(1); pt = hp.group(2); }
            if (!pt.isEmpty() && parsePort(pt, -1) < 0) return "ADB 端口应为 1-65535";
            if (ip.startsWith("[") && ip.endsWith("]")) ip = ip.substring(1, ip.length() - 1);
            if (!ip.isEmpty() && !ip.matches("[A-Za-z0-9.\\-]{1,253}") && !ip.matches("[0-9A-Fa-f:.%a-z]{2,64}")) return "设备 IP 格式不正确";
            return null;
        }
        String ip = ipEdit.getText().toString().trim();
        if (!ip.isEmpty() && !ip.matches("[A-Za-z0-9.\\-]{1,253}")) return "设备地址格式不正确";
        String pt = portEdit.getText().toString().trim();
        if (!pt.isEmpty() && parsePort(pt, -1) < 0) return "后台端口应为 1-65535";
        return null;
    }

    @Override
    public void finish() {
        String p = problem();
        if (p != null && !isFinishing()) {
            new android.app.AlertDialog.Builder(this).setTitle("设置有误").setMessage(p + "。\n返回后将使用默认值。")
                    .setPositiveButton("修改", null)
                    .setNegativeButton("仍然返回", (d, w) -> SettingsActivity.super.finish()).show();
            return;
        }
        super.finish();
    }

    // ------------------------------------------------------------ local APK
    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/vnd.android.package-archive", "application/octet-stream", "application/zip"});
        try {
            startActivityForResult(i, PICK_APK);
        } catch (RuntimeException e) {
            toast("无法打开文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_APK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        localLabel.setText("正在读取…");
        new Thread(() -> {
            try {
                String name = ApkSource.displayName(this, uri);
                if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) name = name + ".apk";
                File dir = new File(getFilesDir(), "local");
                dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
                File dest = new File(dir, name);
                ApkSource.importUri(this, uri, dest);
                ApkSource.verifyZip(dest);
                main.post(() -> { setLocal(dest); save(); toast("已选择本地 APK：" + dest.getName()); });
            } catch (IOException | RuntimeException e) {
                main.post(() -> { localLabel.setText(localApk == null ? "未选择文件" : localApk.getName()); toast("文件不可用：" + e.getMessage()); });
            }
        }).start();
    }

    private void setLocal(File f) {
        localApk = f;
        localLabel.setText(f.getName() + "（" + Installer.mb(f.length()) + "）");
        localLabel.setTypeface(Typeface.DEFAULT_BOLD);
        localLabel.setTextColor(ui.text);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
