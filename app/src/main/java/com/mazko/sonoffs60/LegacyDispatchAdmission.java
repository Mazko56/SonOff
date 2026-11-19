package com.mazko.sonoffs60;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Single-use, short-lived proof that a device already authenticated over HTTPS
 * to the private /dispatch/device endpoint.  Some old factory clients put a
 * distinct or empty API key in the subsequent WebSocket register frame.
 * This is NOT an alternative to TLS or the factory secret at dispatch time.
 */
final class LegacyDispatchAdmission {
    private static final long VALID_MS = 90_000L;
    private static final int MAX_PENDING = 128;
    private final LongSupplier clock;
    private final HashMap<String, Proof> pending = new HashMap<>();

    private static final class Proof {
        final String remoteIp;
        final long createdMs;
        Proof(String remoteIp, long createdMs) { this.remoteIp=remoteIp; this.createdMs=createdMs; }
    }

    LegacyDispatchAdmission() { this(System::currentTimeMillis); }
    LegacyDispatchAdmission(LongSupplier clock) { this.clock=clock; }

    private void expire(long now) {
        Iterator<Map.Entry<String,Proof>> iterator=pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Proof proof=iterator.next().getValue();
            if (now<proof.createdMs || now-proof.createdMs>VALID_MS) iterator.remove();
        }
    }

    synchronized void authorizedDispatch(String deviceId, String remoteIp) {
        if (!LegacyCloudProtocol.isDeviceId(deviceId) || remoteIp==null || remoteIp.isEmpty()) return;
        long now=clock.getAsLong();
        expire(now);
        if (pending.size()>=MAX_PENDING && !pending.containsKey(deviceId)) {
            // Bound memory use even if many distinct legitimate devices reconnect.
            pending.clear();
        }
        pending.put(deviceId,new Proof(remoteIp,now));
    }

    synchronized boolean consume(String deviceId, String remoteIp) {
        long now=clock.getAsLong();
        expire(now);
        Proof proof=pending.get(deviceId);
        if (proof==null || remoteIp==null || !proof.remoteIp.equals(remoteIp)) return false;
        pending.remove(deviceId); // strict one-use admission
        return true;
    }

    synchronized void clear() { pending.clear(); }
}
