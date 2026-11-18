package com.mazko.sonoffs60;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Local Android Keystore encryption; no key is persisted as plaintext. */
final class SecureKeys {
    private final SharedPreferences pref;
    private final String alias = "SonoffS60LabLocalKeys";
    SecureKeys(Context context) {
        pref = context.getSharedPreferences("s60_keys", Context.MODE_PRIVATE);
    }
    private SecretKey master() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(alias)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build());
            kg.generateKey();
        }
        return (SecretKey) ks.getKey(alias, null);
    }
    synchronized void put(String id, String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, master(), new SecureRandom());
        byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] merged = new byte[iv.length + encrypted.length];
        System.arraycopy(iv,0,merged,0,iv.length);
        System.arraycopy(encrypted,0,merged,iv.length,encrypted.length);
        pref.edit().putString("key_" + id, Base64.getEncoder().encodeToString(merged)).apply();
    }
    synchronized String get(String id) {
        String stored = pref.getString("key_" + id, "");
        if (stored.isEmpty()) return "";
        try {
            byte[] merged = Base64.getDecoder().decode(stored);
            if (merged.length < 29) return "";
            byte[] iv = new byte[12], enc = new byte[merged.length - 12];
            System.arraycopy(merged,0,iv,0,12);
            System.arraycopy(merged,12,enc,0,enc.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, master(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(enc), StandardCharsets.UTF_8);
        } catch (Exception ex) { return ""; }
    }
}
