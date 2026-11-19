package com.mazko.sonoffs60;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Conservative, documented local-protocol routing. Classification is not retail model identity. */
final class ProtocolRouter {
    private ProtocolRouter() {}
    static final String DIY="DIY_V1";
    static final String PLAINTEXT="LAN_PLAINTEXT";
    static final String ENCRYPTED="EWELINK_AES";

    static boolean encrypted(String type, String advertised) {
        String flag=advertised==null?"":advertised.toLowerCase(Locale.ROOT);
        if("true".equals(flag)||"1".equals(flag))return true;
        if("false".equals(flag)||"0".equals(flag))return false;
        // Official DIY devices often omit encrypt= entirely and use type=diy_plug.
        return !"diy_plug".equalsIgnoreCase(type);
    }
    static String protocol(String type, boolean encrypted) {
        if(encrypted)return ENCRYPTED;
        if("diy_plug".equalsIgnoreCase(type))return DIY;
        return PLAINTEXT;
    }
    static List<String> reads(String protocol) {
        if(DIY.equals(protocol))return Arrays.asList("info","signal_strength");
        return Arrays.asList("getState","info");
    }
    static String initialRead(String protocol) {return DIY.equals(protocol)?"info":"getState";}
    static boolean trustedPlaintextRelay(String protocol, String localType, boolean hasState, boolean confirmedHttp) {
        if(!hasState||!confirmedHttp)return false;
        // Only advertise controls for a confirmed, locally reachable, plaintext plug API.
        return (DIY.equals(protocol) && "diy_plug".equalsIgnoreCase(localType)) ||
                (PLAINTEXT.equals(protocol) && ("plug".equalsIgnoreCase(localType)||"enhanced_plug".equalsIgnoreCase(localType)));
    }
    static String likelyModels(String type, String uiid, String model, String fw) {
        if(model!=null&&!model.isEmpty())return "advertised="+model;
        if("190".equals(uiid))return "UIID 190: S60TPF / POWR320D / POWCT (потрібна перевірка)";
        if("diy_plug".equalsIgnoreCase(type))return "DIY-сумісний перемикач (конкретна модель не встановлена)";
        if("enhanced_plug".equalsIgnoreCase(type))return "POWR2/PSF-X67/інші з вимірюванням (не точна модель)";
        if("th_plug".equalsIgnoreCase(type))return "TH-серія (не точна модель)";
        if("strip".equalsIgnoreCase(type))return "Багатоканальний перемикач (не точна модель)";
        if("plug".equalsIgnoreCase(type))return "S60TPF та інші Wi-Fi-реле (не точна модель)";
        return "модель невідома; потрібні advertised model/UIID";
    }
    static String failure(int http, int error, boolean encrypted, boolean keyAvailable) {
        if(error==401||error==403)return keyAvailable?"AUTH_REJECTED_OR_BAD_FORMAT":"KEY_REQUIRED_BY_FIRMWARE";
        if(error==404)return "ENDPOINT_OR_DEVICEID_UNSUPPORTED";
        if(error==422)return "COMMAND_NOT_SUPPORTED";
        if(http>=400)return "HTTP_ERROR";
        if(error==0)return "OK";
        return "UNKNOWN_DEVICE_ERROR";
    }
}
