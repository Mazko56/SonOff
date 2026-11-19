"""Compile new private HTTPS/WSS server Java using lightweight Android API stubs.
This is NOT an SDK build or live TLS verification.
"""
import subprocess,tempfile,pathlib,unittest
BASE=pathlib.Path(__file__).resolve().parents[1]
JAVA=BASE/'app/src/main/java/com/mazko/sonoffs60'
STUBS={
'android/content/Context.java':'''package android.content;public class Context {public static String CONNECTIVITY_SERVICE="connectivity";public Context getApplicationContext(){return this;}public Object getSystemService(String s){return null;}}''',
'android/net/ConnectivityManager.java':'''package android.net;public class ConnectivityManager {public Network getActiveNetwork(){return null;} public NetworkCapabilities getNetworkCapabilities(Network n){return null;}public LinkProperties getLinkProperties(Network n){return null;}}''',
'android/net/Network.java':'''package android.net;import java.net.*;public class Network {public URLConnection openConnection(URL u){return null;}}''',
'android/net/NetworkCapabilities.java':'''package android.net;public class NetworkCapabilities{public static final int TRANSPORT_WIFI=1;public boolean hasTransport(int t){return false;}}''',
'android/net/LinkProperties.java':'''package android.net;import java.util.*;public class LinkProperties{public List<LinkAddress> getLinkAddresses(){return java.util.Collections.emptyList();}}''',
'android/net/LinkAddress.java':'''package android.net;import java.net.*;public class LinkAddress{public InetAddress getAddress(){return null;}}''',
'android/security/keystore/KeyGenParameterSpec.java':'''package android.security.keystore;import java.security.spec.AlgorithmParameterSpec;import java.util.Date;import java.math.BigInteger;import javax.security.auth.x500.X500Principal;public class KeyGenParameterSpec implements AlgorithmParameterSpec {public static class Builder{public Builder(String a,int p){}public Builder setKeySize(int s){return this;}public Builder setCertificateSubject(X500Principal x){return this;} public Builder setCertificateSerialNumber(BigInteger x){return this;}public Builder setCertificateNotBefore(Date x){return this;}public Builder setCertificateNotAfter(Date x){return this;}public Builder setDigests(String...s){return this;}public Builder setSignaturePaddings(String...s){return this;} public KeyGenParameterSpec build(){return new KeyGenParameterSpec();}}}''',
'android/security/keystore/KeyProperties.java':'''package android.security.keystore;public class KeyProperties{public static final String KEY_ALGORITHM_RSA="RSA",DIGEST_SHA256="SHA-256",SIGNATURE_PADDING_RSA_PKCS1="PKCS1";public static final int PURPOSE_SIGN=1,PURPOSE_VERIFY=2;}''',
'org/json/JSONObject.java':'''package org.json;public class JSONObject{public JSONObject(){}public JSONObject(String s){}public JSONObject put(String k,Object v){return this;}public String optString(String k,String def){return def;}public String optString(String k){return "";}public int optInt(String k,int def){return def;}public JSONObject optJSONObject(String k){return null;}public JSONArray optJSONArray(String k){return null;}public boolean has(String k){return false;}public Object get(String k){return null;}public String toString(){return "{}";}}''',
'org/json/JSONArray.java':'''package org.json;public class JSONArray{public JSONArray(){}public JSONArray put(Object v){return this;}public int length(){return 0;}public JSONObject optJSONObject(int i){return null;}public String optString(int i,String def){return def;}}''',
'com/mazko/sonoffs60/LanDiagnostics.java':'''package com.mazko.sonoffs60;import org.json.JSONObject;public class LanDiagnostics{static boolean privateIpv4(String ip){return ip.startsWith("192.168.");}static String shortText(String s){return s;}static String telemetrySummary(JSONObject o){return "";}}''',
'com/mazko/sonoffs60/LogBook.java':'''package com.mazko.sonoffs60;public class LogBook{public void add(String k,String v){}}''',
'com/mazko/sonoffs60/Controller.java':'''package com.mazko.sonoffs60;import org.json.*;public class Controller{final LogBook log=new LogBook();boolean legacyAuthorize(String i,String k){return true;}void legacyRegister(String i,String m,String f,String p){}void legacyState(String i,JSONObject v){}JSONObject legacyQuery(String i,JSONArray a){return new JSONObject();}void legacyDisconnect(String i){} }''',
'com/mazko/sonoffs60/LocalPairingProbe.java':'''package com.mazko.sonoffs60;import android.net.Network;import android.content.Context;class LocalPairingProbe{static boolean isDeviceApNetwork(Context c,Network n){return true;}}''',
}
class PrivateCloudJavaCompile(unittest.TestCase):
 def test_new_server_and_ap_classes_compile(self):
  with tempfile.TemporaryDirectory() as d:
   b=pathlib.Path(d)
   for path,data in STUBS.items():
    p=b/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(data)
   new=['LegacyCloudProtocol.java','LegacyDispatchAdmission.java','LegacyPrivateServer.java','LegacyApProvision.java']
   cmd=['javac','-d',str(b/'classes'),*map(str,b.rglob('*.java')),*[str(JAVA/n) for n in new]]
   r=subprocess.run(cmd,capture_output=True,text=True,timeout=25)
   self.assertEqual(r.returncode,0,r.stderr)
 def test_constant_time_key_checks(self):
  with tempfile.TemporaryDirectory() as d:
   b=pathlib.Path(d)
   for path,data in STUBS.items():
    p=b/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(data)
   t=b/'com/mazko/sonoffs60/PrivateProtocolSelfTest.java';t.write_text('''package com.mazko.sonoffs60;class PrivateProtocolSelfTest{public static void main(String[] args){
   if(!LegacyCloudProtocol.equalSecret("secret","secret"))throw new RuntimeException("same key rejected");
   if(LegacyCloudProtocol.equalSecret("secret","other"))throw new RuntimeException("bad key accepted");
   if(LegacyCloudProtocol.equalSecret("", ""))throw new RuntimeException("empty key accepted");
   if(!LegacyCloudProtocol.isDeviceId("10013bec50"))throw new RuntimeException("id rejected");
   if(LegacyCloudProtocol.isDeviceId("not-valid-long"))throw new RuntimeException("bad id accepted");
   if(!LegacyCloudProtocol.isWebSocketPath("/")||!LegacyCloudProtocol.isWebSocketPath("/api/ws")||
      LegacyCloudProtocol.isWebSocketPath("/admin"))throw new RuntimeException("bad WS path map");
   System.out.println("PASS PRIVATE PROTOCOL");}}''')
   cmd=['javac','-d',str(b/'classes'),*map(str,b.rglob('*.java')),str(JAVA/'LegacyCloudProtocol.java'),str(JAVA/'LegacyDispatchAdmission.java')]
   r=subprocess.run(cmd,capture_output=True,text=True,timeout=25)
   self.assertEqual(r.returncode,0,r.stderr)
   result=subprocess.run(['java','-cp',str(b/'classes'),'com.mazko.sonoffs60.PrivateProtocolSelfTest'],capture_output=True,text=True,timeout=10)
   self.assertEqual(result.returncode,0,result.stderr)
   self.assertIn('PASS',result.stdout)
 def test_actual_websocket_frame_decoder(self):
  with tempfile.TemporaryDirectory() as d:
   b=pathlib.Path(d)
   for path,data in STUBS.items():
    f=b/path;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(data)
   t=b/'com/mazko/sonoffs60/PrivateFrameSelfTest.java'
   t.write_text(r'''package com.mazko.sonoffs60;
import java.io.*;import java.lang.reflect.*;import java.nio.charset.StandardCharsets;
class PrivateFrameSelfTest {
 static Object parse(byte[] frame)throws Exception {
   Method m=LegacyPrivateServer.class.getDeclaredMethod("readFrame",InputStream.class);
   m.setAccessible(true);
   try {return m.invoke(null,new ByteArrayInputStream(frame));}
   catch(InvocationTargetException e){throw (Exception)e.getCause();}
 }
 public static void main(String[] args)throws Exception {
   byte[] data="hello-device".getBytes(StandardCharsets.UTF_8),mask={1,2,3,4};
   ByteArrayOutputStream wire=new ByteArrayOutputStream();
   wire.write(0x81);wire.write(0x80|data.length);wire.write(mask);
   for(int i=0;i<data.length;i++)wire.write(data[i]^mask[i%4]);
   Object frame=parse(wire.toByteArray());
   Field f=frame.getClass().getDeclaredField("data");f.setAccessible(true);
   if(!java.util.Arrays.equals(data,(byte[])f.get(frame)))throw new RuntimeException("MASKED_FRAME_WRONG");
   try{parse(new byte[]{(byte)0x81,1,65});throw new RuntimeException("UNMASKED_ACCEPTED");}
   catch(IOException expected){}
   try{parse(new byte[]{(byte)0x81,(byte)0xff});throw new RuntimeException("HUGE_FRAME_ACCEPTED");}
   catch(IOException expected){}
   System.out.println("PASS WS FRAME");
 }
} ''')
   cmd=['javac','-d',str(b/'classes'),*map(str,b.rglob('*.java')),*[str(JAVA/n) for n in ('LegacyCloudProtocol.java','LegacyDispatchAdmission.java','LegacyPrivateServer.java')]]
   r=subprocess.run(cmd,capture_output=True,text=True,timeout=25)
   self.assertEqual(r.returncode,0,r.stderr)
   r=subprocess.run(['java','-cp',str(b/'classes'),'com.mazko.sonoffs60.PrivateFrameSelfTest'],capture_output=True,text=True,timeout=10)
   self.assertEqual(r.returncode,0,r.stderr)
   self.assertIn('PASS WS FRAME',r.stdout)
if __name__=='__main__':unittest.main()
