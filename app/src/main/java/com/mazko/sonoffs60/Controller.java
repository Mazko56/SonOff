package com.mazko.sonoffs60;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.TreeSet;
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
    private final Context appContext;
    private final SecureKeys keys;
    private final android.content.SharedPreferences remembered;
    private final Map<String,S60Device> devices=new HashMap<>();
    private final ExecutorService diagnostic=Executors.newSingleThreadExecutor(); // Read-only, throttled; never blocks authenticated relay controls.
    private final ExecutorService network=Executors.newSingleThreadExecutor(); // The S60 embedded HTTP server processes one request at a time.
    private final ScheduledExecutorService refresh=Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong sequence=new AtomicLong(System.currentTimeMillis());
    private volatile long lastDiscoveryAt=System.currentTimeMillis();
    private volatile long lastApAttempt=0;
    private volatile long lastPrivateServerCheck=0;
    private volatile boolean lastApConnected=false;
    private final ApPairingManager apManager;
    private final LegacyPrivateServer privateServer;
    private volatile String apResult="Не перевірявся";
    private volatile String apLastSsid="";
    private final java.util.Set<String> alternateServices=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private LanDiscovery discovery;
    Controller(Context context) {
        appContext=context.getApplicationContext();
        keys=new SecureKeys(context);
        remembered=context.getSharedPreferences("confirmed_s60", Context.MODE_PRIVATE);
        discovery=new LanDiscovery(context,this,log);
        apManager=new ApPairingManager(context,this,log);
        privateServer=new LegacyPrivateServer(context,this);
        log.add("APP", "Started S60 local control; official firmware untouched");
        refresh.scheduleAtFixedRate(() -> {
            ArrayList<S60Device> snapshot;
            synchronized(this){snapshot=new ArrayList<>(devices.values());}
            long now=System.currentTimeMillis();
            for(S60Device d:snapshot) if(!privateServer.isConnected(d.id) && (d.hasKey || !d.encrypted) && !d.ip.isEmpty() && now-d.lastSeen<120000 &&
                  now-d.lastAutoProbe>20000 && (now-d.lastData>23000 || now-d.lastHttpOk>30000)) {
                d.lastAutoProbe=now;
                network.execute(() -> autoRead(d,false));
            }
            // Try first-party device AP pairing automatically, only on its dedicated subnet.
            // Ordinary home Wi-Fi must NOT trigger a request to 10.10.7.1.
            boolean ap=!apManager.isConnecting() && LocalPairingProbe.canReadFromDeviceAp(appContext);
            if(!ap) {
                if(lastApConnected)log.add("PAIR.NETWORK", "Exited device setup Wi-Fi; returning to LAN discovery");
                lastApConnected=false;lastApAttempt=0;
            } else {
                if(!lastApConnected)log.add("PAIR.NETWORK", "Device setup Wi-Fi detected; verifying local onboarding support");
                lastApConnected=true;
                if(lastApAttempt==0 || now-lastApAttempt>90000) {
                    lastApAttempt=now;
                    network.execute(() -> {
                        try {tryLocalOnboarding();}
                        catch(Exception ex) { /* The reason was recorded in LOCAL.PAIR.UNAVAILABLE */ }
                    });
                }
            }
            if(now-lastPrivateServerCheck>45000){
                lastPrivateServerCheck=now;
                diagnostic.execute(privateServer::startOrRefresh);
            }
            if(now-lastDiscoveryAt>180000) {lastDiscoveryAt=now;discovery.rescan();}
        },10,12,TimeUnit.SECONDS);
    }
    void begin() {
        log.add("NET.ACTIVE",LanDiagnostics.wifiSnapshot(appContext));
        log.add("DIAG.POLICY","private Wi-Fi IPv4 only; eWeLink AES and DIY plaintext selected by TXT; TCP/read-only probes; passive Matter detection; no cloud; writes require verified access");
        discovery.start();log.add("SCAN.AUTOMATIC","Automatic discovery enabled at startup");
        // Legacy SoftAP fallback scans for an actual ITEAD hotspot before requesting OS approval.
        apManager.scan();
        diagnostic.execute(privateServer::startOrRefresh);
    }
    void rescan() {rescanLanOnly();apManager.scan();diagnostic.execute(privateServer::startOrRefresh);}
    void rescanLanOnly() {discovery.rescan();lastDiscoveryAt=System.currentTimeMillis();log.add("SCAN", "Discovery query requested");
        log.add("NET.ACTIVE",LanDiagnostics.wifiSnapshot(appContext));
        diagnostic.execute(privateServer::startOrRefresh);}
    void scanApAfterPermissions() {apManager.scan();}
    void updateApProgress(String ssid,String result) {
        apLastSsid=(ssid==null||ssid.isEmpty())?"":LegacyApIdentity.safeSsid(ssid);apResult=result;
    }
    void close(){privateServer.stop();apManager.close();discovery.stop();diagnostic.shutdownNow();network.shutdownNow();refresh.shutdownNow();}
    synchronized String diagnosticReport() {
        StringBuilder out=new StringBuilder("Sonoff S60 — детальна перевірка локального з’єднання (без секретів)\n");
        out.append("Мережа телефону: ").append(LanDiagnostics.wifiSnapshot(appContext)).append('\n');
        out.append("Приватний сервер (старий Sonoff HTTPS/WSS): ").append(privateServer.status()).append('\n');
        out.append("Виявлено: ").append(devices.size()).append("; NSD/mDNS активні: ").append(discovery.isSearchReady()).append('\n');
        out.append("Керування реле можливе лише після верифікації ключа або відкритого DIY API.\n");
        out.append("Інші протоколи (лише виявлення): ").append(alternateServices.size()).append("; див. PROTOCOL.OTHER у журналі.\n");
        out.append("Legacy SoftAP (S31-like) останній результат: ").append(apResult).append("; AP: ").append(apLastSsid.isEmpty()?"не виявлено":apLastSsid).append('\n');
        for(String id:new TreeSet<>(devices.keySet())) {
            S60Device d=devices.get(id);
            out.append("--- ").append(d.id).append(" ---\n")
                .append("Назва: ").append(d.advertisedName.isEmpty()?"не оголошена локально":d.advertisedName).append('\n')
                .append("Модель: ").append(d.modelHint.isEmpty()?"не встановлена":d.modelHint)
                .append("; UIID: ").append(d.uiidHint.isEmpty()?"не оголошено":d.uiidHint)
                .append("; FW: ").append(d.firmwareHint.isEmpty()?"не оголошено":d.firmwareHint).append('\n')
                .append("Гіпотеза про модель: ").append(d.identifiedAs).append("; профіль: ").append(d.protocol).append('\n')
                .append("LAN: ").append(d.ip).append(':').append(d.port)
                .append("; fallback: ").append(d.fallbackIp.isEmpty()?"немає":d.fallbackIp)
                .append("; type: ").append(d.localType).append("; encrypted: ").append(d.encrypted)
                .append("; via: ").append(d.lastSource).append('\n')
                .append("Сервіс: ").append(d.serviceInstance).append('\n')
                .append("Ключ у Keystore: ").append(d.hasKey?"є":"немає")
                .append("; авторизація: ").append(d.authenticated?"підтверджена":"не підтверджена").append('\n')
                .append("TCP: ").append(d.diagnosticTcp).append("; HTTP read-only: ").append(d.diagnosticHttp).append('\n')
                .append("Маршрут підключення: ").append(d.lastProtocolAttempt).append("; висновок: ").append(d.accessReason).append('\n')
                .append("Остання HTTP-відповідь (успішна): ").append(d.lastHttpOk>0?new java.util.Date(d.lastHttpOk):"немає").append('\n')
                .append("Показники (час): ").append(d.lastData>0?new java.util.Date(d.lastData):"немає").append('\n')
                .append("Поля показників: ").append(LanDiagnostics.jsonKeys(d.values)).append('\n')
                .append("Доступні значення (обмежений перелік, сирі одиниці): ")
                .append(LanDiagnostics.telemetrySummary(d.values)).append('\n');
            if(!d.errorCode.isEmpty())out.append("Остання помилка: ").append(d.errorCode).append("; ").append(d.errorAdvice).append('\n');
        }
        return out.toString();
    }
    void onAlternateService(String type,String instance,String ip,int port) {
        String safe=LanDiagnostics.shortText(instance);
        String id=type+"|"+safe;
        if(alternateServices.add(id)) log.add("PROTOCOL.OTHER",type+" service="+safe+" host="+ip+":"+port+
          "; discovery only: Matter commissioning/credentials are NOT implemented; no unauthorized HTTP commands");
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
        boolean mustReadOnly=false;
        if(privateServer.isConnected(id)) {
            // Do not downgrade a validated private-cloud session to unauthenticated mDNS.
            log.add("PRIVATE.LAN.PARALLEL",id+" mDNS also present; session preserved");
            return;
        }
        synchronized(this) {
            d=devices.get(id);newDevice=d==null;
            if(d==null) {d=new S60Device(id);devices.put(id,d);}
            boolean endpointChanged=false;
            if(ip!=null && !ip.isEmpty() && ip.contains(".") && !ip.equals(d.ip)) {
                // A recently verified endpoint is preferred over an mDNS A record that may be stale.
                if(d.lastHttpOk==0 || System.currentTimeMillis()-d.lastHttpOk>120000) {
                    d.ip=ip;endpointChanged=true;d.lastHttpOk=0;
                    d.authenticated=false;d.plaintextHttpConfirmed=false;
                }
            }
            if(fallbackIp!=null && !fallbackIp.equals(ip) && LanDiagnostics.privateIpv4(fallbackIp))d.fallbackIp=fallbackIp;
            if(port>=1 && port<=65535 && port!=d.port){d.port=port;endpointChanged=true;d.lastHttpOk=0;d.authenticated=false;d.plaintextHttpConfirmed=false;}
            d.lastSource=from;d.lastSeen=System.currentTimeMillis();
            String previousProtocol=d.protocol;
            if(attributes.containsKey("type"))d.localType=LanDiagnostics.shortText(attributes.get("type"));
            String encryptionFlag=attributes.getOrDefault("encrypt", "");
            d.encrypted=ProtocolRouter.encrypted(d.localType,encryptionFlag);
            d.protocol=ProtocolRouter.protocol(d.localType,d.encrypted);
            if(!previousProtocol.equals(d.protocol)) {
                d.authenticated=false;d.plaintextHttpConfirmed=false;d.lastData=0;
                log.add("LAN.PROTOCOL.CHANGED",id+" previous="+previousProtocol+" current="+d.protocol+"; access requires re-verification");
            }
            if(attributes.containsKey("service_instance"))d.serviceInstance=LanDiagnostics.shortText(attributes.get("service_instance"));
            String advertisedName=attributes.getOrDefault("devicename",attributes.getOrDefault("name", ""));
            if(!advertisedName.isEmpty())d.advertisedName=LanDiagnostics.shortText(advertisedName);
            String advertisedModel=attributes.getOrDefault("productmodel",attributes.getOrDefault("model", ""));
            if(!advertisedModel.isEmpty())d.modelHint=LanDiagnostics.shortText(advertisedModel);
            String advertisedFirmware=attributes.getOrDefault("fwversion","");
            if(!advertisedFirmware.isEmpty())d.firmwareHint=LanDiagnostics.shortText(advertisedFirmware);
            String hintedUiid=attributes.getOrDefault("uiid", "");
            if(hintedUiid.matches("[0-9]{1,5}"))d.uiidHint=hintedUiid;
            d.identifiedAs=ProtocolRouter.likelyModels(d.localType,d.uiidHint,d.modelHint,d.firmwareHint)
                 + " | " + SonoffProfiles.identify(d.localType,d.uiidHint,false);
            d.hasKey=!keys.get(id).isEmpty();
            if(newDevice || !previousProtocol.equals(d.protocol) || endpointChanged) {
                d.accessReason=d.encrypted && !d.hasKey?"ключ не оголошений; factory LAN AES":
                    d.encrypted?"ключ знайдено, очікуємо підтвердження AES":"потрібна успішна відповідь відкритого HTTP";
            }
            d.modelConfirmed=remembered.getBoolean(id,false);
            long now=System.currentTimeMillis();
            if((d.hasKey || !d.encrypted) && !d.ip.isEmpty() && (endpointChanged || d.lastAutoProbe==0 || now-d.lastAutoProbe>45000)) {
                d.lastAutoProbe=now;
                mustProbe=true;
            }
            // Safe diagnostics only: run once initially and at most once per minute after address changes.
            if(!d.hasKey && d.encrypted && LanDiagnostics.privateIpv4(d.ip)
                    && (d.lastDiagnostic==0 || (endpointChanged && now-d.lastDiagnostic>60000))) {
                d.lastDiagnostic=now;
                mustReadOnly=true;
            }
        }
        if(newDevice) {
            log.add("DEVICE.FOUND",id+" host="+d.ip+":"+d.port+" type="+d.localType+" encrypted="+d.encrypted+" via "+from);
            log.add("DEVICE.MDNS",id+" "+LanDiagnostics.txtSummary(attributes));
            log.add("MODEL.CANDIDATE",id+" protocol="+d.protocol+" candidate="+d.identifiedAs+"; no model identity is inferred from type alone");
            if(d.encrypted && !d.hasKey)log.add("ACCESS.NOT_YET_AUTHORIZED",id+" encrypted LAN; key absent; read-only diagnostics will run, relay writes disabled");
        }
        final S60Device readyDevice=d;
        if(mustReadOnly)diagnostic.execute(()->readOnlyDiagnosis(readyDevice));
        if(mustProbe) network.execute(()->{
            autoRead(readyDevice,false);
            if(readyDevice.authenticated && readyDevice.encrypted)requestConfiguration(readyDevice);
        });
        StringBuilder enc=new StringBuilder();
        for(int i=1;i<=4;i++)enc.append(attributes.getOrDefault("data"+i,""));
        if(enc.length()>0) {
            if(newDevice)log.add("MDNS.DATA",id+" state announced encrypted="+d.encrypted+" chars="+enc.length()+" iv="+attributes.containsKey("iv")+" keyStored="+d.hasKey);
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
                            d.errorAdvice="Захищене підключення не підтверджено. Надішліть журнал подій.";
                            d.authenticated=false;
                        }
                        log.add("MDNS.DECRYPT.FAIL",id+" "+e.getClass().getSimpleName()+"; key invalid or unsupported firmware");
                    }
                }
            } else {
                try {
                    JSONObject plain=new JSONObject(enc.toString());
                    synchronized(this){d.observeReadOnly(plain);}
                    if(newDevice)log.add("MDNS.PLAINTEXT",id+" unencrypted TXT state available; awaiting HTTP protocol confirmation before writes");
                } catch(Exception e){log.add("MDNS.JSON.ERROR",id+" malformed plain TXT status");}
            }
        }
    }
    private void acceptState(S60Device d,JSONObject received,String channel) {
        boolean confirmed;
        String requested;
        int requestedOutlet;
        synchronized(this){
            requested=d.pending;
            requestedOutlet=d.pendingOutlet;
            d.apply(received);
            confirmed=!requested.isEmpty() && d.pending.isEmpty();
        }
        if(confirmed)log.add("RELAY.CONFIRMED",d.id+" outlet="+requestedOutlet+" relay="+requested+" confirmed via "+channel);
        if(System.currentTimeMillis()-d.lastStatusLog>60000) {
            d.lastStatusLog=System.currentTimeMillis();
            log.add("LAN.STATE.SAMPLE",d.id+" channel="+channel+" "+LanDiagnostics.telemetrySummary(received)+
                " raw; profile="+SonoffProfiles.identify(d.localType,d.uiidHint,d.values.optJSONArray("switches")!=null)+
                "; electric multiplier="+SonoffProfiles.metricMultiplier("power",d.localType,d.uiidHint,
                   d.values.optJSONArray("switches")!=null));
        }
    }
    synchronized void saveKey(String id,String secret) throws Exception {
        if(id==null || !id.matches("[a-zA-Z0-9]{10}"))throw new Exception("Choose discovered device");
        if(secret==null || secret.length()<6 || secret.length()>128)throw new Exception("Key must be 6-128 characters");
        S60Device d=devices.get(id);
        if(d==null)throw new Exception("Device must be discovered first");
        keys.put(id,secret);d.hasKey=true;d.authenticated=false;d.error="";d.errorCode="";d.errorAdvice="";
        log.add("KEY.SAVED",id+" encrypted with Android Keystore (secret omitted from log)");
        network.execute(()->{
            autoRead(d,true);
            if(d.authenticated && d.encrypted)requestConfiguration(d);
        });
    }
    /** Only store a key supplied by a validated factory AP, never fabricate or log it. */
    void acceptApKey(LocalPairingProbe.Result result,String ssid) throws Exception {
        if(result==null || !result.id.matches("[A-Za-z0-9]{10}") ||
           !LegacyApIdentity.matchesId(ssid,result.id))throw new Exception("AP_UNVERIFIED_IDENTITY");
        keys.put(result.id,result.key); // Android Keystore AES-GCM
        synchronized(this) {
            S60Device existing=devices.get(result.id);
            if(existing!=null){
                existing.hasKey=true;existing.authenticated=false;
                existing.errorCode="";existing.errorAdvice="";existing.error="";
                existing.accessReason="AP_KEY_IMPORTED; reconnect to LAN to verify";
            }
        }
        apResult="Ключ отримано локально (LAN-підтвердження ще немає)";
        apLastSsid=LegacyApIdentity.safeSsid(ssid);
        log.add("AP.KEY.STORED",result.id+" encrypted in Android Keystore; AP identity matched; LAN AUTH NOT YET VERIFIED");
        log.add("AP.PAIR.LIMITATION","Legacy AP reading does not join a new plug to home Wi-Fi; do not reset an installed plug merely to run a diagnostic");
    }
    /** Legacy direct AP check, only on an already-verified 10.10.7.x interface. */
    JSONObject tryLocalOnboarding() throws Exception {
        try {
            LocalPairingProbe.Result result=LocalPairingProbe.readFromDeviceAp(appContext);
            // The SSID is not available reliably through ConnectivityManager; this
            // path was entered ONLY because the phone already uses the device subnet.
            keys.put(result.id,result.key);
            synchronized(this){S60Device d=devices.get(result.id);
                if(d!=null){d.hasKey=true;d.authenticated=false;d.accessReason="AP_KEY_IMPORTED; LAN pending";}}
            apResult="Ключ отримано через раніше підключену AP";
            log.add("LOCAL.PAIR.READ",result.id+" acquired through verified 10.10.7.x network; secret hidden; requires LAN verification");
            return new JSONObject().put("ok",true).put("id",result.id)
                .put("message","Ключ збережено локально, потрібне повторне LAN-підключення");
        }catch(Exception ex){
            apResult="AP /device недоступний ("+ex.getClass().getSimpleName()+")";
            log.add("LOCAL.PAIR.UNAVAILABLE",ex.getClass().getSimpleName()+" "+LegacyApIdentity.safeError(ex.getMessage()));throw ex;
        }
    }
    /** Local private-cloud ingress: only an AP-verified or otherwise user-authorized device key is accepted. */
    boolean legacyAuthorize(String id,String offered) {
        return LegacyCloudProtocol.isDeviceId(id) && LegacyCloudProtocol.equalSecret(keys.get(id),offered);
    }
    synchronized void legacyRegister(String id,String model,String firmware,String remoteIp) {
        S60Device d=devices.get(id);
        if(d==null){d=new S60Device(id);devices.put(id,d);}
        d.protocol="LEGACY_CLOUD_WSS";d.lastSource="private HTTPS/WSS server";
        d.encrypted=true;d.hasKey=true;d.authenticated=true;
        d.localType=model.toUpperCase(java.util.Locale.ROOT).contains("DUAL") ||
            "PSF-B04-GL".equalsIgnoreCase(model)?"strip":"plug";
        d.modelHint=LanDiagnostics.shortText(model);
        d.firmwareHint=LanDiagnostics.shortText(firmware);
        d.ip=remoteIp;d.lastSeen=System.currentTimeMillis();
        d.errorCode="";d.error="";d.errorAdvice="";
        d.accessReason="PRIVATE_SERVER_AUTHENTICATED; awaiting state update";
        d.identifiedAs="private server reported: "+d.modelHint;
        log.add("PRIVATE.DEVICE.REGISTERED",id+" model="+d.modelHint+" firmware="+d.firmwareHint+
            " (private server credentials validated; state not yet verified)");
    }
    synchronized void legacyState(String id,JSONObject params) {
        S60Device d=devices.get(id);
        if(d==null||!privateServer.isConnected(id))return;
        d.lastSeen=System.currentTimeMillis();
        if(!LegacyCloudProtocol.hasRelayState(params)) {
            d.observeReadOnly(params);
            log.add("PRIVATE.STATE.METADATA",id+" no confirmed relay field");return;
        }
        acceptState(d,params,"PRIVATE.WSS");
        d.accessReason="PRIVATE_SERVER_AUTHENTICATED_STATE";
    }
    synchronized JSONObject legacyQuery(String id,JSONArray requested)throws Exception {
        JSONObject response=new JSONObject();
        S60Device d=devices.get(id);
        if(d==null||requested==null)return response;
        java.util.Set<String> allowed=new java.util.HashSet<>(java.util.Arrays.asList(
            "switch","switches","power","voltage","current","dayKwh","monthKwh","rssi","sledOnline","startup"));
        for(int i=0;i<Math.min(20,requested.length());i++){
            String field=requested.optString(i,"");
            if(allowed.contains(field)&&d.values.has(field))response.put(field,d.values.get(field));
        }
        return response;
    }
    synchronized void legacyDisconnect(String id) {
        S60Device d=devices.get(id);
        if(d!=null && "LEGACY_CLOUD_WSS".equals(d.protocol)){
            d.authenticated=false;d.lastSeen=0;d.pending="";
            d.errorCode="PRIVATE_CONNECTION_LOST";
            d.error="З'єднання з приватним сервером втрачено.";
            d.errorAdvice="Перевірте Wi-Fi та серверний журнал.";
        }
    }
    String privateServerStatus(){return privateServer.status();}
    void provisionLegacy(String ssid,String password,boolean confirmed)throws Exception {
        if(!confirmed)throw new Exception("Потрібна явна згода на зміну налаштувань Wi-Fi розетки");
        if(!privateServer.isReady()) {
            diagnostic.execute(privateServer::startOrRefresh);
            throw new Exception("Приватний HTTPS-сервер ще не готовий. Повторіть після перевірки журналу.");
        }
        apManager.provisionToPrivateServer(ssid,password,privateServer.ip());
    }
    synchronized void confirmModel(String id) throws Exception {
        S60Device d=devices.get(id);
        if(d==null)throw new Exception("Unknown device");
        d.modelConfirmed=true;
        remembered.edit().putBoolean(id,true).apply();
        log.add("MODEL.CONFIRMED",id+" was explicitly confirmed as S60TPF by operator; unverified via mDNS");
    }
    void relay(String id,boolean on) throws Exception {relayOutlet(id,on,0);}
    void relayOutlet(String id,boolean on,int outlet) throws Exception {
        if(outlet<0||outlet>15)throw new Exception("Недопустимий номер каналу");
        final S60Device d;
        synchronized(this){d=devices.get(id);}
        if(d==null) throw new Exception("Unknown device");
        if(privateServer.isConnected(id)) {
            if(!d.authenticated || (d.lastData==0) ||
                !(d.values.has("switch") || d.hasOutletZero()))
                throw new Exception("Приватний сервер ще не отримав підтверджений стан реле");
            if(outlet>0 && !d.hasOutlet(outlet))throw new Exception("Канал не підтверджений пристроєм");
            Integer channel=d.hasOutletZero()?outlet:null;
            if(!privateServer.relay(id,on,channel))throw new Exception("Не вдалося відправити команду через приватний сервер");
            synchronized(this){d.pending=on?"on":"off";d.pendingOutlet=outlet;d.pendingSince=System.currentTimeMillis();}
            log.add("RELAY.QUEUED",id+" private WebSocket request; confirmation pending");
            return;
        }
        if(!d.encrypted && !ProtocolRouter.trustedPlaintextRelay(d.protocol,d.localType,
             d.values.has("switch")||d.hasOutletZero(),d.plaintextHttpConfirmed))
            throw new Exception("Немає підтвердження відкритого керування для цієї моделі");
        if(!d.hasKey && d.encrypted) throw new Exception("Потрібне одноразове підтвердження доступу до розетки");
        if(!d.authenticated) throw new Exception("Спочатку дочекайтеся відповіді від розетки");
        if(d.ip.isEmpty())throw new Exception("Не вдалося встановити адресу розетки у мережі");
        final String command;
        final JSONObject data;
        // Urban82 Plug selects /switch or /switches based on the ACTUAL decrypted state.
        // AlexxIT XSwitchPOWR3 includes operSide only for UIID190; TH uses extra fields.
        if(d.hasOutlet(outlet)) {
            command="switches";
            data=new JSONObject().put("switches",new JSONArray().put(
                new JSONObject().put("outlet",outlet).put("switch",on?"on":"off")));
            if(SonoffProfiles.needsOperSide(d.uiidHint,d.localType,d.modelHint,true,
                  d.values.has("power") && d.values.has("current") && d.values.has("voltage"))) data.put("operSide",1);
        } else if(outlet==0 && d.values.has("switch")) {
            command="switch";
            data=new JSONObject().put("switch",on?"on":"off");
            if(SonoffProfiles.thSwitchExtras(d.localType,d.uiidHint)) {
                data.put("mainSwitch",on?"on":"off").put("deviceType","normal");
            }
        } else throw new Exception("Розетка ще не підтвердила стан вибраного каналу");
        log.add("LAN.RELAY.PROFILE",id+" endpoint="+command+" uiid="+d.uiidHint+
                " localType="+d.localType+" outlet="+outlet+
                " operSide="+data.has("operSide")+" modelConfirmed="+d.modelConfirmed);
        synchronized(this){d.pending=on?"on":"off";d.pendingOutlet=outlet;d.pendingSince=System.currentTimeMillis();}
        network.execute(()->request(d,command,data,true));
        log.add("RELAY.QUEUED",id+" request="+(on?"on":"off")+"; awaiting hardware confirmation");
    }
    void poll(String id) throws Exception {
        final S60Device d;
        synchronized(this){d=devices.get(id);}
        if(d==null)throw new Exception("Unknown device");
        if(d.ip.isEmpty())throw new Exception("Розетку ще не знайдено в мережі");
        if(d.encrypted && !d.hasKey) {
            diagnostic.execute(()->readOnlyDiagnosis(d));
            log.add("DIAG.MANUAL",id+" read-only diagnostic queued; relay writes disabled");
            return;
        }
        network.execute(()->{
            autoRead(d,true);
            if(d.authenticated && d.encrypted)requestConfiguration(d);
        });
    }
    private void requestConfiguration(S60Device d) {
        if(d.lastConfigurationQuery>0 && System.currentTimeMillis()-d.lastConfigurationQuery<120000)return;
        if(!d.authenticated || !d.encrypted)return;
        d.lastConfigurationQuery=System.currentTimeMillis();
        try {request(d,"configure",new JSONObject().put("configure","get"),false);}
        catch(Exception e){log.add("LAN.CONFIG.FAIL",d.id+" "+e.getClass().getSimpleName());}
    }
    void led(String id,boolean on) throws Exception {
        S60Device d; synchronized(this){d=devices.get(id);}
        if(d==null || !d.encrypted || !d.authenticated || !d.values.has("sledOnline"))
            throw new Exception("Ця функція недоступна, поки розетка не підтвердить стан індикатора");
        JSONObject data=new JSONObject().put("sledOnline",on?"on":"off");
        network.execute(()->request(d,"sledOnline",data,true));
    }
    void startup(String id,String mode) throws Exception {
        if(!mode.equals("on") && !mode.equals("off") && !mode.equals("stay"))
            throw new Exception("Недопустимий режим");
        S60Device d; synchronized(this){d=devices.get(id);}
        if(d==null || !d.encrypted || !d.authenticated || d.values.optJSONArray("configure")==null)
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
    /** Route the first state query by confirmed LAN mode. DIY v1 mandates /info, not getState. */
    private void autoRead(S60Device d,boolean manual) {
        String first=ProtocolRouter.initialRead(d.protocol);
        long before=d.lastData;
        log.add("LAN.PROTOCOL.SELECT",d.id+" mode="+d.protocol+" first="+first+
                " key="+(d.hasKey?"stored":"not stored")+" type="+d.localType);
        d.lastProtocolAttempt=d.protocol+" -> "+first;
        request(d,first,new JSONObject(),manual);
        // Fallback on supported read paths only; for DIY signal_strength is an additional read-only measurement.
        if(ProtocolRouter.DIY.equals(d.protocol)) {
            if(d.authenticated)request(d,"signal_strength",new JSONObject(),false);
        } else if(d.lastData<=before && !"info".equals(first)) {
            log.add("LAN.PROTOCOL.FALLBACK",d.id+" getState did not provide state; trying /info");
            d.lastProtocolAttempt=d.protocol+" -> getState -> info";
            request(d,"info",new JSONObject(),false);
        }
        if(d.encrypted && d.hasKey && d.authenticated && d.lastData<=before) {
            // SonoffLAN update_local: some POW/S60 firmware sends the current state
            // in reply to a re-send of its existing LED setting. This is an
            // IDEMPOTENT WRITE, never a key bypass, used sparingly (>=60s).
            long now=System.currentTimeMillis();
            String led=d.values.optString("sledOnline","");
            if(("on".equals(led)||"off".equals(led)) &&
               SonoffProfiles.shouldTryLedEcho(d.uiidHint,d.localType,true) &&
               now-d.lastLedEchoProbe>60000) {
                d.lastLedEchoProbe=now;
                log.add("LAN.PROTOCOL.FALLBACK",d.id+
                    " authenticated state exists; idempotent LED echo (same value), at most once per minute");
                d.lastProtocolAttempt=d.protocol+" -> getState -> info -> sledOnline (same value)";
                try { request(d,"sledOnline",new JSONObject().put("sledOnline",led),false); }
                catch(org.json.JSONException e) {log.add("LAN.PROTOCOL.FALLBACK.ERROR",d.id+" invalid LED echo body");}
            } else if(SonoffProfiles.shouldTryStatistics(d.uiidHint)) {
                log.add("LAN.PROTOCOL.FALLBACK",d.id+" DUALR3 statistics read");
                request(d,"statistics",new JSONObject(),false);
            }
        }
    }

    /** Optional probes on a private local IPv4 only; no secret, cloud, reset, or relay changes.
     * Some older Sonoff respond to getState even without keys, while S60 may only return
     * encrypted data. A successful TCP/HTTP response is not authorization.
     */
    private void readOnlyDiagnosis(S60Device d) {
        if(!LanDiagnostics.privateIpv4(d.ip)) {
            log.add("DIAG.SKIP",d.id+" non-private or invalid IPv4 endpoint; no request sent");return;
        }
        final String address=d.ip;
        final int port=d.port;
        long started=System.currentTimeMillis();
        try(Socket socket=LanDiagnostics.connectTcp(appContext,address,port,1700)) {
            long latency=System.currentTimeMillis()-started;
            d.diagnosticTcp="OPEN ("+latency+"ms)";
            log.add("LAN.TCP.OK",d.id+" endpoint="+address+":"+port+" connected in "+latency+"ms; NOT authenticated");
        }catch(Exception e) {
            d.diagnosticTcp=e.getClass().getSimpleName();
            d.diagnosticHttp="not attempted: TCP unavailable";
            log.add("LAN.TCP.FAIL",d.id+" endpoint="+address+":"+port+" cause="+e.getClass().getSimpleName());
            return;
        }
        log.add("LAN.PROTOCOL.PROBE",d.id+" mode="+d.protocol+" read-only paths=[getState,info] (no key; no relay writes)");
        for(String cmd:new String[]{"getState","info"}) {
            try {
                JSONObject payload=new JSONObject().put("sequence",String.valueOf(sequence.incrementAndGet()))
                    .put("deviceid",d.id).put("selfApikey","123")
                    .put("encrypt",false).put("data",new JSONObject());
                HttpURLConnection conn=LanDiagnostics.deviceConnection(appContext,address,port,cmd);
                conn.setConnectTimeout(1800);conn.setReadTimeout(2100);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod("POST");conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
                conn.setRequestProperty("Connection","close");conn.setDoOutput(true);
                int status;
                String raw;
                try {
                    try(OutputStream output=conn.getOutputStream()) {
                        output.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    status=conn.getResponseCode();
                    InputStream body=status<400?conn.getInputStream():conn.getErrorStream();
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                    try(InputStream input=body) {
                        if(input!=null){byte[] buffer=new byte[1024];int count;
                            while((count=input.read(buffer))!=-1){
                                if(bytes.size()+count>8192)throw new Exception("response-over-8KiB");
                                bytes.write(buffer,0,count);
                            }
                        }
                    }
                    raw=bytes.toString("UTF-8");
                } finally {conn.disconnect();}
                JSONObject response=new JSONObject(raw);
                int error=response.optInt("error",-1);
                JSONObject plain=response.optJSONObject("data");
                boolean encryptedBody=response.optString("data", "").length()>0 && response.has("iv");
                boolean visible=false;
                if(status==200 && error==0 && plain!=null) {
                    synchronized(this){visible=d.observeReadOnly(plain);}
                }
                String outcome="HTTP="+status+" error="+error+" fields="+LanDiagnostics.jsonKeys(response)+
                    " data="+(plain!=null?"object":encryptedBody?"encrypted":"unavailable")+
                    " plaintextTelemetry="+visible;
                d.diagnosticHttp=cmd+" "+outcome;
                d.lastProtocolAttempt="unencrypted read-only /zeroconf/"+cmd;
                d.accessReason=ProtocolRouter.failure(status,error,d.encrypted,false);
                if(d.encrypted && !d.hasKey && (error==401 || error==403)) {
                    // Terminal result of public read-only probes, not endless "waiting".
                    synchronized(this){d.errorCode="ACCESS_KEY_REQUIRED";
                        d.error="Розетка доступна, але відхилила читання без ключа.";
                        d.errorAdvice="Для цієї моделі відкритий LAN не підтверджений. Перегляньте діагностику AP.";}
                }
                log.add("LAN.READONLY.REPLY",d.id+" cmd="+cmd+" "+outcome+" reason="+d.accessReason+"; NOT authorized");
                if(visible) {
                    log.add("LAN.READONLY.TELEMETRY",d.id+" "+LanDiagnostics.telemetrySummary(plain)+
                         "; relay control remains blocked until verified");
                    return;
                }
                // If current state was returned encrypted, retrying other endpoint will not unlock it.
                if(encryptedBody)return;
            }catch(Exception e){
                d.diagnosticHttp=cmd+" "+e.getClass().getSimpleName();
                log.add("LAN.READONLY.FAIL",d.id+" cmd="+cmd+" "+e.getClass().getSimpleName());
            }
        }
        log.add("LAN.READONLY.RESULT",d.id+" no publicly readable state; mode="+d.protocol+" reason="+d.accessReason+"; terminal=no-access-unless-authorized");
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
            JSONObject payload;
            if(ProtocolRouter.DIY.equals(d.protocol)) {
                // Official DIY v1 request format: exactly {deviceid,data}; other fields are optional at best.
                payload=new JSONObject().put("deviceid",d.id).put("data",data);
            } else {
                payload=new JSONObject().put("sequence",String.valueOf(sequence.incrementAndGet()))
                    .put("deviceid",d.id).put("selfApikey","123");
            }
            if(d.encrypted) {
                JSONObject wrapped=LanCrypto.wrap(data,key);
                payload.put("data",wrapped.getString("data"));
                payload.put("iv",wrapped.getString("iv"));
                payload.put("encrypt",true);
            } else if(!ProtocolRouter.DIY.equals(d.protocol)) payload.put("data",data);
            String location="http://"+d.ip+":"+d.port+"/zeroconf/"+command;
            HttpURLConnection connection=LanDiagnostics.deviceConnection(appContext,d.ip,d.port,command);
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
            if(manual || d.lastHttpOk==0) log.add("LAN.HTTP.RESPONSE",d.id+" mode="+d.protocol+" cmd="+command+" http="+status+" responseKeys="+LanDiagnostics.jsonKeys(response));
            d.lastHttpCommand=command;
            d.lastProtocolAttempt=d.protocol+" -> "+command+" HTTP="+status;
            int error=response.optInt("error",-1);
            d.accessReason=ProtocolRouter.failure(status,error,d.encrypted,d.hasKey);
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
                        d.errorAdvice=(error==401 || error==403)?"Пристрій відхилив доступ. Надішліть журнал подій.":"Пристрій відхилив команду. Надішліть журнал подій.";
                        if(command.equals("switches") || command.equals("switch"))d.pending="";
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
                        d.errorCode="INVALID_DEVICE_KEY";d.errorAdvice="Не підтверджено доступ до пристрою. Надішліть журнал подій.";}
                    log.add("LAN.REPLY.DECRYPT.FAIL",d.id+" cmd="+command+" "+e.getClass().getSimpleName());
                }
            } else if(response.optJSONObject("data")!=null) {
                if(d.encrypted) {
                    boolean gotTelemetry;
                    synchronized(this){gotTelemetry=d.observeReadOnly(response.optJSONObject("data"));}
                    log.add("LAN.REPLY.UNVERIFIED",d.id+" cmd="+command+" unexpected plaintext on encrypted endpoint; readOnlyTelemetry="+gotTelemetry+"; NOT authorized");
                } else {
                    // Successful read response proves endpoint behavior, not the retail model.
                    if("info".equals(command)||"getState".equals(command)) {
                        JSONObject visible=response.optJSONObject("data");
                        boolean realState=visible.has("switch") || visible.optJSONArray("switches")!=null;
                        if(realState) {
                            d.plaintextHttpConfirmed=true;
                            acceptState(d,visible,"HTTP plain");
                            d.accessReason="OPEN_LAN_CONFIRMED";
                            log.add("LAN.REPLY.STATE",d.id+" cmd="+command+" unencrypted relay state confirmed mode="+d.protocol);
                        } else {
                            synchronized(this){d.observeReadOnly(visible);}
                            log.add("LAN.REPLY.READ",d.id+" cmd="+command+" no relay state; control remains locked");
                        }
                    } else {
                        synchronized(this){d.observeReadOnly(response.optJSONObject("data"));}
                        log.add("LAN.REPLY.READ",d.id+" cmd="+command+" plaintext supplemental state");
                    }
                }
            } else {
                log.add("LAN.REPLY.OK",d.id+" cmd="+command+" HTTP acknowledged; waiting for confirmed state via mDNS");
            }
            if(manual && (command.equals("switches") || command.equals("switch"))) {
                // Check actual state from the device rather than report success optimistically.
                try {Thread.sleep(350);} catch(InterruptedException ignored){Thread.currentThread().interrupt();}
                request(d,ProtocolRouter.DIY.equals(d.protocol)?"info":"getState",new JSONObject(),false);
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
            String advice="Перевірте, чи телефон і розетка в одній мережі Wi-Fi.";
            if(e instanceof java.net.SocketTimeoutException){
                reason="TIMEOUT";message="Розетка знайдена, але не відповідає.";
                advice="Перевірте доступ у локальній мережі: ізоляцію Wi-Fi-клієнтів, правильність адреси розетки та її живлення.";
            }else if(e instanceof java.net.ConnectException){
                reason="CONNECTION_REFUSED";message="Розетка не приймає підключення.";
                advice="Перевірте Wi-Fi-мережу й доступність розетки.";
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
                if(command.equals("switches") || command.equals("switch"))d.pending="";
            }
            log.add("LAN.REQUEST.FAIL",d.id+" cmd="+command+" endpoint="+d.ip+":"+d.port+" code="+reason+" cause="+e.getClass().getSimpleName()+" detail="+e.getMessage());
        }
    }
}
