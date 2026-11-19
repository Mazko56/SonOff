package com.mazko.sonoffs60;

import org.json.JSONObject;
import org.json.JSONArray;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Device-server protocol from public legacy Sonoff private-cloud implementations.
 * This is NOT eWeLink AES LAN and does not bypass modern firmware TLS pinning.
 */
final class LegacyCloudProtocol {
    private LegacyCloudProtocol() {}
    private static final java.util.concurrent.atomic.AtomicLong SEQUENCE=new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
    static final String PATH_DISPATCH="/dispatch/device";
    static final String PATH_WEBSOCKET="/api/ws";
    /** Old nodejs-websocket private servers accept both the root WS route and /api/ws. */
    static boolean isWebSocketPath(String path) { return "/api/ws".equals(path) || "/".equals(path); }
    static boolean isDeviceId(String id) {return id!=null && id.matches("[a-zA-Z0-9]{10}");}
    static boolean equalSecret(String expected,String offered) {
        if(expected==null||expected.isEmpty()||offered==null||offered.isEmpty())return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),offered.getBytes(StandardCharsets.UTF_8));
    }
    static String token() {
        byte[] b=new byte[32];new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
    static JSONObject dispatchReply(String ip,int port)throws Exception {
        if(!LanDiagnostics.privateIpv4(ip)||port<=0||port>65535)throw new Exception("PRIVATE_SERVER_ADDRESS_REQUIRED");
        return new JSONObject().put("error",0).put("reason","ok").put("IP",ip).put("port",port);
    }
    static JSONObject registrationReply(String id,String sessionToken)throws Exception {
        if(!isDeviceId(id))throw new Exception("BAD_DEVICE_ID");
        return new JSONObject().put("error",0).put("deviceid",id).put("apikey",sessionToken)
            .put("config",new JSONObject().put("hb",1).put("hbInterval",145));
    }
    static JSONObject relayRequest(String id,boolean on,Integer outlet)throws Exception {
        if(!isDeviceId(id))throw new Exception("BAD_DEVICE_ID");
        JSONObject params;
        if(outlet==null)params=new JSONObject().put("switch",on?"on":"off");
        else if(outlet>=0 && outlet<=15)params=new JSONObject().put("switches",new JSONArray().put(
            new JSONObject().put("switch",on?"on":"off").put("outlet",outlet)));
        else throw new Exception("BAD_OUTLET");
        return new JSONObject().put("action","update").put("userAgent","app")
            .put("deviceid",id).put("params",params)
            .put("sequence",String.valueOf(SEQUENCE.updateAndGet(x->Math.max(x+1,System.currentTimeMillis())))).put("ts",0).put("from","app");
    }
    static boolean hasRelayState(JSONObject params) {
        if(params==null)return false;
        String s=params.optString("switch","");
        if("on".equals(s)||"off".equals(s))return true;
        JSONArray arr=params.optJSONArray("switches");
        if(arr==null)return false;
        for(int i=0;i<arr.length();i++){
            JSONObject row=arr.optJSONObject(i);
            if(row!=null && row.optInt("outlet",-1)>=0 && ("on".equals(row.optString("switch"))||"off".equals(row.optString("switch"))))return true;
        }
        return false;
    }
}
