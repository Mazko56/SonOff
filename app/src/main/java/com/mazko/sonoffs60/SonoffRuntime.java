package com.mazko.sonoffs60;

import android.content.Context;

/** Single owner shared by foreground service and activity; avoids duplicate NSD scans/servers. */
final class SonoffRuntime {
    private static Controller controller;
    private SonoffRuntime(){}
    static synchronized Controller get(Context ctx){
        if(controller==null){
            controller=new Controller(ctx.getApplicationContext());
            controller.begin();
        }
        return controller;
    }
    static synchronized void stop(){
        if(controller!=null){controller.close();controller=null;}
    }
}
