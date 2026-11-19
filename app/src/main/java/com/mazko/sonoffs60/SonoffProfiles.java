package com.mazko.sonoffs60;


/**
 * Model-neutral Sonoff stock-firmware adapters.
 *
 * Protocol and command selection is based on an authenticated state shape where possible.
 * The mDNS local type is NOT a retail model identity. Rules reference:
 *   AlexxIT/SonoffLAN: core/devices.py (UIID 190), switch.py, ewelink/__init__.py
 *   Urban82/sonofflan: devices/{plug,powerplug,thermoplug}.py
 * No firmware change, cloud access, or keyless bypass is performed.
 */
final class SonoffProfiles {
    private SonoffProfiles() {}
    static boolean isUiid(String uiid, int value) {
        return Integer.toString(value).equals(uiid);
    }
    static String identify(String localType, String uiid, boolean switches) {
        if(isUiid(uiid,190)) return "UIID190: S60TPF / POWR320D / POWCT";
        if(isUiid(uiid,32)) return "UIID32: POWR2 / POWR3 / PSF-X67 family";
        if(isUiid(uiid,181))return "UIID181: TH Elite family";
        if(isUiid(uiid,126))return "UIID126: DUALR3";
        if("enhanced_plug".equalsIgnoreCase(localType))return "enhanced_plug: energy-monitoring family (unverified)";
        if("strip".equalsIgnoreCase(localType))return "strip: multi-outlet (unverified)";
        if("th_plug".equalsIgnoreCase(localType))return "th_plug: temperature family (unverified)";
        if(switches)return "switches[] relay: actual model unverified";
        return "model unverified";
    }
    static boolean useSwitches(boolean hasSwitches, boolean hasSwitch) {
        // AlexxIT XSwitchPOWR3; Urban82 Plug: a state with switches[] uses /switches.
        return hasSwitches;
    }
    static boolean needsOperSide(String uiid) {
        // XSwitchPOWR3 uses operSide=1; not a universal extra field.
        return isUiid(uiid,190);
    }
    static boolean needsOperSide(String uiid,String localType,String productModel,
                                 boolean hasSwitches,boolean hasMeters) {
        if(needsOperSide(uiid))return true;
        // Never override an explicitly known OTHER model's UIID.
        if(uiid!=null && !uiid.isEmpty())return false;
        if(!hasSwitches)return false;
        String model=productModel==null?"":productModel.toUpperCase(java.util.Locale.ROOT);
        if(model.startsWith("S60TPF") || model.startsWith("POWR320D") || model.startsWith("POWCT"))return true;
        // Real Sonoff mDNS frequently does not advertise UIID/model. A single-channel
        // `plug` with switches[] AND all electric readings resembles UIID190.
        // Sending operSide=1 is compatible with POWR3's relaying contract; log heuristic.
        return "plug".equalsIgnoreCase(localType) && hasMeters;
    }
    static boolean thSwitchExtras(String localType,String uiid) {
        // AlexxIT XSwitchTH uses mainSwitch and deviceType on supported TH controllers.
        return isUiid(uiid,181) || "th_plug".equalsIgnoreCase(localType);
    }
    static double metricMultiplier(String field, String localType, String uiid, boolean hasSwitches) {
        if("dayKwh".equals(field)||"monthKwh".equals(field))return 0.01;
        if("power".equals(field)||"voltage".equals(field)||"current".equals(field)) {
            // AlexxIT XSensor100 for UIID190, Urban82 PowerPlug for switches[];
            // older enhanced_plug with scalar switch reports engineering units already.
            if(isUiid(uiid,190) || (hasSwitches && !"strip".equalsIgnoreCase(localType)))return 0.01;
            return 1.0;
        }
        return 1.0;
    }
    static boolean shouldTryLedEcho(String uiid, String localType, boolean hasLed) {
        // Only after valid authenticated state: an idempotent write, not a read-only command.
        return hasLed && (isUiid(uiid,190)||isUiid(uiid,32)||isUiid(uiid,181)||isUiid(uiid,182)
                 || "enhanced_plug".equalsIgnoreCase(localType)||"plug".equalsIgnoreCase(localType));
    }
    static boolean shouldTryStatistics(String uiid) {return isUiid(uiid,126);}
}
