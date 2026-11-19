package com.mazko.sonoffs60;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;

/** Explicit owner-consented eWeLink OAuth 2 authorization-code import.
 * OAuth needs a developer AppID/AppSecret; these are never packaged in the APK,
 * persisted on disk or forwarded to the LAN gateway. Tokens exist only for the import.
 * A production multi-user offering should do the client-secret exchange on its own backend.
 */
final class EwelinkOAuth {
    private final Controller controller;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final SecureRandom random=new SecureRandom();
    private String appId="", appSecret="",redirect="",state="";
    private volatile String status="idle";
    private volatile int imported=0;
    private volatile String error="";
    EwelinkOAuth(Controller controller){this.controller=controller;}
    static String encode(String s){try{return URLEncoder.encode(s,"UTF-8");}catch(Exception e){throw new IllegalArgumentException(e);}}
    static String regionHost(String region){
        if(!"eu".equals(region)&&!"us".equals(region)&&!"as".equals(region)&&!"cn".equals(region))throw new IllegalArgumentException("REGION_INVALID");
        return "https://"+region+"-apia.coolkit."+("cn".equals(region)?"cn":"cc");
    }
    private static String hmac(String payload,String secret)throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
    private String randomHex(int n){byte[] b=new byte[n];random.nextBytes(b);StringBuilder s=new StringBuilder();
        for(byte v:b)s.append(String.format("%02x",v&255));return s.toString();}
    synchronized String start(String id,String secret,String redirectUrl) throws Exception {
        if(status.equals("working"))throw new IllegalStateException("IMPORT_BUSY");
        if(id==null||!id.matches("[A-Za-z0-9_-]{8,128}"))throw new IllegalArgumentException("APPID_INVALID");
        if(secret==null||secret.length()<12||secret.length()>256)throw new IllegalArgumentException("APP_SECRET_INVALID");
        URL uri=new URL(redirectUrl);
        if(!"https".equals(uri.getProtocol()) || uri.getUserInfo()!=null || uri.getRef()!=null)
            throw new IllegalArgumentException("HTTPS_REDIRECT_REQUIRED");
        appId=id;appSecret=secret;redirect=redirectUrl;state=randomHex(16);status="awaiting_authorization";error="";imported=0;
        long seq=System.currentTimeMillis();String nonce=randomHex(8);
        String url="https://c2ccdn.coolkit.cc/oauth/index.html?clientId="+encode(appId)+
            "&redirectUrl="+encode(redirect)+"&grantType=authorization_code&state="+encode(state)+
            "&nonce="+encode(nonce)+"&seq="+seq+"&authorization="+encode(hmac(appId+"_"+seq,appSecret));
        controller.log.add("OAUTH.START","Owner authorized browser flow initiated; no device configuration changed");
        return url;
    }
    synchronized JSONObject info(){
        try {return new JSONObject().put("status",status).put("imported",imported).put("error",error);}
        catch(Exception e){return new JSONObject();}
    }
    synchronized void acceptCallback(String callbackUrl,String region) throws Exception {
        if(!"awaiting_authorization".equals(status))throw new IllegalStateException("OAUTH_NOT_STARTED");
        regionHost(region); // strict region allowlist
        if(callbackUrl==null||callbackUrl.length()>8192)throw new IllegalArgumentException("CALLBACK_INVALID");
        URL callback=new URL(callbackUrl);
        URL original=new URL(redirect);
        if(!callback.getProtocol().equals(original.getProtocol()) || !callback.getHost().equalsIgnoreCase(original.getHost()) ||
            callback.getPort()!=original.getPort() || !callback.getPath().equals(original.getPath()))throw new IllegalArgumentException("REDIRECT_MISMATCH");
        java.util.Map<String,String> params=parseQuery(callback.getQuery());
        if(!MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),params.getOrDefault("state","").getBytes(StandardCharsets.UTF_8)))
            throw new IllegalArgumentException("OAUTH_STATE_MISMATCH");
        String code=params.getOrDefault("code","");
        if(!code.matches("[A-Za-z0-9_.-]{4,2048}"))throw new IllegalArgumentException("OAUTH_CODE_INVALID");
        String id=appId,secret=appSecret,returnUrl=redirect;
        appSecret="";status="working";error="";
        executor.execute(()->importKeys(region,returnUrl,code,id,secret));
    }
    static java.util.Map<String,String> parseQuery(String raw)throws Exception {
        java.util.Map<String,String> out=new java.util.HashMap<>();if(raw==null)return out;
        for(String p:raw.split("&")){int eq=p.indexOf('=');if(eq<0)continue;
            String k=java.net.URLDecoder.decode(p.substring(0,eq),"UTF-8");
            String v=java.net.URLDecoder.decode(p.substring(eq+1),"UTF-8");out.putIfAbsent(k,v);}
        return out;
    }
    private static JSONObject getJson(String path,String access,String appId)throws Exception {
        HttpsURLConnection c=(HttpsURLConnection)new URL(path).openConnection();
        c.setConnectTimeout(7000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Authorization","Bearer "+access);c.setRequestProperty("X-CK-Appid",appId);
        c.setRequestProperty("Accept","application/json");
        try{return readResponse(c);}finally{c.disconnect();}
    }
    private static JSONObject postJson(String path,String id,String secret,JSONObject body)throws Exception {
        HttpsURLConnection c=(HttpsURLConnection)new URL(path).openConnection();
        c.setConnectTimeout(7000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(false);
        c.setRequestMethod("POST");c.setDoOutput(true);
        byte[] raw=body.toString().getBytes(StandardCharsets.UTF_8);
        c.setRequestProperty("Content-Type","application/json");
        c.setRequestProperty("X-CK-Appid",id);
        c.setRequestProperty("Authorization","Sign "+hmac(new String(raw,StandardCharsets.UTF_8),secret));
        c.setFixedLengthStreamingMode(raw.length);
        try(OutputStream out=c.getOutputStream()){out.write(raw);}
        try{return readResponse(c);}finally{c.disconnect();}
    }
    private static JSONObject readResponse(HttpURLConnection c)throws Exception {
        if(c.getResponseCode()!=200)throw new IllegalStateException("HTTP_"+c.getResponseCode());
        int length=c.getContentLength();if(length>2_000_000)throw new IllegalStateException("API_RESPONSE_TOO_LARGE");
        ByteArrayOutputStream data=new ByteArrayOutputStream();
        try(InputStream in=c.getInputStream()){byte[] bytes=new byte[8192];int n;
            while((n=in.read(bytes))>=0){data.write(bytes,0,n);if(data.size()>2_000_000)throw new IllegalStateException("API_RESPONSE_TOO_LARGE");}}
        JSONObject json=new JSONObject(data.toString("UTF-8"));
        if(json.optInt("error",-1)!=0)throw new IllegalStateException("EWELINK_ERROR_"+json.optInt("error",-1));
        return json.optJSONObject("data")==null?new JSONObject():json.getJSONObject("data");
    }
    private void importKeys(String region,String redirectUrl,String code,String id,String secret){
        int count=0;Set<String> seen=new HashSet<>();
        try{
            String host=regionHost(region);
            JSONObject tokenResponse=postJson(host+"/v2/user/oauth/token",id,secret,new JSONObject()
                .put("redirectUrl",redirectUrl).put("code",code).put("grantType","authorization_code"));
            String access=tokenResponse.optString("at",tokenResponse.optString("accessToken",""));
            if(access.isEmpty())throw new IllegalStateException("OAUTH_TOKEN_MISSING");
            java.util.List<String> homes=new java.util.ArrayList<>();
            // The Thing List defaults to the current home. Enumerate the owner's homes
            // when the account has permission, without modifying the current home.
            try {
                JSONObject families=getJson(host+"/v2/family",access,id);
                JSONArray familyList=families.optJSONArray("familyList");
                if(familyList!=null)for(int f=0;f<Math.min(familyList.length(),50);f++){
                    JSONObject family=familyList.optJSONObject(f);
                    if(family!=null){String homeId=family.optString("id","");
                        if(homeId.matches("[A-Za-z0-9_-]{6,128}"))homes.add(homeId);}
                }
            }catch(Exception ignored){controller.log.add("OAUTH.FAMILY.FALLBACK","Home listing unavailable; using current home");}
            if(homes.isEmpty())homes.add("");
            for(String homeId:homes){
                int beginIndex=-9999999;
                for(int page=0;page<40;page++){
                    String url=host+"/v2/device/thing?num=30&beginIndex="+beginIndex+
                        (homeId.isEmpty()?"":"&familyid="+encode(homeId));
                    JSONObject response=getJson(url,access,id);
                    JSONArray list=response.optJSONArray("thingList");if(list==null||list.length()==0)break;
                    int last=beginIndex;
                    for(int i=0;i<list.length();i++){
                        JSONObject row=list.optJSONObject(i);if(row==null)continue;
                        int index=row.optInt("index",last);
                        if(index>last)last=index;
                        int kind=row.optInt("itemType",0);
                        if(kind!=1&&kind!=2)continue;
                        JSONObject d=row.optJSONObject("itemData");if(d==null)continue;
                        String deviceId=d.optString("deviceid","");
                        String key=d.optString("devicekey","");
                        if(!deviceId.matches("[A-Za-z0-9]{10}")||key.length()<6||key.length()>128||!seen.add(deviceId))continue;
                        JSONObject extra=d.optJSONObject("extra");
                        String uiid=String.valueOf(extra==null?0:extra.optInt("uiid",0));
                        controller.importAuthorizedKey(deviceId,key,d.optString("name",""),
                            uiid,d.optString("productModel",""));count++;
                    }
                    if(list.length()<30||last<=beginIndex)break;
                    beginIndex=last+1;
                }
            }
            imported=count;status=count>0?"complete":"no_keys_returned";
            controller.log.add("OAUTH.IMPORT.COMPLETE","Account-authorized device keys saved="+count+"; no token persisted; no re-pairing");
        }catch(Exception ex){error=ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage();status="failed";
            controller.log.add("OAUTH.IMPORT.FAILED",ex.getClass().getSimpleName()+"; no token or secret logged");}
        finally{ synchronized(this){appSecret="";appId="";redirect="";state="";} }
    }
    void close(){executor.shutdownNow();}
}
