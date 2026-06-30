package org.minimarex.ethwallet.eth;

/**
 * Ethereum network config for the swap — addresses, decimals, default RPC. Mainnet only; the single
 * supported pair is native MINIMA ↔ USDT (the bridge HTLC vault is ERC20-only). Addresses are the
 * canonical ones from the upstream bridge MiniDapp (dapp/js/htlcvars.js), so swaps interoperate with it.
 */
public enum EthNet {

    MAINNET("Ethereum", 1L,
            "https://ethereum-rpc.publicnode.com",
            "0x67376c3bf3b5a336b14398920cfbc292013718ea",
            "https://etherscan.io/tx/",
            new Token[]{
                    new Token("USDT", "0xdac17f958d2ee523a2206206994597c13d831ec7", 6),
            });

    public final String label;
    public final long chainId;
    public final String defaultRpc;
    public final String htlc;        // shared ERC20 HTLC vault
    public final String explorerTx;  // append a tx hash
    public final Token[] tokens;

    EthNet(String label, long chainId, String defaultRpc, String htlc, String explorerTx, Token[] tokens) {
        this.label = label; this.chainId = chainId; this.defaultRpc = defaultRpc;
        this.htlc = htlc; this.explorerTx = explorerTx; this.tokens = tokens;
    }

    public Token token(String symbol) {
        for (Token t : tokens) if (t.symbol.equalsIgnoreCase(symbol)) return t;
        return null;
    }

    /** Resolve a token by its ERC20 contract address (case-insensitive, 0x-tolerant). */
    public Token tokenByAddress(String address) {
        if (address == null) return null;
        String a = address.startsWith("0x") || address.startsWith("0X") ? address.substring(2) : address;
        for (Token t : tokens) {
            String ta = t.address.startsWith("0x") ? t.address.substring(2) : t.address;
            if (ta.equalsIgnoreCase(a)) return t;
        }
        return null;
    }

    /** Mainnet is the only network now; kept for call-site compatibility. */
    public static EthNet from(String name) {
        return MAINNET;
    }

    public static final class Token {
        public final String symbol, address;
        public final int decimals;
        public Token(String symbol, String address, int decimals) {
            this.symbol = symbol; this.address = address; this.decimals = decimals;
        }
    }
}
