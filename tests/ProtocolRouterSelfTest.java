package com.mazko.sonoffs60;
public final class ProtocolRouterSelfTest {
  static void eq(Object x,Object y) {if(!x.equals(y))throw new AssertionError(x+" != "+y);}
  static void yes(boolean b){if(!b)throw new AssertionError("Expected true");}
  static void no(boolean b){if(b)throw new AssertionError("Expected false");}
  public static void main(String[] a){
    no(ProtocolRouter.encrypted("diy_plug",""));
    yes(ProtocolRouter.encrypted("plug",""));
    yes(ProtocolRouter.encrypted("diy_plug","true"));
    eq(ProtocolRouter.DIY,ProtocolRouter.protocol("diy_plug",false));
    eq(ProtocolRouter.ENCRYPTED,ProtocolRouter.protocol("enhanced_plug",true));
    eq("info",ProtocolRouter.initialRead(ProtocolRouter.DIY));
    eq("getState",ProtocolRouter.initialRead(ProtocolRouter.ENCRYPTED));
    yes(ProtocolRouter.trustedPlaintextRelay(ProtocolRouter.DIY,"diy_plug",true,true));
    no(ProtocolRouter.trustedPlaintextRelay(ProtocolRouter.DIY,"diy_plug",true,false));
    no(ProtocolRouter.trustedPlaintextRelay(ProtocolRouter.PLAINTEXT,"strip",true,true));
    no(ProtocolRouter.trustedPlaintextRelay(ProtocolRouter.ENCRYPTED,"plug",true,true));
    eq("KEY_REQUIRED_BY_FIRMWARE",ProtocolRouter.failure(200,401,true,false));
    System.out.println("ProtocolRouterSelfTest PASS");
  }
}
