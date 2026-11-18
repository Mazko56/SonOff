package com.mazko.sonoffs60;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Minimal bounded DNS-SD parser. Handles compression, PTR/SRV/TXT/A record types. */
public final class MdnsPacket {
    static final class Name { String value; int next; Name(String value, int next) {this.value=value;this.next=next;} }
    public static final class Record {
        public final String name;
        public final int type;
        public final int port;
        public final String host;
        public final String address;
        public final String ptr;
        public final Map<String,String> txt;
        Record(String name,int type,int port,String host,String address,String ptr,Map<String,String> txt) {
            this.name=name; this.type=type; this.port=port; this.host=host;
            this.address=address; this.ptr=ptr; this.txt=txt;
        }
    }
    private static int u8(byte[] b,int i) {return b[i]&255;}
    private static int u16(byte[] b,int i) {return (u8(b,i)<<8)|u8(b,i+1);}
    private static long u32(byte[] b,int i) {return ((long)u16(b,i)<<16)|u16(b,i+2);}
    private static Name name(byte[] b,int at) throws Exception { return name(b,at,0); }
    private static Name name(byte[] b,int at,int depth) throws Exception {
        if(depth>18 || at<0 || at>=b.length) throw new Exception("Bad DNS name pointer");
        StringBuilder s=new StringBuilder();
        int pos=at, next=-1, labels=0;
        while(true) {
            if(pos>=b.length || ++labels>70) throw new Exception("Bad DNS labels");
            int len=u8(b,pos++);
            if((len&0xC0)==0xC0) {
                if(pos>=b.length) throw new Exception("Truncated DNS pointer");
                int ptr=((len&0x3F)<<8)|u8(b,pos++);
                if(next<0)next=pos;
                Name pointed=name(b,ptr,depth+1);
                if(!pointed.value.isEmpty()) {
                    if(s.length()>0)s.append('.');
                    s.append(pointed.value);
                }
                break;
            }
            if((len&0xC0)!=0 || len>63) throw new Exception("Unsupported DNS label");
            if(len==0) {if(next<0)next=pos;break;}
            if(pos+len>b.length) throw new Exception("Truncated DNS label");
            if(s.length()>0)s.append('.');
            s.append(new String(b,pos,len,StandardCharsets.UTF_8));
            pos+=len;
        }
        return new Name(s.toString(),next);
    }
    public static List<Record> parse(byte[] b,int len) throws Exception {
        if(len<12 || len>b.length)throw new Exception("Invalid DNS packet");
        int question=u16(b,4), answer=u16(b,6)+u16(b,8)+u16(b,10), pos=12;
        if(question>100 || answer>300) throw new Exception("Excessive DNS records");
        for(int i=0;i<question;i++) {
            Name n=name(b,pos);pos=n.next+4;if(pos>len)throw new Exception("Truncated query");
        }
        List<Record> result=new ArrayList<>();
        for(int i=0;i<answer;i++) {
            Name n=name(b,pos);pos=n.next;
            if(pos+10>len)throw new Exception("Truncated RR");
            int type=u16(b,pos), rdlen=u16(b,pos+8), offset=pos+10, end=offset+rdlen;
            if(end>len)throw new Exception("Invalid rdata length");
            String ptr="", host="", addr="";int port=0;
            Map<String,String> attributes=new LinkedHashMap<>();
            if(type==12) {ptr=name(b,offset).value;}
            if(type==33 && rdlen>=6) {port=u16(b,offset+4); host=name(b,offset+6).value;}
            if(type==1 && rdlen==4) {addr=u8(b,offset)+"."+u8(b,offset+1)+"."+u8(b,offset+2)+"."+u8(b,offset+3);}
            if(type==16) {
                int p=offset;
                while(p<end) {
                    int itemlen=u8(b,p++);
                    if(p+itemlen>end) throw new Exception("Truncated TXT");
                    String item=new String(b,p,itemlen,StandardCharsets.UTF_8);p+=itemlen;
                    int eq=item.indexOf('=');
                    if(eq>0) attributes.put(item.substring(0,eq).toLowerCase(Locale.ROOT),item.substring(eq+1));
                }
            }
            result.add(new Record(n.value,type,port,host,addr,ptr,attributes));
            pos=end;
        }
        return result;
    }
    public static byte[] discoveryQuery() throws Exception {
        ByteArrayOutputStream arr=new ByteArrayOutputStream();
        DataOutputStream data=new DataOutputStream(arr);
        data.writeShort(0);data.writeShort(0);data.writeShort(1);
        data.writeShort(0);data.writeShort(0);data.writeShort(0);
        for(String part: "_ewelink._tcp.local".split("\\.")) {
            byte[] bytes=part.getBytes(StandardCharsets.US_ASCII);
            data.writeByte(bytes.length);data.write(bytes);
        }
        data.writeByte(0);data.writeShort(12);data.writeShort(1);
        return arr.toByteArray();
    }
}
