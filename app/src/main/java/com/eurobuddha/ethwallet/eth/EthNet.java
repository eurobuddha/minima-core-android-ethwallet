package com.eurobuddha.ethwallet.eth;

/**
 * Ethereum network config — chain id, default RPC, explorer. Mainnet only.
 *
 * The live ERC20 list is {@link TokenStore} (user-editable, persisted to prefs); this enum
 * deliberately carries no token table. It previously also held the bridge HTLC vault address and a
 * fixed USDT entry, both inherited from the swap app and unused here — a stale second source of
 * truth for the token list is exactly the kind of thing that gets read by mistake.
 */
public enum EthNet {

    MAINNET("Ethereum", 1L,
            "https://ethereum-rpc.publicnode.com",
            "https://etherscan.io/tx/");

    public final String label;
    public final long chainId;
    public final String defaultRpc;
    public final String explorerTx;  // append a tx hash

    EthNet(String label, long chainId, String defaultRpc, String explorerTx) {
        this.label = label; this.chainId = chainId; this.defaultRpc = defaultRpc;
        this.explorerTx = explorerTx;
    }

    /** An ERC20 the user tracks: symbol, contract address, decimals. */
    public static final class Token {
        public final String symbol, address;
        public final int decimals;
        public Token(String symbol, String address, int decimals) {
            this.symbol = symbol; this.address = address; this.decimals = decimals;
        }
    }
}
