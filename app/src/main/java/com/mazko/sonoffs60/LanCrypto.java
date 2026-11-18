package com.mazko.sonoffs60;

import org.json.JSONObject;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Original eWeLink firmware LAN protocol, NOT the legacy Sonoff cloud replacement protocol. */
public final class LanCrypto {
    private LanCrypto() { }
    private static byte[] key(String deviceKey) throws Exception {
        return MessageDigest.getInstance("MD5").digest(deviceKey.getBytes(StandardCharsets.UTF_8));
    }
    public static JSONObject wrap(JSONObject data, String deviceKey) throws Exception {
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(deviceKey), "AES"), new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("encrypt", true)
                .put("data", Base64.getEncoder().encodeToString(encrypted))
                .put("iv", Base64.getEncoder().encodeToString(iv));
    }
    public static JSONObject unwrap(String data, String ivB64, String deviceKey) throws Exception {
        byte[] iv = Base64.getDecoder().decode(ivB64);
        if (iv.length != 16) throw new IllegalArgumentException("IV length must be 16 bytes");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(deviceKey), "AES"), new IvParameterSpec(iv));
        return new JSONObject(new String(cipher.doFinal(Base64.getDecoder().decode(data)), StandardCharsets.UTF_8));
    }
}
