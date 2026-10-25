package top.linote.ufiinstaller;

import java.io.File;
import java.io.IOException;

/** Offline JVM test against test/mock.py (no device, no real servers). */
public class JvmTest {
    static void ok(boolean c, String m) { if (!c) throw new AssertionError(m); System.out.println("PASS " + m); }

    public static void main(String[] a) throws Exception {
        String mock = "http://127.0.0.1:18080";
        // 1. ZTE web: login + AD-signed USB_PORT_SETTING
        String r = new ZteWeb("127.0.0.1", 18080, null).enableAdb(ZteWeb.DEFAULT_PASSWORD);
        ok(r.contains("success"), "enableAdb with default password -> " + r);
        try {
            new ZteWeb("127.0.0.1", 18080, null).enableAdb("wrong");
            ok(false, "wrong password must fail");
        } catch (IOException e) {
            ok(e.getMessage().contains("密码错误"), "wrong password rejected: " + e.getMessage());
        }
        // 2. AList listing: newest .apk, dirs and non-apk skipped
        ApkSource.Remote rem = ApkSource.latest(mock + "/", "UFI-TOOLS-UPDATE/");
        ok(rem.name.equals("UFI-TOOLS 3.0.apk"), "latest = " + rem.name);
        ok(rem.url.equals(mock + "/d/UFI-TOOLS-UPDATE/UFI-TOOLS%203.0.apk"), "url = " + rem.url);
        // 3. download + verify
        File dir = new File(a[0]);
        File f = new File(dir, ApkSource.safeName(rem.name));
        ApkSource.download(rem.url, f, null);
        ApkSource.verifyZip(f);
        ok(f.length() == rem.size, "downloaded and verified " + f.length() + " bytes");
        File g = new File(dir, "redir.apk");
        ApkSource.download(mock + "/redirect.apk", g, null);
        ok(g.length() == rem.size, "redirect followed");
        try {
            ApkSource.download(mock + "/error.apk", new File(dir, "err.apk"), null);
            ok(false, "html must be rejected");
        } catch (IOException e) {
            ok(!new File(dir, "err.apk").exists() && !new File(dir, "err.apk.part").exists(), "error page rejected: " + e.getMessage());
        }
        File bad = new File(dir, "bad.apk");
        byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
        for (int i = 200; i < 260; i++) all[i] ^= 0x55;
        java.nio.file.Files.write(bad.toPath(), all);
        try {
            ApkSource.verifyZip(bad);
            ok(false, "corrupt zip must fail");
        } catch (IOException e) {
            ok(true, "corrupt APK rejected: " + e);
        }
        ok(ZteWeb.sha256Upper("abc").equals("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"), "sha256 upper hex");
        System.out.println("ALL OK");
    }
}
