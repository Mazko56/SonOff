#!/usr/bin/env python3
"""Protocol-only unit tests. Passing these does not prove physical SONOFF compatibility."""
import json
import os
import socket
import sys
import threading
import time
import unittest
import urllib.request
from pathlib import Path
from http.server import ThreadingHTTPServer
sys.path.insert(0,str(Path(__file__).resolve().parent))
from fake_s60 import encrypt_obj, decrypt_obj, mdns_advertisement, make_handler, DEVICE_ID, STATE

class ProtocolTests(unittest.TestCase):
    def test_aes_roundtrip(self):
        raw = {'switches': [{'outlet': 0, 'switch': 'on'}], 'operSide': 1}
        blob=encrypt_obj(raw,'test-unique-key')
        self.assertEqual(decrypt_obj(blob['data'],blob['iv'],'test-unique-key'),raw)
        with self.assertRaises(Exception):decrypt_obj(blob['data'],blob['iv'],'WRONG')

    def test_advertisement_without_address(self):
        packet=mdns_advertisement(8081,True)
        self.assertIn(b'_ewelink',packet)
        self.assertIn(DEVICE_ID.encode(),packet)
        self.assertNotIn(socket.inet_aton('127.0.0.1'),packet)

    def test_end_to_end_http_switch_and_getstate(self):
        secret='local-test-only-secret'
        server=ThreadingHTTPServer(('127.0.0.1',0),make_handler(secret))
        th=threading.Thread(target=server.serve_forever,daemon=True);th.start()
        try:
            for command,body in [('switches',{'switches':[{'outlet':0,'switch':'on'}],'operSide':1}),('getState',{}),('sledonline',{'sledOnline':'off'}),('configure',{'configure':'get'}),('configure',{'configure':[{'outlet':0,'startup':'on'}]})]:
                payload={'sequence':'1234','deviceid':DEVICE_ID,'selfApikey':'123',**encrypt_obj(body,secret)}
                raw=json.dumps(payload,separators=(',',':')).encode()
                url=f'http://127.0.0.1:{server.server_port}/zeroconf/{command}'
                request=urllib.request.Request(url,data=raw,headers={'Content-Type':'application/json'},method='POST')
                with urllib.request.urlopen(request,timeout=3) as resp:
                    reply=json.load(resp)
                self.assertEqual(reply['error'],0)
                state=decrypt_obj(reply['data'],reply['iv'],secret)
                if command in ('switches','getState','sledonline'):
                    self.assertEqual(state['switches'][0]['switch'],'on')
                    self.assertEqual(state['power'],724)
                if command=='sledonline':self.assertEqual(state['sledOnline'],'off')
                if command=='configure':self.assertTrue(state['configure'])
        finally:server.shutdown();server.server_close()

if __name__=='__main__':unittest.main(verbosity=2)
