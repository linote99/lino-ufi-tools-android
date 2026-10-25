package top.linote.ufiinstaller;

import android.net.Network;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * ZTE stock web backend client (port 8080), a port of the zreq.exe helper that the
 * Windows installer embeds. Login: password = H(H(pwd) + LD); every set request carries
 * AD = H(H(wa_inner_version + cr_version) + RD), with H = upper-case hex SHA-256.
 */
final class ZteWeb {
    static final String DEFAULT_PASSWORD = "Wa@9w+YWRtaW4=";

    private final String ip;
    private final int port;
    private final Network net;   // Wi-Fi network of the device (may be null = default routing)
    private String cookie;

    ZteWeb(String ip, int port, Network net) {
        this.ip = ip;
        this.port = port;
        this.net = net;
    }

    private String base() { return "http://" + ip + ":" + port; }

    static String sha256Upper(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02X", x & 0xff));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpURLConnection open(String path) throws IOException {
        URL u = new URL(base() + path);
        HttpURLConnection c = (HttpURLConnection) (net != null ? net.openConnection(u) : u.openConnection());
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        c.setInstanceFollowRedirects(false);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        c.setRequestProperty("Referer", base() + "/index.html");
        if (cookie != null) c.setRequestProperty("Cookie", cookie);
        return c;
    }

    private static String readAll(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) throw new IOException("HTTP " + code);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        try {
            while ((r = in.read(buf)) > 0) {
                b.write(buf, 0, r);
                if (b.size() > 1 << 20) break;
            }
        } finally {
            in.close();
        }
        String s = new String(b.toByteArray(), StandardCharsets.UTF_8);
        if (code >= 400) throw new IOException("HTTP " + code + (s.isEmpty() ? "" : "：" + brief(s)));
        return s;
    }

    static String brief(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 160 ? t.substring(0, 157) + "…" : t;
    }

    private JSONObject get(String query) throws IOException {
        HttpURLConnection c = open("/goform/goform_get_cmd_process?isTest=false&" + query + "&_=" + System.currentTimeMillis());
        try {
            String s = readAll(c);
            try {
                return new JSONObject(s);
            } catch (Exception e) {
                throw new IOException("后台返回的不是 JSON：" + brief(s));
            }
        } finally {
            c.disconnect();
        }
    }

    private static String encode(Map<String, String> form) {
        // Go url.Values.Encode(): keys sorted, form-encoded.
        StringBuilder b = new StringBuilder();
        try {
            for (Map.Entry<String, String> e : new TreeMap<>(form).entrySet()) {
                if (b.length() > 0) b.append('&');
                b.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
            }
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        return b.toString();
    }

    private HttpURLConnection post(Map<String, String> form) throws IOException {
        HttpURLConnection c = open("/goform/goform_set_cmd_process");
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        byte[] body = encode(form).getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream o = c.getOutputStream()) { o.write(body); }
        return c;
    }

    /** Logs in to the web backend; throws with a readable reason on failure. */
    void login(String password) throws IOException {
        cookie = null;
        String ld = get("cmd=LD").optString("LD", "");
        if (ld.isEmpty()) throw new IOException("未能获取登录随机数 LD（后台地址或端口可能不对）");
        Map<String, String> f = new TreeMap<>();
        f.put("goformId", "LOGIN");
        f.put("isTest", "false");
        f.put("password", sha256Upper(sha256Upper(password) + ld));
        HttpURLConnection c = post(f);
        String body;
        try {
            body = readAll(c);
            String sc = c.getHeaderField("Set-Cookie");
            if (sc != null && !sc.isEmpty()) cookie = sc.split(";", 2)[0];
        } finally {
            c.disconnect();
        }
        String result = "";
        try { result = new JSONObject(body).optString("result", ""); } catch (Exception ignored) { }
        if ("3".equals(result)) throw new IOException("后台登录失败：密码错误（result=3）");
        if (!result.isEmpty() && !"0".equals(result)) throw new IOException("后台登录失败（result=" + result + "）");
        if (cookie == null) throw new IOException("后台登录失败：无法获取 Cookie（返回：" + brief(body) + "）");
    }

    private String ad() throws IOException {
        JSONObject v = get("cmd=Language,cr_version,wa_inner_version&multi_data=1");
        String inner = v.optString("wa_inner_version", ""), cr = v.optString("cr_version", "");
        String rd = get("cmd=RD").optString("RD", "");
        if (rd.isEmpty()) throw new IOException("未能获取请求校验码 RD（登录可能已失效）");
        return sha256Upper(sha256Upper(inner + cr) + rd);
    }

    /** Sends a goform set request (body like "goformId=X&a=b"); returns the raw JSON reply. */
    String set(String body) throws IOException {
        Map<String, String> f = new TreeMap<>();
        for (String kv : body.split("&")) {
            if (kv.isEmpty()) continue;
            int e = kv.indexOf('=');
            f.put(e < 0 ? kv : kv.substring(0, e), e < 0 ? "" : kv.substring(e + 1));
        }
        f.put("isTest", "false");
        f.put("AD", ad());
        HttpURLConnection c = post(f);
        try {
            return readAll(c);
        } finally {
            c.disconnect();
        }
    }

    /** What the Windows installer does to switch on ADB: USB_PORT_SETTING usb_port_switch=1. */
    String enableAdb(String password) throws IOException {
        login(password);
        String r = set("goformId=USB_PORT_SETTING&usb_port_switch=1");
        if (!r.contains("success")) throw new IOException("后台未确认开启 ADB：" + brief(r));
        return r;
    }
}
