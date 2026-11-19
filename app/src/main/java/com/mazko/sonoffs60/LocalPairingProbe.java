package com.mazko.sonoffs60;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.net.LinkAddress;
import java.net.InetAddress;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Read-only GET /device for legacy compatible-pairing APs (documented for older models).
 * Not proven for S60TPF. Never touches Wi-Fi settings, cloud endpoints or the relay.
 */
final class LocalPairingProbe {
    static final class Result {
        final String id;
        final String key;
        Result(String id,String key){this.id=id;this.key=key;}
    }
    private LocalPairingProbe(){}

    static boolean isDeviceApNetwork(Context context,Network network) {
        ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm==null||network==null)return false;
        try {
            NetworkCapabilities caps=cm.getNetworkCapabilities(network);
            if(caps==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return false;
            LinkProperties links=cm.getLinkProperties(network);
            if(links==null)return false;
            for(LinkAddress la:links.getLinkAddresses()) {
                InetAddress ip=la.getAddress();
                if(DeviceApNetwork.matches(ip,la.getPrefixLength()))return true;
            }
        }catch(SecurityException ignored){}
        return false;
    }
    static Network connectedDeviceAp(Context context) {
        ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm==null)return null;
        try {
            for(Network n:cm.getAllNetworks())if(isDeviceApNetwork(context,n))return n;
        }catch(SecurityException ignored){}
        return null;
    }
    static boolean canReadFromDeviceAp(Context context){return connectedDeviceAp(context)!=null;}
    static Result readFromDeviceAp(Context context)throws Exception {
        Network n=connectedDeviceAp(context);
        if(n==null)throw new Exception("AP_NO_VERIFIED_NETWORK");
        return readFromNetwork(context,n,"",null);
    }
    /** Bind traffic to the AP's own Network object (not the active mobile/home route). */
    static Result readFromNetwork(Context context,Network chosen,String ssid,LogBook log)throws Exception {
        // Wait for DHCP because NetworkCallback.onAvailable can precede LinkProperties.
        for(int i=0;i<5 && !isDeviceApNetwork(context,chosen);i++)Thread.sleep(320);
        if(!isDeviceApNetwork(context,chosen))throw new Exception("AP_UNEXPECTED_DHCP_SUBNET");
        int http=0;
        HttpURLConnection connection=null;
        try {
            URL url=new URL("http://10.10.7.1/device");
            connection=(HttpURLConnection)chosen.openConnection(url);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept","application/json");
            connection.setRequestProperty("Connection","close");
            http=connection.getResponseCode();
            if(log!=null)log.add("AP.PROBE.HTTP","GET /device response HTTP="+http+" from verified AP network");
            if(http!=200) {
                // Diagnostic fallback: HEAD/GET "/" status only, never submit Wi-Fi credentials.
                if(log!=null) {
                    HttpURLConnection root=null;
                    try {
                        root=(HttpURLConnection)chosen.openConnection(new URL("http://10.10.7.1/"));
                        root.setInstanceFollowRedirects(false);
                        root.setConnectTimeout(1200);root.setReadTimeout(1200);
                        root.setRequestMethod("GET");
                        int rootStatus=root.getResponseCode();
                        log.add("AP.PROBE.ROOT","GET / HTTP="+rootStatus+" (no body read; no configuration written)");
                    }catch(Exception e){log.add("AP.PROBE.ROOT","GET / unavailable: "+e.getClass().getSimpleName());}
                    finally {if(root!=null)root.disconnect();}
                }
                throw new Exception("AP_DEVICE_ENDPOINT_HTTP_"+http);
            }
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream stream=connection.getInputStream()) {
                byte[] buffer=new byte[1024];int n;
                while((n=stream.read(buffer))!=-1){
                    if(out.size()+n>8192)throw new Exception("AP_RESPONSE_TOO_LARGE");
                    out.write(buffer,0,n);
                }
            }
            JSONObject parsed;
            try{parsed=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));}
            catch(Exception e){throw new Exception("AP_RESPONSE_NOT_JSON");}
            JSONObject nested=parsed.optJSONObject("data");
            if(nested!=null)parsed=nested;
            String id=parsed.optString("deviceid","");
            String key=parsed.optString("apikey","");
            if(!id.matches("[A-Za-z0-9]{10}")||key.length()<6||key.length()>128)
                throw new Exception("AP_NO_VALID_DEVICEID_APIKEY");
            if(ssid!=null&&!ssid.isEmpty()&&!LegacyApIdentity.matchesId(ssid,id))
                throw new Exception("AP_SSID_DEVICE_ID_MISMATCH");
            if(log!=null)log.add("AP.PROBE.FIELDS","valid deviceid and apikey received; secret excluded; deviceid="+id);
            return new Result(id,key);
        } finally {if(connection!=null)connection.disconnect();}
    }
}
