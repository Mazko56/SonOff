package com.mazko.sonoffs60;
public final class LegacyApIdentitySelfTest {
 private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] args) {
  check(LegacyApIdentity.isPotentialSonoffAp("ITEAD-10000"),"legacy shortened ID");
  check(LegacyApIdentity.isPotentialSonoffAp("ITEAD-10013bec50"),"full ID");
  check(!LegacyApIdentity.isPotentialSonoffAp("SONOFF_S60TPF-M_1234"),"Matter AP must be excluded");
  check(!LegacyApIdentity.isPotentialSonoffAp("MyHomeWifi"),"arbitrary network must be excluded");
  check(LegacyApIdentity.matchesId("ITEAD-10013bec50","10013bec50"),"full match");
  check(!LegacyApIdentity.matchesId("ITEAD-10013bec50","1002864cfe"),"mismatched ID rejected");
  check(LegacyApIdentity.matchesId("ITEAD-10000","1002864cfe"),"short SSID cannot validate full ID");
  check(!LegacyApIdentity.safeSsid("ITEAD-10013bec50").contains("10013bec50"),"do not leak full AP ID");
  System.out.println("Legacy AP identity PASS");
 }
}
