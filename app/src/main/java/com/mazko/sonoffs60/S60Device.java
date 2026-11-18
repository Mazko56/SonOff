package com.mazko.sonoffs60;

import org.json.JSONArray;
import org.json.JSONObject;

final class S60Device {
    final String id;
    String ip = "";
    String localType = "";
    String lastSource = "";
    int port = 8081;
    boolean encrypted = true;
    boolean simulator = false;
    boolean hasKey = false;
    boolean modelConfirmed = false; // mDNS service does not reliably identify retail S60 model
    long lastSeen = System.currentTimeMillis();
    long lastData = 0;
    String error = "";
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
                .put("simulator",simulator).put("hasKey",hasKey).put("modelConfirmed",modelConfirmed)
                .put("lastSeen", lastSeen).put("lastData",lastData)
                .put("online", simulator || System.currentTimeMillis()-lastSeen<65000)
                .put("source",lastSource).put("error",error)
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
        lastData=System.currentTimeMillis();
        error="";
    }
}
