package com.mazko.sonoffs60;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.net.LinkAddress;
import java.net.Inet4Address;
import java.net.InetAddress;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Read-only probe for legacy Sonoff compatible-pairing APs.
 * Some older devices expose /device; stock S60TPF support is NOT verified.
 * This module does not reset, reconfigure, provision, or flash any device.
 */
final class LocalPairingProbe {
    static final class Result {
        final String id;
        final String key;
        Result(String id, String key) { this.id=id; this.key=key; }
    }

    private LocalPairingProbe() { }

    /**
     * Probe only when the actual Wi-Fi interface has a 10.10.7.x address.
     * A regular home LAN (e.g. 192.168.0.x) must never be sent to 10.10.7.1.
     * This does NOT guarantee S60TPF supports the legacy /device endpoint.
     */
    static Network connectedDeviceAp(Context context) {
        ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm==null)return null;
        try {
            for(Network n:cm.getAllNetworks()) {
                NetworkCapabilities caps=cm.getNetworkCapabilities(n);
                if(caps==null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))continue;
                LinkProperties links=cm.getLinkProperties(n);
                if(links==null)continue;
                for(LinkAddress la:links.getLinkAddresses()) {
                    InetAddress ip=la.getAddress();
                    if(DeviceApNetwork.matches(ip,la.getPrefixLength()))return n;
                }
            }
        } catch(SecurityException e) {return null;}
        return null;
    }
    static boolean canReadFromDeviceAp(Context context){return connectedDeviceAp(context)!=null;}
    static Result readFromDeviceAp(Context context) throws Exception {
        Network chosen=connectedDeviceAp(context);
        if(chosen==null)throw new Exception("Телефон не підключений до мережі налаштування розетки.");
        HttpURLConnection connection=null;
        try {
            URL url=new URL("http://10.10.7.1/device");
            connection=(HttpURLConnection)chosen.openConnection(url);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(1800);
            connection.setReadTimeout(1800);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept","application/json");
            if(connection.getResponseCode()!=200)throw new Exception("Розетка не надала локальні дані підключення.");
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream stream=connection.getInputStream()) {
                byte[] buf=new byte[1024];int n;
                while((n=stream.read(buf))!=-1) {
                    if(out.size()+n>8192)throw new Exception("Некоректна відповідь розетки.");
                    out.write(buf,0,n);
                }
            }
            JSONObject data=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
            JSONObject nested=data.optJSONObject("data");
            if(nested!=null)data=nested;
            // Do not accept non-device or incomplete JSON as proof of a successful pairing.

            String id=data.optString("deviceid", "");
            String key=data.optString("apikey", "");
            if(!id.matches("[A-Za-z0-9]{10}") || key.length()<6 || key.length()>128)
                throw new Exception("Ця прошивка не підтримує підтверджений спосіб локального підключення.");
            return new Result(id,key);
        } finally {if(connection!=null)connection.disconnect();}
    }
}
