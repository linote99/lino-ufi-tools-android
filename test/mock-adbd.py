#!/usr/bin/env python3
"""Minimal mock adbd for testing the APK's AdbConnection on a PC.

Implements the parts of the ADB transport protocol the app uses, as specified in
AOSP system/core/adb/protocol.txt and SYNC.TXT:
  * CNXN / AUTH (TOKEN -> SIGNATURE rejected once -> RSAPUBLICKEY -> accepted).
    The signature is verified for real with the public key the client sends, parsed
    from Android's mincrypt RSAPublicKey struct (len, n0inv, n[], rr[], exponent),
    including the n0inv and rr consistency checks adbd performs.
  * Strict flow control: a second WRTE on a stream before our OKAY is a protocol error.
  * shell:<cmd>  -> runs <cmd> with /bin/sh in a scratch root (only our own test strings).
  * sync:        -> SEND path,mode / DATA / DONE -> OKAY, RECV path -> DATA.../DONE or FAIL, QUIT.
  * shell:...app_process ... com.genymobile.scrcpy.Server 5.0.1 scid=X ...  -> fake scrcpy server:
    localabstract:scrcpy_X is refused twice (not listening yet), then the 1st connection is the
    video socket (dummy byte, codec id, session 720x1280, config + 3 frames, rotation to 1280x720,
    1 frame) and the 2nd the control socket (messages are decoded into the log).
Usage: mock-adbd.py PORT ROOT [maxdata]
"""
import base64, hashlib, os, socket, struct, subprocess, sys, threading, time
from cryptography.hazmat.primitives.asymmetric import rsa, padding, utils
from cryptography.hazmat.primitives import hashes

A_SYNC, A_CNXN, A_OPEN, A_OKAY, A_CLSE, A_WRTE, A_AUTH = (
    0x434e5953, 0x4e584e43, 0x4e45504f, 0x59414b4f, 0x45534c43, 0x45545257, 0x48545541)
PORT, ROOT = int(sys.argv[1]), sys.argv[2]
MAXDATA = int(sys.argv[3]) if len(sys.argv) > 3 else 256 * 1024
# MOCK_MODE: stale-before (leftover CLSE + half packet queued before the handshake),
# stale-after (leftover CLSE/OKAY right after our CNXN), hang-done (never answer sync DONE).
MODE = os.environ.get("MOCK_MODE", "")
SCRCPY = {}
log = open(os.path.join(ROOT, "..", "mock-adbd.log"), "a")


def L(*a):
    print(*a, file=log, flush=True)


def parse_android_pubkey(b64):
    raw = base64.b64decode(b64)
    words, n0inv = struct.unpack_from("<II", raw, 0)
    assert words == 64, "modulus words"
    n = int.from_bytes(raw[8:8 + 256], "little")
    rr = int.from_bytes(raw[8 + 256:8 + 512], "little")
    e = struct.unpack_from("<I", raw, 8 + 512)[0]
    assert len(raw) == 8 + 512 + 4, "struct size %d" % len(raw)
    assert (n * n0inv) % (1 << 32) == (1 << 32) - 1, "n0inv must be -1/n mod 2^32"
    assert rr == pow(2, 4096, n), "rr must be 2^(2*2048) mod n"
    return rsa.RSAPublicNumbers(e, n).public_key()


