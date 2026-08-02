package com.eurobuddha.ethwallet;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.File;
import java.security.KeyStore;

/**
 * Android-Keystore-backed encrypted store for an IMPORTED private key — never plain prefs.
 *
 * SELF-HEALING. The Tink keysets live inside the {@code ethwallet_secure} prefs file and are
 * encrypted by a device-bound Keystore master key that is never backed up and never restored.
 * A cloud/D2D restore (the classic uninstall→reinstall path), a Keystore reset, or a lockscreen
 * credential change therefore leaves a keyset that no longer decrypts — Tink surfaces this as
 * {@code AEADBadTagException} / "Signature/MAC verification failed".
 *
 * That used to throw straight out of the constructor and crash the app on every launch, with no
 * recovery short of clearing app data. Now we detect it, wipe the unreadable keyset + ciphertext,
 * recreate from scratch, and expose {@link #wasReset()} so the UI can explain what happened. If
 * even that fails we degrade to unavailable rather than dying — a node-paired wallet never touches
 * this store, so an unusable vault must not stop the app from starting.
 *
 * The only thing lost by a reset is an imported private key, which by definition the user holds
 * elsewhere (they pasted it in). Node-derived keys are re-derived per session and never stored here.
 */
public final class KeyVault {

    private static final String TAG = "KeyVault";
    private static final String PREFS_NAME = "ethwallet_secure";
    /** androidx.security-crypto stores both keysets inside the prefs file above, under these keys. */
    private static final String MASTER_KEY_ALIAS = "_androidx_security_master_key_";

    private final SharedPreferences enc;   // null when the secure store could not be opened at all
    private final boolean wasReset;

    public KeyVault(Context ctx) {
        SharedPreferences opened = null;
        boolean reset = false;
        try {
            opened = open(ctx);
        } catch (Throwable first) {
            // Unreadable keyset (restored backup / Keystore reset). Wipe and rebuild once.
            Log.w(TAG, "secure store unreadable, resetting: " + first);
            wipe(ctx);
            reset = true;
            try {
                opened = open(ctx);
            } catch (Throwable second) {
                Log.e(TAG, "secure store unavailable after reset: " + second);
                opened = null;
            }
        }
        this.enc = opened;
        this.wasReset = reset;
    }

    private static SharedPreferences open(Context ctx) throws Exception {
        MasterKey master = new MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
        return EncryptedSharedPreferences.create(ctx, PREFS_NAME, master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    /** Drop the undecryptable prefs file AND the master key, so the next open() builds a clean pair. */
    private static void wipe(Context ctx) {
        try { ctx.deleteSharedPreferences(PREFS_NAME); } catch (Throwable ignore) {}
        // deleteSharedPreferences can no-op if the file was never loaded in this process — remove it directly too.
        try {
            File f = new File(new File(ctx.getApplicationInfo().dataDir, "shared_prefs"), PREFS_NAME + ".xml");
            if (f.exists() && !f.delete()) Log.w(TAG, "could not delete " + f);
        } catch (Throwable ignore) {}
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(MASTER_KEY_ALIAS)) ks.deleteEntry(MASTER_KEY_ALIAS);
        } catch (Throwable ignore) {}
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
