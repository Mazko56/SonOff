"""Local-first security assertions and public-read-only lab fixtures; no physical device claims."""
import json
import pathlib
import sys
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
ROOT=pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tests'))
from fake_s60 import make_handler, DEVICE_ID, STATE
P=ROOT/'app/src/main/java/com/mazko/sonoffs60'

class Diagnostics(unittest.TestCase):
 def test_ui_byte_identical_to_input(self):
  import hashlib
  current=(ROOT/'app/src/main/assets/index.html').read_bytes()
  self.assertEqual(hashlib.sha256(current).hexdigest(),'44bc8c4f5d48bf0929e118c6bf86fd440872250e5439bc603dc653518fa89b35')
 def test_all_read_only_modes_and_key_guard(self):
  c=(P/'Controller.java').read_text()
  self.assertIn('readOnlyDiagnosis(readyDevice)',c)
  self.assertIn('"getState","info"',c)
  self.assertIn('LAN.READONLY.REPLY',c)
  self.assertIn('if(d.encrypted && key.isEmpty())',c)
  self.assertIn('if(!d.authenticated)',c)
  self.assertIn('d.observeReadOnly(plain)',c)
  self.assertNotIn('eWeLink.com',c)
  helper=(P/'LanDiagnostics.java').read_text()
  self.assertIn('wifi.getSocketFactory()',helper)
  self.assertIn('wifiNetwork(context).openConnection(url)',helper)
  self.assertIn('privateIpv4(ip)',helper)
  self.assertIn('"name"',helper)
  self.assertIn('"productmodel"',helper)
  self.assertNotIn('devicekey","',helper)
 def test_missing_key_default_simulator_rejects_read_only(self):
  self.check_http(False,401)
 def test_optional_public_lab_telemetry(self):
  self.check_http(True,0)
 def check_http(self, public, expected):
  server=ThreadingHTTPServer(('127.0.0.1',0),make_handler('lab-test-key-only',public))
  t=threading.Thread(target=server.serve_forever,daemon=True);t.start()
  try:
   payload={'sequence':'1','deviceid':DEVICE_ID,'selfApikey':'123','encrypt':False,'data':{}}
   def req(path):
    r=urllib.request.Request('http://127.0.0.1:%d/zeroconf/%s'%(server.server_port,path),data=json.dumps(payload).encode(),headers={'Content-Type':'application/json'},method='POST')
    with urllib.request.urlopen(r,timeout=3) as resp:return json.load(resp)
   response=req('getState')
   self.assertEqual(response['error'],expected)
   if public:self.assertEqual(response['data']['voltage'],STATE['voltage'])
   # Even the read-only test fixture must refuse plaintext relay writes.
   self.assertEqual(req('switches')['error'],401)
  finally:server.shutdown();server.server_close()

if __name__=='__main__':unittest.main(verbosity=2)
