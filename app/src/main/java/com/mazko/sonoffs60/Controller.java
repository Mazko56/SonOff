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
    private final android.content.SharedPreferences remembered;
    private final Map<String,S60Device> devices=new HashMap<>();
    private final ExecutorService network=Executors.newSingleThreadExecutor(); // The S60 embedded HTTP server processes one request at a time.
    private final ScheduledExecutorService refresh=Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong sequence=new AtomicLong(System.currentTimeMillis());
    private LanDiscovery discovery;
    Controller(Context context) {
        keys=new SecureKeys(context);
        remembered=context.getSharedPreferences("confirmed_s60", Context.MODE_PRIVATE);
        discovery=new LanDiscovery(context,this,log);
        log.add("APP", "Started S60 local control; official firmware untouched");
        refresh.scheduleAtFixedRate(() -> {
            ArrayList<S60Device> snapshot;
            synchronized(this){snapshot=new ArrayList<>(devices.values());}
            for(S60Device d:snapshot)if(d.hasKey && !d.ip.isEmpty() && System.currentTimeMillis()-d.lastSeen<90000 && System.currentTimeMillis()-d.lastData>15000) {
                network.execute(() -> request(d,"getState",new JSONObject(),false));
            }
        },15,25,TimeUnit.SECONDS);
    }
    void begin() {discovery.start();}
    void rescan() {discovery.rescan(); log.add("SCAN", "User requested another discovery query");}
    void close(){discovery.stop();network.shutdownNow();refresh.shutdownNow();}
    synchronized JSONObject state() {
        JSONObject state=new JSONObject();
        try {
            JSONArray a=new JSONArray();
            for(S60Device d:devices.values()) {
                if(!d.pending.isEmpty() && System.currentTimeMillis()-d.pendingSince>16000) {
                    log.add("RELAY.UNCONFIRMED",d.id+" No state announcement confirming requested "+d.pending);
                    d.pending="";
                    d.error="Немає підтвердження зміни стану. Перевірте розетку та відкрийте діагностику.";
                }
                a.put(d.asJson());
            }
            state.put("devices",a).put("logCount",log.asJson().length()).put("status","ready");
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
            d.modelConfirmed=remembered.getBoolean(id,false);
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
    synchronized void saveKey(String id,String secret) throws Exception {
        if(id==null || !id.matches("[a-zA-Z0-9]{10}"))throw new Exception("Choose discovered device");
        if(secret==null || secret.length()<6 || secret.length()>128)throw new Exception("Key must be 6-128 characters");
        S60Device d=devices.get(id);
        if(d==null)throw new Exception("Device must be discovered first");
        keys.put(id,secret);d.hasKey=true;d.error="";
        log.add("KEY.SAVED",id+" encrypted with Android Keystore (secret omitted from log)");
        network.execute(()->request(d,"getState",new JSONObject(),true));
    }
    synchronized void confirmModel(String id) throws Exception {
        S60Device d=devices.get(id);
        if(d==null)throw new Exception("Unknown device");
        d.modelConfirmed=true;
        remembered.edit().putBoolean(id,true).apply();
        log.add("MODEL.CONFIRMED",id+" was explicitly confirmed as S60TPF by operator; unverified via mDNS");
    }
    void relay(String id,boolean on) throws Exception {
        final S60Device d;
        synchronized(this){d=devices.get(id);}
        if(d==null) throw new Exception("Unknown device");
        if(!d.modelConfirmed) throw new Exception("MODEL_UNCONFIRMED: confirm physical label is S60TPF before switching");
        if(!d.hasKey && d.encrypted) throw new Exception("KEY_REQUIRED: obtain devicekey once from your eWeLink account");
        if(d.ip.isEmpty())throw new Exception("Не вдалося встановити адресу розетки у мережі");
        if(!d.localType.isEmpty() && !"plug".equals(d.localType))
            throw new Exception("Unsupported device local type: "+d.localType+"; refusing unverified relay command");
        JSONObject data=new JSONObject().put("switches",new JSONArray().put(new JSONObject().put("outlet",0).put("switch",on?"on":"off")))
                .put("operSide",1);
        synchronized(this){d.pending=on?"on":"off";d.pendingSince=System.currentTimeMillis();}
        network.execute(()->request(d,"switches",data,true));
        log.add("RELAY.QUEUED",id+" request="+(on?"on":"off")+"; awaiting hardware confirmation");
    }
    void poll(String id) throws Exception {
        final S60Device d;
        synchronized(this){d=devices.get(id);}
        if(d==null)throw new Exception("Unknown device");
        if(d.ip.isEmpty())throw new Exception("Розетку ще не знайдено в мережі");
        if(d.encrypted && !d.hasKey)throw new Exception("Потрібен ключ пристрою");
        network.execute(()->request(d,"getState",new JSONObject(),true));
    }
    void led(String id,boolean on) throws Exception {
        S60Device d; synchronized(this){d=devices.get(id);}
        if(d==null || !d.modelConfirmed || !d.hasKey || !d.values.has("sledOnline"))
            throw new Exception("Ця функція недоступна, поки розетка не підтвердить стан індикатора");
        JSONObject data=new JSONObject().put("sledOnline",on?"on":"off");
        network.execute(()->request(d,"sledonline",data,true));
    }
    void startup(String id,String mode) throws Exception {
        if(!mode.equals("on") && !mode.equals("off") && !mode.equals("stay"))
            throw new Exception("Недопустимий режим");
        S60Device d; synchronized(this){d=devices.get(id);}
        if(d==null || !d.modelConfirmed || !d.hasKey || d.values.optJSONArray("configure")==null)
            throw new Exception("Розетка не підтвердила підтримку цього налаштування");
        JSONArray settings=new JSONArray();
        synchronized(this) {
            JSONArray old=d.values.optJSONArray("configure");
            for(int i=0;i<old.length();i++) {
                JSONObject item=old.optJSONObject(i);
                if(item!=null) {
                    JSONObject copy=new JSONObject(item.toString());
                    if(copy.optInt("outlet",-1)==0)copy.put("startup",mode);
                    settings.put(copy);
                }
            }
        }
        JSONObject data=new JSONObject().put("configure",settings);
        network.execute(()->request(d,"configure",data,true));
    }
    /** Never send the device key or raw decrypted information to logs. */
    private void request(S60Device d,String command,JSONObject data,boolean manual) {
        request(d,command,data,manual,0);
    }
    private void request(S60Device d,String command,JSONObject data,boolean manual,int retry) {
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
                synchronized(this){d.error="Пристрій відхилив команду ("+error+")";d.pending="";}
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
                    try {request(d,"sledonline",new JSONObject().put("sledOnline",sled),false);}
                    catch(Exception ex){log.add("LAN.FALLBACK.ERROR",d.id+" "+ex.getClass().getSimpleName());}
                }
            }
            if(manual && command.equals("switches")) {
                // Check actual state from the device rather than report success optimistically.
                try {Thread.sleep(350);} catch(InterruptedException ignored){Thread.currentThread().interrupt();}
                request(d,"getState",new JSONObject(),false);
            }
        }catch(Exception e) {
            // A Sonoff LAN server may reset a connection while processing a prior request.
            // ON/OFF is an idempotent desired-state command; retry at most once.
            if(e instanceof java.io.IOException && retry<1 && !Thread.currentThread().isInterrupted()) {
                log.add("LAN.RETRY",d.id+" cmd="+command+" after "+e.getClass().getSimpleName());
                try {Thread.sleep(180);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
                request(d,command,data,manual,retry+1);
                return;
            }
            synchronized(this){d.error="Немає відповіді від пристрою";d.pending="";}
            log.add("LAN.REQUEST.FAIL",d.id+" cmd="+command+" "+e.getClass().getSimpleName()+" "+e.getMessage());
        }
    }
}
