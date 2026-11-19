package com.mazko.sonoffs60;

import android.content.Context;
import android.net.Network;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Explicit opt-in legacy factory SoftAP provisioning. Never runs during passive discovery. */
final class LegacyApProvision {
    private LegacyApProvision(){}
    static boolean validSsid(String ssid){return ssid!=null&&!ssid.isEmpty()&&ssid.length()<=32 &&
        ssid.indexOf('\n')<0&&ssid.indexOf('\r')<0;}
    static boolean validPassword(String pass){return pass!=null&&pass.length()>=8&&pass.length()<=63;}
    static void write(Context context,Network network,String ssid,String wifiPassword,
                      String serverIp,int port,LogBook log)throws Exception {
        if(!LocalPairingProbe.isDeviceApNetwork(context,network))throw new Exception("AP_NETWORK_REQUIRED");
        if(!validSsid(ssid)||!validPassword(wifiPassword))throw new Exception("INVALID_WIFI_CREDENTIALS");
        if(!LanDiagnostics.privateIpv4(serverIp)||port<1||port>65535)throw new Exception("INVALID_PRIVATE_SERVER");
        JSONObject body=new JSONObject().put("version",4).put("ssid",ssid).put("password",wifiPassword)
            .put("serverName",serverIp).put("port",port);
        byte[] raw=body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn=(HttpURLConnection)network.openConnection(new URL("http://10.10.7.1/ap"));
        try {
            conn.setConnectTimeout(4000);conn.setReadTimeout(6000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("POST");conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type","application/json");
            conn.setRequestProperty("Accept","application/json");
            conn.setRequestProperty("Connection","close");
            conn.setFixedLengthStreamingMode(raw.length);
            try(OutputStream out=conn.getOutputStream()){out.write(raw);}
            int status=conn.getResponseCode();
            log.add("PRIVATE.AP.CONFIG.HTTP","Legacy /ap provisioning response HTTP="+status+"; Wi-Fi credentials omitted");
            if(status!=200)throw new Exception("AP_CONFIG_HTTP_"+status);
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=conn.getInputStream()){
                byte[] b=new byte[512];int n;
                while((n=in.read(b))>0){if(out.size()+n>8192)throw new Exception("AP_CONFIG_REPLY_TOO_LARGE");out.write(b,0,n);}
            }
            JSONObject response=new JSONObject(out.toString("UTF-8"));
            if(response.optInt("error",-1)!=0)throw new Exception("AP_CONFIG_DEVICE_REJECTED");
            log.add("PRIVATE.AP.CONFIG.ACCEPTED","Legacy device acknowledged server address "+serverIp+":"+port+
                "; reconnect and verify TLS/WS; NOT proof of relay control");
        } finally {conn.disconnect();}
    }
}
