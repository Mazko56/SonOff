package com.mazko.sonoffs60;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Android NSD + raw mDNS fallback for S60TPF missing A-record firmware anomaly. */
final class LanDiscovery {
    private final Context context;
    private final Controller controller;
    private final LogBook log;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener listener;
    private WifiManager.MulticastLock lock;
    private volatile boolean running;
    private volatile boolean forceQuery;
    private volatile boolean rawSocketReady;
    private volatile boolean nsdReady;
    private volatile String discoveryError="";
    boolean isSearchReady(){return rawSocketReady||nsdReady;}
    String getSearchError(){return discoveryError;}
    private MulticastSocket socket;
    private Thread worker;
    private final Set<String> resolving=new HashSet<>();
    private final Pattern idPattern=Pattern.compile("(?i)ewelink[_-]([a-z0-9]{10})");
    LanDiscovery(Context context,Controller controller,LogBook log) {this.context=context;this.controller=controller;this.log=log;}
    private String id(String instance) {
        Matcher m=idPattern.matcher(instance==null?"":instance);
        return m.find()?m.group(1).toLowerCase(Locale.ROOT):"";
    }
    synchronized void start() {
        if(running)return;
        running=true;rawSocketReady=false;nsdReady=false;discoveryError="";
        log.add("SCAN", "Discovery start: Android NSD + passive/active mDNS 224.0.0.251:5353");
        try {
            WifiManager wifi=(WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            lock=wifi.createMulticastLock("SonoffS60Mdns");lock.setReferenceCounted(false);lock.acquire();
        } catch(Exception ex) {log.add("SCAN.WIFI", "Cannot acquire Wi-Fi multicast lock: "+ex.getClass().getSimpleName());}
        startNsd();
        worker=new Thread(this::multicastLoop,"S60-mDNS");worker.setDaemon(true);worker.start();
    }
    private void startNsd() {
        try {
            nsd=(NsdManager)context.getSystemService(Context.NSD_SERVICE);
            listener=new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String t) {nsdReady=true;log.add("NSD", "Service browser started: "+t);}
                @Override public void onServiceFound(NsdServiceInfo info) {
                    String deviceId=id(info.getServiceName());
                    if(deviceId.isEmpty())return;
                    synchronized(resolving) {if(!resolving.add(deviceId))return;}
                    log.add("NSD.FOUND", "Discovered "+deviceId+"; resolving host/TXT");
                    try {
                        nsd.resolveService(info,new NsdManager.ResolveListener() {
                            @Override public void onServiceResolved(NsdServiceInfo resolved) {
                                synchronized(resolving){resolving.remove(deviceId);}
                                Map<String,String> attrs=new HashMap<>();
                                if(resolved.getAttributes()!=null) for(Map.Entry<String,byte[]> e:resolved.getAttributes().entrySet()) {
                                    attrs.put(e.getKey().toLowerCase(Locale.ROOT),new String(e.getValue(),java.nio.charset.StandardCharsets.UTF_8));
                                }
                                attrs.put("service_instance",resolved.getServiceName());
                                String host=resolved.getHost()==null?"":resolved.getHost().getHostAddress();
                                controller.onDiscover(deviceId,host,resolved.getPort(),attrs,"Android NSD");
                            }
                            @Override public void onResolveFailed(NsdServiceInfo s,int code) {
                                synchronized(resolving){resolving.remove(deviceId);}
                                log.add("NSD.NO_A",deviceId+" resolve failed code="+code+"; raw UDP source fallback may recover host");
                            }
                        });
                    } catch(Exception ex) {
                        synchronized(resolving){resolving.remove(deviceId);}
                        log.add("NSD.ERROR",ex.getClass().getSimpleName());
                    }
                }
                @Override public void onServiceLost(NsdServiceInfo info) {log.add("NSD.LOST",id(info.getServiceName()));}
                @Override public void onDiscoveryStopped(String t) {log.add("NSD","stopped");}
                @Override public void onStartDiscoveryFailed(String t,int code) {nsdReady=false;discoveryError="Android NSD failed ("+code+")";log.add("NSD.ERROR","start code="+code);}
                @Override public void onStopDiscoveryFailed(String t,int code) {log.add("NSD.ERROR","stop code="+code);}
            };
            nsd.discoverServices("_ewelink._tcp.",NsdManager.PROTOCOL_DNS_SD,listener);
        } catch(Exception ex) {log.add("NSD.ERROR",ex.getClass().getSimpleName()+": "+ex.getMessage());}
    }
    synchronized void rescan() {
        if(!running){start();return;}
        forceQuery=true;
    }
    private void multicastLoop() {
        try {
            MulticastSocket s=new MulticastSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(5353));
            s.setSoTimeout(1000);
            InetAddress group=InetAddress.getByName("224.0.0.251");
            s.joinGroup(group);
            socket=s;rawSocketReady=true;
            byte[] query=MdnsPacket.discoveryQuery();
            long lastQuery=0;
            while(running) {
                if(forceQuery || System.currentTimeMillis()-lastQuery>9000) {
                    try {s.send(new DatagramPacket(query,query.length,group,5353));}
                    catch(Exception e){log.add("MDNS.SEND",e.getClass().getSimpleName());}
                    lastQuery=System.currentTimeMillis();forceQuery=false;
                }
                byte[] bytes=new byte[9000];DatagramPacket packet=new DatagramPacket(bytes,bytes.length);
                try {s.receive(packet);handle(packet);}
                catch(java.net.SocketTimeoutException ignored) { }
                catch(Exception ex) {if(running)log.add("MDNS.ERROR",ex.getClass().getSimpleName()+" "+ex.getMessage());}
            }
            s.close();
        } catch(Exception e) {
            rawSocketReady=false;discoveryError="mDNS UDP bind failed: "+e.getClass().getSimpleName();
            if(running)log.add("MDNS.BIND.ERROR",e.getClass().getSimpleName()+" "+e.getMessage());
        }
    }
    private void handle(DatagramPacket p) throws Exception {
        List<MdnsPacket.Record> list=MdnsPacket.parse(p.getData(),p.getLength());
        Map<String,String> addressByHost=new HashMap<>();
        Map<String,Integer> ports=new HashMap<>();
        Map<String,String> hosts=new HashMap<>();
        for(MdnsPacket.Record r:list) {
            if(r.type==1)addressByHost.put(r.name.toLowerCase(Locale.ROOT),r.address);
            if(r.type==33 && r.name.toLowerCase(Locale.ROOT).endsWith("._ewelink._tcp.local")) {
                ports.put(r.name.toLowerCase(Locale.ROOT),r.port);
                hosts.put(r.name.toLowerCase(Locale.ROOT),r.host.toLowerCase(Locale.ROOT));
            }
        }
        for(MdnsPacket.Record r:list) {
            if(r.type!=16 || !r.name.toLowerCase(Locale.ROOT).endsWith("._ewelink._tcp.local"))continue;
            String deviceId=id(r.name);
            if(deviceId.isEmpty())continue;
            String instance=r.name.toLowerCase(Locale.ROOT);
            String ip=addressByHost.getOrDefault(hosts.getOrDefault(instance,""),"");
            String source="mDNS TXT + SRV/A";
            if(ip.isEmpty()) {
                // Only infer direct same-LAN sender; never trust public/loopback multicast relays as device hosts.
                if(!p.getAddress().isSiteLocalAddress() && !p.getAddress().isLinkLocalAddress())continue;
                ip=p.getAddress().getHostAddress();source="mDNS TXT + UDP source fallback (no A record)";
                log.add("MDNS.NO_A",deviceId+" used packet source "+ip+"; verify if network uses mDNS relay");
            }
            String sender=p.getAddress().getHostAddress();
            if(!p.getAddress().isSiteLocalAddress() && !p.getAddress().isLinkLocalAddress())sender="";
            Map<String,String> attrs=new HashMap<>(r.txt);
            attrs.put("service_instance",r.name);
            attrs.put("service_host",hosts.getOrDefault(instance,""));
            controller.onDiscover(deviceId,ip,ports.getOrDefault(instance,8081),attrs,source,sender);
        }
    }
    synchronized void stop() {
        running=false;rawSocketReady=false;nsdReady=false;
        if(socket!=null)socket.close();
        if(nsd!=null && listener!=null) {
            try{nsd.stopServiceDiscovery(listener);}catch(Exception ignored){}
        }
        if(lock!=null && lock.isHeld()) {try{lock.release();}catch(Exception ignored){}}
    }
}
