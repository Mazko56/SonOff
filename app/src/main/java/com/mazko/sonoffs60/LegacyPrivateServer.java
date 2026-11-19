package com.mazko.sonoffs60;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.security.auth.x500.X500Principal;

/** Experimental device-initiated legacy Sonoff HTTPS + WebSocket server.
 * Independent from the loopback UI. Works ONLY on a private Wi-Fi interface;
 * only previously AP-onboarded device IDs with a verified local key can join.
 * Factory firmware that pins the vendor TLS cert will reject our self-signed cert.
 */
final class LegacyPrivateServer {
    static final int PORT=1081;
    static final int WEBSOCKET_PORT=8090;
    private static final String ALIAS="SonoffLegacyPrivateServer2026";
    private final Context context;
    private final Controller controller;
    private final LogBook log;
    private final ExecutorService clients=Executors.newFixedThreadPool(6);
    private final ConcurrentHashMap<String,Session> sessions=new ConcurrentHashMap<>();
    private final AtomicBoolean closed=new AtomicBoolean(false);
    private volatile SSLServerSocket listener;
    private volatile SSLServerSocket websocketListener;
    private volatile String bindIp="";
    private volatile Thread acceptor;
    private volatile String tlsFingerprint="";
    private final LegacyDispatchAdmission dispatchAdmission = new LegacyDispatchAdmission();

    private static final class Session {
        final String id,token,initialWireKey;
        final SSLSocket socket;
        final InputStream in;
        final OutputStream out;
        final Object writeLock=new Object();
        volatile boolean open=true;
        Session(String id,SSLSocket socket,InputStream in,OutputStream out,String token,String initialWireKey) {
            this.id=id;this.socket=socket;this.in=in;this.out=out;this.token=token;
            this.initialWireKey=initialWireKey;
        }
        void send(int opcode,byte[] data)throws IOException {
            if(!open)throw new IOException("SESSION_CLOSED");
            synchronized(writeLock) {
                int len=data.length;
                if(len>65535)throw new IOException("FRAME_TOO_LARGE");
                out.write(0x80|opcode);
                if(len<=125)out.write(len);
                else {out.write(126);out.write(len>>8);out.write(len&0xff);}
                out.write(data);out.flush();
            }
        }
        void json(JSONObject json)throws IOException {send(1,json.toString().getBytes(StandardCharsets.UTF_8));}
        void terminate(){open=false;try{socket.close();}catch(Exception ignored){}}
    }

    LegacyPrivateServer(Context context,Controller controller) {
        this.context=context.getApplicationContext();this.controller=controller;this.log=controller.log;
    }
    String ip(){return bindIp;}
    boolean isReady(){return !bindIp.isEmpty()&&listener!=null&&!listener.isClosed()&&
        websocketListener!=null&&!websocketListener.isClosed();}
    boolean isConnected(String deviceId){Session s=sessions.get(deviceId);return s!=null&&s.open;}
    String status(){return isReady()?"HTTPS "+bindIp+":"+PORT+" WSS :"+WEBSOCKET_PORT+
        " clients="+sessions.size():"not listening";}

