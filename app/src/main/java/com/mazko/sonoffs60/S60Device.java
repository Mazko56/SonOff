package com.mazko.sonoffs60;

import org.json.JSONArray;
import org.json.JSONObject;

final class S60Device {
    final String id;
    String ip = "";
    String fallbackIp = "";
    String advertisedName = "";
    String modelHint = "";
    String firmwareHint = "";
    String uiidHint = "";
    String serviceInstance = "";
    String txtSignature = "";
    String diagnosticTcp = "not checked";
    String diagnosticHttp = "not checked";
    long lastDiagnostic = 0;
    long lastStatusLog = 0;
    String localType = "";
    String lastSource = "";
    int port = 8081;
    boolean encrypted = true;
    boolean hasKey = false;
    boolean modelConfirmed = false; // mDNS service does not reliably identify retail S60 model
    long lastSeen = System.currentTimeMillis();
    long lastHttpOk = 0;
    long lastRequest = 0;
    long lastAutoProbe = 0;
    long lastConfigurationQuery = 0;
    boolean authenticated = false;
    String errorCode = "";
    String errorAdvice = "";
    long lastData = 0;
    String error = "";
    String pending = "";
    long pendingSince = 0;
    JSONObject values = new JSONObject();
    S60Device(String id) { this.id = id; }
    private Object metric(String name, double multiplier) {
        if (!values.has(name)) return JSONObject.NULL;
        try { return Math.round(Double.parseDouble(values.get(name).toString()) * multiplier * 100.0)/100.0; }
        catch (Exception ignored) { return JSONObject.NULL; }
    }
    JSONObject asJson() throws Exception {
        JSONObject j = new JSONObject()
                .put("id", id).put("ip", ip).put("port",port)
                .put("type", localType).put("encrypted",encrypted)
                .put("advertisedName",advertisedName).put("modelHint",modelHint)
                .put("uiidHint",uiidHint).put("firmwareHint",firmwareHint)
                .put("hasKey",hasKey).put("authenticated",authenticated)
                .put("modelConfirmed",modelConfirmed)
                .put("lastSeen", lastSeen).put("lastData",lastData).put("lastHttpOk",lastHttpOk)
                .put("online", System.currentTimeMillis()-lastSeen<90000)
                .put("source",lastSource).put("error",error)
                .put("errorCode",errorCode).put("errorAdvice",errorAdvice)
                .put("ready", authenticated && System.currentTimeMillis()-lastData<120000)
                .put("canControl",authenticated && System.currentTimeMillis()-lastData<120000 && !ip.isEmpty() && (localType.isEmpty() || "plug".equals(localType) || "enhanced_plug".equals(localType)) &&
                    (values.has("switch") || ("plug".equals(localType) && values.optJSONArray("switches")!=null)))
                .put("pending",pending).put("pendingSince",pendingSince)
                .put("firmware",values.optString("fwVersion",""))
                .put("led",values.has("sledOnline") ? values.optString("sledOnline") : JSONObject.NULL)
                .put("startup",getStartup())
                .put("power",metric("power",.01)).put("voltage",metric("voltage",.01))
                .put("current",metric("current",.01))
                .put("dayKwh",metric("dayKwh",.01)).put("monthKwh",metric("monthKwh",.01))
                .put("rssi", metric("rssi",1.0));
        Object relay = JSONObject.NULL;
        JSONArray switches = values.optJSONArray("switches");
        if (switches!=null && switches.length()>0) relay = "on".equals(switches.optJSONObject(0).optString("switch"));
        else if (values.has("switch")) relay = "on".equals(values.optString("switch"));
        return j.put("relay",relay);
    }
    private Object getStartup() {
        JSONArray config=values.optJSONArray("configure");
        if(config!=null)for(int i=0;i<config.length();i++) {
            JSONObject item=config.optJSONObject(i);
            if(item!=null && item.optInt("outlet",-1)==0)return item.optString("startup","stay");
        }
        return JSONObject.NULL;
    }
    /** Accept only verified state fields, never treat unauthenticated metadata as authorization. */
    boolean observeReadOnly(JSONObject src) {
        if(src==null)return false;
        JSONObject nested=src.optJSONObject("params");
        if(nested!=null)src=nested;
        String[] safeFields={"switch","switches","power","voltage","current","dayKwh","monthKwh","rssi","sledOnline"};
        boolean hasTelemetry=false;
        for(String field:safeFields) if(src.has(field)) {
            try{values.put(field,src.get(field));hasTelemetry=true;}catch(Exception ignored){}
        }
        if(hasTelemetry){lastData=System.currentTimeMillis();error="";errorCode="";errorAdvice="";}
        captureMetadata(src);
        return hasTelemetry;
    }
    void captureMetadata(JSONObject src) {
        if(src==null)return;
        String n=src.optString("deviceName",src.optString("name",""));
        if(!n.isEmpty())advertisedName=LanDiagnostics.shortText(n);
        String m=src.optString("productModel",src.optString("model",""));
        if(!m.isEmpty())modelHint=LanDiagnostics.shortText(m);
        String fw=src.optString("fwVersion",src.optString("firmware",""));
        if(!fw.isEmpty())firmwareHint=LanDiagnostics.shortText(fw);
        String uiid=src.optString("uiid","");
        if(uiid.matches("[0-9]{1,5}"))uiidHint=uiid;
    }
    void apply(JSONObject src) {
        if (src==null) return;
        JSONObject nested = src.optJSONObject("params");
        if (nested!=null) src=nested;
        java.util.Iterator<String> it=src.keys();
        while(it.hasNext()) {
            String k=it.next();
            // Avoid retaining device/cloud secrets or network details accidentally.
            if (k.equals("devicekey") || k.equals("apikey") || k.equals("ssid") || k.equals("bssid") || k.equals("password") || k.equals("token")) continue;
            try { values.put(k,src.get(k)); } catch(Exception ignored) { }
        }
        captureMetadata(src);
        lastData=System.currentTimeMillis();
        error="";errorCode="";errorAdvice="";
        authenticated=true;
        if(!pending.isEmpty()) {
            JSONArray switches=values.optJSONArray("switches");
            JSONObject first=switches!=null?switches.optJSONObject(0):null;
            String confirmed=first!=null?first.optString("switch"):values.optString("switch","");
            if(pending.equals(confirmed))pending="";
        }
    }
}
