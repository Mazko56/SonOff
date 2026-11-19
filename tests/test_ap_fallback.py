"""AP fallback guard tests; tests do NOT claim S60TPF stock-firmware support."""
import pathlib,subprocess,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java/com/mazko/sonoffs60'
class TestApFallback(unittest.TestCase):
 def test_legacy_ap_policy(self):
  with tempfile.TemporaryDirectory() as tmp:
   files=[JAVA/'LegacyApIdentity.java',ROOT/'tests/LegacyApIdentitySelfTest.java']
   p=subprocess.run(['javac','-d',tmp,*map(str,files)],capture_output=True,text=True)
   self.assertEqual(p.returncode,0,p.stderr)
   p=subprocess.run(['java','-cp',tmp,'com.mazko.sonoffs60.LegacyApIdentitySelfTest'],capture_output=True,text=True)
   self.assertEqual(p.returncode,0,p.stderr)
   self.assertIn('PASS',p.stdout)
 def test_no_credentials_in_logs_or_cloud_api(self):
  ap=(JAVA/'ApPairingManager.java').read_text()
  probe=(JAVA/'LocalPairingProbe.java').read_text()
  ctrl=(JAVA/'Controller.java').read_text()
  manifest=(ROOT/'app/src/main/AndroidManifest.xml').read_text()
  self.assertIn('WifiNetworkSpecifier.Builder()',ap)
  self.assertIn('.setSsid(ssid)',ap)
  self.assertIn('.removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)',ap)
  self.assertIn('ConnectivityManager.NetworkCallback()',ap)
  self.assertIn('AP.PROBE.UNAVAILABLE',ap)
  self.assertIn('chosen.openConnection(url)',probe)
  self.assertIn('AP_UNEXPECTED_DHCP_SUBNET',probe)
  self.assertIn('http://10.10.7.1/device',probe)
  self.assertIn('AP_DEVICE_ENDPOINT_HTTP_',probe)
  self.assertIn('keys.put(result.id,result.key)',ctrl)
  self.assertIn('ACCESS_KEY_REQUIRED',ctrl)
  self.assertIn('android.permission.NEARBY_WIFI_DEVICES',manifest)
  self.assertIn('android.permission.CHANGE_NETWORK_STATE',manifest)
  activity=(JAVA/'MainActivity.java').read_text()
  self.assertIn('requestNearbyWifiPermissions()',activity)
  self.assertIn('onRequestPermissionsResult',activity)
  self.assertNotIn('coolkit.cc',ap+probe+ctrl)
  self.assertNotIn('requestNetwork(request,cb,30000);\n            connectivity.bindProcessToNetwork',ap)
 def test_never_rewrite_hardware(self):
  probe=(JAVA/'LocalPairingProbe.java').read_text()
  ap=(JAVA/'ApPairingManager.java').read_text()
  self.assertNotIn('/zeroconf/switch',probe+ap)
  self.assertNotIn('POST',probe+ap)
  self.assertNotIn('firmwareImage',probe+ap)
if __name__=='__main__':unittest.main()
