package com.eurobuddha.ethwallet.eth;

import java.io.IOException;
import java.math.BigInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.web3j.crypto.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Synthetic key, mocked RPC only. Exercise parsing through the real signing entry point. */
public class EthNonceTest {
    static final Credentials KEY = Credentials.create("01");
    static final String ADDRESS = "0x" + "12".repeat(20), HASH = "0x" + "34".repeat(32);
    static final BigInteger MAX_COUNT = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    EthRpc rpc;
    @Before public void setup() throws Exception {

        rpc = spy(new EthRpc("https://fixture.invalid"));
        JournalFixture.install();JournalFixture.wire(rpc);

    }
    String send() throws Exception {
        return EthTx.send(rpc, KEY, 1L, ADDRESS, "0x", BigInteger.ONE, BigInteger.valueOf(21000), EthTx.GWEI);
    }
    @Test public void malformedOrMissingNonceCannotSignOrBroadcast() throws Exception {
        Object[] bad = {null, JSONObject.NULL, "", "0x", "0x00", "0x01", "1", 1, true,
                new JSONObject(), new JSONArray(), " 0x1", "0x1 ", "0x-1", "-0x1", "0x+1", "0X1", "0xg",
                "0x10000000000000000", "0x" + "f".repeat(4096)};
        try (MockedStatic<TransactionEncoder> encoder = mockStatic(TransactionEncoder.class)) {
            for (Object value : bad) {
                doReturn(value).when(rpc).call(eq("eth_getTransactionCount"), any(JSONArray.class));
                assertThrows(IOException.class, this::send);
            }
            encoder.verifyNoInteractions();
        }
        verify(rpc, never()).sendRawTransaction(anyString());
    }
    @Test public void explicitZeroIsValidAndUsesPendingAccount() throws Exception {
        doReturn("0x0").when(rpc).call(eq("eth_getTransactionCount"), any(JSONArray.class));
        doAnswer(inv -> {
            assertEquals(BigInteger.ZERO, TransactionDecoder.decode(inv.getArgument(0)).getNonce());
            return JournalFixture.accept(inv.getArgument(0));
        }).when(rpc).sendRawTransaction(anyString());
        assertEquals( send(),JournalFixture.lastHash);
        verify(rpc).call(eq("eth_getTransactionCount"), argThat(params ->
                params.length() == 2 && KEY.getAddress().equals(params.optString(0)) && "pending".equals(params.optString(1))));
    }
    @Test public void unsignedNonceIsNeverTruncated() throws Exception {
        BigInteger nonce = BigInteger.ONE.shiftLeft(63).add(BigInteger.valueOf(7));
        doReturn("0x" + nonce.toString(16)).when(rpc).call(eq("eth_getTransactionCount"), any(JSONArray.class));
        doAnswer(inv -> {
            assertEquals(nonce, TransactionDecoder.decode(inv.getArgument(0)).getNonce());
            return JournalFixture.accept(inv.getArgument(0));
        }).when(rpc).sendRawTransaction(anyString());
        assertEquals( send(),JournalFixture.lastHash);
    }
    @Test public void exhaustedAccountIsReadableButCannotSign() throws Exception {
        doReturn("0xffffffffffffffff").when(rpc).call(eq("eth_getTransactionCount"), any(JSONArray.class));
        assertEquals(MAX_COUNT, rpc.getTransactionCount(KEY.getAddress()));
        try (MockedStatic<TransactionEncoder> encoder = mockStatic(TransactionEncoder.class)) {
            assertThrows(IOException.class, this::send);
            encoder.verifyNoInteractions();
        }
        verify(rpc, never()).sendRawTransaction(anyString());
    }
    @Test public void malformedPrimaryUsesFallbackInsteadOfReturningZero() throws Exception {
        // Reuse Salon GithubProfileUploadTest's mocked connection boundary: no sockets or live RPC.
        try (MockedStatic<EthRpc> transport = mockStatic(EthRpc.class, CALLS_REAL_METHODS)) {
            for (String body : new String[]{"{}", "{\"result\":null}", "{\"result\":0}", "{\"result\":\"0x\"}", "{\"result\":\"0x00\"}"}) {
                java.net.HttpURLConnection primary = response(body);
                java.net.HttpURLConnection fallback = response("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x4\"}");
                java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
                transport.when(() -> EthRpc.openConnection(anyString())).thenAnswer(inv -> {
                    int attempt = calls.getAndIncrement();
                    if (attempt > 1) throw new AssertionError("Unexpected network attempt");
                    return attempt == 0 ? primary : fallback;
                });
                assertEquals(BigInteger.valueOf(4), new EthRpc("https://fixture.invalid").getTransactionCount(KEY.getAddress()));
                assertEquals(2, calls.get());
                verify(primary).disconnect(); verify(fallback).disconnect();
            }
        }
    }
    private static java.net.HttpURLConnection response(String body) throws Exception {
        java.net.HttpURLConnection c = mock(java.net.HttpURLConnection.class);
        when(c.getResponseCode()).thenReturn(200);
        when(c.getOutputStream()).thenReturn(new java.io.ByteArrayOutputStream());
        when(c.getInputStream()).thenReturn(new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return c;
    }
    @Test public void paddedContractDataKeepsExistingMeaning() throws Exception {
        assertEquals(BigInteger.ONE, EthRpc.hexToBig("0x" + "0".repeat(63) + "1"));
        assertEquals(BigInteger.valueOf(255), EthRpc.transactionCount("0xfF"));
    }


    @Test public void invalidRpcEnvelopeOrHttpStatusCannotSupplyANonce() throws Exception {
        String good="{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x4\"}";
        String[] bodies={good.replace("2.0","1.0"),good.replace("\"id\":1","\"id\":2"),good.replace("\"id\":1","\"id\":\"1\""),
            good+" trailing",good.replace("\"result\"", "\"missing\""),good.replace("}",",\"error\":null}"),good};
        for(int index=0;index<bodies.length;index++) {
            try(MockedStatic<EthRpc> transport=mockStatic(EthRpc.class,CALLS_REAL_METHODS)) {
                java.net.HttpURLConnection primary=response(bodies[index]),fallback=response(good);
                if(index==bodies.length-1)when(primary.getResponseCode()).thenReturn(302);
                java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
                transport.when(()->EthRpc.openConnection(anyString())).thenAnswer(i->{int count=calls.getAndIncrement();if(count>1)throw new AssertionError("Unexpected fallback");return count==0?primary:fallback;});
                assertEquals(BigInteger.valueOf(4),new EthRpc("https://fixture.invalid").getTransactionCount(KEY.getAddress()));
                assertEquals(2,calls.get());verify(primary).setInstanceFollowRedirects(false);verify(primary).disconnect();
            }
        }
    }
    @Test public void oversizedRpcBodyIsClosedAndFallsBackWithoutParsing() throws Exception {
        try(MockedStatic<EthRpc> transport=mockStatic(EthRpc.class,CALLS_REAL_METHODS)) {
            java.net.HttpURLConnection primary=response(""),fallback=response("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x4\"}");
            java.io.InputStream body=spy(new java.io.ByteArrayInputStream(new byte[4*1024*1024+1]));when(primary.getInputStream()).thenReturn(body);
            java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
            transport.when(()->EthRpc.openConnection(anyString())).thenAnswer(i->{int count=calls.getAndIncrement();if(count>1)throw new AssertionError("Unexpected fallback");return count==0?primary:fallback;});
            assertEquals(BigInteger.valueOf(4),new EthRpc("https://fixture.invalid").getTransactionCount(KEY.getAddress()));verify(body).close();verify(primary).disconnect();
        }
    }
}
