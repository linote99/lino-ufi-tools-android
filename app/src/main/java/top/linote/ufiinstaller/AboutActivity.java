package top.linote.ufiinstaller;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 关于：使用说明、连接方式、注意事项，页脚友情链接（与 Lino 迷你输入法 / Diff Studio 相同样式）。 */
public class AboutActivity extends Activity {
    private Ui ui;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Ui(this);
        getWindow().getDecorView().setBackgroundColor(ui.bg);
        getWindow().setStatusBarColor(ui.bg);
        if (!ui.dark && Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = ui.col();
        root.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(24));
        scroll.addView(root);

        TextView back = ui.button("‹  返回", 0, v -> finish());
        back.setContentDescription("返回主页面");
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ui.dp(40));
        root.addView(back, bp);
        root.addView(ui.overline("ABOUT"), ui.mw(16));
        root.addView(ui.h1("关于 Lino-UFI工具"));
        root.addView(ui.sub("版本 " + BuildInfo.VERSION_NAME + "。把 UFI-TOOLS 电脑版一键安装器的流程搬到手机上：手机作为 ADB 主机，"
                + "完成开启 ADB、安装、授权、保活设置、启动和重启设备，不需要电脑；另外提供 ADB 投屏和安装包备份/恢复。"));

        section(root, "使用说明", new String[][]{
                {"1", "手机连上随身 WiFi 的热点。登录后台开启 ADB、无线 ADB 和访问 UFI-TOOLS 都要经过这个热点。"},
                {"2", "默认设置（设备地址 192.168.0.1、后台端口 8080、电脑版内置后台密码、在线最新版安装包）适用于大多数设备，可以直接安装。"},
                {"3", "需要修改时，在主页面连续点击标题“Lino-UFI工具”5 次，打开单独的“高级设置”页面：可改设备地址、后台端口、后台密码、一键安装的 ADB 连接方式，以及安装包来源（在线最新版、自定义下载链接或本地 APK）。点“完成”或返回键回到主页面，设置自动保存。"},
                {"4", "点“开始一键安装UFI-TOOLS”。系统弹出 USB 访问授权或设备弹出调试授权时，请点允许。"},
                {"5", "看到“操作完成”后设备会自动重启；重启后用浏览器打开 http://192.168.0.1:2333 使用 UFI-TOOLS。"},
                {"6", "只想打开 ADB 时点“仅开启 ADB”；自动开启失败时，可点“后台 USB 设置”在设备网页中手动打开。"},
        }, true);

        section(root, "ADB 工具的连接", new String[][]{
                {"•", "ADB 投屏、备份和恢复安装包使用单独的设备 IP 和 ADB 端口（默认 192.168.0.1:5555，在主页面“ADB 工具”卡片右上角的“⚙ 设置”里修改），直接连接无线 ADB，不登录设备后台，也不需要后台密码。"},
                {"•", "在“⚙ 设置”里也可以改选“USB OTG”，用 OTG 线直连。"},
                {"•", "设备必须已经开启 ADB。没开启时先点“仅开启 ADB”或完成一键安装（这一步才会用到高级设置里的后台地址和密码）。"},
                {"•", "IP 栏也可以直接填“地址:端口”，例如 192.168.0.1:5555，会自动拆分。"},
        }, false);

        section(root, "ADB 投屏", new String[][]{
                {"•", "点“ADB 投屏”查看并操作随身 WiFi 的屏幕：单指点按、滑动、多指操作都会传给设备；底部有返回、主页和多任务键。"},
                {"•", "流畅模式使用开源项目 scrcpy 5.0.1 的设备端组件（临时推送到设备的 /data/local/tmp），H.264 实时画面，可在右上角切换画质。"},
                {"•", "设备不支持硬件编码或流畅模式启动失败时，会自动改用截图模式（循环刷新截图），速度较慢但兼容性更好；也可在右上角手动切换。"},
                {"•", "投屏不传输声音；屏幕熄灭时会先点亮屏幕。投屏期间请保持 OTG 线或热点连接。"},
        }, false);

        section(root, "备份与恢复安装包", new String[][]{
                {"•", "“备份安装包”列出设备上用户安装的应用，勾选后通过 ADB 拉取它们的安装包，保存到你选择的手机文件夹（首次使用时选择，可随时更改）。"},
                {"•", "单个 APK 保存为“名称_版本.apk”；拆分安装的应用保存为 .apks（内含 base.apk 与各个 split APK）。"},
                {"•", "“恢复安装包”可多选 .apk / .apks 文件，通过 ADB 重新安装到设备（允许覆盖安装和降级）。"},
                {"•", "只备份安装包本身，不包含应用数据和设置。"},
        }, false);

        section(root, "连接方式", new String[][]{
                {"•", "自动模式先找 USB OTG 设备，再试无线 ADB（设备地址:5555）；都找不到时会登录后台开启 ADB，再等待 ADB 出现。"},
                {"•", "无线 ADB：手机和随身 WiFi 在同一热点下即可，不需要接线。部分固件开启 ADB 后只提供 USB 调试，此时请改用 OTG。"},
                {"•", "USB OTG：手机必须支持 OTG（USB 主机模式），用 OTG 转接头或 OTG 数据线把随身 WiFi 接到手机。随身 WiFi 由手机供电，耗电会稍快。"},
                {"•", "开启 ADB 后随身 WiFi 的 USB 会重新枚举，可能再次弹出 USB 访问授权，请再点一次允许。"},
                {"•", "热点提示“无互联网”时请保持连接；App 会通过 Wi-Fi 访问设备，同时开着移动数据也没关系。"},
                {"•", "不支持 Android 11+ 的“无线调试”配对端口（TLS），只支持经典 5555 无线 ADB 和 USB 调试。"},
        }, false);

        section(root, "注意事项", new String[][]{
                {"•", "系统版本过高时官方禁用了 ADB，需要先降级：F50 需降到 B15 以下，U30 亚太版需降到 B19 以下，U30 普通版需降到 B25 以下，V50 需降到 B21 以下。"},
                {"•", "安装过程中不要拔线、断开热点或手动重启设备；完成后设备会在 15 秒后自动重启（可在主页面关闭）。"},
                {"•", "只安装可信来源的安装包。包名不是 com.minikano.f50_sms 时 App 会先询问；使用 HTTP 链接时也会先提醒。"},
                {"•", "日志会显示安装包的 SHA-256，可与发布页核对。"},
                {"•", "安装失败且提示签名不一致时，先在设备上卸载旧版 UFI-TOOLS 再安装。"},
                {"•", "其他程序（例如电脑上的 adb）占用设备 ADB 时可能连接失败，请先断开它们。"},
        }, false);

        LinearLayout credit = ui.card();
        credit.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
        credit.setLayoutParams(ui.mw(14));
        credit.addView(ui.text("UFI-TOOLS", 17, ui.text, true));
        TextView ct = ui.text("UFI-TOOLS 由 MiniKano 开发并开源，本安装器只是其电脑版一键安装器的安卓移植，不包含 UFI-TOOLS 本体。UFI-TOOLS 为免费工具，严禁倒卖。", 14, ui.text, false);
        ct.setPadding(0, ui.dp(8), 0, 0);
        credit.addView(ct);
        TextView link = ui.text("github.com/kanoqwq/UFI-TOOLS", 13.5f, ui.primary, true);
        link.setPadding(0, ui.dp(8), 0, ui.dp(4));
        link.setPaintFlags(link.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        link.setOnClickListener(v -> open("https://github.com/kanoqwq/UFI-TOOLS"));
        credit.addView(link);
        root.addView(credit);

        // footer: friend links, as in Lino 迷你输入法 / Diff Studio
        LinearLayout title = ui.row();
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, ui.dp(24));
        tlp.topMargin = ui.dp(24);
        tlp.bottomMargin = ui.dp(5);
        root.addView(title, tlp);
        View left = new View(this);
        left.setBackgroundColor(ui.border);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, ui.dp(1), 1f);
        llp.setMarginEnd(ui.dp(10));
        title.addView(left, llp);
        TextView fl = ui.text("友情链接", 13, ui.muted, false);
        fl.setGravity(Gravity.CENTER);
        title.addView(fl, new LinearLayout.LayoutParams(-2, -1));
        View right = new View(this);
        right.setBackgroundColor(ui.border);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, ui.dp(1), 1f);
        rlp.setMarginStart(ui.dp(10));
        title.addView(right, rlp);
        LinearLayout links = ui.row();
        links.setGravity(Gravity.CENTER);
        links.addView(friendLink(R.drawable.github_invertocat, "GitHub 主页", "https://github.com/linote99", true));
        links.addView(new View(this), new LinearLayout.LayoutParams(ui.dp(16), 1));
        links.addView(friendLink(R.drawable.linote_logo, "Lino 笔记", "https://www.linote.top/", false));
        LinearLayout.LayoutParams lkp = new LinearLayout.LayoutParams(-1, -2);
        lkp.topMargin = ui.dp(2);
        root.addView(links, lkp);

        setContentView(scroll);
    }

    private void section(LinearLayout parent, String title, String[][] items, boolean numbered) {
        LinearLayout c = ui.card();
        c.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(14));
        c.addView(ui.text(title, 17, ui.text, true));
        for (String[] item : items) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            TextView mark = ui.text(item[0], 14, numbered ? ui.primary : ui.muted, numbered);
            mark.setMinWidth(ui.dp(22));
            r.addView(mark, new LinearLayout.LayoutParams(-2, -2));
            TextView t = ui.text(item[1], 14, ui.text, false);
            t.setTextIsSelectable(true);
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            c.addView(r, ui.mw(8));
        }
        parent.addView(c, ui.mw(14));
    }

    private LinearLayout friendLink(int icon, String title, String url, boolean tint) {
        LinearLayout r = ui.row();
        r.setPadding(ui.dp(5), ui.dp(3), ui.dp(5), ui.dp(3));
        r.setClickable(true);
        r.setFocusable(true);
        r.setContentDescription(title + "，打开链接");
        r.setBackground(ui.ripple(ui.round(0x00000000, 0, 8)));
        r.setOnClickListener(v -> open(url));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (tint) iv.setImageTintList(ColorStateList.valueOf(ui.text));
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22));
        ip.setMarginEnd(ui.dp(6));
        r.addView(iv, ip);
        r.addView(ui.text(title, 13, ui.text, true), new LinearLayout.LayoutParams(-2, -2));
        r.setLayoutParams(new LinearLayout.LayoutParams(-2, ui.dp(34)));
        return r;
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "没有可打开网页的应用", Toast.LENGTH_SHORT).show();
        }
    }
}
