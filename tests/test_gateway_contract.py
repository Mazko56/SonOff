"""Code-contract checks do not substitute for real Android SDK build or device tests."""
import pathlib,re,subprocess,unittest,shutil,json
ROOT=pathlib.Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java/com/mazko/sonoffs60'
UI=(ROOT/'app/src/main/assets/index.html').read_text()
CONT=(JAVA/'Controller.java').read_text()
GATEWAY=(JAVA/'AndroidGatewayServer.java').read_text()
OAUTH=(JAVA/'EwelinkOAuth.java').read_text()
PANEL=(JAVA/'LocalHttpServer.java').read_text()

class GatewayContracts(unittest.TestCase):
 def test_app_label_and_version(self):
  self.assertIn('versionName \'2.0.0-clean-gateway\'',(ROOT/'app/build.gradle').read_text())
 def test_gateway_private_wifi_only(self):
  self.assertIn('TRANSPORT_WIFI',GATEWAY)
  self.assertIn('LanDiagnostics.privateIpv4',GATEWAY)
  self.assertIn('InetAddress.getByName(ip),PORT',GATEWAY)
 def test_gateway_has_owner_token(self):
  self.assertIn('MessageDigest.isEqual',GATEWAY)
  self.assertIn('AndroidKeyStore', (JAVA/'SecureKeys.java').read_text())
  self.assertIn('Bearer ',GATEWAY)
 def test_gateway_protocol(self):
  for route in ['/v1/health','/v1/devices','/v1/command','/v1/ws']:
   self.assertIn(route,GATEWAY)
  self.assertIn('SocketTimeoutException',GATEWAY)
  self.assertIn('CLIENT_MASK_REQUIRED',GATEWAY)
 def test_oauth_official_endpoints(self):
  for value in ('c2ccdn.coolkit.cc/oauth/index.html','/v2/user/oauth/token','/v2/device/thing','HmacSHA256','X-CK-Appid'):
   self.assertIn(value,OAUTH)
 def test_oauth_state_and_redirect_verification(self):
  self.assertIn('OAUTH_STATE_MISMATCH',OAUTH)
  self.assertIn('REDIRECT_MISMATCH',OAUTH)
  self.assertIn('MessageDigest.isEqual',OAUTH)
 def test_one_time_oauth_no_account_password(self):
  self.assertNotIn('/v2/user/login',OAUTH)
  self.assertNotIn('EWELINK_PASSWORD',OAUTH)
  self.assertIn('appSecret=""',OAUTH)
  self.assertNotIn('tokenResponse.toString()',OAUTH)
 def test_dont_reconfigure_device(self):
  for text in ('10.10.7.1/ap','/api/provision','serverName','LegacyPrivateServer'):
   self.assertNotIn(text,CONT)
   self.assertNotIn(text,PANEL)
   self.assertNotIn(text,UI)
  self.assertNotIn('CHANGE_WIFI_STATE',(ROOT/'app/src/main/AndroidManifest.xml').read_text())
 def test_encrypted_device_still_requires_key(self):
  self.assertIn('if(d.encrypted && key.isEmpty())',CONT)
  self.assertIn('if(!d.hasKey && d.encrypted)',CONT)
  self.assertIn('if(!d.authenticated)',CONT)
 def test_hw_acks_are_required(self):
  self.assertIn('RELAY.CONFIRMED',CONT)
  self.assertIn('NO_RELAY_CONFIRMATION',CONT)
  self.assertIn('confirmed",false',GATEWAY)
 def test_oauth_changes_lan_authorization_not_wifi(self):
  self.assertIn('keys.put(id,key)',CONT)
  self.assertIn('autoRead(target,true)',CONT)
 def test_app_ui_preserved(self):
  for word in ('Мої пристрої','Пошук пристроїв','Журнал подій','Поділитися журналом'):
   self.assertIn(word,UI)
  self.assertIn('oauthStart()',UI)
  self.assertIn('oauthImport()',UI)
 def test_no_stale_html_callbacks(self):
  callbacks=set(re.findall(r'(?:onclick|onchange)="([a-zA-Z]\w*)\(',UI));callbacks.update(re.findall(r'(?:onclick|onchange)=\\?"([a-zA-Z]\w*)\(',UI))
  for name in callbacks:self.assertRegex(UI,rf'(?:async\s+)?function\s+{name}\s*\(',name)
 def test_javascript_syntax(self):
  node=shutil.which('node')
  if not node:self.skipTest('node not installed')
  script=UI.split('<script>',1)[1].split('</script>',1)[0]
  r=subprocess.run([node,'--check','-'],input=script,capture_output=True,text=True,timeout=15)
  self.assertEqual(r.returncode,0,r.stderr)
 def test_no_unwanted_project_files(self):
  self.assertFalse((JAVA/'LegacyApProvision.java').exists())
  self.assertFalse((JAVA/'LegacyPrivateServer.java').exists())
 def test_secure_ws_transport_and_http(self):
  self.assertIn('SSLServerSocket',GATEWAY)
  self.assertIn('SSLSocket',GATEWAY)
  self.assertIn('setInstanceFollowRedirects(false)',OAUTH)
if __name__=='__main__':unittest.main()
