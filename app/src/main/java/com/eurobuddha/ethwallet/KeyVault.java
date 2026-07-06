package com.eurobuddha.ethwallet;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/** Android-Keystore-backed encrypted store for an IMPORTED private key — never plain prefs. */
public final class KeyVault {

    private final SharedPreferences enc;

    public KeyVault(Context ctx) {
        try {
            MasterKey master = new MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
            enc = EncryptedSharedPreferences.create(ctx, "ethwallet_secure", master,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            throw new RuntimeException("secure store init failed: " + e.getMessage(), e);
        }
    }

    public void saveKey(String hex) { enc.edit().putString("priv", hex).apply(); }
    public String loadKey() { return enc.getString("priv", null); }
    public void clear() { enc.edit().remove("priv").apply(); }
}
