package top.linote.ufiinstaller;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Where the UFI-TOOLS APK comes from: the AList share the Windows installer uses, a direct link, or a local file. */
final class ApkSource {
    static final String DEFAULT_SERVER = "https://pan.kanokano.cn";
    static final String DEFAULT_DIR = "/UFI-TOOLS-UPDATE";
    static final String PACKAGE = "com.minikano.f50_sms";

    interface Progress { void update(long done, long total); boolean cancelled(); }

    static final class Remote {
        final String name, url;
        final long size;
        Remote(String name, String url, long size) { this.name = name; this.url = url; this.size = size; }
    }

    private ApkSource() { }

    static String trimSlash(String s) {
        String t = s == null ? "" : s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    static String normDir(String d) {
        String t = trimSlash(d);
        if (!t.startsWith("/")) t = "/" + t;
        return t;
    }

    private static long modified(JSONObject o) {
        String m = o.optString("modified", "");
        try {
            return java.time.OffsetDateTime.parse(m).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Same request as the Windows installer: POST {server}/api/fs/list, newest .apk by modified time. */
    static Remote latest(String server, String dir) throws IOException {
        String base = trimSlash(server);
        String path = normDir(dir);
        URL u = new URL(base + "/api/fs/list");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        JSONObject req = new JSONObject();
        try {
            req.put("path", path).put("password", "").put("page", 1).put("per_page", 0).put("refresh", false);
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
        byte[] body = req.toString().getBytes(StandardCharsets.UTF_8);
        String text;
        try {
            try (OutputStream o = c.getOutputStream()) { o.write(body); }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new IOException("HTTP " + code);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            try { while ((r = in.read(buf)) > 0 && b.size() < (4 << 20)) b.write(buf, 0, r); } finally { in.close(); }
            text = new String(b.toByteArray(), StandardCharsets.UTF_8);
            if (code >= 400) throw new IOException("HTTP " + code);
        } finally {
            c.disconnect();
        }
        JSONArray content;
        try {
            JSONObject j = new JSONObject(text);
            if (j.optInt("code", 200) != 200) throw new IOException("服务器返回错误：" + j.optString("message", text));
            JSONObject data = j.optJSONObject("data");
            content = data == null ? null : data.optJSONArray("content");
        } catch (org.json.JSONException e) {
            throw new IOException("服务器返回的不是有效 JSON");
        }
        if (content == null) throw new IOException("目录为空或无法读取：" + path);
        JSONObject best = null;
        for (int i = 0; i < content.length(); i++) {
            JSONObject o = content.optJSONObject(i);
            if (o == null || o.optBoolean("is_dir", false)) continue;
            String n = o.optString("name", "");
            if (!n.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) continue;
            if (best == null || modified(o) > modified(best)) best = o;
        }
        if (best == null) throw new IOException("目录中没有 APK 文件：" + path);
        String name = best.optString("name");
        return new Remote(name, base + "/d" + path + "/" + encodeSegment(name), best.optLong("size", -1));
    }

    static String encodeSegment(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A safe local file name taken from a URL (falls back to UFI-TOOLS-custom.apk). */
    static String nameFromUrl(String url) {
        try {
            String p = Uri.parse(url).getLastPathSegment();
            if (p != null && p.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) return safeName(p);
        } catch (RuntimeException ignored) { }
        return "UFI-TOOLS-custom.apk";
    }

    static String safeName(String n) {
        String t = n.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        if (t.isEmpty() || t.startsWith(".")) t = "UFI-TOOLS.apk";
        return t.length() > 120 ? t.substring(t.length() - 120) : t;
    }

    /** Downloads to dest via a .part file; follows redirects; rejects non-ZIP content. */
    static void download(String url, File dest, Progress p) throws IOException {
        File part = new File(dest.getPath() + ".part");
        String cur = url;
        HttpURLConnection c = null;
        for (int hop = 0; hop < 6; hop++) {
            c = (HttpURLConnection) new URL(cur).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                cur = new URL(new URL(cur), c.getHeaderField("Location")).toString();
                c.disconnect();
                c = null;
                continue;
            }
            if (code != 200) { c.disconnect(); throw new IOException("下载失败：HTTP " + code); }
            break;
        }
        if (c == null) throw new IOException("下载失败：重定向次数过多");
        long total = c.getContentLengthLong();
        long done = 0;
        try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            long last = 0;
            while ((r = in.read(buf)) > 0) {
                if (p != null && p.cancelled()) throw new IOException("已取消");
                o.write(buf, 0, r);
                done += r;
                long now = System.currentTimeMillis();
                if (p != null && now - last > 300) { p.update(done, total); last = now; }
            }
        } catch (IOException e) {
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (p != null) p.update(done, total);
        if (total > 0 && done != total) { part.delete(); throw new IOException("下载不完整：" + done + "/" + total + " 字节"); }
        if (!zipHeader(part)) {
            part.delete();
            throw new IOException("下载内容不是 APK/ZIP（可能是网页错误页），已丢弃");
        }
        if (dest.exists() && !dest.delete()) throw new IOException("无法替换旧文件");
        if (!part.renameTo(dest)) throw new IOException("无法保存下载文件");
    }

    static boolean zipHeader(File f) {
        byte[] h = new byte[4];
        try (InputStream in = new FileInputStream(f)) {
            return in.read(h) == 4 && h[0] == 0x50 && h[1] == 0x4B && h[2] == 0x03 && h[3] == 0x04;
        } catch (IOException e) {
            return false;
        }
    }

    /** Like the Windows installer's CheckApkValid: open the archive and read every entry (CRC checked). */
    static void verifyZip(File f) throws IOException {
        if (!f.isFile() || f.length() < 1024) throw new IOException("APK 文件不存在或过小");
        boolean manifest = false;
        byte[] buf = new byte[64 * 1024];
        try (ZipFile z = new ZipFile(f)) {
            Enumeration<? extends ZipEntry> e = z.entries();
            while (e.hasMoreElements()) {
                ZipEntry en = e.nextElement();
                if ("AndroidManifest.xml".equals(en.getName())) manifest = true;
                if (en.isDirectory()) continue;
                try (InputStream in = z.getInputStream(en)) { while (in.read(buf) > 0) { /* CRC verified at EOF */ } }
            }
        }
        if (!manifest) throw new IOException("压缩包内没有 AndroidManifest.xml，不是 APK");
    }

    /** Package name and version read from the archive by this phone's package parser (null if unreadable). */
    static String[] packageInfo(Context ctx, File f) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
            if (pi == null) return null;
            long vc = android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            return new String[]{pi.packageName, pi.versionName == null ? "?" : pi.versionName, String.valueOf(vc)};
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            StringBuilder b = new StringBuilder();
            for (byte x : md.digest()) b.append(String.format("%02x", x & 0xff));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** Copies a document chosen in the system file picker into app storage. */
    static void importUri(Context ctx, Uri uri, File dest) throws IOException {
        File part = new File(dest.getPath() + ".part");
        try (InputStream in = ctx.getContentResolver().openInputStream(uri); OutputStream o = new FileOutputStream(part)) {
            if (in == null) throw new IOException("无法读取所选文件");
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
        } catch (IOException e) {
            part.delete();
            throw e;
        }
        if (dest.exists()) dest.delete();
        if (!part.renameTo(dest)) throw new IOException("无法保存所选文件");
    }

    static String displayName(Context ctx, Uri uri) {
        try (android.database.Cursor cur = ctx.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                String n = cur.getString(0);
                if (n != null && !n.isEmpty()) return safeName(n);
            }
        } catch (RuntimeException ignored) { }
        return "UFI-TOOLS-local.apk";
    }

    static boolean isMinikano(String[] info) { return info != null && PACKAGE.equals(info[0]); }
}
