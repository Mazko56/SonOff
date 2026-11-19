"""Compile key Android application Java classes against minimal contract stubs.
This does NOT replace a real Gradle+Android SDK build, but it catches Java syntax/type errors.
"""
import subprocess, tempfile, unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'app/src/main/java/com/mazko/sonoffs60'
STUBS={
'org/json/JSONException.java':'''package org.json; public class JSONException extends Exception {public JSONException() {} public JSONException(String s){super(s);}}''',
'org/json/JSONArray.java':'''package org.json; public class JSONArray {
 public JSONArray(){} public JSONArray put(Object a){return this;} public int length(){return 0;}
 public JSONObject optJSONObject(int i){return null;} public String optString(int i,String def){return def;}
}''',
'org/json/JSONObject.java':'''package org.json; import java.util.*; public class JSONObject {
 public static final Object NULL=new Object(); public JSONObject(){} public JSONObject(String s)throws JSONException{}
 public JSONObject put(String s,Object v)throws JSONException{return this;}
 public boolean has(String s){return false;} public Object get(String s)throws JSONException{return null;}
 public String getString(String s)throws JSONException{return "";} public Object opt(String s){return null;}
 public String optString(String s){return "";} public String optString(String s,String fallback){return fallback;}
 public int optInt(String s,int fallback){return fallback;} public JSONObject optJSONObject(String s){return null;}
 public JSONArray optJSONArray(String s){return null;} public Iterator<String> keys(){return Collections.emptyIterator();}
 public String toString(){return "{}";}
}''',
'android/content/SharedPreferences.java':'''package android.content; public interface SharedPreferences {
 boolean getBoolean(String key,boolean fallback); Editor edit();
 interface Editor { Editor putBoolean(String key,boolean val); void apply(); }
}''',
'android/content/Context.java':'''package android.content; public class Context {
 public static final int MODE_PRIVATE=0; public Context getApplicationContext(){return this;}
 public SharedPreferences getSharedPreferences(String s,int m){return null;}
}''',
'com/mazko/sonoffs60/SecureKeys.java':'''package com.mazko.sonoffs60; import android.content.Context;
 final class SecureKeys { SecureKeys(Context c){} String get(String id){return "";} void put(String id,String key){} }''',
'com/mazko/sonoffs60/LanDiscovery.java':'''package com.mazko.sonoffs60; import android.content.Context;
 final class LanDiscovery {LanDiscovery(Context c,Controller b,LogBook log){} void start(){} void stop(){} void rescan(){}
 boolean isSearchReady(){return true;} String getSearchError(){return "";} }''',
'com/mazko/sonoffs60/LanDiagnostics.java':'''package com.mazko.sonoffs60; import android.content.Context; import org.json.JSONObject;
 import java.util.Map; import java.net.*; import java.io.*;
 final class LanDiagnostics {static String wifiSnapshot(Context c){return "";} static String shortText(String s){return s;}
 static String jsonKeys(JSONObject j){return "";} static String telemetrySummary(JSONObject j){return "";}
 static String txtSummary(Map<String,String> m){return "";} static boolean privateIpv4(String s){return true;}
 static Socket connectTcp(Context c,String ip,int port,int timeout)throws IOException{return null;}
 static HttpURLConnection deviceConnection(Context c,String ip,int port,String cmd)throws IOException{return null;} }''',
'com/mazko/sonoffs60/AndroidGatewayServer.java': 'package com.mazko.sonoffs60;import android.content.Context; final class AndroidGatewayServer { AndroidGatewayServer(Context c,Controller ctrl){} void startOrRefresh(){} void stop(){} String status(){return "";} }',
'com/mazko/sonoffs60/EwelinkOAuth.java': 'package com.mazko.sonoffs60; final class EwelinkOAuth {EwelinkOAuth(Controller c){} void close(){} }',

}
class CompileStubs(unittest.TestCase):
 def test_compile_controller_and_data_model(self):
  with tempfile.TemporaryDirectory() as folder:
   base=Path(folder)
   for path,contents in STUBS.items():
    p=base/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(contents)
   paths=[str(p) for p in base.rglob('*.java')]
   paths += [str(SRC/n) for n in ('SonoffProfiles.java','ProtocolRouter.java','Controller.java','LanCrypto.java','S60Device.java','LogBook.java')]
   result=subprocess.run(['javac','-d',str(base/'classes'),*paths],capture_output=True,text=True,timeout=30)
   self.assertEqual(result.returncode,0,result.stderr)
if __name__=='__main__':unittest.main()
