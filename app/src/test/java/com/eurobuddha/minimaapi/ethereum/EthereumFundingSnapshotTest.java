package com.eurobuddha.minimaapi.ethereum;
import java.math.BigInteger;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.eurobuddha.minimaapi.ethereum.EthereumTransactionsTest.*;

/** Reuses the shared journal's public fixture key and fake RPC; no real transactions. */
public class EthereumFundingSnapshotTest {
    @Test public void unknownAttemptSurvivesRestartInFundingView()throws Exception{
        Memory store=new Memory();EthereumTransactions journal=new EthereumTransactions(store);Rpc rpc=new Rpc();rpc.fail=true;
        assertThrows(EthereumTransactions.Uncertain.class,()->send(journal,rpc,TO,"lock:one"));
        int reads=rpc.reads,writes=store.writes;
        EthereumTransactions.FundingSnapshot view=new EthereumTransactions(store).fundingSnapshot(1,KEY.getAddress());
        assertEquals(1,view.intents.size());assertEquals(BigInteger.valueOf(4),view.intents.get(0).nonce);
        assertEquals(TO,view.intents.get(0).to);assertEquals(BigInteger.ONE,view.intents.get(0).value);
        assertEquals(writes,store.writes);assertEquals(reads,rpc.reads);assertTrue(view.unchanged());
        assertThrows(UnsupportedOperationException.class,()->view.intents.clear());
    }
    @Test public void feeReplacementIsOneIntentAndInvalidatesPriorView()throws Exception{
        EthereumTransactions journal=new EthereumTransactions(new Memory());Rpc rpc=new Rpc();send(journal,rpc,TO,"lock:a");
        EthereumTransactions.FundingSnapshot before=journal.fundingSnapshot(1,KEY.getAddress());
        journal.send(rpc,KEY,1,TO,"0x",BigInteger.ONE,GAS,PRICE.multiply(BigInteger.TWO),"lock:a",()->{});
        assertFalse(before.unchanged());EthereumTransactions.FundingSnapshot after=journal.fundingSnapshot(1,KEY.getAddress());
        assertEquals(1,after.intents.size());assertEquals(PRICE.multiply(BigInteger.TWO),after.intents.get(0).price);
    }
    @Test public void anotherAccountCannotInvalidateThisAccount()throws Exception{
        EthereumTransactions journal=new EthereumTransactions(new Memory());Rpc rpc=new Rpc();
        EthereumTransactions.FundingSnapshot view=journal.fundingSnapshot(1,KEY.getAddress());
        journal.send(rpc,org.web3j.crypto.Credentials.create("02"),1,TO,"0x",BigInteger.ONE,GAS,PRICE,null,()->{});
        assertTrue(view.unchanged());send(journal,rpc,TO,null);assertFalse(view.unchanged());
    }
    @Test public void preparedButNeverBroadcastDoesNotReserveFunds()throws Exception{
        Memory store=new Memory();EthereumTransactions journal=new EthereumTransactions(store);Rpc rpc=new Rpc();int[] checks={0};
        assertThrows(IllegalStateException.class,()->journal.send(rpc,KEY,1,TO,"0x",BigInteger.ONE,GAS,PRICE,null,()->{if(++checks[0]==6)throw new IllegalStateException();}));
        assertTrue(journal.fundingSnapshot(1,KEY.getAddress()).intents.isEmpty());assertTrue(rpc.sent.isEmpty());
    }
    @Test public void savedSettlementCannotHideIntentFromLaggingBalanceView()throws Exception{
        EthereumTransactions journal=new EthereumTransactions(new Memory());Rpc rpc=new Rpc();send(journal,rpc,TO,"lock:a");rpc.settle(rpc.sent.get(0),true);send(journal,rpc,TO,"lock:a");
        assertEquals(1,journal.fundingSnapshot(1,KEY.getAddress()).intents.size());
    }
    @Test public void malformedStorageAndInvalidAccountCannotProduceFundingView()throws Exception{
        Memory store=new Memory();store.values.put("1:"+KEY.getAddress(),"[] garbage");EthereumTransactions journal=new EthereumTransactions(store);
        assertThrows(Exception.class,()->journal.fundingSnapshot(1,KEY.getAddress()));assertThrows(Exception.class,()->journal.fundingSnapshot(0,KEY.getAddress()));
        assertThrows(Exception.class,()->journal.fundingSnapshot(1,"bad"));assertEquals(0,store.writes);
    }
    @Test public void failedWriteStillInvalidatesReviewedFunding()throws Exception{
        Memory store=new Memory();EthereumTransactions journal=new EthereumTransactions(store);Rpc rpc=new Rpc();
        EthereumTransactions.FundingSnapshot view=journal.fundingSnapshot(1,KEY.getAddress());store.failAt=1;
        assertThrows(Exception.class,()->send(journal,rpc,TO,null));assertFalse(view.unchanged());assertTrue(rpc.sent.isEmpty());
    }
    @Test public void delayedPostGuardDoesNotBlockBehindAnEthereumNetworkCall()throws Exception{
        EthereumTransactions journal=new EthereumTransactions(new Memory());
        EthereumTransactions.FundingSnapshot view=journal.fundingSnapshot(1,KEY.getAddress());
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Rpc rpc=new Rpc(){@Override public String broadcast(String raw)throws Exception{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return super.broadcast(raw);}};
        ExecutorService threads=Executors.newFixedThreadPool(2);
        try{
            Future<String> sending=threads.submit(()->send(journal,rpc,TO,null));assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertFalse(threads.submit(view::unchanged).get(1,TimeUnit.SECONDS));release.countDown();sending.get(5,TimeUnit.SECONDS);
        }finally{release.countDown();threads.shutdownNow();}
    }
    @Test public void sendInvalidatesFundingBeforeItsFirstNetworkReadOrDiskWrite()throws Exception{
        Memory store=new Memory();EthereumTransactions journal=new EthereumTransactions(store);
        EthereumTransactions.FundingSnapshot view=journal.fundingSnapshot(1,KEY.getAddress());
        Rpc rpc=new Rpc(){@Override public BigInteger chain(){assertFalse(view.unchanged());assertEquals(0,store.writes);return super.chain();}};
        send(journal,rpc,TO,null);
    }

}
