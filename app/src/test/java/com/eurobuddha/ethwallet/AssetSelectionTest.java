package com.eurobuddha.ethwallet;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.ethwallet.eth.*;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Mocked RPC and signer only: no network, node access or real transfers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class AssetSelectionTest {
    private MainActivity activity;
    private EthRpc rpc;
    private TokenStore tokens;
    private final List<Runnable> jobs=new ArrayList<>();
    private MockedStatic<IconLoader> icons;
    private static final String TO="0x"+"4".repeat(40);
    private static final EthNet.Token FIRST=new EthNet.Token("DUP","0x"+"1".repeat(40),6);
    private static final EthNet.Token SECOND=new EthNet.Token("DUP","0x"+"2".repeat(40),8);
    private static final EthNet.Token NAMED_ETH=new EthNet.Token("ETH","0x"+"3".repeat(40),6);
    private Field field(String name)throws Exception {Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private void set(String name,Object value)throws Exception {field(name).set(activity,value);}
    private Object call(String name,Class<?>[] types,Object...args)throws Exception {Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}
    private void job(){assertFalse(jobs.isEmpty());jobs.remove(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private AlertDialog dialog(){Shadows.shadowOf(Looper.getMainLooper()).idle();return (AlertDialog)ShadowDialog.getLatestDialog();}
    private static TextView find(View v,String text){if(v instanceof TextView&&text.equals(((TextView)v).getText().toString()))return (TextView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView out=find(((ViewGroup)v).getChildAt(i),text);if(out!=null)return out;}return null;}
    private static List<EditText> inputs(View v){List<EditText> out=new ArrayList<>();if(v instanceof EditText)out.add((EditText)v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)out.addAll(inputs(((ViewGroup)v).getChildAt(i)));return out;}
    @Before public void setup()throws Exception {
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.Theme_EthWallet);
        ((ExecutorService)field("io").get(activity)).shutdownNow();ExecutorService worker=mock(ExecutorService.class);set("io",worker);
        doAnswer(i->{jobs.add(i.getArgument(0));return null;}).when(worker).execute(any(Runnable.class));
        android.content.SharedPreferences prefs=activity.getSharedPreferences("asset-tests",Context.MODE_PRIVATE);prefs.edit().clear().commit();set("prefs",prefs);
        tokens=mock(TokenStore.class);when(tokens.tokens()).thenReturn(Arrays.asList(FIRST,SECOND,NAMED_ETH));when(tokens.has(anyString())).thenReturn(true);set("tokens",tokens);
        rpc=mock(EthRpc.class);set("rpc",rpc);set("net",EthNet.MAINNET);
        when(rpc.estimateGas(anyString(),anyString(),nullable(String.class),any())).thenReturn(BigInteger.valueOf(21000));when(rpc.gasPrice()).thenReturn(BigInteger.valueOf(1_000_000_000));
        ((EthWallet)field("wallet").get(activity)).importKey("1".repeat(64));
        set("root",new LinearLayout(activity));set("scroller",new ScrollView(activity));set("ethAddr",((EthWallet)field("wallet").get(activity)).address());
        icons=mockStatic(IconLoader.class);
    }
    @After public void cleanup()throws Exception {android.app.Dialog d=ShadowDialog.getLatestDialog();if(d!=null)d.dismiss();icons.close();((ExecutorService)field("io").get(activity)).shutdownNow();}
    @SuppressWarnings("unchecked")
    @Test public void duplicateSymbolsKeepSeparateBalancesIncludingTokenNamedEth()throws Exception {
        Map<String,String> balances=(Map<String,String>)field("tokenBals").get(activity);balances.put(FIRST.address,"11");balances.put(SECOND.address,"22");balances.put(NAMED_ETH.address,"33");set("ethBal","44");
        for(Object[] row:new Object[][]{{FIRST,"11"},{SECOND,"22"},{NAMED_ETH,"33"},{null,"44"}})
            assertEquals(row[1],call("balOf",new Class<?>[]{EthNet.Token.class},row[0]));
        call("sendDialog",new Class<?>[]{EthNet.Token.class},SECOND);
        assertNotNull(find(dialog().getWindow().getDecorView(),"Token: DUP · "+SECOND.address+"  (tap to change · balance 22)"));
    }
    @Test public void confirmationAndMockedSendUseChosenContractEvenWhenSymbolIsEth()throws Exception {
        for(EthNet.Token token:new EthNet.Token[]{SECOND,NAMED_ETH}) {
            call("confirmSend",new Class<?>[]{EthNet.Token.class,String.class,String.class},token,TO,"1");job();
            verify(rpc).estimateGas(anyString(),eq(token.address),startsWith("0xa9059cbb"),eq(BigInteger.ZERO));
            AlertDialog confirmation=dialog();assertNotNull(find(confirmation.getWindow().getDecorView(),token.address));
            try(MockedStatic<EthTx> send=mockStatic(EthTx.class,CALLS_REAL_METHODS)) {
                send.when(()->EthTx.send(eq(rpc),any(),eq(1L),anyString(),anyString(),any(),any(),any(),any())).thenReturn("0x"+"ab".repeat(32));
                confirmation.getButton(-1).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();job();
                send.verify(()->EthTx.send(eq(rpc),any(),eq(1L),eq(token.address),startsWith("0xa9059cbb"),eq(BigInteger.ZERO),any(),any(),any()));
            }
            dialog().dismiss();jobs.clear();
        }
    }
    @Test public void removingAnAssetCannotSilentlyChooseNativeEth()throws Exception {
        when(tokens.tokens()).thenReturn(Collections.emptyList());when(tokens.has(SECOND.address)).thenReturn(false);
        call("sendDialog",new Class<?>[]{EthNet.Token.class},SECOND);assertTrue(jobs.isEmpty());assertFalse((Boolean)field("modalOpen").get(activity));
        call("confirmSend",new Class<?>[]{EthNet.Token.class,String.class,String.class},SECOND,TO,"1");assertTrue(jobs.isEmpty());verifyNoInteractions(rpc);
        assertEquals("Token DUP is no longer in your list",ShadowToast.getTextOfLatestToast());
    }
    @Test public void delayedNativeMaxCannotOverwriteAmountAfterTokenSelection()throws Exception {
        when(rpc.getBalance(anyString())).thenReturn(BigInteger.TEN.pow(18));
        call("sendDialog",new Class<?>[]{EthNet.Token.class},(Object)null);AlertDialog send=dialog();View content=send.getWindow().getDecorView();
        EditText amount=inputs(content).get(1);find(content,"MAX").performClick();assertEquals(1,jobs.size());
        find(content,"Token: ETH · Ethereum  (tap to change · balance —)").performClick();AlertDialog picker=dialog();
        picker.getListView().performItemClick(null,3,3);picker.dismiss();amount.setText("7");job();assertEquals("7",amount.getText().toString());
    }
    @Test public void refreshDoesNotOverwriteBalancesOfSameSymbolContracts()throws Exception {
        when(rpc.getBalance(anyString())).thenReturn(BigInteger.TEN.pow(18).multiply(BigInteger.valueOf(44)));
        int i=1;
        for(EthNet.Token token:new EthNet.Token[]{FIRST,SECOND,NAMED_ETH}) {
            BigInteger amount=BigInteger.TEN.pow(token.decimals).multiply(BigInteger.valueOf(11L*i++));
            when(rpc.ethCall(eq(token.address),anyString())).thenReturn("0x"+String.format("%064x",amount));
        }
        call("refresh",new Class<?>[]{});job();
        for(Object[] row:new Object[][]{{FIRST,"11"},{SECOND,"22"},{NAMED_ETH,"33"},{null,"44"}})
            assertEquals(row[1],call("balOf",new Class<?>[]{EthNet.Token.class},row[0]));
    }
    @Test public void nativeEthSelectionKeepsNativeValueAndRecipient()throws Exception {
        call("confirmSend",new Class<?>[]{EthNet.Token.class,String.class,String.class},null,TO,"1");job();
        verify(rpc).estimateGas(anyString(),eq(TO),isNull(),eq(BigInteger.TEN.pow(18)));
    }

    @Test public void accountChangeWhileEstimatingCannotOpenConfirmation()throws Exception {
        when(rpc.estimateGas(anyString(),anyString(),nullable(String.class),any())).thenAnswer(i->{
            ((EthWallet)field("wallet").get(activity)).importKey("2".repeat(64));return BigInteger.valueOf(21000);
        });
        call("confirmSend",new Class<?>[]{EthNet.Token.class,String.class,String.class},SECOND,TO,"1");job();
        assertNull(ShadowDialog.getLatestDialog());
        assertEquals("Wallet session changed. Review again.",ShadowToast.getTextOfLatestToast());
        verify(rpc,never()).sendRawTransaction(anyString());
    }
    @Test public void changedConnectionCannotUsePreviouslyReviewedTransfer()throws Exception {
        call("confirmSend",new Class<?>[]{EthNet.Token.class,String.class,String.class},SECOND,TO,"1");job();
        AlertDialog confirmation=dialog();set("rpc",mock(EthRpc.class));confirmation.getButton(-1).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(jobs.isEmpty());verify(rpc,never()).sendRawTransaction(anyString());
        assertEquals("Wallet session changed. Review again.",ShadowToast.getTextOfLatestToast());
    }
    @Test public void queuedSendIsCancelledBeforeNonceReadWhenAccountChanges()throws Exception {
        call("confirmSend",new Class<?>[]{EthNet.Token.class,String.class,String.class},SECOND,TO,"1");job();
        dialog().getButton(-1).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,jobs.size());
        ((EthWallet)field("wallet").get(activity)).clear();job();
        verify(rpc,never()).getTransactionCount(anyString());verify(rpc,never()).sendRawTransaction(anyString());
    }
    @Test public void oldBalanceResponseCannotPaintReplacementAccount()throws Exception {
        when(rpc.getBalance(anyString())).thenAnswer(i->{((EthWallet)field("wallet").get(activity)).importKey("2".repeat(64));return BigInteger.TEN.pow(18);});
        call("refresh",new Class<?>[]{});job();assertEquals("—",field("ethBal").get(activity));
    }

}
