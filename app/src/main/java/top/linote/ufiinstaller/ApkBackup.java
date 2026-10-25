package top.linote.ufiinstaller;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * ADB 备份安装包：从设备拉取已安装应用的 APK（含拆分 APK）保存到用户选择的文件夹；
 * ADB 恢复安装包：把备份的 .apk / .apks 重新安装到设备。
 * 单个 APK 保存为 .apk；拆分安装的应用保存为 .apks（zip，内含 base.apk 与 split_*.apk）。
 */
final class ApkBackup {
    private static final String REMOTE_DIR = "/data/local/tmp";
    private static final Pattern SAFE_PATH = Pattern.compile("[A-Za-z0-9._/~=+@,-]+");
    private static final Pattern SAFE_PKG = Pattern.compile("[A-Za-z0-9_.]+");

    private final Context ctx;
    private final Installer inst;
    private final Installer.Host h;
    private final File work;

    ApkBackup(Context ctx, Installer inst, Installer.Host h) {
        this.ctx = ctx;
        this.inst = inst;
        this.h = h;
        work = new File(ctx.getCacheDir(), "backup");
    }

    private static void wipe(File d) {
        File[] l = d.listFiles();
        if (l != null) for (File f : l) { if (f.isDirectory()) wipe(f); f.delete(); }
    }

    private File freshWork() {
        wipe(work);
        work.mkdirs();
        return work;
    }

