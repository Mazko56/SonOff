"""Compile the new Android foreground-service source against API-shaped stubs.
Not a substitute for an actual Android SDK build.
"""
import pathlib,subprocess,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java/com/mazko/sonoffs60'
STUBS={
'android/os/IBinder.java':'package android.os;public interface IBinder {}',
'android/os/Build.java':'package android.os;public class Build {public static class VERSION{public static int SDK_INT=35;}}',
'android/content/Context.java':'''package android.content;public class Context {public static final String NOTIFICATION_SERVICE="notification";public Context getApplicationContext(){return this;}public Object getSystemService(String name){return null;}}''',
'android/content/Intent.java':'package android.content;public class Intent {public Intent(Context c,Class<?> cls){}}',
'android/app/Service.java':'''package android.app;import android.content.*;import android.os.*;public class Service extends Context{public static final int START_STICKY=1;public void onCreate(){}public int onStartCommand(Intent i,int f,int id){return 1;}public IBinder onBind(Intent i){return null;}public void onDestroy(){}public void startForeground(int id,Notification n){}public void stopSelf(){} }''',
'android/app/Notification.java':'''package android.app;import android.content.*;public class Notification {public static class Builder{public Builder(Context c,String id){} public Builder setContentTitle(String s){return this;}public Builder setContentText(String s){return this;}public Builder setSmallIcon(int i){return this;}public Builder setOngoing(boolean b){return this;}public Builder setContentIntent(PendingIntent p){return this;}public Notification build(){return new Notification();}}}''',
'android/app/NotificationManager.java':'''package android.app;public class NotificationManager {public static final int IMPORTANCE_LOW=2;public void createNotificationChannel(NotificationChannel c){}}''',
'android/app/NotificationChannel.java':'''package android.app;public class NotificationChannel {public NotificationChannel(String id,String name,int importance){}public void setDescription(String d){}}''',
'android/app/PendingIntent.java':'''package android.app;import android.content.*;public class PendingIntent {public static final int FLAG_UPDATE_CURRENT=2,FLAG_IMMUTABLE=4;public static PendingIntent getActivity(Context c,int req,Intent intent,int flags){return new PendingIntent();}}''',
'com/mazko/sonoffs60/R.java':'''package com.mazko.sonoffs60;public class R {public static class drawable{public static final int ic_launcher=1;}}''',
'com/mazko/sonoffs60/MainActivity.java':'''package com.mazko.sonoffs60;public class MainActivity {}''',
'com/mazko/sonoffs60/LogBook.java':'''package com.mazko.sonoffs60;public class LogBook{public void add(String a,String b){}}''',
'com/mazko/sonoffs60/Controller.java':'''package com.mazko.sonoffs60;import android.content.Context;class Controller {final LogBook log=new LogBook();Controller(Context c){}void begin(){}void close(){}}''',
}
class BackgroundServiceCompile(unittest.TestCase):
 def test_service_compiles(self):
  with tempfile.TemporaryDirectory() as tmp:
   base=pathlib.Path(tmp)
   for path,src in STUBS.items():
    f=base/path;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(src)
   actual=[JAVA/'SonoffRuntime.java',JAVA/'SonoffForegroundService.java']
   cmd=['javac','-d',str(base/'cls'),*map(str,base.rglob('*.java')),*map(str,actual)]
   p=subprocess.run(cmd,capture_output=True,text=True,timeout=20)
   self.assertEqual(p.returncode,0,p.stderr)
if __name__=='__main__':unittest.main()
