package com.eurobuddha.ethwallet.eth;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import com.eurobuddha.minimaapi.ethereum.EthereumTransactions;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Two standalone RPC clients, real private SQLite, synthetic key, mocked network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, application=Application.class)
public class WalletJournalPersistenceTest {
    static final Credentials KEY=Credentials.create("01");
    static final String TO="0x"+"12".repeat(20),OTHER="0x"+"34".repeat(20);
    static final BigInteger GAS=BigInteger.valueOf(21000),PRICE=BigInteger.valueOf(1_000_000_000L);
    final List<String> sent=new ArrayList<>();final Map<String,String> known=new HashMap<>();boolean fail;
    com.eurobuddha.ethwallet.eth.EthRpc second;
    com.eurobuddha.ethwallet.eth.EthRpc first;
    @Before public void setup()throws Exception {
        reset();RuntimeEnvironment.getApplication().deleteDatabase("pandamonium_eth_submissions.db");
        EthereumTransactions.initialize(RuntimeEnvironment.getApplication());
        second=mock(com.eurobuddha.ethwallet.eth.EthRpc.class);first=mock(com.eurobuddha.ethwallet.eth.EthRpc.class);
        when(second.getTransactionCount(anyString())).thenReturn(BigInteger.valueOf(4));when(first.getTransactionCount(anyString())).thenReturn(BigInteger.valueOf(4));
        when(second.call(eq("eth_chainId"),any())).thenReturn("0x1");when(first.call(eq("eth_chainId"),any())).thenReturn("0x1");
        when(second.call(eq("eth_getTransactionByHash"),any())).thenAnswer(i->lookup(((JSONArray)i.getArgument(1)).getString(0)));
        when(first.call(eq("eth_getTransactionByHash"),any())).thenAnswer(i->lookup(((JSONArray)i.getArgument(1)).getString(0)));
        when(second.sendRawTransaction(anyString())).thenAnswer(i->broadcast(i.getArgument(0)));
        when(first.sendRawTransaction(anyString())).thenAnswer(i->broadcast(i.getArgument(0)));
    }
    @After public void cleanup()throws Exception {reset();RuntimeEnvironment.getApplication().deleteDatabase("pandamonium_eth_submissions.db");}
    static void reset()throws Exception {
        Field f=EthereumTransactions.class.getDeclaredField("shared");f.setAccessible(true);Object engine=f.get(null);
        if(engine!=null){Field st=EthereumTransactions.class.getDeclaredField("store");st.setAccessible(true);Object store=st.get(engine);if(store instanceof android.database.sqlite.SQLiteOpenHelper)((android.database.sqlite.SQLiteOpenHelper)store).close();}
        f.set(null,null);
    }
    String broadcast(String raw)throws Exception {sent.add(raw);if(fail)throw new java.io.IOException("fixture lost response");String hash=Hash.sha3(raw);known.put(hash,raw);return hash;}
    JSONObject lookup(String hash)throws Exception {
        String raw=known.get(hash);if(raw==null)return null;SignedRawTransaction tx=(SignedRawTransaction)TransactionDecoder.decode(raw);
        return new JSONObject().put("hash",hash).put("from",tx.getFrom()).put("to",tx.getTo()).put("nonce",hex(tx.getNonce()))
            .put("value",hex(tx.getValue())).put("gas",hex(tx.getGasLimit())).put("gasPrice",hex(tx.getGasPrice())).put("input",Numeric.prependHexPrefix(tx.getData())).put("chainId","0x1");
    }
    static String hex(BigInteger value){return "0x"+value.toString(16);}
    String second(String to)throws Exception{return com.eurobuddha.ethwallet.eth.EthTx.send(second,KEY,1,to,"0x",BigInteger.ONE,GAS,PRICE,()->true);}
    String first(String to)throws Exception{return com.eurobuddha.ethwallet.eth.EthTx.send(first,KEY,1,to,"0x",BigInteger.ONE,GAS,PRICE,()->true);}
    @Test public void actualClientsShareNonceAcrossSqliteReopen()throws Exception {
        first(TO);second(OTHER);reset();EthereumTransactions.initialize(RuntimeEnvironment.getApplication());first(OTHER);
        for(int i=0;i<3;i++)assertEquals(BigInteger.valueOf(4+i),TransactionDecoder.decode(sent.get(i)).getNonce());
    }
    @Test public void lostResponseRecoveredByAnotherConnectionAfterRestartUsesIdenticalBytes()throws Exception {
        fail=true;EthereumTransactions.Uncertain error=assertThrows(EthereumTransactions.Uncertain.class,()->first(TO));
        reset();EthereumTransactions.initialize(RuntimeEnvironment.getApplication());fail=false;
        assertEquals(error.hash,second(TO));assertEquals(sent.get(0),sent.get(1));
    }
    @Test public void corruptedSqliteJournalIsPreservedAndNeitherClientCanBroadcast()throws Exception {
        SQLiteDatabase db=SQLiteDatabase.openDatabase(RuntimeEnvironment.getApplication().getDatabasePath("pandamonium_eth_submissions.db").getPath(),null,SQLiteDatabase.CREATE_IF_NECESSARY);
        db.execSQL("CREATE TABLE submissions(account TEXT PRIMARY KEY NOT NULL,state TEXT NOT NULL)");db.setVersion(1);
        db.execSQL("INSERT INTO submissions VALUES(?,?)",new Object[]{"1:"+KEY.getAddress(),"[] trailing"});db.close();
        assertThrows(Exception.class,()->first(TO));assertThrows(Exception.class,()->second(OTHER));assertTrue(sent.isEmpty());
        db=SQLiteDatabase.openDatabase(RuntimeEnvironment.getApplication().getDatabasePath("pandamonium_eth_submissions.db").getPath(),null,SQLiteDatabase.OPEN_READONLY);
        try(android.database.Cursor c=db.rawQuery("SELECT state FROM submissions",null)){assertTrue(c.moveToFirst());assertEquals("[] trailing",c.getString(0));}finally{db.close();}
    }
    @Test public void activityStartupInitializesTheJournalBeforeSending()throws Exception {
        reset();
        org.robolectric.android.controller.ActivityController<com.eurobuddha.ethwallet.MainActivity> controller=
            Robolectric.buildActivity(com.eurobuddha.ethwallet.MainActivity.class).create();
        try {assertNotNull(EthereumTransactions.shared());}
        finally {android.app.Dialog dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog();if(dialog!=null)dialog.dismiss();controller.destroy();}
    }
}
