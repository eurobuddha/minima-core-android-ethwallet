package com.eurobuddha.ethwallet;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class KeyVaultFailureTest {
    @Test public void openingFailurePreservesStorageAndLaterOpenCanRecover() throws Exception {
        Context context=mock(Context.class); MasterKey key=mock(MasterKey.class);
        SharedPreferences prefs=mock(SharedPreferences.class);
        when(prefs.getString("priv",null)).thenReturn("fixture-key");
        try(MockedConstruction<MasterKey.Builder> builders=mockConstruction(MasterKey.Builder.class,(b,c)->{
            assertEquals("Keep the standalone default master-key alias",1,c.arguments().size());
            assertSame(context,c.arguments().get(0));
            when(b.setKeyScheme(any())).thenReturn(b); when(b.build()).thenReturn(key);
        }); MockedStatic<EncryptedSharedPreferences> encrypted=mockStatic(EncryptedSharedPreferences.class)) {
            encrypted.when(()->EncryptedSharedPreferences.create(eq(context),eq("ethwallet_secure"),eq(key),any(),any()))
                    .thenThrow(new java.security.GeneralSecurityException("temporarily unavailable")).thenReturn(prefs);
            KeyVault failed=new KeyVault(context);
            assertFalse(failed.available()); assertFalse(failed.wasReset()); assertFalse(failed.saveKey("replacement"));
            verify(context,never()).deleteSharedPreferences(anyString());
            verify(context,never()).getApplicationInfo();
            KeyVault recovered=new KeyVault(context);
            assertTrue(recovered.available()); assertEquals("fixture-key",recovered.loadKey());
            verify(prefs,never()).edit();
        }
    }
}
