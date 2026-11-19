package com.mazko.sonoffs60;

import android.content.Context;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Embedded loopback control-plane server, not a substitute for a device-side TCP listener. */
final class LocalHttpServer {
    private final Context context;
    private final Controller controller;
    private final LogBook log;
    private final String authToken;
    private ServerSocket listener;
    private Thread worker;
    private final ExecutorService clients=Executors.newFixedThreadPool(4);
    LocalHttpServer(Context context,Controller controller,String authToken) {this.context=context;this.controller=controller;this.log=controller.log;this.authToken=authToken;}
    void start() throws Exception {
        listener=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        worker=new Thread(()-> {
            while(!listener.isClosed()) {
                try {Socket s=listener.accept();clients.execute(()->handle(s));}
                catch(Exception e){if(!listener.isClosed())log.add("PANEL.SERVER",e.getClass().getSimpleName());}
            }
        },"S60-panel-server");worker.setDaemon(true);worker.start();
        log.add("PANEL.SERVER","Secure loopback UI bound to 127.0.0.1:"+listener.getLocalPort());
    }
    int getPort(){return listener.getLocalPort();}
    void stop() {
        try{if(listener!=null)listener.close();}catch(Exception ignored){}
        clients.shutdownNow();
    }
    private String line(InputStream in)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();int c;
        while((c=in.read())!=-1 && c!='\n') {
            if(c!='\r')out.write(c);
            if(out.size()>8192)throw new Exception("Request header line too long");
        }
        return out.toString("UTF-8");
    }
    private byte[] asset() throws Exception {
        try(InputStream in=context.getAssets().open("index.html")) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;
            while((n=in.read(buf))!=-1)out.write(buf,0,n);
            return out.toByteArray();
        }
    }
    private void respond(OutputStream out,int status,String type,byte[] data)throws Exception {
        String message=status==200?"OK":status==202?"Accepted":status==400?"Bad Request":status==403?"Forbidden":status==404?"Not Found":"Error";
        String headers="HTTP/1.1 "+status+" "+message+"\r\nContent-Type: "+type+"\r\nContent-Length: "+data.length+
                "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nContent-Security-Policy: default-src 'self' 'unsafe-inline'; connect-src 'self'; frame-src 'none'\r\nConnection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));out.write(data);out.flush();
    }
    private void json(OutputStream out,int status,JSONObject obj)throws Exception {
        respond(out,status,"application/json; charset=utf-8",obj.toString().getBytes(StandardCharsets.UTF_8));
    }
    private void handle(Socket s) {
        Socket socket=s;
        try {
            socket.setSoTimeout(5000);
            InputStream in=socket.getInputStream();OutputStream out=socket.getOutputStream();
            String request=line(in);String[] parts=request.split(" ");
            if(parts.length<2){json(out,400,new JSONObject().put("error","Invalid HTTP request"));return;}
            String method=parts[0], path=parts[1].split("\\?")[0];
            Map<String,String> headers=new HashMap<>();
            for(int n=0;n<60;n++) {
                String h=line(in);if(h.isEmpty())break;
                int i=h.indexOf(':');if(i>0)headers.put(h.substring(0,i).trim().toLowerCase(Locale.ROOT),h.substring(i+1).trim());
            }
            if(method.equals("GET") && path.equals("/")) {
                respond(out,200,"text/html; charset=utf-8",asset());return;
            }
            // Token is exposed to the trusted Android WebView only, never served in HTML.
            if(!java.security.MessageDigest.isEqual(authToken.getBytes(StandardCharsets.US_ASCII),
                    headers.getOrDefault("x-sonoff-token", "").getBytes(StandardCharsets.US_ASCII))) {
                json(out,403,new JSONObject().put("error","Unauthorized loopback request"));return;
            }
            if(method.equals("GET") && path.equals("/api/state")) {json(out,200,controller.state());return;}
            if(method.equals("GET") && path.equals("/api/logs")) {
                json(out,200,new JSONObject().put("lines",log.asJson()));return;
            }
            if(method.equals("GET") && path.equals("/api/oauth/status")) {
                json(out,200,controller.oauth.info());return;
            }
            if(method.equals("GET") && path.equals("/api/gateway")) {
                json(out,200,new JSONObject().put("status",controller.gateway.status()).put("port",AndroidGatewayServer.PORT));return;
            }
            if(!method.equals("POST") || !path.startsWith("/api/")) {
                json(out,404,new JSONObject().put("error","Not found"));return;
            }
            // Non-simple custom header prevents browser-based CSRF from another origin.
            if(!"1".equals(headers.get("x-sonoff-panel"))) {
                json(out,403,new JSONObject().put("error","Missing local-only request marker"));return;
            }
            int length=Integer.parseInt(headers.getOrDefault("content-length","0"));
            if(length<0 || length>16384){json(out,400,new JSONObject().put("error","Invalid payload size"));return;}
            byte[] raw=new byte[length];int count=0,n;
            while(count<length && (n=in.read(raw,count,length-count))>0)count+=n;
            if(count!=length)throw new Exception("Truncated request body");
            JSONObject payload=length==0?new JSONObject():new JSONObject(new String(raw,StandardCharsets.UTF_8));
            switch(path) {
                case "/api/scan":controller.rescan();json(out,202,new JSONObject().put("ok",true));break;
                case "/api/oauth/start":
                    String login=controller.oauth.start(payload.optString("appId"),payload.optString("appSecret"),payload.optString("redirectUrl"));
                    json(out,200,new JSONObject().put("loginUrl",login));break;
                case "/api/oauth/exchange":
                    controller.oauth.acceptCallback(payload.optString("callbackUrl"),payload.optString("region","eu"));
                    json(out,202,new JSONObject().put("started",true));break;
                case "/api/confirm":controller.confirmModel(payload.optString("id"));json(out,200,new JSONObject().put("ok",true));break;
                case "/api/relay":controller.relayOutlet(payload.optString("id"),payload.optBoolean("on"),payload.optInt("outlet",0));json(out,202,new JSONObject().put("queued",true));break;
                case "/api/poll":controller.poll(payload.optString("id"));json(out,202,new JSONObject().put("queued",true));break;
                case "/api/led":controller.led(payload.optString("id"),payload.optBoolean("on"));json(out,202,new JSONObject().put("queued",true));break;
                case "/api/startup":controller.startup(payload.optString("id"),payload.optString("mode"));json(out,202,new JSONObject().put("queued",true));break;
                default:json(out,404,new JSONObject().put("error","No API route"));
            }
        } catch(Exception e) {
            try {
                if(!socket.isClosed())json(socket.getOutputStream(),400,new JSONObject().put("error",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));
            }catch(Exception ignored){}
            log.add("PANEL.REQUEST.ERROR",e.getClass().getSimpleName()+" "+e.getMessage());
        } finally {try{socket.close();}catch(Exception ignored){}}
    }
}
