package com.fadcam.ui.faditor.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;

import com.fadcam.FLog;
import com.fadcam.SharedPreferencesManager;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The user's AI API key, encrypted at rest.
 *
 * <p>Stored as AES-256-GCM ciphertext; the wrapping key lives in the Android Keystore and never
 * leaves it (same scheme as {@link com.fadcam.security.StreamKeyManager}). Before this the key sat
 * in plain preferences, readable by anything that could read the app's files.</p>
 *
 * <p>A key saved by an older build is moved in on first read and the plain copy deleted. If the
 * Keystore key is gone (data kept across an uninstall, a restored phone), the ciphertext can't be
 * read: it is dropped and the user is asked for the key again, which beats a crash or a wrong key.</p>
 */
public final class AiKey {

    private static final String TAG = "AiKey";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "joy_ai_api_key_wrap";
    private static final String PREFS = "JoyAiSecrets";
    private static final String BLOB = "api_key_enc";     // Base64 of iv || ciphertext+tag
    /** Where older builds kept the key in plain text. */
    private static final String LEGACY = "ai_api_key";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private AiKey() {}

    /** The saved key, or "" when none. */
    @NonNull
    public static synchronized String get(@NonNull Context context) {
        SharedPreferences legacy = SharedPreferencesManager.getInstance(context).sharedPreferences;
        String plain = legacy.getString(LEGACY, "");
        if (plain != null && !plain.isEmpty()) {
            if (store(context, plain)) legacy.edit().remove(LEGACY).apply();
            return plain;
        }
        String blob = prefs(context).getString(BLOB, null);
        if (blob == null) return "";
        try {
            byte[] all = Base64.decode(blob, Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, wrapKey(), new GCMParameterSpec(TAG_BITS, all, 0, IV_LEN));
            return new String(c.doFinal(all, IV_LEN, all.length - IV_LEN), StandardCharsets.UTF_8);
        } catch (Exception e) {
            FLog.w(TAG, "Saved AI key can't be read on this install; asking for it again", e);
            prefs(context).edit().remove(BLOB).apply();
            return "";
        }
    }

    /** Save {@code key}; empty clears it. */
    public static synchronized void set(@NonNull Context context, @NonNull String key) {
        SharedPreferencesManager.getInstance(context).sharedPreferences.edit().remove(LEGACY).apply();
        if (key.isEmpty()) {
            prefs(context).edit().remove(BLOB).apply();
            return;
        }
        if (!store(context, key)) {
            // No usable Keystore on this phone: keep it working the way it always did.
            SharedPreferencesManager.getInstance(context).sharedPreferences.edit().putString(LEGACY, key).apply();
        }
    }

    /** {@code key} encrypted with the same Keystore key, for a saved account; null if it can't be. */
    static synchronized String seal(@NonNull String key) {
        if (key.isEmpty()) return "";
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, wrapKey());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(key.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array(),
                    Base64.NO_WRAP);
        } catch (Exception e) {
            FLog.w(TAG, "Keystore unavailable; saved account key not kept", e);
            return null;
        }
    }

    /** A {@link #seal}ed key, or "" when it can't be read on this install. */
    @NonNull
    static synchronized String open(String blob) {
        if (blob == null || blob.isEmpty()) return "";
        try {
            byte[] all = Base64.decode(blob, Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, wrapKey(), new GCMParameterSpec(TAG_BITS, all, 0, IV_LEN));
            return new String(c.doFinal(all, IV_LEN, all.length - IV_LEN), StandardCharsets.UTF_8);
        } catch (Exception e) {
            FLog.w(TAG, "Saved account key can't be read on this install", e);
            return "";
        }
    }

    private static boolean store(Context context, String key) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, wrapKey());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(key.getBytes(StandardCharsets.UTF_8));
            byte[] all = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
            prefs(context).edit().putString(BLOB, Base64.encodeToString(all, Base64.NO_WRAP)).apply();
            return true;
        } catch (Exception e) {
            FLog.w(TAG, "Keystore unavailable; AI key not encrypted", e);
            return false;
        }
    }

    private static SecretKey wrapKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return gen.generateKey();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
