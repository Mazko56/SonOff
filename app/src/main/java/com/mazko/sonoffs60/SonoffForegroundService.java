package com.mazko.sonoffs60;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/** Keep the Sonoff private server and discovery alive while the user leaves the UI.
 * Android may still stop a foreground service, notably under user/system restrictions.
 */
public final class SonoffForegroundService extends Service {
    private static final int NOTIFICATION_ID=22071;
    private static final String CHANNEL="sonoff_local_controller";
    @Override public void onCreate(){
        super.onCreate();
        try {
            Controller c=SonoffRuntime.get(this);
            NotificationManager nm=(NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
            if(Build.VERSION.SDK_INT>=26&&nm!=null){
                NotificationChannel ch=new NotificationChannel(CHANNEL,"Локальне керування Sonoff",NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Підключення до розумних пристроїв у локальній мережі");
                nm.createNotificationChannel(ch);
            }
            Intent open=new Intent(this,MainActivity.class);
            PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification notification=new Notification.Builder(this,CHANNEL)
                .setContentTitle("Sonoff — локальний сервер")
                .setContentText("Підключення та керування пристроями в локальній мережі")
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true).setContentIntent(pi).build();
            startForeground(NOTIFICATION_ID,notification);
            c.log.add("RUNTIME.FOREGROUND","Connected-device foreground service started; OS may still stop it");
        }catch(Exception e){
            try {SonoffRuntime.get(this).log.add("RUNTIME.FOREGROUND.FAIL",e.getClass().getSimpleName());}catch(Exception ignored){}
            stopSelf();
        }
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_STICKY;}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){SonoffRuntime.stop();super.onDestroy();}
}
