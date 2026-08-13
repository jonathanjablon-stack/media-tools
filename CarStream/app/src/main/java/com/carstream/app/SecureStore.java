package com.carstream.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureStore {
    private static final String STORE = "carstream_secure";
    private static final String KEY_ALIAS = "carstream_api_key";
    private static final String VALUE = "torbox_key";
    private final SharedPreferences preferences;

    public SecureStore(Context context) {
        preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    public void saveTorBoxKey(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(value.trim().getBytes(StandardCharsets.UTF_8));
        preferences.edit()
                .putString(VALUE + "_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString(VALUE, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .apply();
    }

    public String loadTorBoxKey() {
        String encryptedText = preferences.getString(VALUE, null);
        String ivText = preferences.getString(VALUE + "_iv", null);
        if (encryptedText == null || ivText == null) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                    new GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)));
            byte[] clear = cipher.doFinal(Base64.decode(encryptedText, Base64.NO_WRAP));
            return new String(clear, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
