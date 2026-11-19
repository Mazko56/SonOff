package com.mazko.sonoffs60;

/** Pure policy decisions for legacy Sonoff AP discovery and redacted diagnostics. */
final class LegacyApIdentity {
    private LegacyApIdentity(){}
    static boolean isPotentialSonoffAp(String ssid) {
        // Only the legacy factory SSID is used. SONOFF_ is not treated as S31 AP
        // because Matter/OpenEdge models have a DIFFERENT protocol.
        return ssid!=null && ssid.matches("ITEAD-[A-Za-z0-9_-]{4,32}");
    }
    static boolean matchesId(String ssid,String deviceId) {
        if(!isPotentialSonoffAp(ssid)||deviceId==null||!deviceId.matches("[A-Za-z0-9]{10}"))return false;
        String suffix=ssid.substring(6);
        // Some older Sonoff APs use abbreviated suffixes such as ITEAD-10000.
        // Reject a mismatch ONLY when the SSID includes a full ten-character ID.
        return !suffix.matches("[A-Za-z0-9]{10}") || suffix.equalsIgnoreCase(deviceId);
    }
    static String safeSsid(String ssid) {
        if(!isPotentialSonoffAp(ssid))return "invalid/unknown hotspot";
        // SSIDs contain a potential device identifier; log only its suffix.
        return "ITEAD-…"+ssid.substring(Math.max(6,ssid.length()-4));
    }
    static String safeError(String message) {
        if(message==null)return "no details";
        // Only messages OUR code emits may appear. Never include response bodies,
        // network secrets or URL/headers in logs.
        if(message.startsWith("AP_"))return message;
        return "device response or networking error";
    }
}
