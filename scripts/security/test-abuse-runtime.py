"""Run against ONLY the isolated Redis/NGINX runtime produced by start-isolated-test-runtime.sh."""
import concurrent.futures
import http.client
import http.server
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]
LUA = (ROOT / 'src/main/resources/redis/abuse-token-bucket.lua').read_text()

def redis(*args):
    with socket.create_connection(('127.0.0.1', 16379), timeout=2) as s:
        chunks = [str(v).encode() for v in args]
        s.sendall(b'*%d\r\n' % len(chunks) + b''.join(b'$%d\r\n' % len(v) + v + b'\r\n' for v in chunks))
        stream = s.makefile('rb')
        def read():
            line = stream.readline(); prefix, payload = line[:1], line[1:-2]
            if prefix == b'-': raise RuntimeError(payload.decode())
            if prefix == b':': return int(payload)
            if prefix == b'+': return payload.decode()
            if prefix == b'$':
                length = int(payload)
                if length < 0: return None
                value = stream.read(length); stream.read(2); return value.decode()
            if prefix == b'*': return [read() for _ in range(int(payload))]
            raise RuntimeError('Invalid isolated Redis response')
        result = read(); stream.close(); return result

class RedisAbuseTests(unittest.TestCase):
    def setUp(self):
        # Never FLUSHDB: even this fixture deletes only its own random namespace.
        self.prefix = 'abuse:fixture:' + uuid.uuid4().hex
    def keys(self, actor='a', ip='ip'):
        return [self.prefix + ':' + x for x in (ip, ip+':strikes', ip+':cooldown', actor, actor+':strikes', actor+':cooldown')]
    def acquire(self, actor='a', ip='ip', multiplier=10):
        return redis('EVAL', LUA, 6, *self.keys(actor, ip), 5, multiplier, 600, 900, 3)
    def now(self):
        t = redis('TIME'); return int(t[0])*1000 + int(t[1])//1000
    def test_concurrency_reserves_exactly_capacity(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
            results = list(pool.map(lambda _: self.acquire(), range(30)))
        self.assertEqual(sum(r[0] == 0 for r in results), 5)
        self.assertGreater(redis('PTTL', self.keys()[3]), 0)
        self.assertLessEqual(redis('PTTL', self.keys()[3]), 120000)
    def test_changing_ip_does_not_reset_subject(self):
        for _ in range(5): self.assertEqual(self.acquire()[0], 0)
        self.assertEqual(self.acquire(ip='another-ip')[2], 2)
    def test_shared_ip_does_not_blame_fresh_subject(self):
        for actor in range(10):
            for _ in range(5): self.assertEqual(self.acquire(actor=str(actor))[0], 0)
        result = self.acquire(actor='innocent')
        self.assertEqual(result, [1, 2, 1])
        self.assertEqual(redis('EXISTS', self.keys('innocent')[4]), 0)
        self.assertEqual(redis('EXISTS', self.keys('innocent')[3]), 0)
    def test_distinct_minutes_trigger_non_extending_cooldown(self):
        keys = self.keys(); now = self.now()
        redis('ZADD', keys[4], now-120000, str(now//60000-2), now-60000, str(now//60000-1))
        redis('HSET', keys[3], 'tokens', 0, 'at', now)
        self.assertEqual(self.acquire()[0], 2)
        before = redis('PTTL', keys[5])
        for _ in range(10): self.assertEqual(self.acquire()[0], 2)
        self.assertLessEqual(redis('PTTL', keys[5]), before)
        redis('PEXPIRE', keys[5], 80); time.sleep(.12)
        self.assertNotEqual(self.acquire()[0], 2)
    def test_retries_in_one_minute_and_old_strikes(self):
        keys = self.keys(); now = self.now()
        redis('ZADD', keys[4], now-700000, 'old')
        redis('HSET', keys[3], 'tokens', 0, 'at', now)
        for _ in range(10): self.assertEqual(self.acquire()[0], 1)
        self.assertEqual(redis('ZCARD', keys[4]), 1)
    def test_cooldown_keeps_other_ip_subject_untouched(self):
        keys = self.keys(); redis('SET', keys[2], 1, 'PX', 2000)
        self.assertEqual(self.acquire(actor='new')[2], 1)
        self.assertEqual(redis('EXISTS', self.keys('new')[3]), 0)
    def test_ws_subject_only_and_refill(self):
        for _ in range(5): self.assertEqual(self.acquire(multiplier=0)[0], 0)
        keys = self.keys(); redis('HSET', keys[3], 'tokens', 0, 'at', self.now()-60000)
        self.assertEqual(self.acquire(multiplier=0)[0], 0)
        self.assertEqual(redis('EXISTS', keys[0]), 0)
    def tearDown(self):
        for key in redis('KEYS', self.prefix + ':*'): redis('DEL', key)

class Echo(http.server.BaseHTTPRequestHandler):
    def do_GET(self): self.respond()
    def do_POST(self): self.respond()
    def respond(self):
        length = int(self.headers.get('Content-Length', 0)); self.rfile.read(length)
        data = json.dumps({k: self.headers.get(k) for k in ('X-Real-IP', 'X-Forwarded-For', 'Forwarded', 'CF-Connecting-IP')}).encode()
        self.send_response(200); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
    def log_message(self, *_): pass

class GatewayTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.upstream = http.server.ThreadingHTTPServer(('127.0.0.1',0), Echo)
        threading.Thread(target=cls.upstream.serve_forever,daemon=True).start()
    @classmethod
    def tearDownClass(cls): cls.upstream.shutdown(); cls.upstream.server_close()
    def start(self, observe=False):
        self.directory = tempfile.TemporaryDirectory(prefix='chargeops-nginx-')
        folder = Path(self.directory.name); (folder/'logs').mkdir()
        with socket.socket() as s: s.bind(('127.0.0.1',0)); self.port=s.getsockname()[1]
        config = (ROOT/'gateway/domain-templates/default.conf.template').read_text()
        for variable in ('API_UPSTREAM','KEYCLOAK_UPSTREAM','GRAFANA_UPSTREAM'):
            config = config.replace('${'+variable+'}', '127.0.0.1:'+str(self.upstream.server_port))
        config = config.replace('${ABUSE_GATEWAY_DRY_RUN}', 'on' if observe else 'off')
        config = config.replace('listen 80', 'listen '+str(self.port))
        # Isolated loopback fixture represents the single trusted connector peer.
        config = config.replace('set_real_ip_from 172.30.240.2;', 'set_real_ip_from 127.0.0.2;')
        config = config.replace('/etc/nginx/domain_proxy_params', str(ROOT/'gateway/domain-proxy_params'))
        config = config.replace('/var/log/nginx/chargeops/access.log', str(folder/'access.log'))
        temps = '\n'.join(f'{name}_temp_path {folder/name};' for name in ('client_body','proxy','fastcgi','uwsgi','scgi'))
        path=folder/'nginx.conf'; path.write_text(f'daemon off; master_process off; pid {folder}/nginx.pid;\nevents {{}}\nhttp {{\n'+temps+'\n'+config+'\n}')
        binary=Path(sys.argv[1])/'usr/sbin/nginx'
        env=dict(os.environ); env['LD_LIBRARY_PATH']=str(Path(sys.argv[1])/'usr/lib/x86_64-linux-gnu')
        checked = subprocess.run([str(binary),'-t','-e','stderr','-p',str(folder)+'/', '-c',str(path)],env=env,capture_output=True,text=True)
        if checked.returncode: raise RuntimeError(checked.stderr)
        self.process=subprocess.Popen([str(binary),'-e','stderr','-p',str(folder)+'/', '-c',str(path)],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
        for _ in range(100):
            try:
                with socket.create_connection(('127.0.0.1',self.port),timeout=.1): return
            except OSError: time.sleep(.02)
        raise RuntimeError('Isolated NGINX did not start')
    def request(self,path,method='GET',peer='127.0.0.1',host='api.chargeops.online',ip='198.51.100.9'):
        conn=http.client.HTTPConnection('127.0.0.1',self.port,timeout=3,source_address=(peer,0))
        conn.request(method,path,headers={'Host':host,'CF-Connecting-IP':ip,'X-Forwarded-For':'203.0.113.8','Forwarded':'for=203.0.113.8','Origin':'https://console.chargeops.online'})
        response=conn.getresponse(); result=(response.status,dict(response.getheaders()),response.read()); conn.close(); return result
    def test_spoofed_headers_and_trusted_connector(self):
        self.start()
        _,_,body=self.request('/api/v1/stations'); parsed=json.loads(body)
        self.assertEqual(parsed['X-Real-IP'],'127.0.0.1'); self.assertIsNone(parsed['Forwarded']); self.assertIsNone(parsed['CF-Connecting-IP'])
        _,_,body=self.request('/api/v1/stations',peer='127.0.0.2'); parsed=json.loads(body)
        self.assertEqual(parsed['X-Real-IP'],'198.51.100.9'); self.assertEqual(parsed['X-Forwarded-For'],'198.51.100.9')
    def test_auth_limits_callbacks_and_api_contract(self):
        self.start()
        replies=[self.request('/realms/chargeops/login-actions/authenticate','POST',host='auth.chargeops.online') for _ in range(15)]
        limited=[r for r in replies if r[0]==429]; self.assertTrue(limited)
        self.assertEqual(json.loads(limited[0][2])['error']['code'],'SEC_001'); self.assertIn('Retry-After',limited[0][1])
        self.assertEqual(limited[0][1]['Access-Control-Allow-Origin'],'https://console.chargeops.online')
        for _ in range(15):
            self.assertEqual(self.request('/realms/chargeops/broker/google/endpoint?code=fixture',host='auth.chargeops.online')[0],200)
            self.assertEqual(self.request('/resources/fixture.js',host='auth.chargeops.online')[0],200)
    def test_token_and_handshake_limits(self):
        self.start()
        self.assertIn(429,[self.request('/realms/chargeops/protocol/openid-connect/token','POST',host='auth.chargeops.online')[0] for _ in range(35)])
        self.assertIn(429,[self.request('/ws')[0] for _ in range(15)])
    def test_observe_never_rejects(self):
        self.start(observe=True)
        for _ in range(15): self.assertEqual(self.request('/realms/chargeops/login-actions/authenticate','POST',host='auth.chargeops.online')[0],200)
    def tearDown(self):
        if hasattr(self,'process'):
            self.process.terminate(); self.process.communicate(timeout=5)
        if hasattr(self,'directory'): self.directory.cleanup()

if __name__ == '__main__':
    if len(sys.argv)!=2 or not sys.argv[1].startswith('/tmp/chargeops-abuse.'):
        raise SystemExit('Pass the isolated /tmp/chargeops-abuse.* runtime directory')
    unittest.main(argv=[sys.argv[0]],verbosity=2)
