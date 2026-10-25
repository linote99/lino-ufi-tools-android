"""Local mock of the ZTE web backend (8080) and an AList share, for offline tests only."""
import hashlib, json, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

H = lambda s: hashlib.sha256(s.encode()).hexdigest().upper()
PWD = 'Wa@9w+YWRtaW4='; LD = 'LDNONCE123'; RD = 'RDNONCE456'; INNER = 'BD_F50V1.0.0B10'; CR = 'CR_1.0'
APK = open(sys.argv[2], 'rb').read()


class Z(BaseHTTPRequestHandler):
    def log_message(self, *a):
        sys.stderr.write('%s %s\n' % (self.command, self.path))

    def j(self, o, cookie=None):
        b = json.dumps(o).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        if cookie:
            self.send_header('Set-Cookie', cookie)
        self.send_header('Content-Length', str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        u = urlparse(self.path); q = parse_qs(u.query)
        if u.path == '/goform/goform_get_cmd_process':
            cmd = q.get('cmd', [''])[0]
            assert q.get('isTest') == ['false']
            if cmd == 'LD':
                return self.j({'LD': LD})
            if cmd == 'RD':
                assert self.headers.get('Cookie') == 'stok="abc"', self.headers.get('Cookie')
                return self.j({'RD': RD})
            if cmd == 'Language,cr_version,wa_inner_version':
                return self.j({'Language': 'zh-cn', 'cr_version': CR, 'wa_inner_version': INNER})
        if u.path == '/d/UFI-TOOLS-UPDATE/UFI-TOOLS%203.0.apk' or u.path == '/d/UFI-TOOLS-UPDATE/UFI-TOOLS 3.0.apk':
            self.send_response(200); self.send_header('Content-Length', str(len(APK))); self.end_headers(); self.wfile.write(APK); return
        if u.path == '/redirect.apk':
            self.send_response(302); self.send_header('Location', '/d/UFI-TOOLS-UPDATE/UFI-TOOLS%203.0.apk'); self.end_headers(); return
        if u.path == '/error.apk':
            b = b'<html>not found</html>'
            self.send_response(200); self.send_header('Content-Length', str(len(b))); self.end_headers(); self.wfile.write(b); return
        self.send_response(404); self.end_headers()

    def do_POST(self):
        n = int(self.headers.get('Content-Length', '0')); body = self.rfile.read(n).decode()
        if self.path == '/api/fs/list':
            req = json.loads(body)
            assert req == {'path': '/UFI-TOOLS-UPDATE', 'password': '', 'page': 1, 'per_page': 0, 'refresh': False}, req
            return self.j({'code': 200, 'message': 'success', 'data': {'content': [
                {'name': 'old.apk', 'is_dir': False, 'modified': '2025-01-01T00:00:00+08:00', 'size': 10},
                {'name': 'UFI-TOOLS 3.0.apk', 'is_dir': False, 'modified': '2026-09-30T12:00:00.123+08:00', 'size': len(APK)},
                {'name': 'notes.txt', 'is_dir': False, 'modified': '2026-10-30T00:00:00Z'},
                {'name': 'dir.apk', 'is_dir': True, 'modified': '2027-01-01T00:00:00Z'}], 'total': 4}})
        sys.stderr.write('BODY ' + body + '\n')
        q = parse_qs(body, keep_blank_values=True); f = {k: v[0] for k, v in q.items()}
        assert self.headers.get('Referer', '').endswith(':18080/index.html')
        assert self.headers.get('User-Agent') == 'Mozilla/5.0'
        if f.get('goformId') == 'LOGIN':
            ok = f['password'] == H(H(PWD) + LD) and f['isTest'] == 'false'
            return self.j({'result': '0' if ok else '3'}, cookie='stok="abc"; path=/; HttpOnly' if ok else None)
        assert self.headers.get('Cookie') == 'stok="abc"'
        ok = f.get('AD') == H(H(INNER + CR) + RD) and f.get('isTest') == 'false' and f.get('usb_port_switch') == '1'
        return self.j({'result': 'success' if ok else 'failure'})


ThreadingHTTPServer(('127.0.0.1', int(sys.argv[1])), Z).serve_forever()
