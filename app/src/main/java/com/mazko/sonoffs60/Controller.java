package com.mazko.sonoffs60;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** LAN state machine. Does not pair/reflash/reset hardware and does not intercept eWeLink cloud traffic. */
final class Controller {
    final LogBook log = new LogBook();
    private final SecureKeys keys;
    private final Map<String,S60Device> devices=new HashMap<>();
    private final ExecutorService network=Executors.newFixedThreadPool(3);
    private final ScheduledExecutorService refresh=Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong sequence=new AtomicLong(System.currentTimeMillis());
    private LanDiscovery discovery;
    private boolean simulation;
    Controller(Context context) {
        keys=new SecureKeys(context);
        discovery=new LanDiscovery(context,this,log);
        log.add("APP", "Started v0.1.0 (Wi-Fi S60TPF/UIID190). Safe: no cloud interception, no firmware writes");
        refresh.scheduleAtFixedRate(() -> {
            ArrayList<S60Device> snapshot;
            synchronized(this){snapshot=new ArrayList<>(devices.values());}
            for(S60Device d:snapshot)if(!d.simulator && d.hasKey && !d.ip.isEmpty() && System.currentTimeMillis()-d.lastSeen<65000) {
                network.execute(() -> request(d,"getState",new JSONObject(),false));
            }
        },12,20,TimeUnit.SECONDS);
    }
    void begin() {discovery.start();}
    void close(){discovery.stop();network.shutdownNow();refresh.shutdownNow();}
    synchronized JSONObject state() {
        JSONObject state=new JSONObject();
        try {
            JSONArray a=new JSONArray();for(S60Device d:devices.values())a.put(d.asJson());
            state.put("devices",a).put("simulator",simulation)
                .put("logCount",log.asJson().length())
                .put("status","ready")
                .put("limitations","Encrypted LAN needs per-device key; socket listens only on phone loopback; device is HTTP server, not an HTTP client");
        } catch(Exception ex){log.add("UI.ERROR",ex.getClass().getSimpleName());}
        return state;
    }
    void onDiscover(String id,String ip,int port,Map<String,String> attributes,String from) {
        if(!id.matches("[a-zA-Z0-9]{10}"))return;
        S60Device d;
        boolean newDevice;
        synchronized(this) {
            d=devices.get(id);newDevice=d==null;
            if(d==null) {d=new S60Device(id);devices.put(id,d);}
            if(ip!=null && !ip.isEmpty() && ip.contains("."))d.ip=ip;
            if(port>=1 && port<=65535)d.port=port;
            d.lastSource=from;d.lastSeen=System.currentTimeMillis();
            d.encrypted=!"false".equalsIgnoreCase(attributes.get("encrypt"));
            if(attributes.containsKey("type"))d.localType=attributes.get("type");
            d.hasKey=!keys.get(id).isEmpty();
        }
        if(newDevice)log.add("DEVICE.FOUND",id+" host="+d.ip+":"+d.port+" type="+d.localType+" encrypted="+d.encrypted+" via "+from);
        StringBuilder enc=new StringBuilder();
        for(int i=1;i<=4;i++)enc.append(attributes.getOrDefault("data"+i,""));
        if(enc.length()>0) {
            if(d.encrypted) {
                String secret=keys.get(id);
                if(!secret.isEmpty() && attributes.containsKey("iv")) {
                    try {
                        JSONObject vals=LanCrypto.unwrap(enc.toString(),attributes.get("iv"),secret);
                        synchronized(this){d.apply(vals);}
                        // Do not include decrypted values in exported diagnostics.
                        log.add("MDNS.DECRYPT.OK",id+" received valid encrypted device state");
                    } catch(Exception e) {
                        synchronized(this){d.error="DECRYPT_FAILED";}
                        log.add("MDNS.DECRYPT.FAIL",id+" "+e.getClass().getSimpleName()+"; check devicekey");
                    }
                }
            } else {
                try {synchronized(this){d.apply(new JSONObject(enc.toString()));}}
                catch(Exception e){log.add("MDNS.JSON.ERROR",id+" malformed plain TXT status");}
            }
        }
    }
    synchronized void setSimulator(boolean enabled) {
        simulation=enabled;
        if(enabled) {
            S60Device d=devices.get("SIM0000001");
            if(d==null){d=new S60Device("SIM0000001");devices.put(d.id,d);}
            d.simulator=true;d.ip="internal-simulator";d.localType="plug";d.hasKey=true;d.modelConfirmed=true;d.encrypted=true;
            d.lastSource="ВБУДОВАНА СИМУЛЯЦІЯ — НЕ РЕАЛЬНА РОЗЕТКА";
            try{d.apply(new JSONObject("{\"switches\":[{\"outlet\":0,\"switch\":\"off\"}],\"voltage\":23010,\"current\":0,\"power\":0,\"dayKwh\":0,\"monthKwh\":112,\"rssi\":-45}"));}
            catch(Exception ignored){}
        } else {devices.remove("SIM0000001");}
        log.add("SIM.MODE",enabled?"Enabled, explicitly isolated from hardware":"Disabled");
    }
    synchronized void saveKey(String id,String secret) throws Exception {
        if(id==null || !id.matches("[a-zA-Z0-9]{10}"))throw new Exception("Choose discovered device");
        if(secret==null || secret.length()<6 || secret.length()>128)throw new Exception("Key must be 6-128 characters");
        S60Device d=devices.get(id);
        if(d==null || d.simulator)throw new Exception("Real detected device required");
        keys.put(id,secret);d.hasKey=true;d.error="";
        log.add("KEY.SAVED",id+" encrypted with Android Keystore (secret omitted from log)");
        network.execute(()->request(d,"getState",new JSONObject(),true));
    }
    synchronized void confirmModel(String id) throws Exception {
        S60Device d=devices.get(id);
        if(d==null || d.simulator)throw new Exception("Unknown real device");
        d.modelConfirmed=true;
        log.add("MODEL.CONFIRMED",id+" was explicitly confirmed as S60TPF by operator; unverified via mDNS");
    }
    void relay(String id,boolean on) throws Exception {
        final S60Device d;
        synchronized(this){d=devices.get(id);}
        if(d==null) throw new Exception("Unknown device");
        if(d.simulator) {
            synchronized(this) {
                try {
                    d.apply(new JSONObject().put("switches",new JSONArray().put(new JSONObject().put("outlet",0).put("switch",on?"on":"off")))
                            .put("power",on?724:0).put("current",on?5:0));
                } catch(Exception e){throw new Exception(e);}
            }
            log.add("SIM.RELAY",on?"ON (simulated)":"OFF (simulated)");return;
        }
        if(!d.modelConfirmed) throw new Exception("MODEL_UNCONFIRMED: confirm physical label is S60TPF before switching");
        if(!d.hasKey && d.encrypted) throw new Exception("KEY_REQUIRED: obtain devicekey once from your eWeLink account");
        if(d.ip.isEmpty())throw new Exception("No resolved LAN host; check Wi-Fi and multicast");
        if(!d.localType.isEmpty() && !"plug".equals(d.localType))
            throw new Exception("Unsupported device local type: "+d.localType+"; refusing unverified relay command");
        JSONObject data=new JSONObject().put("switches",new JSONArray().put(new JSONObject().put("outlet",0).put("switch",on?"on":"off")))
                .put("operSide",1);
        network.execute(()->request(d,"switches",data,true));
        log.add("RELAY.QUEUED",id+" request="+(on?"on":"off")+"; awaiting hardware confirmation");
    }
    void poll(String id) throws Exception {
        final S60Device d;
        synchronized(this){d=devices.get(id);}
        if(d==null)throw new Exception("Unknown device");
        if(d.simulator){log.add("SIM.POLL","Simulated telemetry available");return;}
        if(d.ip.isEmpty())throw new Exception("No detected LAN address");
        if(d.encrypted && !d.hasKey)throw new Exception("KEY_REQUIRED");
        network.execute(()->request(d,"getState",new JSONObject(),true));
    }
    /** Never send the device key or raw decrypted information to logs. */
    private void request(S60Device d,String command,JSONObject data,boolean manual) {
        String key=keys.get(d.id);
        if(d.encrypted && key.isEmpty()) {log.add("LAN.KEY.MISSING",d.id+" cannot request "+command);return;}
        try {
            if(d.ip.isEmpty())return;
            JSONObject payload=new JSONObject().put("sequence",String.valueOf(sequence.incrementAndGet()))
                    .put("deviceid",d.id).put("selfApikey","123");
            if(d.encrypted) {
                JSONObject wrapped=LanCrypto.wrap(data,key);
                payload.put("data",wrapped.getString("data"));
                payload.put("iv",wrapped.getString("iv"));
                payload.put("encrypt",true);
            } else payload.put("data",data);
            String location="http://"+d.ip+":"+d.port+"/zeroconf/"+command;
            HttpURLConnection connection=(HttpURLConnection)new URL(location).openConnection();
            connection.setConnectTimeout(3500);connection.setReadTimeout(4500);
            connection.setRequestMethod("POST");connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
            connection.setRequestProperty("Connection","close");connection.setDoOutput(true);
            byte[] raw=payload.toString().getBytes(StandardCharsets.UTF_8);
            try(OutputStream out=connection.getOutputStream()){out.write(raw);}
            int status=connection.getResponseCode();
            InputStream body=status<400?connection.getInputStream():connection.getErrorStream();
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buf=new byte[1024];int total=0,n;
            try(InputStream input=body) {
                if(input!=null)while((n=input.read(buf))!=-1){total+=n;if(total>65536)throw new Exception("response too large");bytes.write(buf,0,n);}
            } finally {connection.disconnect();}
            JSONObject response=new JSONObject(bytes.toString("UTF-8"));
            int error=response.optInt("error",-1);
            if(status>=400 || error!=0) {
                synchronized(this){d.error="HTTP "+status+" SONOFF "+error;}
                log.add("LAN.REPLY.ERROR",d.id+" cmd="+command+" http="+status+" error="+error);return;
            }
            synchronized(this){d.error="";d.lastSeen=System.currentTimeMillis();}
            if(response.has("data") && response.has("iv")) {
                try {
                    JSONObject fields=LanCrypto.unwrap(response.getString("data"),response.getString("iv"),key);
                    synchronized(this){d.apply(fields);}
                    log.add("LAN.REPLY.STATE",d.id+" cmd="+command+" reply state decrypted");
                }catch(Exception e){log.add("LAN.REPLY.DECRYPT.FAIL",d.id+" "+e.getClass().getSimpleName());}
            } else if(response.optJSONObject("data")!=null) {
                synchronized(this){d.apply(response.optJSONObject("data"));}
                log.add("LAN.REPLY.STATE",d.id+" cmd="+command+" unencrypted state");
            } else {
                log.add("LAN.REPLY.OK",d.id+" cmd="+command+" OK, no state in response; waiting for mDNS");
            }
            if(command.equals("getState") && !response.has("data")) {
                String sled;
                synchronized(this){sled=d.values.optString("sledOnline");}
                if(sled.equals("on") || sled.equals("off")) {
                    // Known idempotent LED state from device; never invent a value.
                    try {request(d,"sledOnline",new JSONObject().put("sledOnline",sled),false);}
                    catch(Exception ex){log.add("LAN.FALLBACK.ERROR",d.id+" "+ex.getClass().getSimpleName());}
                }
            }
            if(manual && command.equals("switches")) {
                // Check actual state from the device rather than report success optimistically.
                try {Thread.sleep(350);} catch(InterruptedException ignored){Thread.currentThread().interrupt();}
                request(d,"getState",new JSONObject(),false);
            }
        }catch(Exception e) {
            synchronized(this){d.error=e.getClass().getSimpleName()+": "+e.getMessage();}
            log.add("LAN.REQUEST.FAIL",d.id+" cmd="+command+" "+e.getClass().getSimpleName()+" "+e.getMessage());
        }
    }
}
