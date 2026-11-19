"""Compile actual AP/NetworkSpecifier code with limited Android stubs.
This catches Java syntax/API method signatures represented in the stubs,
not Gradle/SDK linkage or physical Wi-Fi behavior.
"""
import pathlib, subprocess, tempfile, unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java/com/mazko/sonoffs60'
STUBS={
'android/Manifest.java': 'package android; public final class Manifest {public static final class permission {public static final String ACCESS_FINE_LOCATION="x",NEARBY_WIFI_DEVICES="y";}}',
'android/content/pm/PackageManager.java': 'package android.content.pm; public final class PackageManager {public static final int PERMISSION_GRANTED=0;}',
'android/content/Intent.java': 'package android.content; public class Intent {public String getAction(){return "";}public boolean getBooleanExtra(String s,boolean d){return d;}}',
'android/content/IntentFilter.java': 'package android.content; public class IntentFilter {public IntentFilter(String s) {}}',
'android/content/BroadcastReceiver.java':'package android.content; public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}',
'android/content/Context.java': '''package android.content;public class Context {
 public static final String WIFI_SERVICE="wifi",CONNECTIVITY_SERVICE="connectivity"; public static final int RECEIVER_NOT_EXPORTED=4;
 public Context getApplicationContext(){return this;} public Object getSystemService(String a){return null;}
 public int checkSelfPermission(String permission){return 0;}
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter f){return null;}
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,int flags){return null;}
 public void unregisterReceiver(BroadcastReceiver r){} }''',
'android/os/Build.java': 'package android.os; public final class Build {public static final class VERSION{public static final int SDK_INT=35;}}',
'android/os/SystemClock.java':'package android.os;public final class SystemClock {public static long elapsedRealtime(){return 100000L;}}',
'android/net/Network.java':'''package android.net;import java.net.*;import java.io.*; public class Network {
 public URLConnection openConnection(URL url)throws IOException{return null;} }''',
'android/net/NetworkCapabilities.java':'''package android.net;public class NetworkCapabilities {
 public static final int TRANSPORT_WIFI=1,NET_CAPABILITY_INTERNET=12;
 public boolean hasTransport(int t){return true;} }''',
'android/net/LinkProperties.java':'''package android.net;import java.util.*;public class LinkProperties {
 public List<LinkAddress> getLinkAddresses(){return new ArrayList<>();} }''',
'android/net/LinkAddress.java':'''package android.net;import java.net.*; public class LinkAddress {
 public InetAddress getAddress(){return null;} public int getPrefixLength(){return 24;} }''',
'android/net/NetworkRequest.java':'''package android.net; public class NetworkRequest {public static class Builder {
 public Builder addTransportType(int t){return this;}public Builder removeCapability(int c){return this;}
 public Builder setNetworkSpecifier(Object o){return this;}public NetworkRequest build(){return new NetworkRequest();}}}''',
'android/net/ConnectivityManager.java':'''package android.net;public class ConnectivityManager {
 public Network[] getAllNetworks(){return new Network[0];}
 public LinkProperties getLinkProperties(Network n){return null;}
 public NetworkCapabilities getNetworkCapabilities(Network n){return null;}
 public void requestNetwork(NetworkRequest r,NetworkCallback cb,int ms){}
 public void unregisterNetworkCallback(NetworkCallback cb){}
 public abstract static class NetworkCallback {
 public void onAvailable(Network n){} public void onUnavailable(){} public void onLost(Network n){} } }''',
'android/net/wifi/ScanResult.java': 'package android.net.wifi;public class ScanResult{public String SSID;public long timestamp;public int level;}',
'android/net/wifi/WifiManager.java':'''package android.net.wifi;import java.util.*;public class WifiManager {
 public static final String SCAN_RESULTS_AVAILABLE_ACTION="a",EXTRA_RESULTS_UPDATED="b";
 public boolean startScan(){return true;}public List<ScanResult> getScanResults(){return new ArrayList<>();}}''',
'android/net/wifi/WifiNetworkSpecifier.java':'''package android.net.wifi;public class WifiNetworkSpecifier {public static class Builder {
 public Builder setSsid(String s){return this;}public Builder setWpa2Passphrase(String p){return this;}
 public WifiNetworkSpecifier build(){return new WifiNetworkSpecifier();}}}''',
'org/json/JSONObject.java':'''package org.json;public class JSONObject {public JSONObject(String s){} public JSONObject optJSONObject(String n){return null;}
 public String optString(String n,String d){return "";} }''',
'com/mazko/sonoffs60/LegacyPrivateServer.java': 'package com.mazko.sonoffs60; final class LegacyPrivateServer {static final int PORT=1081;}',
'com/mazko/sonoffs60/LegacyApProvision.java': 'package com.mazko.sonoffs60; import android.net.Network;import android.content.Context;final class LegacyApProvision {static boolean validSsid(String s){return true;}static boolean validPassword(String p){return true;}static void write(Context c,Network n,String s,String p,String i,int port,LogBook l){} }',
'com/mazko/sonoffs60/LanDiagnostics.java': 'package com.mazko.sonoffs60;final class LanDiagnostics {static boolean privateIpv4(String s){return true;}}',
'com/mazko/sonoffs60/LogBook.java':'''package com.mazko.sonoffs60;final class LogBook {void add(String a,String b){} }''',
'com/mazko/sonoffs60/Controller.java':'''package com.mazko.sonoffs60; final class Controller {
 void acceptApKey(LocalPairingProbe.Result r,String s)throws Exception{}void rescanLanOnly(){}
 void updateApProgress(String s,String r){} String privateServerStatus(){return "HTTPS";} }''',
}
class APJavaCompile(unittest.TestCase):
 def test_compile_actual_android_ap_classes(self):
  with tempfile.TemporaryDirectory() as tmp:
   base=pathlib.Path(tmp)
   for p,c in STUBS.items():
    f=base/p;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(c)
   actual=[JAVA/n for n in ('DeviceApNetwork.java','LegacyApIdentity.java','LocalPairingProbe.java','ApPairingManager.java')]
   cmd=['javac','-d',str(base/'classes'),*[str(f) for f in base.rglob('*.java')],*map(str,actual)]
   p=subprocess.run(cmd,capture_output=True,text=True,timeout=20)
   self.assertEqual(p.returncode,0,p.stderr)
if __name__=='__main__':unittest.main()
