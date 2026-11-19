"""Tests the documented DIY profile and safe routing; no devicekey bypass."""
import json, subprocess, threading, unittest, urllib.request, sys
from pathlib import Path
from http.server import ThreadingHTTPServer
sys.path.insert(0,str(Path(__file__).parent))
from fake_s60 import make_handler, mdns_advertisement, DEVICE_ID
ROOT=Path(__file__).resolve().parents[1]
class MultiProtocolTests(unittest.TestCase):
 def test_diy_info_switch_signal_and_no_getstate(self):
  server=ThreadingHTTPServer(('127.0.0.1',0),make_handler('unused',diy=True))
  th=threading.Thread(target=server.serve_forever,daemon=True);th.start()
  try:
   def do(command,data={}):
    payload=json.dumps({'deviceid':DEVICE_ID,'data':data}).encode()
    rq=urllib.request.Request(f'http://127.0.0.1:{server.server_port}/zeroconf/{command}',data=payload,headers={'Content-Type':'application/json'})
    return json.load(urllib.request.urlopen(rq,timeout=3))
   self.assertEqual(do('info')['error'],0)
   self.assertEqual(do('switch',{'switch':'on'})['data']['switch'],'on')
   self.assertEqual(do('info')['data']['switch'],'on')
   self.assertEqual(do('signal_strength')['error'],0)
   self.assertEqual(do('getState')['error'],404)
  finally:server.shutdown();server.server_close()
 def test_diy_mdns_txt_contains_profile(self):
  packet=mdns_advertisement(8081,diy=True)
  self.assertIn(b'type=diy_plug',packet)
  self.assertNotIn(b'encrypt=true',packet)
 def test_router_javac(self):
  import tempfile
  with tempfile.TemporaryDirectory() as directory:
   proc=subprocess.run(['javac','-d',directory,str(ROOT/'app/src/main/java/com/mazko/sonoffs60/ProtocolRouter.java'),str(ROOT/'tests/ProtocolRouterSelfTest.java')],capture_output=True,text=True)
   self.assertEqual(proc.returncode,0,proc.stderr)
   proc=subprocess.run(['java','-cp',directory,'com.mazko.sonoffs60.ProtocolRouterSelfTest'],capture_output=True,text=True)
   self.assertEqual(proc.returncode,0,proc.stderr)
   self.assertIn('PASS',proc.stdout)
 def test_html_byte_preserved_except_nothing(self):
  expected='44bc8c4f5d48bf0929e118c6bf86fd440872250e5439bc603dc653518fa89b35'
  self.assertEqual(__import__('hashlib').sha256((ROOT/'app/src/main/assets/index.html').read_bytes()).hexdigest(),expected)
if __name__=='__main__':unittest.main()
