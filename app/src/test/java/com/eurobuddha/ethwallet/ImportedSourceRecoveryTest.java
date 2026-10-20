package com.eurobuddha.ethwallet;

import android.content.SharedPreferences;
import com.eurobuddha.ethwallet.eth.EthWallet;
import java.lang.reflect.*;
import java.util.concurrent.ExecutorService;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Reuses AssetSelectionTest's unstarted Activity fixture; no node or key derivation. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33)
public class ImportedSourceRecoveryTest {
    private MainActivity activity;
    private SharedPreferences prefs;
    private KeyVault vault;
    private Field field(String name) throws Exception {
        Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);return field;
    }
    @Before public void setup() throws Exception {
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.Theme_EthWallet);
        ((ExecutorService)field("io").get(activity)).shutdownNow();field("modalOpen").set(activity,true);
        prefs=activity.getSharedPreferences("import-recovery-fixture",0);
        prefs.edit().clear().putString("source","import").commit();field("prefs").set(activity,prefs);
        vault=mock(KeyVault.class);field("vault").set(activity,vault);
    }
    private void startAndCheckPreserved() throws Exception {
        Method start=MainActivity.class.getDeclaredMethod("startWallet");start.setAccessible(true);start.invoke(activity);
        assertEquals("import",prefs.getString("source",null));
        assertTrue(((String)field("ethErr").get(activity)).contains("preserved"));
        assertFalse(((EthWallet)field("wallet").get(activity)).ready());
        verify(vault,never()).clear();verify(vault,never()).saveKey(org.mockito.ArgumentMatchers.anyString());
    }
    @Test public void unavailableVaultKeepsImportedSourceForNextLaunch() throws Exception {
        when(vault.available()).thenReturn(false);startAndCheckPreserved();verify(vault,never()).loadKey();
    }
    @Test public void unreadableStoredKeyAlsoKeepsImportedSource() throws Exception {
        when(vault.available()).thenReturn(true);when(vault.loadKey()).thenReturn(null);
        startAndCheckPreserved();verify(vault).loadKey();
    }
}