class Conn:
    def __init__(self, s):
        self.s, self.lock, self.streams, self.next_id = s, threading.Lock(), {}, 100

    def recv_exact(self, n):
        b = b""
        while len(b) < n:
            c = self.s.recv(n - len(b))
            if not c:
                raise EOFError
            b += c
        return b

    def read(self):
        h = self.recv_exact(24)
        cmd, a0, a1, ln, crc, magic = struct.unpack("<6I", h)
        assert magic == cmd ^ 0xFFFFFFFF, "magic"
        d = self.recv_exact(ln) if ln else b""
        assert ln <= MAXDATA, "payload %d > maxdata %d" % (ln, MAXDATA)
        assert crc == 0 or crc == sum(d) & 0xFFFFFFFF, "crc"
        return cmd, a0, a1, d

    def send(self, cmd, a0, a1, d=b""):
        with self.lock:
            self.s.sendall(struct.pack("<6I", cmd, a0, a1, len(d), sum(d) & 0xFFFFFFFF, cmd ^ 0xFFFFFFFF) + d)

    def run(self):
        if MODE == "stale-before":
            self.s.sendall(struct.pack("<6I", A_CLSE, 7, 8, 0, 0, A_CLSE ^ 0xFFFFFFFF) + b"\x57\x52\x54\x45" + b"\0" * 6)
        cmd, a0, a1, d = self.read()
        assert cmd == A_CNXN and d.startswith(b"host::"), "first packet must be CNXN host::"
        L("CNXN version=%x maxdata=%d" % (a0, a1))
        if MODE == "stale-after":
            self.send(A_CLSE, 7, 8)
            self.send(A_OKAY, 9, 10)
        token = os.urandom(20)
        self.send(A_AUTH, 1, 0, token)
        cmd, a0, a1, d = self.read()
        assert cmd == A_AUTH and a0 == 2, "expected AUTH SIGNATURE"
        L("got signature (%d bytes); pretend key unknown" % len(d))
        sig = d
        self.send(A_AUTH, 1, 0, token)            # unknown key: ask again
        cmd, a0, a1, d = self.read()
        if cmd == A_AUTH and a0 == 2:             # client may re-sign once; then must send key
            cmd, a0, a1, d = self.read()
        assert cmd == A_AUTH and a0 == 3, "expected AUTH RSAPUBLICKEY"
        text = d.rstrip(b"\0").decode()
        b64, _, name = text.partition(" ")
        key = parse_android_pubkey(b64)
        key.verify(sig, token, padding.PKCS1v15(), utils.Prehashed(hashes.SHA1()))
        L("public key OK, name=%s, signature verifies" % name)
        if MODE == "auth-slow":                  # the user taps "allow" after 5 s
            time.sleep(5)
        if MODE == "auth-wait":                  # the user never answers
            while True:
                time.sleep(1)
        self.send(A_CNXN, 0x01000001, MAXDATA,
                  b"device::ro.product.name=mock;ro.product.model=E5;ro.product.device=e5;features=shell_v2,cmd\0")
        while True:
            cmd, a0, a1, d = self.read()
            if cmd == A_OPEN:
                lid = self.next_id; self.next_id += 1
                svc = d.rstrip(b"\0").decode()
                if svc.startswith("localabstract:"):
                    name = svc[len("localabstract:"):]
                    srv = SCRCPY.get(name)
                    if srv is None or srv["refuse"] > 0:
                        if srv is not None: srv["refuse"] -= 1
                        L("refused " + svc)
                        self.send(A_CLSE, 0, a0)
                        continue
                st = Stream(self, lid, a0, svc)
                self.streams[lid] = st
                self.send(A_OKAY, lid, a0)
                threading.Thread(target=st.serve, daemon=True).start()
            elif cmd == A_WRTE:
                st = self.streams[a1]
                assert not st.unacked_in, "client sent WRTE before our OKAY (flow control)"
                st.unacked_in = True
                st.feed(d)
            elif cmd == A_OKAY:
                st = self.streams.get(a1)
                if st: st.okay.set()
            elif cmd == A_CLSE:
                st = self.streams.pop(a1, None)
                if st: st.closed = True; st.feed(None); st.okay.set()


