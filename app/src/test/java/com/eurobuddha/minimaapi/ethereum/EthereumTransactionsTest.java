package com.eurobuddha.minimaapi.ethereum;

import org.junit.Test;
import org.json.*;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Public synthetic key 1. Disposable stores and fake RPC only; no sockets or node. */
public class EthereumTransactionsTest {
    static final Credentials KEY=Credentials.create("01");
    static final String TO="0x"+"12".repeat(20),OTHER="0x"+"34".repeat(20);
    static final BigInteger GAS=BigInteger.valueOf(21000),PRICE=BigInteger.valueOf(1_000_000_000L);
    static class Memory implements EthereumTransactions.Store {
        Map<String,String> values=new HashMap<>();int writes,failAt=-1;
        public String read(String key){return values.getOrDefault(key,"[]");}
        public void write(String key,String value)throws Exception {if(++writes==failAt)throw new IOException("fixture disk unavailable");values.put(key,value);}
    }
    static class Rpc implements EthereumTransactions.Rpc {
        BigInteger chain=BigInteger.ONE,nonce=BigInteger.valueOf(4);
        final List<String> sent=new ArrayList<>();final Map<String,String> known=new HashMap<>();final Map<String,JSONObject> receipts=new HashMap<>();
        boolean fail,acceptThenFail,wrongHash;int reads;
        public BigInteger chain(){return chain;}
        public BigInteger pending(String address){return nonce;}
        public String broadcast(String raw)throws Exception {
            sent.add(raw);String hash=Hash.sha3(raw);if(acceptThenFail)known.put(hash,raw);
            if(fail||acceptThenFail)throw new IOException("fixture lost response");
            if(wrongHash)return "0x"+"ff".repeat(32);
            known.put(hash,raw);return hash;
        }
        public JSONObject transaction(String hash)throws Exception {
            String raw=known.get(hash);if(raw==null)return null;SignedRawTransaction tx=(SignedRawTransaction)TransactionDecoder.decode(raw);
            return new JSONObject().put("hash",hash).put("from",tx.getFrom()).put("to",tx.getTo()).put("nonce",hex(tx.getNonce()))
                .put("value",hex(tx.getValue())).put("gas",hex(tx.getGasLimit())).put("gasPrice",hex(tx.getGasPrice()))
                .put("input",Numeric.prependHexPrefix(tx.getData())).put("chainId","0x1");
        }
        public JSONObject receipt(String hash){reads++;return receipts.get(hash);}
        void settle(String raw,boolean success)throws Exception {SignedRawTransaction tx=(SignedRawTransaction)TransactionDecoder.decode(raw);String hash=Hash.sha3(raw);
            receipts.put(hash,new JSONObject().put("transactionHash",hash).put("from",tx.getFrom()).put("to",tx.getTo())
                .put("blockHash","0x"+"ab".repeat(32)).put("blockNumber","0x64").put("status",success?"0x1":"0x0"));
            nonce=tx.getNonce().add(BigInteger.ONE);
        }
        static String hex(BigInteger value){return "0x"+value.toString(16);}
    }
    static String send(EthereumTransactions e,Rpc rpc,String to,String op)throws Exception {return e.send(rpc,KEY,1,to,"0x",BigInteger.ONE,GAS,PRICE,op,()->{});}
    static BigInteger nonce(String raw){return TransactionDecoder.decode(raw).getNonce();}
    @Test public void differentClientsShareAccountNonceAndRestartKeepsOwnership()throws Exception {
        Memory m=new Memory();EthereumTransactions e=new EthereumTransactions(m);Rpc a=new Rpc();
        send(e,a,TO,null);send(e,a,OTHER,null);assertEquals(BigInteger.valueOf(4),nonce(a.sent.get(0)));assertEquals(BigInteger.valueOf(5),nonce(a.sent.get(1)));
        e=new EthereumTransactions(m);send(e,a,TO,null);assertEquals(BigInteger.valueOf(6),nonce(a.sent.get(2)));
    }
    @Test public void lostResponseRetriesExactBytesAcrossRestartAndDoesNotResignAnotherNonce()throws Exception {
        Memory m=new Memory();EthereumTransactions e=new EthereumTransactions(m);Rpc rpc=new Rpc();rpc.fail=true;
        EthereumTransactions.Uncertain first=assertThrows(EthereumTransactions.Uncertain.class,()->send(e,rpc,TO,"lock:one"));
        assertEquals(Hash.sha3(rpc.sent.get(0)),first.hash);rpc.fail=false;EthereumTransactions restarted=new EthereumTransactions(m);
        assertEquals(first.hash,send(restarted,rpc,TO,"lock:one"));assertEquals(rpc.sent.get(0),rpc.sent.get(1));
    }
    @Test public void unknownManualRetryDoesNotCreateASecondPayment()throws Exception {
        Memory m=new Memory();EthereumTransactions e=new EthereumTransactions(m);Rpc rpc=new Rpc();rpc.fail=true;
        assertThrows(EthereumTransactions.Uncertain.class,()->send(e,rpc,TO,null));rpc.fail=false;
        send(e,rpc,TO,null);assertEquals(rpc.sent.get(0),rpc.sent.get(1));
    }
    @Test public void acceptedThenLostResponseUsesExactLookupAndReturnsLocalHash()throws Exception {
        EthereumTransactions e=new EthereumTransactions(new Memory());Rpc rpc=new Rpc();rpc.acceptThenFail=true;
        assertEquals(send(e,rpc,TO,"lock:one"),Hash.sha3(rpc.sent.get(0)));
    }
    @Test public void fabricatedRpcHashCannotReportSuccess()throws Exception {
        EthereumTransactions e=new EthereumTransactions(new Memory());Rpc rpc=new Rpc();rpc.wrongHash=true;
        assertThrows(EthereumTransactions.Uncertain.class,()->send(e,rpc,TO,null));assertEquals(1,rpc.sent.size());
    }
    @Test public void noBroadcastBeforeBothAcknowledgedPreparationAndPostingWrites()throws Exception {
        for(int fail:new int[]{1,2}) {Memory m=new Memory();m.failAt=fail;EthereumTransactions e=new EthereumTransactions(m);Rpc rpc=new Rpc();
            assertThrows(IOException.class,()->send(e,rpc,TO,null));assertTrue(rpc.sent.isEmpty());
            m.failAt=-1;EthereumTransactions restarted=new EthereumTransactions(m);send(restarted,rpc,TO,null);assertEquals(BigInteger.valueOf(4),nonce(rpc.sent.get(0)));
        }
    }
    @Test public void failedPostAckPersistenceRetainsRecoverableAttempt()throws Exception {
        Memory m=new Memory();m.failAt=3;EthereumTransactions e=new EthereumTransactions(m);Rpc rpc=new Rpc();
        assertThrows(EthereumTransactions.Uncertain.class,()->send(e,rpc,TO,"lock:a"));m.failAt=-1;
        String hash=send(new EthereumTransactions(m),rpc,TO,"lock:a");assertEquals(Hash.sha3(rpc.sent.get(0)),hash);assertEquals(rpc.sent.get(0),rpc.sent.get(1));
    }
    @Test public void validFeeReplacementPreservesNonceAndEveryPaymentField()throws Exception {
        Memory m=new Memory();EthereumTransactions e=new EthereumTransactions(m);Rpc rpc=new Rpc();String old=send(e,rpc,TO,"withdraw:x");
        String next=e.send(rpc,KEY,1,TO,"0x",BigInteger.ONE,GAS,PRICE.multiply(BigInteger.TWO),"withdraw:x",()->{});
        assertNotEquals(old,next);assertEquals(nonce(rpc.sent.get(0)),nonce(rpc.sent.get(1)));
        RawTransaction a=TransactionDecoder.decode(rpc.sent.get(0)),b=TransactionDecoder.decode(rpc.sent.get(1));
        assertEquals(a.getTo(),b.getTo());assertEquals(a.getValue(),b.getValue());assertEquals(a.getData(),b.getData());assertEquals(a.getGasLimit(),b.getGasLimit());
        rpc.settle(rpc.sent.get(0),true);assertEquals(old,send(e,rpc,TO,"withdraw:x"));assertEquals(2,rpc.sent.size());
    }
    @Test public void differentIntentCannotReplaceAnExistingOperation()throws Exception {
        EthereumTransactions e=new EthereumTransactions(new Memory());Rpc rpc=new Rpc();send(e,rpc,TO,"lock:a");
        assertThrows(IOException.class,()->send(e,rpc,OTHER,"lock:a"));assertEquals(1,rpc.sent.size());
    }
    @Test public void revertedReceiptCanRetryAtNextNonceAndSuccessfulStableOperationCannotRepeat()throws Exception {
        EthereumTransactions e=new EthereumTransactions(new Memory());Rpc rpc=new Rpc();send(e,rpc,TO,"refund:a");rpc.settle(rpc.sent.get(0),false);
        String retry=send(e,rpc,TO,"refund:a");assertEquals(BigInteger.valueOf(5),nonce(rpc.sent.get(1)));rpc.settle(rpc.sent.get(1),true);
        assertEquals(retry,send(e,rpc,TO,"refund:a"));assertEquals(2,rpc.sent.size());
    }
    @Test public void independentPendingApprovalsDoNotShareAnOperationIdentity()throws Exception {
        EthereumTransactions e=new EthereumTransactions(new Memory());Rpc rpc=new Rpc();send(e,rpc,TO,"pending");send(e,rpc,OTHER,"pending");
        assertEquals(BigInteger.valueOf(5),nonce(rpc.sent.get(1)));
    }
    @Test public void knownNonceAdvancementWithoutOurReceiptCannotReplaceOurTransaction()throws Exception {
        Memory m=new Memory();EthereumTransactions e=new EthereumTransactions(m);Rpc rpc=new Rpc();rpc.fail=true;
        assertThrows(EthereumTransactions.Uncertain.class,()->send(e,rpc,TO,"withdraw:a"));rpc.nonce=BigInteger.valueOf(5);rpc.fail=false;
        assertThrows(EthereumTransactions.Uncertain.class,()->send(e,rpc,OTHER,null));assertEquals(1,rpc.sent.size());
    }
    @Test public void malformedJournalFailsWithoutOverwritingOrBroadcasting()throws Exception {
        Memory m=new Memory();String key="1:"+KEY.getAddress();m.values.put(key,"[] garbage");Rpc rpc=new Rpc();
        assertThrows(Exception.class,()->send(new EthereumTransactions(m),rpc,TO,null));assertEquals("[] garbage",m.values.get(key));assertEquals(0,m.writes);assertTrue(rpc.sent.isEmpty());
    }
    @Test public void wrongChainAndInvalidNonceCannotReachSigningOrJournal()throws Exception {
        Memory m=new Memory();EthereumTransactions e=new EthereumTransactions(m);Rpc rpc=new Rpc();rpc.chain=BigInteger.TEN;
        assertThrows(IOException.class,()->send(e,rpc,TO,null));rpc.chain=BigInteger.ONE;rpc.nonce=null;
        assertThrows(IOException.class,()->send(e,rpc,TO,null));assertEquals(0,m.writes);assertTrue(rpc.sent.isEmpty());
    }
    @Test public void deniedAuthorizationBeforeAndAfterSigningCannotBroadcast()throws Exception {
        Memory m=new Memory();EthereumTransactions e=new EthereumTransactions(m);Rpc rpc=new Rpc();int[] calls={0};
        assertThrows(IllegalStateException.class,()->e.send(rpc,KEY,1,TO,"0x",BigInteger.ONE,GAS,PRICE,null,()->{if(++calls[0]==5)throw new IllegalStateException("revoked");}));
        assertTrue(rpc.sent.isEmpty());send(e,rpc,TO,null);assertEquals(BigInteger.valueOf(4),nonce(rpc.sent.get(0)));
    }
    @Test public void concurrentCrossClientSendsCannotAllocateTheSameNonce()throws Exception {
        EthereumTransactions e=new EthereumTransactions(new Memory());Rpc rpc=new Rpc();ExecutorService pool=Executors.newFixedThreadPool(2);
        try {Future<String>a=pool.submit(()->send(e,rpc,TO,null)),b=pool.submit(()->send(e,rpc,OTHER,null));a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
            assertEquals(2,rpc.sent.size());assertNotEquals(nonce(rpc.sent.get(0)),nonce(rpc.sent.get(1)));}
        finally{pool.shutdownNow();}
    }

