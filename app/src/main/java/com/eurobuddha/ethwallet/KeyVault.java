package com.eurobuddha.ethwallet;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;


/** Keystore-backed imported key storage. Opening failures preserve all existing key material. */
public final class KeyVault {

    private static final String TAG = "KeyVault";
    private static final String PREFS_NAME = "ethwallet_secure";
    /** androidx.security-crypto stores both keysets inside the prefs file above, under these keys. */
    private static final String MASTER_KEY_ALIAS = "_androidx_security_master_key_";

    private final SharedPreferences enc;   // null when the secure store could not be opened at all
    private final boolean wasReset;

    public KeyVault(Context ctx) {
        SharedPreferences opened = null;
        try {
            opened = open(ctx);
        } catch (Exception failure) {
            Log.w(TAG, "Secure store unavailable; existing keys preserved.");
        }
        this.enc = opened;
        this.wasReset = false;
    }

    private static SharedPreferences open(Context ctx) throws Exception {
        MasterKey master = new MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
        return EncryptedSharedPreferences.create(ctx, PREFS_NAME, master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    /** False when the secure store could not be opened — callers must not offer key import. */
    public boolean available() { return enc != null; }

    /** True when a corrupt/undecryptable store was discarded during construction. */
    public boolean wasReset() { return wasReset; }

    /** @return true if the key was actually persisted. */
    public boolean saveKey(String hex) {
        if (enc == null) return false;
        try { return enc.edit().putString("priv", hex).commit(); }
        catch (Throwable t) { Log.e(TAG, "saveKey failed: " + t); return false; }
    }

    public String loadKey() {
        if (enc == null) return null;
        try { return enc.getString("priv", null); }
        catch (Throwable t) { Log.e(TAG, "loadKey failed: " + t); return null; }
    }

    public void clear() {
        if (enc == null) return;
        try { enc.edit().remove("priv").apply(); } catch (Throwable ignore) {}
    }
}
