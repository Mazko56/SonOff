package com.mazko.sonoffs60;
import java.net.InetAddress;
public final class ApNetworkSelfTest {
    public static void main(String[] args) throws Exception {
        if(!DeviceApNetwork.matches(InetAddress.getByName("10.10.7.2"),24))throw new AssertionError("device AP should match");
        if(!DeviceApNetwork.matches(InetAddress.getByName("10.10.7.122"),24))throw new AssertionError("device AP should match");
        if(DeviceApNetwork.matches(InetAddress.getByName("192.168.0.105"),24))throw new AssertionError("home network incorrectly matches");
        if(DeviceApNetwork.matches(InetAddress.getByName("10.10.7.1"),24))throw new AssertionError("gateway should not match");
        if(DeviceApNetwork.matches(InetAddress.getByName("10.10.7.2"),16))throw new AssertionError("wider subnet should not match");
        if(DeviceApNetwork.matches(InetAddress.getByName("fe80::1"),64))throw new AssertionError("IPv6 should not match");
        System.out.println("Device AP network checks: PASS");
    }
}
