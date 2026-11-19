"""Compile embedded loopback panel server using Android model stubs (not full SDK)."""
import pathlib,subprocess,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
SRC=ROOT/'app/src/main/java/com/mazko/sonoffs60'
STUBS={
'android/content/res/AssetManager.java': 'package android.content.res;import java.io.*;public class AssetManager{public InputStream open(String p)throws IOException{return null;}}',
'android/content/Context.java':'package android.content;import android.content.res.*;public class Context{public AssetManager getAssets(){return null;}}',
'org/json/JSONObject.java':'''package org.json; public class JSONObject {
public JSONObject(){}public JSONObject(String s){} public JSONObject put(String s,Object o){return this;}
public String optString(String s){return "";}public String optString(String s,String v){return v;}
public boolean optBoolean(String s){return false;}public int optInt(String s,int x){return x;}
public String toString(){return "{}";}
}''',
'org/json/JSONArray.java':'package org.json;public class JSONArray{}',
'com/mazko/sonoffs60/LogBook.java':'package com.mazko.sonoffs60;import org.json.JSONArray;class LogBook{void add(String a,String b){}JSONArray asJson(){return new JSONArray();}}',
'com/mazko/sonoffs60/EwelinkOAuth.java':'package com.mazko.sonoffs60;import org.json.JSONObject;class EwelinkOAuth{JSONObject info(){return new JSONObject();}String start(String a,String b,String c){return "";}void acceptCallback(String a,String b){}}',
'com/mazko/sonoffs60/AndroidGatewayServer.java':'package com.mazko.sonoffs60;class AndroidGatewayServer{static final int PORT=8765;String status(){return "";}}',
'com/mazko/sonoffs60/Controller.java':'''package com.mazko.sonoffs60;import org.json.JSONObject;class Controller {
LogBook log=new LogBook();EwelinkOAuth oauth=new EwelinkOAuth();AndroidGatewayServer gateway=new AndroidGatewayServer();
JSONObject state(){return new JSONObject();}void rescan(){}void confirmModel(String id){}
void relayOutlet(String id,boolean on,int outlet){}void poll(String id){}void led(String id,boolean on){}
void startup(String id,String mode){}
}''',
}
class PanelCompile(unittest.TestCase):
 def test_loopback_panel_java_compiles(self):
  with tempfile.TemporaryDirectory() as tmp:
   path=pathlib.Path(tmp)
   for target,content in STUBS.items():
    p=path/target;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
   cmd=['javac','-d',str(path/'classes'),*map(str,path.rglob('*.java')),str(SRC/'LocalHttpServer.java')]
   result=subprocess.run(cmd,capture_output=True,text=True,timeout=30)
   self.assertEqual(result.returncode,0,result.stderr)
if __name__=='__main__':unittest.main()
