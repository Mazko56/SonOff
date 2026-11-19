package com.mazko.sonoffs60;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.SystemClock;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional on-device AP discovery / read-only claim for legacy Sonoff models.
 * Android Wi-Fi dialogs are controlled by the OS; this app NEVER silently switches Wi-Fi.
 * No AP reconfiguration, cloud login, firmware flashing or relay command is performed.
 * Firmware compatibility of this method with retail S60TPF is unverified.
 */
final class ApPairingManager {
    private static final long SCAN_INTERVAL_MS=45000;
    private static final long RETRY_INTERVAL_MS=120000;
    private static final long MAX_SCAN_AGE_US=120000000L;
    private final Context context;
    private final Controller controller;
    private final LogBook log;
    private final WifiManager wifi;
    private final ConnectivityManager connectivity;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final BroadcastReceiver receiver;
    private volatile boolean closed=false;
    private volatile boolean connecting=false;
    private volatile long lastScan=0;
    private volatile long lastAttempt=0;
    private volatile long lastNoneLog=0;
    private volatile String lastSsid="";
    private ConnectivityManager.NetworkCallback callback;
    private boolean registered=false;

    ApPairingManager(Context context,Controller controller,LogBook log) {
        this.context=context.getApplicationContext();this.controller=controller;this.log=log;
        this.wifi=(WifiManager)this.context.getSystemService(Context.WIFI_SERVICE);
        this.connectivity=(ConnectivityManager)this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
        receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){
            if(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction()))io.execute(()->inspectResults("scan broadcast"));
        }};
        if(Build.VERSION.SDK_INT>=29)try {
            IntentFilter filter=new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            if(Build.VERSION.SDK_INT>=33)this.context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);
            else this.context.registerReceiver(receiver,filter);
            registered=true;
        }catch(Exception e){log.add("AP.SCAN.RECEIVER",e.getClass().getSimpleName());}
    }

    /** On app start / existing Scan button only. Frequency-limited to avoid Android Wi-Fi scan throttling. */
    void scan() {
        if(closed||connecting)return;
        if(Build.VERSION.SDK_INT<29){log.add("AP.UNSUPPORTED","WifiNetworkSpecifier requires Android 10+; LAN discovery remains active");return;}
        if(wifi==null||connectivity==null){log.add("AP.UNAVAILABLE","Wi-Fi connectivity services unavailable");return;}
        if(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED ||
          (Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED)) {
            log.add("AP.PERMISSION.MISSING","Nearby Wi-Fi / Location permission not granted; normal LAN continues");return;
        }
        long now=SystemClock.elapsedRealtime();
        if(lastScan>0 && now-lastScan<SCAN_INTERVAL_MS){log.add("AP.SCAN.THROTTLED","Last AP check within 45 s; cached results will be evaluated");io.execute(()->inspectResults("cached"));return;}
        lastScan=now;
        io.execute(()->{
            try {
                boolean accepted=wifi.startScan();
                log.add("AP.SCAN.START","Legacy ITEAD AP nearby scan; Android accepted="+accepted+
                    "; system Location toggle may be required");
                // A scan may be throttled; use only fresh cached results (do not join stale SSIDs).
                inspectResults("cached");
            }catch(SecurityException ex){log.add("AP.PERMISSION.DENIED",ex.getClass().getSimpleName());}
            catch(Exception ex){log.add("AP.SCAN.FAIL",ex.getClass().getSimpleName());}
        });
    }

    private void inspectResults(String source) {
        if(closed||connecting||wifi==null)return;
        try {
            List<ScanResult> all=wifi.getScanResults();
            ArrayList<ScanResult> found=new ArrayList<>();
            long nowUs=SystemClock.elapsedRealtime()*1000L;
            if(all!=null)for(ScanResult r:all) {
                if(!LegacyApIdentity.isPotentialSonoffAp(r.SSID))continue;
                if(r.timestamp<=0 || Math.abs(nowUs-r.timestamp)>MAX_SCAN_AGE_US)continue;
                boolean already=false;for(ScanResult old:found)if(old.SSID.equals(r.SSID)){already=true;break;}
                if(!already)found.add(r);
            }
            int otherSonoff=0;
            if(all!=null)for(ScanResult r:all)if(r.SSID!=null && r.SSID.startsWith("SONOFF_") &&
                r.timestamp>0 && Math.abs(nowUs-r.timestamp)<MAX_SCAN_AGE_US)otherSonoff++;
            if(otherSonoff>0)log.add("AP.SCAN.OTHER","Found "+otherSonoff+
                 " SONOFF_* hotspot(s); may be Matter/OpenEdge, not probed with legacy /device");
            if(found.isEmpty()) {
                if(lastNoneLog==0 || SystemClock.elapsedRealtime()-lastNoneLog>30000) {
                    lastNoneLog=SystemClock.elapsedRealtime();
                    log.add("AP.SCAN.NONE","No recent ITEAD-* setup hotspot; LAN control remains unchanged; source="+source);
                    controller.updateApProgress("","AP не виявлено; штатний LAN продовжує працювати");
                }
                return;
            }
            if(found.size()>1) {
                log.add("AP.SCAN.MULTIPLE","Multiple ITEAD hotspots found ("+found.size()+"); no automatic target choice; retry with one plug in pairing mode");
                controller.updateApProgress("","Знайдено кілька AP; вибір заблоковано задля безпеки");return;
            }
            ScanResult target=found.get(0);
            long now=SystemClock.elapsedRealtime();
            if(lastSsid.equals(target.SSID)&&now-lastAttempt<RETRY_INTERVAL_MS)return;
            lastSsid=target.SSID;lastAttempt=now;
            connect(target.SSID);
        }catch(SecurityException e){log.add("AP.SCAN.DENIED","Wi-Fi results unavailable: "+e.getClass().getSimpleName());}
        catch(Exception e){log.add("AP.SCAN.FAIL",e.getClass().getSimpleName());}
    }

    private synchronized void connect(String ssid) {
        if(closed||connecting)return;
        connecting=true;
        try {
            WifiNetworkSpecifier specifier=new WifiNetworkSpecifier.Builder()
                .setSsid(ssid).setWpa2Passphrase("12345678").build();
            NetworkRequest request=new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier).build();
            final ConnectivityManager.NetworkCallback cb=new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    log.add("AP.CONNECT.AVAILABLE","Android provided local-only Wi-Fi network for "+LegacyApIdentity.safeSsid(ssid));
                    io.execute(()->probe(network,ssid,this));
                }
                @Override public void onUnavailable() {
                    log.add("AP.CONNECT.UNAVAILABLE","Android did not connect to "+LegacyApIdentity.safeSsid(ssid)+
                        "; user approval, availability and WPA2 support are required");
                    controller.updateApProgress(ssid,"Android не надав AP-з’єднання");
                    finish(this);
                }
                @Override public void onLost(Network network) {
                    log.add("AP.CONNECT.LOST","Setup Wi-Fi disconnected; local LAN discovery remains enabled");
                    finish(this);
                }
            };
            callback=cb;
            controller.updateApProgress(ssid,"Знайдено AP; очікується підтвердження Android");
            log.add("AP.CONNECT.REQUEST","Found "+LegacyApIdentity.safeSsid(ssid)+
                "; requesting system-confirmed local-only Wi-Fi connection; no manual key entry");
            connectivity.requestNetwork(request,cb,30000);
        }catch(Exception e){connecting=false;callback=null;
            controller.updateApProgress(ssid,"AP-з’єднання не вдалося встановити");
            log.add("AP.CONNECT.ERROR",e.getClass().getSimpleName()+"; normal LAN remains available");}
    }

    private void probe(Network network,String ssid,ConnectivityManager.NetworkCallback cb) {
        try {
            LocalPairingProbe.Result result=LocalPairingProbe.readFromNetwork(context,network,ssid,log);
            controller.acceptApKey(result,ssid);
            log.add("AP.PROBE.SUCCESS","Local factory credential imported for "+result.id+
                "; Wi-Fi onboarding is NOT implied; no cloud contact");
        }catch(Exception e){controller.updateApProgress(ssid,"AP /device не надав ключ ("+LegacyApIdentity.safeError(e.getMessage())+")");
            log.add("AP.PROBE.UNAVAILABLE",LegacyApIdentity.safeSsid(ssid)+" "+
            e.getClass().getSimpleName()+"; "+LegacyApIdentity.safeError(e.getMessage())+
            "; this model may not expose /device");}
        finally {finish(cb);}
    }
    private synchronized void finish(ConnectivityManager.NetworkCallback cb) {
        if(callback!=cb)return;
        try{connectivity.unregisterNetworkCallback(cb);}catch(Exception ignored){}
        callback=null;connecting=false;
        log.add("AP.RETURN.LAN","Released temporary setup Wi-Fi request; home LAN available when Android reconnects");
        controller.rescanLanOnly();
    }
    boolean isConnecting(){return connecting;}
    synchronized void close() {
        closed=true;
        try {if(callback!=null)connectivity.unregisterNetworkCallback(callback);}catch(Exception ignored){}
        callback=null;connecting=false;
        if(registered)try{context.unregisterReceiver(receiver);}catch(Exception ignored){}
        registered=false;io.shutdownNow();
    }
}
