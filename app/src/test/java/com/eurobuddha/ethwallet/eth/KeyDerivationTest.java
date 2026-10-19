package com.eurobuddha.ethwallet.eth;

import android.os.Handler;
import com.eurobuddha.comms.NodeApi;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.web3j.crypto.Credentials;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Public synthetic scalars, mocked node and UI queue; no network or signatures. */
public class KeyDerivationTest {
    static final String ONE="0".repeat(63)+"1", TWO="0".repeat(63)+"2";
    static class Result implements EthWallet.Cb {
        String address,error,changed; int calls;
        public void ok(String a){address=a;calls++;}
        public void err(String e){error=e;calls++;}
        public void addressChanged(String before,String after){changed=after;calls++;}
    }
    static JSONObject reply(Object status,Object key)throws Exception {
        return new JSONObject().put("status",status).put("response",new JSONObject().put("seedrandom",key));
    }
    static class Fixture {
        final EthWallet wallet=new EthWallet(); final NodeApi node=mock(NodeApi.class);
        final List<NodeApi.Cb> pending=new ArrayList<>(); final List<Runnable> queued=new ArrayList<>();
        final Handler ui=mock(Handler.class);
        Fixture(){
            doAnswer(i->{pending.add(i.getArgument(1));return null;}).when(node).cmd(eq("seedrandom modifier:ethbridge"),any());
            when(ui.post(any())).thenAnswer(i->{queued.add(i.getArgument(0));return true;});
        }
        Result start(Handler handler,String pin){Result r=new Result();wallet.deriveFromNode(node,handler,pin,r);return r;}
    }
    @Test public void validNodeFormsMatchImportedKey()throws Exception {
        for(String key:new String[]{ONE,"0x"+ONE,"0X"+ONE," 0x"+ONE+" "}) {
            Fixture f=new Fixture();Result r=f.start(null,null);f.pending.get(0).onResult(reply(true,key));
            assertEquals(Credentials.create(ONE).getAddress(),r.address);assertFalse(f.wallet.isImported());
            assertEquals(r.address,f.wallet.address());assertEquals(1,r.calls);
        }
    }
    @Test public void malformedKeysAndFailedEnvelopesPreserveExistingOwner()throws Exception {
        String order=org.web3j.crypto.Sign.CURVE_PARAMS.getN().toString(16);
        for(Object key:new Object[]{"", "0x01","g".repeat(64),"0".repeat(64),order,"f".repeat(64),123,JSONObject.NULL,new JSONObject().put("secret",ONE)}) {
            Fixture f=new Fixture();f.wallet.importKey(TWO);Object kept=f.wallet.creds();Result r=f.start(null,null);
            f.pending.get(0).onResult(reply(true,key));assertSame(kept,f.wallet.creds());assertTrue(f.wallet.isImported());
            assertEquals("Node returned an invalid Ethereum key. Not using it.",r.error);
        }
        for(JSONObject invalid:new JSONObject[]{null,new JSONObject(),reply(false,ONE),reply("true",ONE),reply(JSONObject.NULL,ONE)}) {
            Fixture f=new Fixture();Result r=f.start(null,null);f.pending.get(0).onResult(invalid);assertFalse(f.wallet.ready());assertNotNull(r.error);
        }
    }
    @Test public void importedOrClearedWalletRejectsPendingNodeReply()throws Exception {
        for(boolean clear:new boolean[]{false,true}) {
            Fixture f=new Fixture();Result r=f.start(null,null);f.wallet.importKey(TWO);if(clear)f.wallet.clear();Object kept=f.wallet.creds();
            f.pending.get(0).onResult(reply(true,ONE));assertSame(kept,f.wallet.creds());assertEquals(0,r.calls);
        }
    }
    @Test public void queuedUiDeliveryCannotReplaceImportedKey()throws Exception {
        Fixture f=new Fixture();Result r=f.start(f.ui,null);f.pending.get(0).onResult(reply(true,ONE));
        assertFalse(f.wallet.ready());f.wallet.importKey(TWO);Object kept=f.wallet.creds();f.queued.remove(0).run();
        assertSame(kept,f.wallet.creds());assertEquals(0,r.calls);
    }
    @Test public void onlyLatestRequestCanAdoptAndCallbackOnce()throws Exception {
        Fixture f=new Fixture();Result old=f.start(null,null),latest=f.start(null,null);
        f.pending.get(1).onResult(reply(true,TWO));Object kept=f.wallet.creds();
        f.pending.get(0).onResult(reply(true,ONE));f.pending.get(1).onResult(reply(true,ONE));f.pending.get(1).onError("secret "+ONE);
        assertSame(kept,f.wallet.creds());assertEquals(0,old.calls);assertEquals(1,latest.calls);
    }
    @Test public void addressMismatchDoesNotReplaceExistingKey()throws Exception {
        Fixture f=new Fixture();f.wallet.importKey(TWO);Object kept=f.wallet.creds();Result r=f.start(null,f.wallet.address());
        f.pending.get(0).onResult(reply(true,ONE));assertSame(kept,f.wallet.creds());assertNotNull(r.changed);assertNull(r.address);
    }
    @Test public void staleErrorsDoNotReachUiAndCurrentErrorsNeverExposeSecrets() {
        Fixture f=new Fixture();Result old=f.start(f.ui,null);f.pending.get(0).onError("secret "+ONE);f.wallet.clear();f.queued.remove(0).run();assertEquals(0,old.calls);
        Result current=f.start(null,null);f.pending.get(1).onError("secret "+ONE);
        assertEquals("Ethereum key derivation failed. Retry when the node is ready.",current.error);
    }
    @Test public void invalidImportsPreserveOwnerAndNeverPublishAnInvalidScalar() {
        Fixture f=new Fixture();f.wallet.importKey(TWO);Object kept=f.wallet.creds();
        for(String key:new String[]{null,"01","0".repeat(64),"f".repeat(64),org.web3j.crypto.Sign.CURVE_PARAMS.getN().toString(16)}) {
            assertThrows(IllegalArgumentException.class,()->f.wallet.importKey(key));assertSame(kept,f.wallet.creds());
        }
    }
}
