"""Loopback-only TLS fixture proving the read-only desktop test script's transport.
This does not execute AndroidKeyStore, real Sonoff TLS or command handling.
"""
import importlib.util
import pathlib
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch

BASE=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('probe_private_server',BASE/'probe_private_server.py')
probe=importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)

class _TLSFixture:
    def __init__(self, ctx, code):
        self.code=code
        self.ctx=ctx
        self.sock=socket.socket(socket.AF_INET,socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        self.sock.bind(('127.0.0.1',0))
        self.sock.listen(1)
        self.port=self.sock.getsockname()[1]
        self.thread=threading.Thread(target=self.serve,daemon=True)
        self.thread.start()
    def serve(self):
        try:
            conn,_=self.sock.accept()
            with self.ctx.wrap_socket(conn,server_side=True) as tls:
                req=tls.recv(4096)
                if self.code==200:
                    assert b'GET /health' in req
                    payload=b'{}'
                    tls.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n'+payload)
                else:
                    assert b'GET /api/ws' in req
                    tls.sendall(b'HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n')
        finally:
            self.sock.close()
    def join(self):self.thread.join(timeout=3)

@unittest.skipUnless(shutil.which('openssl'),'openssL unavailable on test runner')
class RealTransportFixtureTests(unittest.TestCase):
    def test_probe_reports_both_real_local_tls_handshakes(self):
        with tempfile.TemporaryDirectory() as d:
            cert=pathlib.Path(d)/'test.crt';key=pathlib.Path(d)/'test.key'
            p=subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes',
                              '-keyout',str(key),'-out',str(cert),'-subj','/CN=lab-only',
                              '-days','1'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=15)
            self.assertEqual(p.returncode,0)
            ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(certfile=str(cert),keyfile=str(key))
            https=_TLSFixture(ctx,200)
            wss=_TLSFixture(ctx,101)
            with patch.object(probe,'local_ip',return_value='127.0.0.1'):
                result=probe.diagnose('127.0.0.1',https_port=https.port,wss_port=wss.port,timeout=3)
            https.join();wss.join()
            self.assertTrue(result['https_ok'],result)
            self.assertTrue(result['wss_ok'],result)
            self.assertTrue(result.get('https_tls_cipher'))

if __name__=='__main__':unittest.main()
