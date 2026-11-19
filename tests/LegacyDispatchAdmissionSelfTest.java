package com.mazko.sonoffs60;
import java.util.concurrent.atomic.AtomicLong;
/** Checks old-firmware dispatch -> websocket fallback with strict one-use, IP and TTL. */
class LegacyDispatchAdmissionSelfTest {
    private static void check(boolean yes,String text) { if(!yes) throw new AssertionError(text); }
    public static void main(String[] args) {
        AtomicLong now=new AtomicLong(1_000_000);
        LegacyDispatchAdmission admission=new LegacyDispatchAdmission(now::get);
        check(!admission.consume("10013bec50","192.168.0.171"),"missing proof accepted");
        admission.authorizedDispatch("10013bec50","192.168.0.171");
        check(!admission.consume("10013bec50","192.168.0.172"),"foreign IP admitted");
        check(admission.consume("10013bec50","192.168.0.171"),"same IP proof rejected");
        check(!admission.consume("10013bec50","192.168.0.171"),"replayed proof admitted");
        admission.authorizedDispatch("10013bec50","192.168.0.171");
        now.addAndGet(90_001);
        check(!admission.consume("10013bec50","192.168.0.171"),"expired proof admitted");
        admission.authorizedDispatch("10013bec50","192.168.0.171");
        admission.clear();
        check(!admission.consume("10013bec50","192.168.0.171"),"proof survived restart");
        admission.authorizedDispatch("invalid", "192.168.0.171");
        check(!admission.consume("invalid", "192.168.0.171"),"invalid id admitted");
        System.out.println("PASS DISPATCH ADMISSION");
    }
}
