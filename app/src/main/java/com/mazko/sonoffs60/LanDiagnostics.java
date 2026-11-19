package com.mazko.sonoffs60;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import org.json.JSONObject;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Safe local-only diagnostic helpers. Never expose tokens, keys, Wi-Fi SSID or raw payloads. */
final class LanDiagnostics {
    private LanDiagnostics() {}

    static boolean privateIpv4(String value) {
        if (value==null) return false;
        String[] p=value.split("\\.",-1);
        if(p.length!=4) return false;
        int[] bytes=new int[4];
        for(int i=0;i<4;i++) {
            if(p[i].isEmpty() || p[i].length()>3) return false;
            for(int n=0;n<p[i].length();n++) if(p[i].charAt(n)<'0'||p[i].charAt(n)>'9')return false;
            try {bytes[i]=Integer.parseInt(p[i]);}catch(Exception e){return false;}
            if(bytes[i]>255)return false;
        }
        return bytes[0]==10 || (bytes[0]==172 && bytes[1]>=16 && bytes[1]<=31)
                || (bytes[0]==192 && bytes[1]==168);
    }

    /** Obtain a Wi-Fi transport with a private LAN address; ignore mobile-data networks. */
    private static Network wifiNetwork(Context context) throws IOException {
        ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm==null)throw new IOException("CONNECTIVITY_UNAVAILABLE");
        try {
            for(Network n:cm.getAllNetworks()) {
                NetworkCapabilities c=cm.getNetworkCapabilities(n);
                if(c==null || !c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))continue;
                LinkProperties props=cm.getLinkProperties(n);
                if(props==null)continue;
                for(LinkAddress a:props.getLinkAddresses()) {
                    if(a.getAddress() instanceof Inet4Address && privateIpv4(a.getAddress().getHostAddress()))return n;
                }
            }
        }catch(SecurityException e){throw new IOException("WIFI_NETWORK_PERMISSION_UNAVAILABLE");}
        throw new IOException("NO_PRIVATE_WIFI_NETWORK");
    }

    static Socket connectTcp(Context context,String ip,int port,int timeout) throws IOException {
        if(!privateIpv4(ip) || port<1 || port>65535)throw new IOException("LAN_PRIVATE_IPV4_REQUIRED");
        Network wifi=wifiNetwork(context);
        Socket socket=wifi.getSocketFactory().createSocket();
        try {
            socket.connect(new InetSocketAddress(ip,port),timeout);
            return socket;
        }catch(IOException e){try{socket.close();}catch(IOException ignored){}throw e;}
    }

    /** Force LAN traffic over an actual Wi-Fi network, never the cellular default route. */
    static HttpURLConnection deviceConnection(Context context, String ip, int port, String command) throws IOException {
        if(!privateIpv4(ip) || port<1 || port>65535)throw new IOException("LAN_PRIVATE_IPV4_REQUIRED");
        URL url=new URL("http://"+ip+":"+port+"/zeroconf/"+command);
        return (HttpURLConnection)wifiNetwork(context).openConnection(url);
    }

    static String jsonKeys(JSONObject json) {
        ArrayList<String> keys=new ArrayList<>();
        if(json!=null){Iterator<String> it=json.keys();while(it.hasNext())keys.add(it.next());}
        Collections.sort(keys);
        return keys.toString();
    }

    static String shortText(String s) {
        if(s==null)return "";
        String cleaned=s.replace('\n',' ').replace('\r',' ').replace('\t',' ').trim();
        return cleaned.length()>100?cleaned.substring(0,100)+"…":cleaned;
    }

    /** Only a fixed allowlist of discovery fields may expose their values in exported logs. */
    static String txtSummary(Map<String,String> data) {
        if(data==null||data.isEmpty())return "TXT empty";
        TreeMap<String,String> ordered=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        ordered.putAll(data);
        StringBuilder b=new StringBuilder("keys=").append(ordered.keySet());
        for(String name:Arrays.asList("id","type","encrypt","txtvers","apivers","seq","model","modelname","productmodel","brandname","uiid","fwversion","name","devicename","service_instance","service_host")) {
            String value=ordered.get(name);
            if(value!=null && !value.isEmpty())b.append(" ").append(name).append("=").append(shortText(value));
        }
        int size=0;
        for(int i=1;i<=4;i++)size+=ordered.getOrDefault("data"+i,"").length();
        if(size>0)b.append(" data.chars=").append(size);
        if(ordered.containsKey("iv"))b.append(" iv.present=true");
        return b.toString();
    }

    static String wifiSnapshot(Context context) {
        try {
            ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if(cm==null)return "connectivity service unavailable";
            Network n=cm.getActiveNetwork();
            if(n==null)return "no active network";
            NetworkCapabilities c=cm.getNetworkCapabilities(n);
            LinkProperties link=cm.getLinkProperties(n);
            String transport=c==null?"unknown":c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi":
                    c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"cellular":"other";
            StringBuilder b=new StringBuilder("transport=").append(transport);
            b.append(" internetCapability=").append(c!=null&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
            if(link!=null) {
                for(LinkAddress a:link.getLinkAddresses()) {
                    InetAddress host=a.getAddress();
                    if(host instanceof Inet4Address)b.append(" ipv4=").append(host.getHostAddress()).append('/').append(a.getPrefixLength());
                }
                if(link.getDnsServers()!=null)b.append(" dnsServers=").append(link.getDnsServers().size());
            }
            return b.toString();
        }catch(Exception e){return "network info unavailable: "+e.getClass().getSimpleName();}
    }
}
