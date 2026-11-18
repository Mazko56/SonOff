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
    private volatile long lastDiscoveryAt=System.currentTimeMillis();
    private LanDiscovery discovery;
    Controller(Context context) {
        keys=new SecureKeys(context);
        remembered=context.getSharedPreferences("confirmed_s60", Context.MODE_PRIVATE);
        discovery=new LanDiscovery(context,this,log);
        log.add("APP", "Started S60 local control; official firmware untouched");
        refresh.scheduleAtFixedRate(() -> {
            ArrayList<S60Device> snapshot;
            synchronized(this){snapshot=new ArrayList<>(devices.values());}
            long now=System.currentTimeMillis();
            for(S60Device d:snapshot) if((d.hasKey || !d.encrypted) && !d.ip.isEmpty() && now-d.lastSeen<120000 &&
                  now-d.lastAutoProbe>20000 && (now-d.lastData>23000 || now-d.lastHttpOk>30000)) {
                d.lastAutoProbe=now;
                network.execute(() -> request(d,"getState",new JSONObject(),false));
            }
            if(now-lastDiscoveryAt>180000) {lastDiscoveryAt=now;discovery.rescan();}
        },10,12,TimeUnit.SECONDS);
    }
    void begin() {discovery.start();log.add("SCAN.AUTOMATIC","Automatic discovery enabled at startup");}
    void rescan() {discovery.rescan();lastDiscoveryAt=System.currentTimeMillis();log.add("SCAN", "Discovery query requested");}
    void close(){discovery.stop();network.shutdownNow();refresh.shutdownNow();}
    synchronized String diagnosticReport() {
        StringBuilder out=new StringBuilder("Sonoff S60 — перевірка з’єднання\n");
        out.append("Знайдено пристроїв: ").append(devices.size()).append('\n');
        for(S60Device d:devices.values()) {
            out.append("--- ").append(d.id).append(" ---\n");
            out.append("Пошук: ").append(d.lastSeen>0?"виявлено":"не виявлено")
               .append("; адреса ").append(d.ip).append(':').append(d.port).append('\n');
            out.append("Захищене з’єднання: ").append(d.authenticated?"підтверджено":d.hasKey?"перевіряється":"потрібне підтвердження доступу").append('\n');
            out.append("Останній HTTP-відгук: ").append(d.lastHttpOk>0?new java.util.Date(d.lastHttpOk):"не надходив").append('\n');
            out.append("Останнє оновлення показників: ").append(d.lastData>0?new java.util.Date(d.lastData):"немає").append('\n');
            if(!d.errorCode.isEmpty())out.append("Причина: ").append(d.errorCode).append("; ").append(d.errorAdvice).append('\n');
        }
        return out.toString();
    }
    synchronized JSONObject state() {
        JSONObject state=new JSONObject();
        try {
            JSONArray a=new JSONArray();
            for(S60Device d:devices.values()) {
                if(!d.pending.isEmpty() && System.currentTimeMillis()-d.pendingSince>16000) {
                    log.add("RELAY.UNCONFIRMED",d.id+" No state announcement confirming requested "+d.pending);
                    d.pending="";
                    d.error="Розетка не підтвердила зміну стану.";
                    d.errorCode="NO_RELAY_CONFIRMATION";
                    d.errorAdvice="Перевірте фактичний стан розетки. Якщо він не збігається, відкрийте «Допомога» і надішліть журнал.";
                }
                a.put(d.asJson());
            }
            state.put("devices",a).put("logCount",log.asJson().length()).put("status","ready")
                .put("searchReady",discovery.isSearchReady()).put("searchIssue",discovery.getSearchError());
        } catch(Exception ex){log.add("UI.ERROR",ex.getClass().getSimpleName());}
        return state;
    }
    void onDiscover(String id,String ip,int port,Map<String,String> attributes,String from) {
        onDiscover(id,ip,port,attributes,from,"");
    }
    void onDiscover(String id,String ip,int port,Map<String,String> attributes,String from,String fallbackIp) {
        if(!id.matches("[a-zA-Z0-9]{10}"))return;
        S60Device d;
        boolean newDevice;
        boolean mustProbe=false;
        synchronized(this) {
            d=devices.get(id);newDevice=d==null;
            if(d==null) {d=new S60Device(id);devices.put(id,d);}
            boolean endpointChanged=false;
            if(ip!=null && !ip.isEmpty() && ip.contains(".") && !ip.equals(d.ip)) {
                // A recently verified endpoint is preferred over an mDNS A record that may be stale.
                if(d.lastHttpOk==0 || System.currentTimeMillis()-d.lastHttpOk>120000) {
                    d.ip=ip;endpointChanged=true;d.lastHttpOk=0;
                }
            }
            if(fallbackIp!=null && !fallbackIp.equals(ip) && fallbackIp.matches("(?:\\d{1,3}\\.){3}\\d{1,3}"))d.fallbackIp=fallbackIp;
            if(port>=1 && port<=65535 && port!=d.port){d.port=port;endpointChanged=true;d.lastHttpOk=0;}
            d.lastSource=from;d.lastSeen=System.currentTimeMillis();
            d.encrypted=!"false".equalsIgnoreCase(attributes.get("encrypt"));
            if(attributes.containsKey("type"))d.localType=attributes.get("type");
            d.hasKey=!keys.get(id).isEmpty();
            d.modelConfirmed=remembered.getBoolean(id,false);
            long now=System.currentTimeMillis();
            if((d.hasKey || !d.encrypted) && !d.ip.isEmpty() && (endpointChanged || d.lastAutoProbe==0 || now-d.lastAutoProbe>45000)) {
                d.lastAutoProbe=now;
                mustProbe=true;
            }
        }
        if(newDevice)log.add("DEVICE.FOUND",id+" host="+d.ip+":"+d.port+" type="+d.localType+" encrypted="+d.encrypted+" via "+from);
        final S60Device readyDevice=d;
        if(mustProbe) network.execute(()->{
            request(readyDevice,"getState",new JSONObject(),false);
            if(readyDevice.authenticated)requestConfiguration(readyDevice);
        });
        StringBuilder enc=new StringBuilder();
        for(int i=1;i<=4;i++)enc.append(attributes.getOrDefault("data"+i,""));
        if(enc.length()>0) {
            if(d.encrypted) {
                String secret=keys.get(id);
                if(!secret.isEmpty() && attributes.containsKey("iv")) {
                    try {
                        JSONObject vals=LanCrypto.unwrap(enc.toString(),attributes.get("iv"),secret);
                        acceptState(d,vals,"mDNS");
                        // Do not include raw decrypted information in diagnostics.
                        if(System.currentTimeMillis()-d.lastStatusLog>60000) {
                            d.lastStatusLog=System.currentTimeMillis();
                            log.add("MDNS.DECRYPT.OK",id+" received valid encrypted device state");
                        }
                    } catch(Exception e) {
                        synchronized(this){
                            d.error="Не вдалося встановити захищений зв’язок.";
                            d.errorCode="INVALID_DEVICE_KEY";
                            d.errorAdvice="Перевірте ключ цієї розетки в авторизованому eWeLink. IP вручну вводити не потрібно.";
                            d.authenticated=false;
                        }
                        log.add("MDNS.DECRYPT.FAIL",id+" "+e.getClass().getSimpleName()+"; key invalid or unsupported firmware");
                    }
                }
            } else {
                try {acceptState(d,new JSONObject(enc.toString()),"mDNS plain");}
                catch(Exception e){log.add("MDNS.JSON.ERROR",id+" malformed plain TXT status");}
            }
        }
    }
    private void acceptState(S60Device d,JSONObject received,String channel) {
        boolean confirmed;
        String requested;
        synchronized(this){
            requested=d.pending;
            d.apply(received);
            confirmed=!requested.isEmpty() && d.pending.isEmpty();
        }
        if(confirmed)log.add("RELAY.CONFIRMED",d.id+" relay="+requested+" confirmed via "+channel);
    }
    synchronized void saveKey(String id,String secret) throws Exception {
        if(id==null || !id.matches("[a-zA-Z0-9]{10}"))throw new Exception("Choose discovered device");
        if(secret==null || secret.length()<6 || secret.length()>128)throw new Exception("Key must be 6-128 characters");
        S60Device d=devices.get(id);
        if(d==null)throw new Exception("Device must be discovered first");
        keys.put(id,secret);d.hasKey=true;d.authenticated=false;d.error="";d.errorCode="";d.errorAdvice="";
        log.add("KEY.SAVED",id+" encrypted with Android Keystore (secret omitted from log)");
        network.execute(()->{
            request(d,"getState",new JSONObject(),true);
            if(d.authenticated)requestConfiguration(d);
        });
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
        if(!d.hasKey && d.encrypted) throw new Exception("Потрібне одноразове підтвердження доступу до розетки");
        if(!d.authenticated) throw new Exception("Спочатку дочекайтеся відповіді від розетки");
        if(d.ip.isEmpty())throw new Exception("Не вдалося встановити адресу розетки у мережі");
        if(!d.localType.isEmpty() && !"plug".equals(d.localType))
            throw new Exception("Знайдений пристрій не визначено як сумісну розетку");
        if(!d.values.has("switch") && d.values.optJSONArray("switches")==null)
            throw new Exception("Немає достовірної інформації про стан реле");
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
        network.execute(()->{
            request(d,"getState",new JSONObject(),true);
            if(d.authenticated)requestConfiguration(d);
        });
    }
    private void requestConfiguration(S60Device d) {
        if(d.lastConfigurationQuery>0 && System.currentTimeMillis()-d.lastConfigurationQuery<120000)return;
        if(!d.authenticated)return;
        d.lastConfigurationQuery=System.currentTimeMillis();
        try {request(d,"configure",new JSONObject().put("configure","get"),false);}
        catch(Exception e){log.add("LAN.CONFIG.FAIL",d.id+" "+e.getClass().getSimpleName());}
    }
    void led(String id,boolean on) throws Exception {
        S60Device d; synchronized(this){d=devices.get(id);}
        if(d==null || !d.authenticated || !d.values.has("sledOnline"))
            throw new Exception("Ця функція недоступна, поки розетка не підтвердить стан індикатора");
        JSONObject data=new JSONObject().put("sledOnline",on?"on":"off");
        network.execute(()->request(d,"sledonline",data,true));
    }
    void startup(String id,String mode) throws Exception {
        if(!mode.equals("on") && !mode.equals("off") && !mode.equals("stay"))
            throw new Exception("Недопустимий режим");
        S60Device d; synchronized(this){d=devices.get(id);}
        if(d==null || !d.authenticated || d.values.optJSONArray("configure")==null)
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
        d.lastRequest=System.currentTimeMillis();
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
                synchronized(this){
                    if(command.equals("getState") && error==404) {
                        // Some firmwares don't implement getState; mDNS state still works.
                        d.errorCode="";d.error="";
                    } else if(command.equals("configure") && error!=0) {
                        // Not a universal LAN capability; do not mark relay communication offline.
                    } else {
                        d.error="Розетка відхилила запит.";
                        d.errorCode=(error==401 || error==403)?"AUTH_REJECTED":"DEVICE_REJECTED";
                        d.errorAdvice=(error==401 || error==403)?"Перевірте ключ доступу до цієї розетки.":"Перевірте, чи увімкнено LAN-керування у eWeLink та чи підтримує прошивка цю дію.";
                        if(command.equals("switches"))d.pending="";
                    }
                }
                log.add("LAN.REPLY.ERROR",d.id+" cmd="+command+" http="+status+" device_error="+error);return;
            }
            synchronized(this){d.lastHttpOk=System.currentTimeMillis();d.lastSeen=d.lastHttpOk;}
            if(response.has("data") && response.has("iv")) {
                try {
                    JSONObject fields=LanCrypto.unwrap(response.getString("data"),response.getString("iv"),key);
                    acceptState(d,fields,"HTTP response");
                    log.add("LAN.REPLY.STATE",d.id+" cmd="+command+" reply state decrypted");
                }catch(Exception e){
                    synchronized(this){d.authenticated=false;d.error="Не вдалося розшифрувати відповідь розетки.";
                        d.errorCode="INVALID_DEVICE_KEY";d.errorAdvice="Перевірте ключ доступу або версію прошивки в eWeLink.";}
                    log.add("LAN.REPLY.DECRYPT.FAIL",d.id+" cmd="+command+" "+e.getClass().getSimpleName());
                }
            } else if(response.optJSONObject("data")!=null) {
                acceptState(d,response.optJSONObject("data"),"HTTP plain");
                log.add("LAN.REPLY.STATE",d.id+" cmd="+command+" unencrypted state");
            } else {
                log.add("LAN.REPLY.OK",d.id+" cmd="+command+" HTTP acknowledged; waiting for confirmed state via mDNS");
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
                // Try the sender's private IP when the service A record is stale.
                if(!d.fallbackIp.isEmpty() && !d.fallbackIp.equals(d.ip)) {
                    String old=d.ip;
                    d.ip=d.fallbackIp;
                    log.add("LAN.IP.FALLBACK",d.id+" advertised="+old+" sender="+d.ip+" after "+e.getClass().getSimpleName());
                } else {
                    log.add("LAN.RETRY",d.id+" cmd="+command+" after "+e.getClass().getSimpleName());
                }
                try {Thread.sleep(250);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
                request(d,command,data,manual,retry+1);
                return;
            }
            String reason="NETWORK_ERROR";
            String message="Не вдалося зв’язатися з розеткою.";
            String advice="Перевірте, що телефон і розетка в одній Wi-Fi-мережі та LAN Control увімкнено в eWeLink.";
            if(e instanceof java.net.SocketTimeoutException){
                reason="TIMEOUT";message="Розетка знайдена, але не відповідає.";
                advice="Перевірте доступ у локальній мережі: ізоляцію Wi-Fi-клієнтів, правильність адреси розетки та її живлення.";
            }else if(e instanceof java.net.ConnectException){
                reason="CONNECTION_REFUSED";message="Розетка не приймає підключення.";
                advice="Перевірте LAN Control у eWeLink, порт пристрою та налаштування роутера.";
            }else if(e instanceof java.net.NoRouteToHostException){
                reason="NO_ROUTE";message="Немає мережевого маршруту до розетки.";
                advice="Підключіть телефон до тієї ж Wi-Fi-мережі й вимкніть гостьову ізоляцію.";
            }else if(e instanceof org.json.JSONException){
                reason="INVALID_RESPONSE";message="Розетка повернула незрозумілу відповідь.";
                advice="Перевірте модель і прошивку. Для діагностики поділіться журналом.";
            }
            synchronized(this){
                if(!command.equals("configure")) {
                    d.error=message;d.errorCode=reason;d.errorAdvice=advice;
                }
                if(command.equals("switches"))d.pending="";
            }
            log.add("LAN.REQUEST.FAIL",d.id+" cmd="+command+" endpoint="+d.ip+":"+d.port+" code="+reason+" cause="+e.getClass().getSimpleName()+" detail="+e.getMessage());
        }
    }
}
