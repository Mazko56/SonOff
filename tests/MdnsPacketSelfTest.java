import com.mazko.sonoffs60.MdnsPacket;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
public class MdnsPacketSelfTest {
    static void dns(DataOutputStream out,String s)throws Exception {
        for(String t:s.split("\\.")){byte[] b=t.getBytes(StandardCharsets.UTF_8);out.writeByte(b.length);out.write(b);}
        out.writeByte(0);
    }
    static void rr(DataOutputStream out,String name,int type,byte[] bytes)throws Exception{
        dns(out,name);out.writeShort(type);out.writeShort(1);out.writeInt(30);out.writeShort(bytes.length);out.write(bytes);
    }
    static byte[] payload(String name,int port)throws Exception {
        ByteArrayOutputStream buf=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(buf);
        out.writeShort(0);out.writeShort(0x8400);out.writeShort(0);out.writeShort(2);out.writeShort(0);out.writeShort(0);
        ByteArrayOutputStream txtBytes=new ByteArrayOutputStream();
        for(String k:new String[]{"id=1000abcdef","type=plug","encrypt=true","data1=hello","iv=abcd"}){
            byte[] b=k.getBytes(StandardCharsets.UTF_8);txtBytes.write(b.length);txtBytes.write(b);
        }
        rr(out,name,16,txtBytes.toByteArray());
        ByteArrayOutputStream srvBytes=new ByteArrayOutputStream();DataOutputStream srv=new DataOutputStream(srvBytes);
        srv.writeShort(0);srv.writeShort(0);srv.writeShort(port);dns(srv,"eWeLink_1000abcdef.local");
        rr(out,name,33,srvBytes.toByteArray());return buf.toByteArray();
    }
    public static void main(String[]args)throws Exception{
        String svc="eWeLink_1000abcdef._ewelink._tcp.local";
        byte[] p=payload(svc,8081);
        List<MdnsPacket.Record> records=MdnsPacket.parse(p,p.length);
        if(records.size()!=2 || records.get(0).type!=16 || records.get(1).port!=8081 ||
                !"plug".equals(records.get(0).txt.get("type")))throw new AssertionError("decode failed");
        byte[] query=MdnsPacket.discoveryQuery();
        if(MdnsPacket.parse(query,query.length).size()!=0)throw new AssertionError("query parse failed");
        System.out.println("PASS MdnsPacket: TXT+SRV no-A, active discovery query");
    }
}
