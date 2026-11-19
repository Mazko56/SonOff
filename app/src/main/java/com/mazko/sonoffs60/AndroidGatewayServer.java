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
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.*;
import javax.security.auth.x500.X500Principal;

/** Single TLS-protected gateway for eWeLink LAN devices; NEVER overwrites their cloud/server settings.
 * The HTTPS server terminates on the phone and forwards validated commands to the factory LAN endpoint.
 * It is NOT a replacement for Sonoff's vendor cloud server.
 */
final class AndroidGatewayServer {
    static final int PORT=8765;
    private static final String ALIAS="SonoffGatewayTlsV2";
    private final Context context;
    private final Controller controller;
    private final LogBook log;
    private final SecureKeys keys;
    private final ExecutorService clients=Executors.newFixedThreadPool(24);
    private final AtomicBoolean closed=new AtomicBoolean(false);
    private volatile SSLServerSocket listener;
    private volatile String bindIp="";
    private volatile String tlsFingerprint="";
    private final String bearer;
    private final Semaphore connectionLimit=new Semaphore(32);
    private final Set<SSLSocket> activeClients=ConcurrentHashMap.newKeySet();
    AndroidGatewayServer(Context context,Controller controller){
        this.context=context.getApplicationContext();this.controller=controller;this.log=controller.log;
        keys=new SecureKeys(this.context);
        String b=keys.get("_gatewayBearer_v2");
        if(b.length()<32){byte[] v=new byte[32];new SecureRandom().nextBytes(v);
            b=Base64.getUrlEncoder().withoutPadding().encodeToString(v);
            try{keys.put("_gatewayBearer_v2",b);}catch(Exception e){log.add("GATEWAY.KEY.FAIL","Unable to persist bearer token");b="";}
        }
        bearer=b;
    }
    String tokenForOwner(){return bearer;}
    String status(){return ready()?"HTTPS "+bindIp+":"+PORT+" sha256="+tlsFingerprint:"not listening";}
    boolean ready(){return listener!=null&&!listener.isClosed()&&!bindIp.isEmpty()&&!bearer.isEmpty();}
    synchronized void startOrRefresh(){
        if(closed.get())return;
        String ip=activePrivateWifiIp();
        if(ip.isEmpty()){stopListening();return;}
        if(ip.equals(bindIp)&&ready())return;
        stopListening();
        if(bearer.isEmpty()){log.add("GATEWAY.UNAVAILABLE","Owner token inaccessible");return;}
        try{
            SSLContext ssl=sslContext();
            SSLServerSocket s=(SSLServerSocket)ssl.getServerSocketFactory().createServerSocket();
            s.setReuseAddress(true);s.bind(new InetSocketAddress(InetAddress.getByName(ip),PORT),32);
            listener=s;bindIp=ip;
            Thread acceptor=new Thread(()->accept(s),"Sonoff-gateway-https");acceptor.setDaemon(true);acceptor.start();
            log.add("GATEWAY.READY","HTTPS "+ip+":"+PORT+" certificate.sha256="+tlsFingerprint+"; local LAN gateway active");
        } catch(Exception e){stopListening();log.add("GATEWAY.UNAVAILABLE",e.getClass().getSimpleName());}
    }
    private synchronized void stopListening(){SSLServerSocket s=listener;listener=null;bindIp="";
        if(s!=null)try{s.close();}catch(Exception ignored){}
        for(SSLSocket client:activeClients)try{client.close();}catch(Exception ignored){}
        activeClients.clear();
    }
    void stop(){closed.set(true);stopListening();clients.shutdownNow();}
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
    private SSLContext sslContext()throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(ALIAS)) {
            KeyPairGenerator gen=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore");
            Date start=new Date(System.currentTimeMillis()-3600000);
            Date end=new Date(System.currentTimeMillis()+3650L*86400000L);
            KeyGenParameterSpec spec=new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048).setCertificateSubject(new X500Principal("CN=Sonoff Android LAN gateway"))
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
        StringBuilder b=new StringBuilder();for(int i=0;i<fp.length;i++)b.append(String.format("%02x",fp[i]));
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
    private void accept(SSLServerSocket server){
        while(!server.isClosed()&&!closed.get()){
            try{
                SSLSocket s=(SSLSocket)server.accept();
                if(!connectionLimit.tryAcquire()){s.close();continue;}
                activeClients.add(s);
                try{clients.execute(()->{try{handle(s);}finally{activeClients.remove(s);connectionLimit.release();}});}
                catch(RejectedExecutionException e){activeClients.remove(s);connectionLimit.release();s.close();}
            } catch(Exception e){if(!server.isClosed())log.add("GATEWAY.ACCEPT.ERROR",e.getClass().getSimpleName());}
        }
    }
    private static String line(InputStream in)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();int ch;
        while((ch=in.read())!=-1&&ch!='\n'){
            if(ch!='\r')out.write(ch);
            if(out.size()>8192)throw new IOException("HEADER_LINE_TOO_LONG");
        }
        if(ch==-1&&out.size()==0)throw new EOFException();
        return out.toString("UTF-8");
    }
    private static byte[] exact(InputStream in,int n)throws IOException {
        byte[] b=new byte[n];int pos=0;
        while(pos<n){int read=in.read(b,pos,n-pos);if(read<0)throw new EOFException();pos+=read;}
        return b;
    }
    private static void reply(OutputStream out,int code,JSONObject body)throws IOException{
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
        String status=code==200?"OK":code==202?"Accepted":code==400?"Bad Request":code==401?"Unauthorized":code==403?"Forbidden":code==503?"Service Unavailable":"Not Found";
        String headers="HTTP/1.1 "+code+" "+status+"\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
    }
    private boolean authorized(String supplied){return !bearer.isEmpty()&&MessageDigest.isEqual(
        ("Bearer "+bearer).getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8));}
    private void handle(SSLSocket socket){
        boolean upgraded=false;
        try{
            if(!LanDiagnostics.privateIpv4(socket.getInetAddress().getHostAddress()))return;
            socket.setSoTimeout(7000);socket.startHandshake();
            InputStream in=socket.getInputStream();OutputStream out=socket.getOutputStream();
            String[] req=line(in).split(" ");if(req.length<2)throw new IOException("REQUEST_LINE");
            String method=req[0],rawPath=req[1],path=rawPath.split("\\?",2)[0];
            Map<String,String> headers=new HashMap<>();
            for(int i=0;i<48;i++){String row=line(in);if(row.isEmpty())break;
                int col=row.indexOf(':');if(col>0)headers.put(row.substring(0,col).trim().toLowerCase(Locale.ROOT),row.substring(col+1).trim());}
            if("GET".equals(method)&&"/v1/health".equals(path)){
                reply(out,200,new JSONObject().put("ok",true).put("service","sonoff-lan-gateway").put("port",PORT));return;
            }
            boolean withBearer=authorized(headers.getOrDefault("authorization",""));
            // Browser WebSockets cannot set Authorization headers. Permit the random bearer
            // in WSS query string only, never in HTTP control routes or diagnostic logs.
            boolean wsQueryToken=false;
            if("/v1/ws".equals(path)&&rawPath.contains("?")){
                try {String query=rawPath.substring(rawPath.indexOf('?')+1);
                    for(String param:query.split("&"))if(param.startsWith("token=")){
                        String offered=java.net.URLDecoder.decode(param.substring(6),"UTF-8");
                        wsQueryToken=authorized("Bearer "+offered);break;
                    }}catch(Exception ignored){}
            }
            if(!withBearer&&!wsQueryToken){reply(out,401,new JSONObject().put("error","unauthorized"));return;}
            if("GET".equals(method)&&"/v1/devices".equals(path)){
                reply(out,200,controller.state());return;
            }
            if("POST".equals(method)&&"/v1/command".equals(path)){
                if(!"application/json".equals(headers.getOrDefault("content-type","").split(";",2)[0].trim())){
                    reply(out,400,new JSONObject().put("error","JSON_ONLY"));return;
                }
                int size=Integer.parseInt(headers.getOrDefault("content-length","-1"));
                if(size<2||size>4096)throw new IOException("BODY_SIZE_INVALID");
                JSONObject json=new JSONObject(new String(exact(in,size),StandardCharsets.UTF_8));
                if(!json.has("on") || !(json.opt("on") instanceof Boolean))throw new IOException("ON_MUST_BE_BOOLEAN");
                controller.relayOutlet(json.optString("deviceid",""),json.optBoolean("on"),json.optInt("outlet",0));
                reply(out,202,new JSONObject().put("queued",true).put("confirmed",false));return;
            }
            if("GET".equals(method)&&"/v1/ws".equals(path)&&"websocket".equalsIgnoreCase(headers.getOrDefault("upgrade",""))){
                String key=headers.getOrDefault("sec-websocket-key","");
                byte[] decoded;
                try{decoded=Base64.getDecoder().decode(key);}catch(Exception e){throw new IOException("WS_KEY_INVALID");}
                if(decoded.length!=16 || !"13".equals(headers.getOrDefault("sec-websocket-version","")))throw new IOException("WS_KEY_INVALID");
                byte[] digest=MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
                String response="HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+Base64.getEncoder().encodeToString(digest)+"\r\n\r\n";
                out.write(response.getBytes(StandardCharsets.US_ASCII));out.flush();upgraded=true;
                websocketLoop(socket,in,out);return;
            }
            reply(out,404,new JSONObject().put("error","not_found"));
        }catch(Exception e){
            log.add("GATEWAY.CLIENT.ERROR",e.getClass().getSimpleName()+" transport="+(upgraded?"WSS":"HTTPS")+"; no secrets logged");
        }finally{try{socket.close();}catch(Exception ignored){}}
    }
    private static void sendFrame(OutputStream out,int opcode,byte[] data)throws IOException{
        if(data.length>65535)throw new IOException("WS_FRAME_TOO_LARGE");
        out.write(0x80|opcode);
        if(data.length<=125)out.write(data.length);else{out.write(126);out.write((data.length>>8)&255);out.write(data.length&255);}
        out.write(data);out.flush();
    }
    private static final class Frame {int opcode;byte[] data;Frame(int o,byte[] d){opcode=o;data=d;}}
    private static Frame readFrame(InputStream in)throws IOException{
        int a=in.read(),b=in.read();if(a<0||b<0)throw new EOFException();
        if((a&0xf0)!=0x80)throw new IOException("FRAGMENT_NOT_SUPPORTED");
        int size=b&127;if(size==127)throw new IOException("FRAME_TOO_LARGE");
        if(size==126){int hi=in.read(),lo=in.read();if(hi<0||lo<0)throw new EOFException();size=(hi<<8)|lo;}
        if(size>65535||(b&128)==0)throw new IOException("CLIENT_MASK_REQUIRED");
        byte[] mask=exact(in,4),payload=exact(in,size);for(int i=0;i<size;i++)payload[i]^=mask[i%4];
        return new Frame(a&15,payload);
    }
    private void websocketLoop(SSLSocket s,InputStream in,OutputStream out)throws Exception {
        // Persistent TLS WebSocket with snapshots, no device provisioning/takeover.
        s.setSoTimeout(2500);
        String previous="";long lastSend=0;
        for(int i=0;i<100000&&!closed.get();i++){
            String current=controller.state().toString();
            long now=System.currentTimeMillis();
            if(!current.equals(previous)||now-lastSend>20000){sendFrame(out,1,current.getBytes(StandardCharsets.UTF_8));previous=current;lastSend=now;}
            try{
                Frame f=readFrame(in);
                if(f.opcode==8)break;
                if(f.opcode==9){sendFrame(out,10,f.data);continue;}
                if(f.opcode!=1)continue;
                JSONObject cmd=new JSONObject(new String(f.data,StandardCharsets.UTF_8));
                if("relay".equals(cmd.optString("action","")) && cmd.opt("on") instanceof Boolean){
                    controller.relayOutlet(cmd.optString("deviceid",""),cmd.optBoolean("on"),cmd.optInt("outlet",0));
                    sendFrame(out,1,new JSONObject().put("queued",true).put("confirmed",false).toString().getBytes(StandardCharsets.UTF_8));
                }
            }catch(SocketTimeoutException ignored){}
        }
    }
}
