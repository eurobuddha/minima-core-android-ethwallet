package com.eurobuddha.ethwallet.eth;
import com.eurobuddha.minimaapi.ethereum.EthereumTransactions;
import org.json.*;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
/** Disposable public fixtures; never contacts an RPC or uses an owner's key. */
public final class JournalFixture {
    static Map<String,String> saved=new HashMap<>(),raws=new HashMap<>();
    public static String lastHash;
    public static void install() throws Exception {
        saved=new HashMap<>();raws=new HashMap<>();lastHash="";
        EthereumTransactions engine=new EthereumTransactions(new EthereumTransactions.Store(){
            public String read(String key){return saved.getOrDefault(key,"[]");}
            public void write(String key,String value){saved.put(key,value);}
        });
        java.lang.reflect.Field field=EthereumTransactions.class.getDeclaredField("shared");field.setAccessible(true);field.set(null,engine);
    }
    public static void wire(EthRpc rpc) throws Exception {
        doReturn("0x1").when(rpc).call(eq("eth_chainId"),any(JSONArray.class));
        doReturn(null).when(rpc).call(eq("eth_getTransactionReceipt"),any(JSONArray.class));
        doAnswer(i->transaction(((JSONArray)i.getArgument(1)).optString(0))).when(rpc).call(eq("eth_getTransactionByHash"),any(JSONArray.class));
        doAnswer(i->accept(i.getArgument(0))).when(rpc).sendRawTransaction(anyString());
    }
    public static String accept(String raw){lastHash=Hash.sha3(raw);raws.put(lastHash,raw);return lastHash;}
    public static JSONObject transaction(String hash)throws Exception {
        String raw=raws.get(hash);if(raw==null)return null;SignedRawTransaction tx=(SignedRawTransaction)TransactionDecoder.decode(raw);
        return new JSONObject().put("hash",hash).put("from",tx.getFrom()).put("to",tx.getTo())
            .put("nonce","0x"+tx.getNonce().toString(16)).put("value","0x"+tx.getValue().toString(16))
            .put("gas","0x"+tx.getGasLimit().toString(16)).put("gasPrice","0x"+tx.getGasPrice().toString(16))
            .put("input",Numeric.prependHexPrefix(tx.getData())).put("chainId","0x"+Long.toHexString(tx.getChainId()));
    }
}