    // ------------------------------------------------------------ backup
    void backup(Uri tree) throws IOException {
        File tmp = freshWork();
        try (AdbConnection a = inst.connectTools()) {
            String model = inst.sh(a, "getprop ro.product.model").trim();
            List<String> pkgs = new ArrayList<>();
            for (String l : inst.sh(a, "pm list packages -3").split("\n")) {
                l = l.trim();
                if (l.startsWith("package:")) {
                    String p = l.substring(8).trim();
                    if (SAFE_PKG.matcher(p).matches()) pkgs.add(p);
                }
            }
            java.util.Collections.sort(pkgs);
            if (pkgs.remove(Installer.PKG)) pkgs.add(0, Installer.PKG);
            if (pkgs.isEmpty()) throw new IOException("设备上没有找到用户安装的应用");
            String[] items = new String[pkgs.size()];
            boolean[] checked = new boolean[pkgs.size()];
            for (int i = 0; i < items.length; i++) {
                String p = pkgs.get(i);
                items[i] = Installer.PKG.equals(p) ? p + "（UFI-TOOLS）" : p;
                checked[i] = Installer.PKG.equals(p);
            }
            boolean[] sel = h.choose("选择要备份的应用（" + pkgs.size() + " 个）", items, checked);
            if (sel == null) throw new Installer.Cancelled();
            List<String> chosen = new ArrayList<>();
            for (int i = 0; i < sel.length; i++) if (sel[i]) chosen.add(pkgs.get(i));
            if (chosen.isEmpty()) throw new IOException("未选择任何应用");
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date());
            int ok = 0;
            for (String pkg : chosen) {
                inst.check();
                try {
                    String name = backupOne(a, pkg, stamp, tmp, tree);
                    h.log("✅ 已保存：" + name);
                    ok++;
                } catch (Installer.Cancelled e) {
                    throw e;
                } catch (IOException e) {
                    h.log("❌ " + pkg + " 备份失败：" + e.getMessage());
                    if (a.isClosed()) throw new IOException("与设备的连接已断开，已停止备份");
                }
            }
            h.log((ok == chosen.size() ? "✅ " : "⚠️ ") + "备份完成：" + ok + " / " + chosen.size() + " 个应用"
                    + (model.isEmpty() ? "" : "（设备 " + model + "）") + "，保存在所选文件夹。");
            if (ok == 0) throw new IOException("没有备份成功的应用");
        } finally {
            wipe(work);
        }
    }

    private String backupOne(AdbConnection a, String pkg, String stamp, File tmp, Uri tree) throws IOException {
        h.log("📦 " + pkg);
        List<String> paths = new ArrayList<>();
        for (String l : inst.sh(a, "pm path " + pkg).split("\n")) {
            l = l.trim();
            if (l.startsWith("package:")) paths.add(l.substring(8).trim());
        }
        if (paths.isEmpty()) throw new IOException("找不到安装包路径");
        String ver = "";
        Matcher m = Pattern.compile("versionName=(\\S+)").matcher(inst.sh(a, "dumpsys package " + pkg + " | grep -m1 versionName"));
        if (m.find()) ver = m.group(1).replaceAll("[^A-Za-z0-9._-]", "_");
        List<File> files = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            String p = paths.get(i);
            if (!SAFE_PATH.matcher(p).matches()) throw new IOException("安装包路径含异常字符：" + p);
            String base = p.substring(p.lastIndexOf('/') + 1);
            if (!base.toLowerCase(Locale.ROOT).endsWith(".apk")) base = base + ".apk";
            File f = new File(tmp, i + "_" + base);
            pullFile(a, p, f);
            ApkSource.verifyZip(f);
            files.add(f);
        }
        String name = pkg + (ver.isEmpty() ? "" : "_v" + ver) + "_" + stamp;
        if (files.size() == 1) {
            name += ".apk";
            writeDoc(tree, name, "application/vnd.android.package-archive", files.get(0));
        } else {
            name += ".apks";
            File z = new File(tmp, "bundle.apks");
            try (ZipOutputStream zo = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(z)))) {
                for (File f : files) {
                    String entry = f.getName().substring(f.getName().indexOf('_') + 1);
                    zo.putNextEntry(new ZipEntry(entry));
                    try (InputStream in = new FileInputStream(f)) { copy(in, zo); }
                    zo.closeEntry();
                }
            }
            h.log("  拆分安装包 " + files.size() + " 个，打包为 .apks");
            writeDoc(tree, name, "application/octet-stream", z);
        }
        for (File f : files) f.delete();
        return name;
    }

    private void pullFile(AdbConnection a, String remote, File dest) throws IOException {
        h.log("  ⬇️ " + remote);
        h.progress("  拉取: 0 MB");
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(dest))) {
            a.pull(remote, o, new AdbConnection.Progress() {
                long last;
                @Override public void bytes(long done) {
                    long now = System.currentTimeMillis();
                    if (now - last > 300) { last = now; h.progress("  拉取: " + Installer.mb(done)); }
                }
                @Override public boolean cancelled() { return h.cancelled(); }
            });
        } catch (IOException e) {
            dest.delete();
            if (h.cancelled()) throw new Installer.Cancelled();
            throw e;
        }
        h.progress("  拉取完成: " + Installer.mb(dest.length()));
    }

    private void writeDoc(Uri tree, String name, String mime, File src) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        Uri doc;
        try {
            Uri dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            doc = DocumentsContract.createDocument(cr, dir, mime, name);
        } catch (RuntimeException | java.io.FileNotFoundException e) {
            throw new IOException("无法在备份文件夹中创建文件（请在“更改备份位置”中重新选择文件夹）：" + e.getMessage());
        }
        if (doc == null) throw new IOException("无法在备份文件夹中创建文件，请重新选择备份位置");
        try (InputStream in = new FileInputStream(src); OutputStream o = cr.openOutputStream(doc)) {
            if (o == null) throw new IOException("无法写入备份文件");
            copy(in, o);
        } catch (IOException e) {
            try { DocumentsContract.deleteDocument(cr, doc); } catch (Exception ignored) { }
            throw e;
        }
    }

    private static void copy(InputStream in, OutputStream o) throws IOException {
        byte[] b = new byte[65536];
        int r;
        while ((r = in.read(b)) > 0) o.write(b, 0, r);
    }

    // ------------------------------------------------------------ restore
    /** One picked backup: a single APK, or the APKs of a split install. */
    private static final class Item {
        String name;
        final List<File> apks = new ArrayList<>();
        String[] info;   // package, versionName, versionCode of the base APK
    }

    void restore(List<Uri> uris) throws IOException {
        File tmp = freshWork();
        try {
            List<Item> items = new ArrayList<>();
            for (int i = 0; i < uris.size(); i++) {
                inst.check();
                items.add(load(uris.get(i), new File(tmp, "r" + i)));
            }
            StringBuilder b = new StringBuilder();
            boolean ufi = false;
            for (Item it : items) {
                b.append("• ").append(it.name).append('\n')
                        .append(it.info != null ? "   " + it.info[0] + " " + it.info[1] : "   （无法解析包名）")
                        .append(it.apks.size() > 1 ? "，" + it.apks.size() + " 个拆分 APK" : "").append('\n');
                if (ApkSource.isMinikano(it.info)) ufi = true;
            }
            if (!h.confirm("恢复安装包", "将通过 ADB 安装到设备（覆盖安装，保留应用数据）：\n\n" + b.toString().trim(), "开始恢复", "取消"))
                throw new Installer.Cancelled();
            try (AdbConnection a = inst.connectTools()) {
                int ok = 0;
                boolean ufiOk = false;
                for (Item it : items) {
                    inst.check();
                    try {
                        installItem(a, it);
                        ok++;
                        if (ApkSource.isMinikano(it.info)) ufiOk = true;
                    } catch (Installer.Cancelled e) {
                        throw e;
                    } catch (IOException e) {
                        h.log("❌ " + it.name + " 恢复失败：" + e.getMessage());
                        if (a.isClosed()) throw new IOException("与设备的连接已断开，已停止恢复");
                    }
                }
                h.log((ok == items.size() ? "✅ " : "⚠️ ") + "恢复完成：" + ok + " / " + items.size() + " 个安装包");
                if (ok == 0) throw new IOException("没有恢复成功的安装包");
                if (ufi && ufiOk && h.confirm("UFI-TOOLS 已恢复", "是否继续执行与一键安装相同的授权、保活设置并启动 UFI-TOOLS？"
                        + (inst.autoReboot() ? "\n完成后设备会在 15 秒后自动重启。" : ""), "执行", "跳过")) {
                    inst.configure(a);
                }
            }
        } finally {
            wipe(work);
        }
    }

    private Item load(Uri uri, File dir) throws IOException {
        dir.mkdirs();
        Item it = new Item();
        it.name = ApkSource.displayName(ctx, uri);
        File f = new File(dir, "picked.bin");
        ApkSource.importUri(ctx, uri, f);
        ApkSource.verifyZip(f);
        boolean apk = false;
        List<String> inner = new ArrayList<>();
        try (ZipFile z = new ZipFile(f)) {
            Enumeration<? extends ZipEntry> e = z.entries();
            while (e.hasMoreElements()) {
                ZipEntry ze = e.nextElement();
                String n = ze.getName();
                if (n.equals("AndroidManifest.xml")) apk = true;
                if (!ze.isDirectory() && n.toLowerCase(Locale.ROOT).endsWith(".apk") && !n.contains("..")) inner.add(n);
            }
            if (!apk) {
                if (inner.isEmpty()) throw new IOException(it.name + " 不是 APK，也不是 .apks 备份");
                java.util.Collections.sort(inner, (x, y) -> Boolean.compare(!base(x), !base(y)));
                int i = 0;
                for (String n : inner) {
                    File out = new File(dir, (i++) + "_" + n.substring(n.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_"));
                    try (InputStream in = z.getInputStream(z.getEntry(n)); OutputStream o = new FileOutputStream(out)) { copy(in, o); }
                    it.apks.add(out);
                }
            }
        }
        if (apk) {
            File a = new File(dir, "0_base.apk");
            if (!f.renameTo(a)) throw new IOException("无法读取 " + it.name);
            it.apks.add(a);
        } else {
            f.delete();
        }
        it.info = ApkSource.packageInfo(ctx, it.apks.get(0));
        h.log("已读取：" + it.name + (it.info != null ? "（" + it.info[0] + " " + it.info[1] + "）" : ""));
        return it;
    }

    private static boolean base(String n) {
        String b = n.substring(n.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return b.equals("base.apk") || b.equals("base-master.apk");
    }

    private void installItem(AdbConnection a, Item it) throws IOException {
        h.log("📱 正在恢复 " + it.name + "...");
        int n = it.apks.size();
        File[] locals = it.apks.toArray(new File[0]);
        String[] remotes = new String[n];
        int[] modes = new int[n];
        long total = 0;
        for (int i = 0; i < n; i++) {
            remotes[i] = REMOTE_DIR + "/ufi-restore-" + i + ".apk";
            modes[i] = 0644;
            total += locals[i].length();
        }
        final long t = total;
        h.progress("推送: 0%");
        a.push(locals, remotes, modes, new AdbConnection.Progress() {
            long last;
            @Override public void bytes(long done) {
                long now = System.currentTimeMillis();
                if (now - last > 300 || done == t) { last = now; h.progress("推送: " + (done * 100 / Math.max(1, t)) + "%（" + Installer.mb(done) + " / " + Installer.mb(t) + "）"); }
            }
            @Override public boolean cancelled() { return h.cancelled(); }
        });
        String out;
        try {
            if (n == 1) {
                out = inst.sh(a, "pm install -r -d " + remotes[0]);
            } else {
                String c = inst.sh(a, "pm install-create -r -d -S " + total);
                Matcher m = Pattern.compile("\\[(\\d+)]").matcher(c);
                if (!m.find()) throw new IOException("创建安装会话失败：" + ZteWeb.brief(c));
                String sid = m.group(1);
                for (int i = 0; i < n; i++) {
                    String w = inst.sh(a, "pm install-write -S " + locals[i].length() + " " + sid + " " + i + ".apk " + remotes[i]);
                    if (!w.contains("Success")) {
                        inst.sh(a, "pm install-abandon " + sid);
                        throw new IOException("写入拆分 APK 失败：" + ZteWeb.brief(w));
                    }
                }
                out = inst.sh(a, "pm install-commit " + sid);
            }
        } finally {
            StringBuilder rm = new StringBuilder("rm -f");
            for (String r : remotes) rm.append(' ').append(r);
            inst.sh(a, rm.toString());
        }
        if (!out.contains("Success")) {
            String why = out.contains("VERSION_DOWNGRADE") ? "\n备份版本低于设备上已装版本，需先在设备上卸载该应用再恢复"
                    : out.contains("UPDATE_INCOMPATIBLE") || out.contains("SIGNATURES") ? "\n备份与设备上已装版本签名不同，需先在设备上卸载该应用再恢复" : "";
            throw new IOException("安装失败：" + ZteWeb.brief(out) + why);
        }
        h.log("✅ 已恢复：" + it.name);
    }
}
