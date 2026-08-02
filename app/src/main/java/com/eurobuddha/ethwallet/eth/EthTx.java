package com.eurobuddha.ethwallet.eth;

import org.json.JSONArray;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

import java.math.BigInteger;

/**
 * Signs + sends a legacy (EIP-155) Ethereum transaction via our JSON-RPC client. We use legacy
 * gasPrice transactions (accepted on Ethereum mainnet) so we need no Infura gas API / key.
 * Blocking — call off the main thread.
 */
public final class EthTx {

    public static final BigInteger GWEI = BigInteger.valueOf(1_000_000_000L);

    /**
     * Gas safety rails. Neither the gas price nor the gas limit is chosen by this app — both come
     * back from an RPC endpoint, including the public fallbacks. A hostile or compromised endpoint
     * that inflates either one drains the balance as FEES without ever touching the private key,
     * so the values are bounded before anything is signed.
     *
     * WARN_* only colours a warning into the confirm dialog (mainnet has genuinely spiked past
     * 500 gwei, so blocking there would strand a legitimate urgent send). MAX_* is the hard refusal
     * and sits far above any real network condition — crossing it means something is lying.
     */
    public static final BigInteger WARN_GAS_PRICE = BigInteger.valueOf(500).multiply(GWEI);
    public static final BigInteger MAX_GAS_PRICE  = BigInteger.valueOf(1_500).multiply(GWEI);
    public static final BigInteger WARN_GAS_LIMIT = BigInteger.valueOf(400_000);
    public static final BigInteger MAX_GAS_LIMIT  = BigInteger.valueOf(1_000_000);

    private static final BigInteger FALLBACK_GAS_PRICE = BigInteger.valueOf(2_000_000_000L); // 2 gwei

    /** Gas price in gwei, for messages. */
    public static String gwei(BigInteger wei) {
        return new java.math.BigDecimal(wei).divide(new java.math.BigDecimal(GWEI))
                .setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** Explicit gas price — the caller picks the fee tier (low/medium/high). */
    public static String send(EthRpc rpc, Credentials creds, long chainId,
                              String to, String data, BigInteger value, BigInteger gasLimit, BigInteger gasPrice) throws Exception {
        if (gasPrice == null || gasPrice.signum() <= 0) gasPrice = FALLBACK_GAS_PRICE;

        // Last gate before the key touches the transaction — every send in the app funnels through here.
        if (gasPrice.compareTo(MAX_GAS_PRICE) > 0) {
            throw new Exception("Refusing to sign: gas price " + gwei(gasPrice)
                    + " gwei exceeds the " + gwei(MAX_GAS_PRICE) + " gwei safety cap. Check your RPC endpoint.");
        }
        if (gasLimit == null || gasLimit.signum() <= 0 || gasLimit.compareTo(MAX_GAS_LIMIT) > 0) {
            throw new Exception("Refusing to sign: gas limit " + gasLimit
                    + " is outside the safe range (max " + MAX_GAS_LIMIT + "). Check your RPC endpoint.");
        }

        BigInteger nonce = rpc.getTransactionCount(creds.getAddress());

        RawTransaction raw = RawTransaction.createTransaction(
                nonce, gasPrice, gasLimit, to,
                value == null ? BigInteger.ZERO : value,
                data == null ? "" : data);   // native ETH transfer has no data — web3j NPEs on a null data hex
        byte[] signed = TransactionEncoder.signMessage(raw, chainId, creds);
        String txHash = rpc.sendRawTransaction(Numeric.toHexString(signed));
        if (txHash == null || !txHash.startsWith("0x")) throw new Exception("send failed: " + txHash);
        return txHash;
    }
}
