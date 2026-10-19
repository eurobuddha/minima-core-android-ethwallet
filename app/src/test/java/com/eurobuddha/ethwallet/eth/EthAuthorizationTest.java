package com.eurobuddha.ethwallet.eth;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.mockito.MockedStatic;
import org.web3j.crypto.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Reused AtomiX authorization regressions. Synthetic key and mocked RPC only. */
public class EthAuthorizationTest {
    private static final Credentials FIXTURE=Credentials.create("01");
    private static final String TO="0x"+"12".repeat(20),HASH="0x"+"34".repeat(32);
    private EthRpc rpc;
    @Before public void setup()throws Exception {
        rpc=mock(EthRpc.class);when(rpc.getTransactionCount(anyString())).thenReturn(BigInteger.valueOf(4));
        when(rpc.sendRawTransaction(anyString())).thenReturn(HASH);
    }
    private String send(BooleanSupplier guard)throws Exception {
        return EthTx.send(rpc,FIXTURE,1L,TO,"0x",BigInteger.ONE,BigInteger.valueOf(21000),EthTx.GWEI,guard);
    }
    @Test public void falseMissingOrThrowingGuardCannotReachRpc()throws Exception {
        for(BooleanSupplier guard:new BooleanSupplier[]{null,()->false,()->{throw new IllegalStateException("fixture");}})
            assertThrows(IllegalStateException.class,()->send(guard));
        verifyNoInteractions(rpc);
    }
    @Test public void nonceReadInvalidationCannotSignOrBroadcast()throws Exception {
        AtomicBoolean allowed=new AtomicBoolean(true);
        when(rpc.getTransactionCount(anyString())).thenAnswer(i->{allowed.set(false);return BigInteger.valueOf(4);});
        try(MockedStatic<TransactionEncoder> encoder=mockStatic(TransactionEncoder.class)) {
            assertThrows(IllegalStateException.class,()->send(allowed::get));encoder.verifyNoInteractions();
        }
        verify(rpc,never()).sendRawTransaction(anyString());
    }
    @Test public void invalidationAfterSigningCannotBroadcast()throws Exception {
        AtomicBoolean allowed=new AtomicBoolean(true);
        try(MockedStatic<TransactionEncoder> encoder=mockStatic(TransactionEncoder.class,CALLS_REAL_METHODS)) {
            encoder.when(()->TransactionEncoder.signMessage(any(RawTransaction.class),eq(1L),eq(FIXTURE)))
                .thenAnswer(i->{byte[] bytes=(byte[])i.callRealMethod();allowed.set(false);return bytes;});
            assertThrows(IllegalStateException.class,()->send(allowed::get));
        }
        verify(rpc,never()).sendRawTransaction(anyString());
    }
    @Test public void dispatchedReceiptSurvivesSubsequentAccountChange()throws Exception {
        AtomicBoolean allowed=new AtomicBoolean(true);
        when(rpc.sendRawTransaction(anyString())).thenAnswer(i->{allowed.set(false);return HASH;});
        assertEquals(HASH,send(allowed::get));
    }
}
