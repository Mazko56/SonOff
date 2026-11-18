import pathlib,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
HTML=(ROOT/'app/src/main/assets/index.html').read_text()
PROBE=(ROOT/'app/src/main/java/com/mazko/sonoffs60/LocalPairingProbe.java').read_text()
CONT=(ROOT/'app/src/main/java/com/mazko/sonoffs60/Controller.java').read_text()
SERVER=(ROOT/'app/src/main/java/com/mazko/sonoffs60/LocalHttpServer.java').read_text()
class AutoLocal(unittest.TestCase):
 def test_no_explanatory_pairing_ui(self):
  self.assertNotIn('setupScreen',HTML)
  self.assertNotIn('claimLocal()',HTML)
  self.assertNotIn('Ключ доступу',HTML)
  self.assertNotIn('Нова розетка',HTML)
  self.assertIn('Поділитися журналом',HTML)
 def test_safe_ap_network_gate(self):
  self.assertIn('getLinkAddresses()',PROBE)
  self.assertIn('TRANSPORT_WIFI',PROBE)
  self.assertIn('DeviceApNetwork.matches(ip,la.getPrefixLength())',PROBE)
  self.assertIn('if(chosen==null)throw',PROBE)
  self.assertIn('http://10.10.7.1/device',PROBE)
 def test_no_unsafe_bypass(self):
  self.assertIn('ACCESS.NOT_YET_AUTHORIZED',CONT)
  self.assertIn('if(d.encrypted && key.isEmpty())',CONT)
  self.assertNotIn('case "/api/devicekey"',SERVER)
  self.assertNotIn('case "/api/local-claim"',SERVER)
 def test_auto_pair_triggered_only_in_ap(self):
  self.assertIn('if(!ap) {',CONT)
  self.assertIn('if(lastApAttempt==0 || now-lastApAttempt>90000)',CONT)
  self.assertIn('network.execute(() ->',CONT)
if __name__=='__main__':unittest.main()