    /** Fail closed rather than advertise a server on cellular, public or stale addresses. */
    private String activePrivateWifiIp() {
        try {
            ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if(cm==null)return "";
            Network n=cm.getActiveNetwork();
            if(n==null)return "";
            NetworkCapabilities caps=cm.getNetworkCapabilities(n);
            if(caps==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return "";
            LinkProperties link=cm.getLinkProperties(n);
            if(link==null)return "";
            for(LinkAddress a:link.getLinkAddresses()){
                InetAddress ip=a.getAddress();
                if(ip instanceof Inet4Address && LanDiagnostics.privateIpv4(ip.getHostAddress()))return ip.getHostAddress();
            }
        } catch(Exception ignored){}
        return "";
    }
    synchronized void startOrRefresh() {
        if(closed.get())return;
        String ip=activePrivateWifiIp();
        if(ip.isEmpty()){
            if(listener!=null)stopListening();
            log.add("PRIVATE.SERVER.NETWORK","No active private Wi-Fi IPv4; listener disabled");return;
        }
        if(ip.equals(bindIp) && isReady())return;
        stopListening();
        try {
            SSLContext ssl=sslContext();
            SSLServerSocketFactory f=ssl.getServerSocketFactory();
            SSLServerSocket s=(SSLServerSocket)f.createServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(InetAddress.getByName(ip),PORT),12);
            SSLServerSocket wss=(SSLServerSocket)f.createServerSocket();
            try {
                wss.setReuseAddress(true);
                wss.bind(new InetSocketAddress(InetAddress.getByName(ip),WEBSOCKET_PORT),12);
            } catch (Exception ex) {
                s.close();wss.close();throw ex;
            }
            listener=s;websocketListener=wss;bindIp=ip;
            acceptor=new Thread(()->serve(s),"Sonoff-private-dispatch");
            acceptor.setDaemon(true);acceptor.start();
            Thread wsAcceptor=new Thread(()->serve(wss),"Sonoff-private-websocket");
            wsAcceptor.setDaemon(true);wsAcceptor.start();
            log.add("PRIVATE.SERVER.READY","Private Wi-Fi-only HTTPS dispatch "+ip+":"+PORT+
                " and WSS "+ip+":"+WEBSOCKET_PORT+
                " fingerprint.sha256="+tlsFingerprint+"; old firmware only; certificate pinning may reject it");
        }catch(Exception ex){listener=null;bindIp="";log.add("PRIVATE.SERVER.UNAVAILABLE",ex.getClass().getSimpleName()+"; no private server listener; LAN path remains active");}
    }
    private synchronized void stopListening() {
        SSLServerSocket old=listener,oldWs=websocketListener;
        listener=null;websocketListener=null;bindIp="";
        if(old!=null)try{old.close();}catch(Exception ignored){}
        if(oldWs!=null)try{oldWs.close();}catch(Exception ignored){}
        for(Session s:sessions.values())s.terminate();
        sessions.clear();
        dispatchAdmission.clear();
    }
    void stop(){closed.set(true);stopListening();clients.shutdownNow();}