    @Test public void knownPendingTransactionDoesNotBlockNextNonceAfterRpcCountAdvances()throws Exception {
        EthereumTransactions e=new EthereumTransactions(new Memory());Rpc rpc=new Rpc();send(e,rpc,TO,null);
        rpc.nonce=BigInteger.valueOf(5);send(e,rpc,OTHER,null);assertEquals(BigInteger.valueOf(5),nonce(rpc.sent.get(1)));
    }
    @Test public void rejectedReplacementReturnsOnlyTheOlderHashActuallyFound()throws Exception {
        EthereumTransactions e=new EthereumTransactions(new Memory());Rpc rpc=new Rpc();String old=send(e,rpc,TO,"lock:a");rpc.fail=true;
        String result=e.send(rpc,KEY,1,TO,"0x",BigInteger.ONE,GAS,PRICE.multiply(BigInteger.TWO),"lock:a",()->{});
        assertEquals(old,result);assertNotEquals(Hash.sha3(rpc.sent.get(1)),result);
    }
    @Test public void receiptReadFailureRetainsTheUncertainHashAndCannotCreateAnotherSend()throws Exception {
        EthereumTransactions e=new EthereumTransactions(new Memory());Rpc rpc=new Rpc(){public JSONObject receipt(String hash){throw new IllegalStateException("fixture offline");}};
        String hash=send(e,rpc,TO,"lock:a");EthereumTransactions.Uncertain error=assertThrows(EthereumTransactions.Uncertain.class,()->send(e,rpc,TO,"lock:a"));
        assertEquals(hash,error.hash);assertEquals(1,rpc.sent.size());
    }
    @Test public void cancelledAfterPreparationCanBeRetriedButNeverBroadcastsWithoutPostingWrite()throws Exception {
        Memory m=new Memory();EthereumTransactions e=new EthereumTransactions(m);Rpc rpc=new Rpc();int[] checks={0};
        assertThrows(IllegalStateException.class,()->e.send(rpc,KEY,1,TO,"0x",BigInteger.ONE,GAS,PRICE,null,()->{if(++checks[0]==6)throw new IllegalStateException("cancelled");}));
        assertEquals(1,m.writes);assertTrue(rpc.sent.isEmpty());send(e,rpc,TO,null);assertEquals(BigInteger.valueOf(4),nonce(rpc.sent.get(0)));
    }
    @Test public void unknownManualRetryWithChangedGasEstimateCannotCreateAnotherPayment()throws Exception {
        Memory store=new Memory();EthereumTransactions journal=new EthereumTransactions(store);Rpc rpc=new Rpc();rpc.fail=true;
        EthereumTransactions.Uncertain first=assertThrows(EthereumTransactions.Uncertain.class,()->send(journal,rpc,TO,null));
        rpc.fail=false;EthereumTransactions restarted=new EthereumTransactions(store);
        String result=restarted.send(rpc,KEY,1,TO,"0x",BigInteger.ONE,GAS.multiply(BigInteger.TWO),PRICE,null,()->{});
        assertEquals(first.hash,result);assertEquals(2,rpc.sent.size());assertEquals(rpc.sent.get(0),rpc.sent.get(1));
    }
    @Test public void lowerReviewedGasLimitCannotAuthorizeReplayOrAnotherNonce()throws Exception {
        Memory store=new Memory();EthereumTransactions journal=new EthereumTransactions(store);Rpc rpc=new Rpc();rpc.fail=true;
        EthereumTransactions.Uncertain first=assertThrows(EthereumTransactions.Uncertain.class,()->journal.send(rpc,KEY,1,TO,"0x",BigInteger.ONE,GAS.multiply(BigInteger.TWO),PRICE,null,()->{}));
        rpc.fail=false;EthereumTransactions restarted=new EthereumTransactions(store);
        EthereumTransactions.Uncertain retry=assertThrows(EthereumTransactions.Uncertain.class,()->send(restarted,rpc,TO,null));
        assertEquals(first.hash,retry.hash);assertEquals(1,rpc.sent.size());
    }
    @Test public void lowerReviewedGasPriceCannotReplayAnOlderMoreExpensiveAttempt()throws Exception {
        EthereumTransactions journal=new EthereumTransactions(new Memory());Rpc rpc=new Rpc();rpc.fail=true;
        EthereumTransactions.Uncertain first=assertThrows(EthereumTransactions.Uncertain.class,()->send(journal,rpc,TO,null));rpc.fail=false;
        EthereumTransactions.Uncertain retry=assertThrows(EthereumTransactions.Uncertain.class,()->journal.send(rpc,KEY,1,TO,"0x",BigInteger.ONE,GAS,PRICE.divide(BigInteger.TWO),null,()->{}));
        assertEquals(first.hash,retry.hash);assertEquals(1,rpc.sent.size());
    }
    @Test public void changedGasRetryReturnsAlreadyConfirmedOriginalInsteadOfSendingAgain()throws Exception {
        EthereumTransactions journal=new EthereumTransactions(new Memory());Rpc rpc=new Rpc();rpc.fail=true;
        EthereumTransactions.Uncertain first=assertThrows(EthereumTransactions.Uncertain.class,()->send(journal,rpc,TO,null));
        rpc.fail=false;rpc.settle(rpc.sent.get(0),true);
        assertEquals(first.hash,journal.send(rpc,KEY,1,TO,"0x",BigInteger.ONE,GAS.multiply(BigInteger.TWO),PRICE,null,()->{}));
        assertEquals(1,rpc.sent.size());
    }
}
