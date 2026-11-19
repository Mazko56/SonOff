package com.mazko.sonoffs60;

import android.app.Activity;
import android.Manifest;
import android.os.Build;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.graphics.Color;

/** No external dependency: classic Activity, embedded browser panel and local server. */
public final class MainActivity extends Activity {
    private Controller controller;
    private LocalHttpServer panel;
    private WebView web;
    private final String localToken = makeToken();
    private static String makeToken() {
        byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private class NativeApi {
        @JavascriptInterface public String getAuthToken() {return localToken;}
        @JavascriptInterface public void shareLogs() {
            runOnUiThread(()->{
                Intent send=new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT,"Діагностика Sonoff S60");
                send.putExtra(Intent.EXTRA_TEXT,controller.diagnosticReport()+"\n"+controller.log.asText());
                startActivity(Intent.createChooser(send,"Поділитися діагностикою"));
            });
        }
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            controller=new Controller(getApplicationContext());
            panel=new LocalHttpServer(getApplicationContext(),controller,localToken);
            panel.start();
            web=new WebView(this);
            web.setBackgroundColor(Color.rgb(8,14,25));
            WebSettings settings=web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(false);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            web.addJavascriptInterface(new NativeApi(),"SonoffNative");
            final int localPort=panel.getPort();
            web.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest r) {
                    return !"127.0.0.1".equals(r.getUrl().getHost()) || r.getUrl().getPort()!=localPort;
                }
            });
            setContentView(web);
            web.loadUrl("http://127.0.0.1:"+localPort+"/");
            controller.begin();
            requestNearbyWifiPermissions();
        } catch(Exception e) {
            TextView t=new TextView(this);
            t.setText("Не вдалося запустити застосунок: "+e.getClass().getSimpleName()+" "+e.getMessage());
            t.setPadding(25,50,25,25);setContentView(t);
        }
    }
    /** System permission dialogs only; no changes to the HTML panel. */
    private void requestNearbyWifiPermissions() {
        ArrayList<String> missing=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=29 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        if(!missing.isEmpty())requestPermissions(missing.toArray(new String[0]),104);
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(requestCode,permissions,grants);
        if(requestCode==104 && controller!=null){
            boolean granted=true;
            for(int g:grants)if(g!=PackageManager.PERMISSION_GRANTED)granted=false;
            controller.log.add(granted?"AP.PERMISSION.GRANTED":"AP.PERMISSION.DENIED",
                    granted?"Nearby Wi-Fi scan permission granted":"AP fallback disabled; ordinary LAN discovery remains active");
            if(granted)controller.scanApAfterPermissions();
        }
    }
    @Override public void onResume() {
        super.onResume();
        if(controller!=null)controller.rescan();
    }
    @Override public void onDestroy() {
        if(controller!=null)controller.close();
        if(panel!=null)panel.stop();
        if(web!=null) {web.removeJavascriptInterface("SonoffNative");web.destroy();}
        super.onDestroy();
    }
}