    private SSLContext sslContext()throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(ALIAS)) {
            KeyPairGenerator gen=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore");
            Date start=new Date(System.currentTimeMillis()-3600000);
            Date end=new Date(System.currentTimeMillis()+3650L*86400000L);
            KeyGenParameterSpec spec=new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048).setCertificateSubject(new X500Principal("CN=Sonoff local private server"))
                .setCertificateSerialNumber(java.math.BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(start).setCertificateNotAfter(end)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).build();
            gen.initialize(spec);gen.generateKeyPair();
            store.load(null);
        }
        X509Certificate cert=(X509Certificate)store.getCertificate(ALIAS);
        PrivateKey privateKey=(PrivateKey)store.getKey(ALIAS,null);
        if(cert==null||privateKey==null)throw new IOException("TLS_KEY_UNAVAILABLE");
        byte[] fp=MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        StringBuilder b=new StringBuilder();for(int i=0;i<Math.min(8,fp.length);i++)b.append(String.format("%02x",fp[i]));
        tlsFingerprint=b.toString();
        final PrivateKey key=privateKey;final X509Certificate[] chain={cert};
        X509ExtendedKeyManager km=new X509ExtendedKeyManager(){
            public String[] getClientAliases(String type,java.security.Principal[] issuers){return null;}
            public String chooseClientAlias(String[] types,java.security.Principal[] issuers,Socket s){return null;}
            public String[] getServerAliases(String type,java.security.Principal[] issuers){return "RSA".equalsIgnoreCase(type)?new String[]{ALIAS}:null;}
            public String chooseServerAlias(String type,java.security.Principal[] issuers,Socket s){return "RSA".equalsIgnoreCase(type)?ALIAS:null;}
            public String chooseEngineServerAlias(String type,java.security.Principal[] issuers,javax.net.ssl.SSLEngine engine){return "RSA".equalsIgnoreCase(type)?ALIAS:null;}
            public X509Certificate[] getCertificateChain(String alias){return ALIAS.equals(alias)?chain.clone():null;}
            public PrivateKey getPrivateKey(String alias){return ALIAS.equals(alias)?key:null;}
        };
        SSLContext ctx=SSLContext.getInstance("TLS");ctx.init(new javax.net.ssl.KeyManager[]{km},null,new SecureRandom());
        return ctx;
    }
    private void serve(SSLServerSocket s) {
        while(!s.isClosed()&&!closed.get()){
            try {
                SSLSocket client=(SSLSocket)s.accept();
                if(!LanDiagnostics.privateIpv4(client.getInetAddress().getHostAddress())) {client.close();continue;}
                clients.execute(()->handle(client));
            }catch(Exception ex){if(!s.isClosed())log.add("PRIVATE.SERVER.ACCEPT.ERROR",ex.getClass().getSimpleName());}
        }
    }
    private static String line(InputStream in)throws IOException {
        ByteArrayOutputStream raw=new ByteArrayOutputStream();int c;
        while((c=in.read())!=-1 && c!='\n'){
            if(c!='\r')raw.write(c);
            if(raw.size()>8192)throw new IOException("HEADER_TOO_LONG");
        }
        if(c==-1 && raw.size()==0)throw new EOFException();
        return raw.toString("UTF-8");
    }
    private static byte[] readExactly(InputStream in,int n)throws IOException {
        byte[] data=new byte[n];int pos=0;
        while(pos<n){int r=in.read(data,pos,n-pos);if(r<0)throw new EOFException();pos+=r;}
        return data;
    }
    private static void reply(OutputStream out,int code,JSONObject json)throws IOException {
        byte[] body=json.toString().getBytes(StandardCharsets.UTF_8);
        String status=code==200?"OK":code==400?"Bad Request":code==403?"Forbidden":"Not Found";
        String header="HTTP/1.1 "+code+" "+status+"\r\nContent-Type: application/json\r\nContent-Length: "+body.length+
            "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
    }
    private void handle(SSLSocket client) {
        boolean upgraded=false;
        try {
            client.setSoTimeout(12000);client.startHandshake();
            InputStream in=client.getInputStream();OutputStream out=client.getOutputStream();
            String[] req=line(in).split(" ");
            if(req.length<2)throw new IOException("BAD_REQUEST_LINE");
            String method=req[0],path=req[1].split("\\?",2)[0];
            HashMap<String,String> headers=new HashMap<>();
            for(int i=0;i<48;i++){String l=line(in);if(l.isEmpty())break;int k=l.indexOf(':');if(k>0)headers.put(l.substring(0,k).trim().toLowerCase(java.util.Locale.ROOT),l.substring(k+1).trim());}
            if("GET".equals(method) && "/health".equals(path)){
                reply(out,200,new JSONObject().put("ok",true).put("protocol","legacy-private")
                    .put("activeSessions",sessions.size()));
                return;
            }
            if("POST".equals(method) && LegacyCloudProtocol.PATH_DISPATCH.equals(path)){
                int n=Integer.parseInt(headers.getOrDefault("content-length","-1"));
                if(n<2||n>8192)throw new IOException("BODY_SIZE_INVALID");
                JSONObject body=new JSONObject(new String(readExactly(in,n),StandardCharsets.UTF_8));
                String id=body.optString("deviceid","");
                if(!LegacyCloudProtocol.isDeviceId(id)||!controller.legacyAuthorize(id,body.optString("apikey",""))){
                    log.add("PRIVATE.DISPATCH.REJECTED","Invalid or unknown device key; no enrollment performed");
                    reply(out,403,new JSONObject().put("error",403));return;
                }
                if(!isReady()){reply(out,403,new JSONObject().put("error",503));return;}
                dispatchAdmission.authorizedDispatch(id,client.getInetAddress().getHostAddress());
                reply(out,200,LegacyCloudProtocol.dispatchReply(bindIp,WEBSOCKET_PORT));
                log.add("PRIVATE.DISPATCH.ACCEPTED",id+" connected to trusted dispatch route; TLS handshake passed");
                return;
            }
            if("GET".equals(method)&&LegacyCloudProtocol.isWebSocketPath(path)&&
                "websocket".equalsIgnoreCase(headers.getOrDefault("upgrade",""))) {
                String k=headers.getOrDefault("sec-websocket-key","");
                if(!k.matches("[A-Za-z0-9+/=]{24,32}"))throw new IOException("INVALID_WEBSOCKET_KEY");
                byte[] digest=MessageDigest.getInstance("SHA-1").digest((k+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
                String accept=Base64.getEncoder().encodeToString(digest);
                String upgrade="HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+accept+"\r\n\r\n";
                out.write(upgrade.getBytes(StandardCharsets.US_ASCII));out.flush();
                upgraded=true;
                client.setSoTimeout(180000);
                log.add("PRIVATE.WS.UPGRADE","Legacy /api/ws transport upgraded; awaiting validated device registration");
                websocketLoop(client,in,out);return;
            }
            reply(out,404,new JSONObject().put("error",404));
        }catch(javax.net.ssl.SSLHandshakeException e){
            log.add("PRIVATE.TLS.REJECTED","peer="+client.getInetAddress().getHostAddress()+
                " localPort="+client.getLocalPort()+" TLS handshake failed ("+e.getClass().getSimpleName()+
                "); certificate rejection, unsupported TLS version or network interruption possible; cause NOT determined");
        }catch(Exception e){
            log.add("PRIVATE.SERVER.CLIENT.ERROR","peer="+client.getInetAddress().getHostAddress()+
                " localPort="+client.getLocalPort()+" "+e.getClass().getSimpleName()+
                "; transport="+(upgraded?"WS":"HTTPS")+" (credentials/payload excluded)");
        }finally{try{client.close();}catch(Exception ignored){}}
    }
    private static final class Frame {
        final int opcode;final byte[] data;
        Frame(int opcode,byte[] data){this.opcode=opcode;this.data=data;}
    }
    private static Frame readFrame(InputStream in)throws IOException {
        int first=in.read(),second=in.read();if(first<0||second<0)throw new EOFException();
        if((first&0x70)!=0)throw new IOException("WS_RESERVED_FLAGS");
        int opcode=first&0x0f,len=second&0x7f;
        if(len==127)throw new IOException("WS_FRAME_TOO_LARGE");
        if(len==126){len=(in.read()<<8)|in.read();if(len<0)throw new IOException("WS_BAD_LENGTH");}
        if(len>65535)throw new IOException("WS_PAYLOAD_LIMIT");
        if((second&0x80)==0)throw new IOException("WS_CLIENT_MASK_REQUIRED");
        byte[] mask=readExactly(in,4),payload=readExactly(in,len);
        for(int i=0;i<payload.length;i++)payload[i]^=mask[i%4];
        if((first&0x80)==0)throw new IOException("WS_FRAGMENT_UNSUPPORTED");
        return new Frame(opcode,payload);
    }
    private void websocketLoop(SSLSocket socket,InputStream in,OutputStream out)throws Exception {
        Session session=null;
        try {
            for(int frameNo=0;frameNo<100000;frameNo++) {
                Frame frame=readFrame(in);
                if(frame.opcode==8)break;
                if(frame.opcode==9) {if(session!=null)session.send(10,frame.data);else sendRaw(out,10,frame.data);continue;}
                if(frame.opcode==10)continue;
                if(frame.opcode!=1)throw new IOException("WS_TEXT_ONLY");
                JSONObject data=new JSONObject(new String(frame.data,StandardCharsets.UTF_8));
                String action=data.optString("action","");
                String id=data.optString("deviceid","");
                if(session==null) {
                    if(!"register".equals(action)||!LegacyCloudProtocol.isDeviceId(id)) {
                        log.add("PRIVATE.WS.REGISTER.DENIED","Unknown/invalid device or action; no session opened");
                        break;
                    }
                    String firstWireKey=data.optString("apikey","");
                    boolean factoryKey=controller.legacyAuthorize(id,firstWireKey);
                    // Old firmwares may use a different wire key at registration.
                    // Only a device that previously proved its factory key via
                    // HTTPS dispatch from the SAME IP may use this fallback.
                    boolean dispatchProof=dispatchAdmission.consume(id,socket.getInetAddress().getHostAddress());
                    if(!factoryKey&&!dispatchProof) {
                        log.add("PRIVATE.WS.REGISTER.DENIED","No matching factory key or recent verified HTTPS dispatch");
                        break;
                    }
                    session=new Session(id,socket,in,out,LegacyCloudProtocol.token(),firstWireKey);
                    Session old=sessions.put(id,session);if(old!=null)old.terminate();
                    controller.legacyRegister(id,data.optString("model",""),data.optString("romVersion",""),
                        socket.getInetAddress().getHostAddress());
                    JSONObject registered=LegacyCloudProtocol.registrationReply(id,session.token);
                    if(data.has("sequence"))registered.put("sequence",data.optString("sequence",""));
                    session.json(registered);
                    log.add("PRIVATE.WS.REGISTER.OK",id+" model="+LanDiagnostics.shortText(data.optString("model",""))+
                        "; authenticatedBy="+(factoryKey?"factory-key":"recent-dispatch-proof")+
                        "; session authenticated");
                    continue;
                }
                String wireKey=data.optString("apikey","");
                // The TLS/WebSocket session is already authenticated. On old
                // firmwares the key can be absent from subsequent frames; a
                // nonempty but INCORRECT key is always rejected.
                if(!session.id.equals(id) || (!wireKey.isEmpty() &&
                    !LegacyCloudProtocol.equalSecret(session.token,wireKey) &&
                    !LegacyCloudProtocol.equalSecret(session.initialWireKey,wireKey) &&
                    !controller.legacyAuthorize(session.id,wireKey))){
                    log.add("PRIVATE.WS.SESSION.DENIED",session.id+" invalid session key or device identity");break;
                }
                JSONObject response=new JSONObject().put("error",0).put("deviceid",session.id).put("apikey",session.token);
                if(data.has("sequence"))response.put("sequence",data.optString("sequence",""));
                switch(action) {
                    case "date":response.put("date",new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",java.util.Locale.US){{setTimeZone(java.util.TimeZone.getTimeZone("UTC"));}}.format(new Date()));break;
                    case "update":
                        JSONObject params=data.optJSONObject("params");
                        if(params!=null){controller.legacyState(session.id,params);log.add("PRIVATE.WS.UPDATE",session.id+" "+LanDiagnostics.telemetrySummary(params));}
                        else log.add("PRIVATE.WS.UPDATE.EMPTY",session.id+" no params");
                        break;
                    case "query":
                        response.put("params",controller.legacyQuery(session.id,data.optJSONArray("params")));
                        break;
                    default:
                        if(!action.isEmpty())log.add("PRIVATE.WS.ACTION",session.id+" action="+LanDiagnostics.shortText(action));
                        break;
                }
                session.json(response);
            }
        } finally {
            if(session!=null){
                boolean wasActive=sessions.remove(session.id,session);
                session.terminate();
                if(wasActive)controller.legacyDisconnect(session.id);
                log.add("PRIVATE.WS.DISCONNECTED",session.id+" device session closed; active="+wasActive);
            }
        }
    }
    private static void sendRaw(OutputStream out,int opcode,byte[] payload)throws IOException {
        if(payload.length>125)throw new IOException("BAD_CONTROL_FRAME");
        out.write(0x80|opcode);out.write(payload.length);out.write(payload);out.flush();
    }
    /** Queue command only for a currently authenticated device session. Confirmation comes from actual update frame. */
    boolean relay(String id,boolean on,Integer outlet) {
        Session s=sessions.get(id);
        if(s==null||!s.open)return false;
        try {
            JSONObject command=LegacyCloudProtocol.relayRequest(id,on,outlet).put("apikey",s.token);
            s.json(command);
            log.add("PRIVATE.WS.RELAY.QUEUED",id+" outlet="+(outlet==null?"single":outlet)+" state="+(on?"on":"off")+"; awaiting device update");
            return true;
        }catch(Exception ex){log.add("PRIVATE.WS.RELAY.FAIL",id+" "+ex.getClass().getSimpleName());return false;}
    }
}
