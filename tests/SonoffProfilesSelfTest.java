package com.mazko.sonoffs60;
public final class SonoffProfilesSelfTest {
  static void eq(Object a,Object b){if(!a.equals(b))throw new AssertionError(a+" != "+b);}
  static void yes(boolean a){if(!a)throw new AssertionError("expected true");}
  static void no(boolean a){if(a)throw new AssertionError("expected false");}
  public static void main(String[] args){
    eq("UIID190: S60TPF / POWR320D / POWCT",SonoffProfiles.identify("plug","190",true));
    eq("enhanced_plug: energy-monitoring family (unverified)",SonoffProfiles.identify("enhanced_plug","",false));
    eq(0.01,SonoffProfiles.metricMultiplier("voltage","plug","190",true));
    eq(1.0,SonoffProfiles.metricMultiplier("voltage","enhanced_plug","",false));
    eq(0.01,SonoffProfiles.metricMultiplier("dayKwh","enhanced_plug","",false));
    yes(SonoffProfiles.useSwitches(true,false));
    no(SonoffProfiles.useSwitches(false,true));
    yes(SonoffProfiles.needsOperSide("190"));
    no(SonoffProfiles.needsOperSide("32"));
    yes(SonoffProfiles.needsOperSide("","plug","",true,true));
    no(SonoffProfiles.needsOperSide("","strip","",true,true));
    no(SonoffProfiles.needsOperSide("32","plug","S60TPF",true,true));
    yes(SonoffProfiles.thSwitchExtras("th_plug",""));
    yes(SonoffProfiles.shouldTryLedEcho("190","plug",true));
    no(SonoffProfiles.shouldTryLedEcho("190","plug",false));
    yes(SonoffProfiles.shouldTryStatistics("126"));
    System.out.println("SonoffProfilesSelfTest PASS");
  }
}
