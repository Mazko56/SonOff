package com.mazko.sonoffs60;
import java.net.Inet4Address;
import java.net.InetAddress;
/** Narrow AP network guard: normal LAN discovery must never probe 10.10.7.1. */
final class DeviceApNetwork {
    private DeviceApNetwork() {}
    static boolean matches(InetAddress addr, int prefix) {
        if(!(addr instanceof Inet4Address) || prefix<24)return false;
        byte[] p=addr.getAddress();
        return (p[0]&255)==10 && (p[1]&255)==10 && (p[2]&255)==7 &&
               (p[3]&255)>1 && (p[3]&255)<255;
    }
}
