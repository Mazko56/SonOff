import json,threading,unittest,urllib.error,urllib.request
from http.server import ThreadingHTTPServer
from fake_legacy_ap import make_handler
class TestLegacyAPFixture(unittest.TestCase):
 def run_fixture(self,mode):
  server=ThreadingHTTPServer(('127.0.0.1',0),make_handler(mode=mode))
  t=threading.Thread(target=server.serve_forever,daemon=True);t.start()
  try:
   return urllib.request.urlopen('http://127.0.0.1:%d/device'%server.server_port,timeout=2)
  finally:server.shutdown();server.server_close()
 def test_get_device_returns_legacy_fields(self):
  x=self.run_fixture('success')
  result=json.loads(x.read());x.close()
  self.assertEqual(result['deviceid'],'1000abcdef')
  self.assertEqual(result['apikey'],'lab-test-key-only')
 def test_missing_key_or_endpoint_produces_failure(self):
  x=self.run_fixture('no-key');result=json.loads(x.read());x.close()
  self.assertNotIn('apikey',result)
  with self.assertRaises(urllib.error.HTTPError):self.run_fixture('missing')
if __name__=='__main__':unittest.main()
