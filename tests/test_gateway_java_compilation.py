"""Compile actual new gateway classes against typed Android contract stubs.
This checks Java syntax and API references but is NOT an Android SDK build.
"""
import pathlib,subprocess,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
SRC=ROOT/'app/src/main/java/com/mazko/sonoffs60'
STUBS={
'android/content/Context.java':'''package android.content;public class Context{public static final String CONNECTIVITY_SERVICE="connectivity";public Context getApplicationContext(){return this;}public Object getSystemService(String s){return null;}}''',
'android/net/ConnectivityManager.java':'''package android.net;public class ConnectivityManager {public Network getActiveNetwork(){return null;} public NetworkCapabilities getNetworkCapabilities(Network n){return null;}public LinkProperties getLinkProperties(Network n){return null;}}''',
'android/net/Network.java':'''package android.net;public class Network {}''',
'android/net/NetworkCapabilities.java':'''package android.net;public class NetworkCapabilities{public static final int TRANSPORT_WIFI=1;public boolean hasTransport(int t){return false;}}''',
'android/net/LinkProperties.java':'''package android.net;import java.util.*;public class LinkProperties {public List<LinkAddress> getLinkAddresses(){return Collections.emptyList();}}''',
'android/net/LinkAddress.java':'''package android.net;import java.net.*;public class LinkAddress {public InetAddress getAddress(){return null;}}''',
'android/security/keystore/KeyGenParameterSpec.java':'''package android.security.keystore;import java.security.spec.AlgorithmParameterSpec;import java.util.*;import java.math.*;import javax.security.auth.x500.X500Principal;public class KeyGenParameterSpec implements AlgorithmParameterSpec{public static class Builder{public Builder(String a,int b){}public Builder setKeySize(int s){return this;}public Builder setCertificateSubject(X500Principal a){return this;}public Builder setCertificateSerialNumber(BigInteger b){return this;}public Builder setCertificateNotBefore(Date d){return this;}public Builder setCertificateNotAfter(Date d){return this;}public Builder setDigests(String ...a){return this;}public Builder setSignaturePaddings(String... a){return this;}public KeyGenParameterSpec build(){return new KeyGenParameterSpec();}}}''',
'android/security/keystore/KeyProperties.java':'''package android.security.keystore;public class KeyProperties {public static final String KEY_ALGORITHM_RSA="RSA",DIGEST_SHA256="SHA-256",SIGNATURE_PADDING_RSA_PKCS1="PKCS1";public static final int PURPOSE_SIGN=1,PURPOSE_VERIFY=2;}''',
'org/json/JSONObject.java':'''package org.json;public class JSONObject {public JSONObject(){} public JSONObject(String s){}public JSONObject put(String k,Object v){return this;}public String optString(String k){return "";}public String optString(String k,String def){return def;}public int optInt(String k,int def){return def;}public Object opt(String k){return null;}public boolean optBoolean(String k){return false;}public boolean has(String k){return false;}public JSONObject optJSONObject(String k){return null;}public JSONArray optJSONArray(String k){return null;}public JSONObject getJSONObject(String k){return null;}public String toString(){return "{}";}}''',
'org/json/JSONArray.java':'''package org.json;public class JSONArray{public int length(){return 0;}public JSONObject optJSONObject(int i){return null;}}''',
'com/mazko/sonoffs60/Controller.java':'''package com.mazko.sonoffs60;import org.json.JSONObject;class Controller{LogBook log=new LogBook();JSONObject state(){return new JSONObject();}void relayOutlet(String s,boolean b,int i){}void importAuthorizedKey(String i,String k,String n,String u,String m){} }''',
'com/mazko/sonoffs60/SecureKeys.java':'''package com.mazko.sonoffs60;import android.content.Context;class SecureKeys{SecureKeys(Context c){}String get(String k){return "";}void put(String k,String v){}}''',
'com/mazko/sonoffs60/LogBook.java':'''package com.mazko.sonoffs60;class LogBook{void add(String a,String b){}}''',
'com/mazko/sonoffs60/LanDiagnostics.java':'''package com.mazko.sonoffs60;class LanDiagnostics{static boolean privateIpv4(String s){return true;}}''',
}
class JavaCompiles(unittest.TestCase):
 def _compile(self, extras=None):
  with tempfile.TemporaryDirectory() as work:
   base=pathlib.Path(work)
   for path,txt in STUBS.items():
    f=base/path;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(txt)
   extra=[]
   if extras:
    target=base/'com/mazko/sonoffs60/GatewaySelfTest.java';target.write_text(extras);extra=[str(target)]
   cmd=['javac','-encoding','UTF-8','-d',str(base/'classes'),*map(str,base.rglob('*.java')),str(SRC/'AndroidGatewayServer.java'),str(SRC/'EwelinkOAuth.java')]
   r=subprocess.run(cmd,capture_output=True,text=True,timeout=35)
   self.assertEqual(r.returncode,0,r.stderr)
   if extras:
    x=subprocess.run(['java','-cp',str(base/'classes'),'com.mazko.sonoffs60.GatewaySelfTest'],capture_output=True,text=True,timeout=10)
    self.assertEqual(x.returncode,0,x.stderr)
 def test_new_gateway_and_oauth_compile(self):self._compile()
 def test_oauth_region_and_callback_helpers(self):
  self._compile('''package com.mazko.sonoffs60;class GatewaySelfTest{public static void main(String[] args)throws Exception{
   if(!EwelinkOAuth.regionHost("eu").equals("https://eu-apia.coolkit.cc"))throw new Exception("eu host");
   if(!EwelinkOAuth.regionHost("cn").equals("https://cn-apia.coolkit.cn"))throw new Exception("cn host");
   try{EwelinkOAuth.regionHost("evil.com");throw new Exception("host bypass");}catch(IllegalArgumentException ok){}
   if(!EwelinkOAuth.parseQuery("code=abc%2B123&state=ok").get("code").equals("abc+123"))throw new Exception("callback parse");
   System.out.println("PASS");}}''')
# Browser authorization request URL and rejection of an unrelated callback are tested without network.
class OAuthUrlTests(JavaCompiles):
 def test_signed_login_url_does_not_embed_secret(self):
  self._compile('''package com.mazko.sonoffs60;class GatewaySelfTest {public static void main(String[] a)throws Exception {
   EwelinkOAuth oauth=new EwelinkOAuth(new Controller());
   String url=oauth.start("abcdEFGH12345678","secretForLocalDeveloperOnly","https://127.0.0.1/callback");
   if(!url.startsWith("https://c2ccdn.coolkit.cc/oauth/index.html?"))throw new Exception("OAuth host invalid");
   if(!url.contains("authorization=")||!url.contains("state=")||!url.contains("clientId="))throw new Exception("OAuth signed params missing");
   if(url.contains("secretForLocalDeveloperOnly"))throw new Exception("secret leaked");
   try {oauth.acceptCallback("https://127.0.0.1/callback?code=abcd&state=wrong","eu");throw new Exception("Invalid state accepted");}
   catch(IllegalArgumentException expected){}
   System.out.println("PASS");
 }}''')
if __name__=='__main__':unittest.main()
