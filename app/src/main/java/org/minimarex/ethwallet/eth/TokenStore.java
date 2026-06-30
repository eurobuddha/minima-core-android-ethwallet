package org.minimarex.ethwallet.eth;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint8;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Runtime-editable ERC20 token list, persisted to prefs. Seeded with common mainnet tokens. */
public final class TokenStore {

    private static final String KEY = "tokens";
    private final SharedPreferences prefs;
    private final List<EthNet.Token> tokens = new ArrayList<>();

    public TokenStore(SharedPreferences prefs) { this.prefs = prefs; load(); }

    private void load() {
        tokens.clear();
        String raw = prefs.getString(KEY, null);
        if (raw == null) { seedDefaults(); return; }
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                tokens.add(new EthNet.Token(o.getString("s"), o.getString("a"), o.getInt("d")));
            }
            if (tokens.isEmpty()) seedDefaults();
        } catch (Exception e) { seedDefaults(); }
    }

    private void seedDefaults() {
        tokens.clear();
        tokens.add(new EthNet.Token("USDT", "0xdac17f958d2ee523a2206206994597c13d831ec7", 6));
        tokens.add(new EthNet.Token("USDC", "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", 6));
        tokens.add(new EthNet.Token("DAI",  "0x6b175474e89094c44da98b954eedeac495271d0f", 18));
        tokens.add(new EthNet.Token("WETH", "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2", 18));
        save();
    }

    private void save() {
        JSONArray a = new JSONArray();
        for (EthNet.Token t : tokens) {
            try { a.put(new JSONObject().put("s", t.symbol).put("a", t.address).put("d", t.decimals)); } catch (Exception ignore) {}
        }
        prefs.edit().putString(KEY, a.toString()).apply();
    }

    public List<EthNet.Token> tokens() { return new ArrayList<>(tokens); }

    public boolean has(String address) {
        String a = strip(address);
        for (EthNet.Token t : tokens) if (strip(t.address).equalsIgnoreCase(a)) return true;
        return false;
    }

    public void add(EthNet.Token t) { if (!has(t.address)) { tokens.add(t); save(); } }

    public void remove(String address) {
        String a = strip(address);
        tokens.removeIf(t -> strip(t.address).equalsIgnoreCase(a));
        save();
    }

    private static String strip(String a) { return a == null ? "" : (a.startsWith("0x") || a.startsWith("0X") ? a.substring(2) : a); }

    /** Read symbol()+decimals() for a contract via eth_call — for add-by-address. Blocking; off-main-thread. */
    public static EthNet.Token fetch(EthRpc rpc, String address) throws IOException {
        int decimals = fetchDecimals(rpc, address);   // throws if not an ERC20
        String symbol = fetchSymbol(rpc, address);
        return new EthNet.Token(symbol, address, decimals);
    }

    private static int fetchDecimals(EthRpc rpc, String addr) throws IOException {
        Function fn = new Function("decimals", Collections.emptyList(),
                Collections.singletonList(new TypeReference<Uint8>() {}));
        String ret = rpc.ethCall(addr, FunctionEncoder.encode(fn));
        List<Type> out = FunctionReturnDecoder.decode(ret, fn.getOutputParameters());
        if (out.isEmpty() || out.get(0).getValue() == null) throw new IOException("no decimals() — not an ERC20?");
        return ((BigInteger) out.get(0).getValue()).intValue();
    }

    private static String fetchSymbol(EthRpc rpc, String addr) {
        try {
            Function fn = new Function("symbol", Collections.emptyList(),
                    Collections.singletonList(new TypeReference<Utf8String>() {}));
            String ret = rpc.ethCall(addr, FunctionEncoder.encode(fn));
            List<Type> out = FunctionReturnDecoder.decode(ret, fn.getOutputParameters());
            if (!out.isEmpty() && out.get(0).getValue() != null) {
                String s = out.get(0).getValue().toString().trim();
                if (!s.isEmpty()) return s;
            }
        } catch (Exception ignore) { /* non-standard symbol() (e.g. bytes32) → fall back */ }
        return addr.length() > 6 ? addr.substring(2, 6).toUpperCase() : "TOKEN";
    }
}
