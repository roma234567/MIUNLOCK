package com.miunlock.sniper.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Хранилище секретов на AES-256-GCM с ключом в AndroidKeyStore.
 * Если Keystore недоступен, значения сохраняются с пометкой plain: и {@link #secure()} возвращает false.
 */
public final class SecureStore {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "miunlock.vault.v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;
    private static final int IV_LENGTH = 12;
    private static final String PLAIN_PREFIX = "plain:";
    private static final String SEALED_PREFIX = "sealed:";

    private final SharedPreferences sp;
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecureStore(Context context, String fileName) {
        sp = context.getApplicationContext().getSharedPreferences(fileName, Context.MODE_PRIVATE);
        key = loadOrCreateKey();
    }

    public boolean secure() {
        return key != null;
    }

    public void put(String name, String value) {
        if (value == null) {
            sp.edit().remove(name).apply();
            return;
        }
        if (key == null) {
            sp.edit().putString(name, PLAIN_PREFIX + value).apply();
            return;
        }
        try {
            final byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            final byte[] sealed = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

            final byte[] packed = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(sealed, 0, packed, iv.length, sealed.length);
            sp.edit().putString(name, SEALED_PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)).apply();
        } catch (Exception error) {
            Trace.e("SecureStore", "не удалось зашифровать " + name, error);
        }
    }

    public String get(String name, String fallback) {
        final String stored = sp.getString(name, null);
        if (stored == null) {
            return fallback;
        }
        if (stored.startsWith(PLAIN_PREFIX)) {
            return stored.substring(PLAIN_PREFIX.length());
        }
        if (!stored.startsWith(SEALED_PREFIX)) {
            return stored;
        }
        try {
            final byte[] packed = Base64.decode(stored.substring(SEALED_PREFIX.length()), Base64.NO_WRAP);
            if (packed.length <= IV_LENGTH) {
                throw new IllegalStateException("короткий контейнер");
            }
            final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, packed, 0, IV_LENGTH));
            final byte[] plain = cipher.doFinal(packed, IV_LENGTH, packed.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception error) {
            Trace.e("SecureStore", "не удалось расшифровать " + name + ", запись удалена", error);
            sp.edit().remove(name).apply();
            return fallback;
        }
    }

    public void remove(String name) {
        sp.edit().remove(name).apply();
    }

    private static SecretKey loadOrCreateKey() {
        try {
            final KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            final java.security.Key existing = keyStore.getKey(ALIAS, null);
            if (existing instanceof SecretKey) {
                return (SecretKey) existing;
            }
            final KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            return generator.generateKey();
        } catch (Exception error) {
            Trace.e("SecureStore", "Keystore недоступен, сессия будет сохранена без шифрования", error);
            return null;
        }
    }
}
