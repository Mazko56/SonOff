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
    String protocol = ProtocolRouter.ENCRYPTED;
    String identifiedAs = "не встановлено";
    String lastProtocolAttempt = "не виконано";
    String accessReason = "очікуємо оголошення";
    boolean plaintextHttpConfirmed = false;
    String lastHttpCommand = "";
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
    long lastLedEchoProbe = 0;
    boolean authenticated = false;
    String errorCode = "";
    String errorAdvice = "";
    long lastData = 0;
    String error = "";
    String pending = "";
    long pendingSince = 0;
    JSONObject values = new JSONObject();
    S60Device(String id) { this.id = id; }
    boolean hasOutletZero() {
        JSONArray arr=values.optJSONArray("switches");
        if(arr==null)return false;
        for(int i=0;i<arr.length();i++) {
            JSONObject row=arr.optJSONObject(i);
            if(row!=null && row.optInt("outlet",-1)==0 && row.has("switch"))return true;
        }
        return false;
    }
    String outletZeroSwitch() {
        JSONArray arr=values.optJSONArray("switches");
        if(arr==null)return "";
        for(int i=0;i<arr.length();i++) {
            JSONObject row=arr.optJSONObject(i);
            if(row!=null && row.optInt("outlet",-1)==0)return row.optString("switch","");
        }
        return "";
    }
    private Object metric(String name) {
        double multiplier=SonoffProfiles.metricMultiplier(name,localType,uiidHint,values.optJSONArray("switches")!=null);
        if (!values.has(name)) return JSONObject.NULL;
        try { return Math.round(Double.parseDouble(values.get(name).toString()) * multiplier * 100.0)/100.0; }
        catch (Exception ignored) { return JSONObject.NULL; }
    }
    JSONObject asJson() throws Exception {
        JSONObject j = new JSONObject()
                .put("id", id).put("ip", ip).put("port",port)
                .put("type", localType).put("protocol",protocol).put("encrypted",encrypted)
                .put("advertisedName",advertisedName).put("modelHint",modelHint)
                .put("uiidHint",uiidHint).put("firmwareHint",firmwareHint)
                .put("hasKey",hasKey).put("authenticated",authenticated)
                .put("modelConfirmed",modelConfirmed)
                .put("lastSeen", lastSeen).put("lastData",lastData).put("lastHttpOk",lastHttpOk)
                .put("online", System.currentTimeMillis()-lastSeen<90000)
                .put("source",lastSource).put("error",error)
                .put("errorCode",errorCode).put("errorAdvice",errorAdvice)
                .put("ready", authenticated && System.currentTimeMillis()-lastData<120000)
                .put("canControl",authenticated && System.currentTimeMillis()-lastData<120000 && !ip.isEmpty() &&
                    (encrypted || ProtocolRouter.trustedPlaintextRelay(protocol,localType,
                         values.has("switch") || hasOutletZero(),plaintextHttpConfirmed)) &&
                    (values.has("switch") || hasOutletZero()) &&
                    ("plug".equals(localType)||"enhanced_plug".equals(localType)||"diy_plug".equals(localType)||
                     "th_plug".equals(localType)||"strip".equals(localType)))
                .put("pending",pending).put("pendingSince",pendingSince)
                .put("firmware",values.optString("fwVersion",""))
                .put("led",values.has("sledOnline") ? values.optString("sledOnline") : JSONObject.NULL)
                .put("startup",getStartup())
                .put("power",metric("power")).put("voltage",metric("voltage"))
                .put("current",metric("current"))
                .put("dayKwh",metric("dayKwh")).put("monthKwh",metric("monthKwh"))
                .put("rssi", metric("rssi"));
        Object relay = JSONObject.NULL;
        JSONArray switches = values.optJSONArray("switches");
        if (switches!=null && hasOutletZero()) relay = "on".equals(outletZeroSwitch());
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
        String[] safeFields={"switch","switches","power","voltage","current","dayKwh","monthKwh","rssi","signalStrength","sledOnline"};
        boolean hasTelemetry=false;
        for(String field:safeFields) if(src.has(field)) {
            try{values.put(field,src.get(field));hasTelemetry=true;}catch(Exception ignored){}
        }
        if(src.has("signalStrength")&&!src.has("rssi"))try{values.put("rssi",src.get("signalStrength"));}catch(Exception ignored){}
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
        if(src.has("signalStrength")&&!src.has("rssi"))try{values.put("rssi",src.get("signalStrength"));}catch(Exception ignored){}
        lastData=System.currentTimeMillis();
        error="";errorCode="";errorAdvice="";
        authenticated=true;
        if(!pending.isEmpty()) {
            JSONArray switches=values.optJSONArray("switches");
            String confirmed=hasOutletZero()?outletZeroSwitch():values.optString("switch","");
            if(pending.equals(confirmed))pending="";
        }
    }
}