class Stream:
    def __init__(self, c, lid, rid, svc):
        self.c, self.lid, self.rid, self.svc = c, lid, rid, svc
        self.buf, self.cv, self.closed = b"", threading.Condition(), False
        self.okay, self.unacked_in = threading.Event(), False

    def feed(self, d):
        with self.cv:
            if d is not None: self.buf += d
            self.cv.notify_all()

    def take(self, n):
        with self.cv:
            while len(self.buf) < n:
                if self.closed: raise EOFError
                if self.unacked_in:          # consumed everything: acknowledge, allow next WRTE
                    self.unacked_in = False
                    self.c.send(A_OKAY, self.lid, self.rid)
                self.cv.wait()
            r, self.buf = self.buf[:n], self.buf[n:]
            if not self.buf and self.unacked_in:
                self.unacked_in = False
                self.c.send(A_OKAY, self.lid, self.rid)
            return r

    def write(self, data):
        for i in range(0, len(data), MAXDATA):
            self.okay.clear()
            self.c.send(A_WRTE, self.lid, self.rid, data[i:i + MAXDATA])
            self.okay.wait(30)

    def serve(self):
        try:
            if self.svc.startswith("shell:") and "app_process" in self.svc:
                self.fake_scrcpy_server(self.svc[6:])
            elif self.svc.startswith("localabstract:scrcpy_"):
                self.fake_scrcpy_socket(self.svc[len("localabstract:"):])
            elif self.svc.startswith("shell:"):
                cmd = self.svc[6:]
                L("shell: " + cmd)
                p = subprocess.run(["/bin/sh", "-c", cmd], cwd=ROOT, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, env={"PATH": "/usr/bin:/bin", "MOCKROOT": ROOT})
                self.write(p.stdout)
            elif self.svc.startswith("exec:"):
                # Like adbd: stream the process's stdout while it runs, one WRTE per pipe read, and
                # wait for the client's OKAY before the next one. The sizes cycle through values that
                # are multiples of the USB packet size and below 16 KiB (no short packet ends them).
                cmd = self.svc[5:]
                L("exec: " + cmd)
                p = subprocess.Popen(["/bin/sh", "-c", cmd], cwd=ROOT, stdout=subprocess.PIPE,
                                     stderr=subprocess.DEVNULL, env={"PATH": "/usr/bin:/bin", "MOCKROOT": ROOT})
                sizes, i, sent = [4096, 8192, 512, 65536, 1000, 16384, 12288, MAXDATA], 0, 0
                while True:
                    want = min(sizes[i % len(sizes)], MAXDATA)
                    i += 1
                    chunk = p.stdout.read(want)
                    if not chunk:
                        break
                    self.okay.clear()
                    self.c.send(A_WRTE, self.lid, self.rid, chunk)
                    sent += len(chunk)
                    if not self.okay.wait(30):
                        L("exec: no OKAY for a %d-byte WRTE after %d bytes (client stalled)" % (len(chunk), sent))
                        p.kill()
                        break
                p.wait()
                L("exec: sent %d bytes" % sent)
            elif self.svc == "sync:":
                self.sync()
        except EOFError:
            pass
        finally:
            self.c.send(A_CLSE, self.lid, self.rid)

    def fake_scrcpy_server(self, cmd):
        L("scrcpy-server: " + cmd)
        args = cmd.split()
        assert args[0] == "CLASSPATH=/data/local/tmp/ufi-scrcpy-server.jar", args[0]
        assert args[1:5] == ["app_process", "/", "com.genymobile.scrcpy.Server", "5.0.1"], args[1:5]
        opts = dict(a.split("=", 1) for a in args[5:])
        for k, v in (("tunnel_forward", "true"), ("audio", "false"), ("control", "true"), ("video_codec", "h264"),
                     ("send_device_meta", "false")):
            assert opts.get(k) == v, (k, opts.get(k))
        assert len(opts["scid"]) == 8 and int(opts["scid"], 16) < 2 ** 31
        jar = os.path.join(ROOT, "data/local/tmp/ufi-scrcpy-server.jar")
        assert os.path.isfile(jar), "server jar was not pushed"
        name = "scrcpy_" + opts["scid"]
        SCRCPY[name] = {"refuse": 2, "n": 0}
        self.write(b"[server] INFO: Device: [mock] E5 (Android 11)\n")
        with self.cv:
            while not self.closed:
                self.cv.wait()
        L("scrcpy-server: shell closed by client")
    def fake_scrcpy_socket(self, name):
        srv = SCRCPY[name]
        srv["n"] += 1
        if srv["n"] == 1:
            L("scrcpy video socket")
            def frame(payload, config=False, key=False, pts=0):
                flags = (1 << 62 if config else 0) | (1 << 61 if key else 0) | pts
                return struct.pack(">QI", flags, len(payload)) + payload
            self.write(b"\0" + struct.pack(">I", 0x68323634))
            self.write(struct.pack(">III", 0x80000000, 720, 1280))
            self.write(frame(b"\0\0\0\1gSPS\0\0\0\1hPPS", config=True))
            self.write(frame(b"\0\0\0\1e" + b"K" * 300000, key=True, pts=1000))   # > MAXDATA: several WRTEs
            self.write(frame(b"\0\0\0\1A" + b"P" * 5000, pts=34000))
            self.write(frame(b"\0\0\0\1A" + b"P" * 5000, pts=67000))
            self.write(struct.pack(">III", 0x80000000, 1280, 720))
            self.write(frame(b"\0\0\0\1gSPS2", config=True))
            self.write(frame(b"\0\0\0\1e" + b"k" * 1000, key=True, pts=100000))
            L("scrcpy video sent")
            with self.cv:
                while not self.closed:
                    self.cv.wait()
        else:
            L("scrcpy control socket")
            while True:
                t = self.take(1)[0]
                if t == 2:
                    m = self.take(31)
                    action, pid, x, y, w, h, pr, ab, bt = struct.unpack(">BqiiHHHii", m)
                    L("ctrl touch action=%d id=%d x=%d y=%d size=%dx%d pressure=%d" % (action, pid, x, y, w, h, pr))
                elif t == 0:
                    action, code, rep, meta = struct.unpack(">Biii", self.take(13))
                    L("ctrl key action=%d code=%d" % (action, code))
                else:
                    L("ctrl unknown type %d" % t)
                    return
    def sync(self):
        while True:
            hdr = self.take(8)
            ident, ln = hdr[:4], struct.unpack("<I", hdr[4:])[0]
            if ident == b"QUIT":
                return
            if ident == b"RECV":
                path = self.take(ln).decode()
                src = os.path.join(ROOT, path.lstrip("/"))
                if not os.path.isfile(src):
                    msg = b"No such file or directory"
                    self.write(b"FAIL" + struct.pack("<I", len(msg)) + msg)
                    L("recv %s: FAIL" % path)
                    continue
                data = open(src, "rb").read()
                out = b""
                for i in range(0, len(data), 64 * 1024):
                    c = data[i:i + 64 * 1024]
                    out += b"DATA" + struct.pack("<I", len(c)) + c
                self.write(out + b"DONE" + b"\0" * 4)
                L("recv %s bytes=%d sha256=%s" % (path, len(data), hashlib.sha256(data).hexdigest()))
                continue
            assert ident == b"SEND", ident
            spec = self.take(ln).decode()
            path, _, mode = spec.rpartition(",")
            mode = int(mode)
            out = os.path.join(ROOT, path.lstrip("/"))
            os.makedirs(os.path.dirname(out), exist_ok=True)
            h, total = hashlib.sha256(), 0
            with open(out, "wb") as f:
                while True:
                    hdr = self.take(8)
                    ident, ln = hdr[:4], struct.unpack("<I", hdr[4:])[0]
                    if ident == b"DATA":
                        assert ln <= 64 * 1024, "sync DATA chunk too large"
                        b = self.take(ln); f.write(b); h.update(b); total += len(b)
                    elif ident == b"DONE":
                        if MODE == "hang-done":
                            L("hang-done: not answering DONE")
                            import time; time.sleep(3600)
                        break
                    else:
                        raise AssertionError(ident)
            os.chmod(out, mode & 0o7777)
            L("pushed %s mode=%o bytes=%d sha256=%s" % (path, mode & 0o7777, total, h.hexdigest()))
            self.write(b"OKAY" + b"\0" * 4)


srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", PORT)); srv.listen(4)
print("READY", flush=True)
while True:
    s, _ = srv.accept()
    def go(s=s):
        try:
            Conn(s).run()
        except EOFError:
            L("client closed")
        except Exception as e:
            L("PROTOCOL-ERROR " + repr(e))
            s.close()
    threading.Thread(target=go, daemon=True).start()
