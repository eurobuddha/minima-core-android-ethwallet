package org.minimarex.ethwallet.eth;

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

    private static final BigInteger FALLBACK_GAS_PRICE = BigInteger.valueOf(2_000_000_000L); // 2 gwei

    public static String send(EthRpc rpc, Credentials creds, long chainId,
                              String to, String data, BigInteger value, BigInteger gasLimit) throws Exception {
        BigInteger nonce = rpc.getTransactionCount(creds.getAddress());
        BigInteger gasPrice = EthRpc.hexToBig(rpc.callStr("eth_gasPrice", new JSONArray()));
        if (gasPrice.signum() <= 0) gasPrice = FALLBACK_GAS_PRICE;
        gasPrice = gasPrice.multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN); // +20% headroom

        RawTransaction raw = RawTransaction.createTransaction(
                nonce, gasPrice, gasLimit, to,
                value == null ? BigInteger.ZERO : value, data);
        byte[] signed = TransactionEncoder.signMessage(raw, chainId, creds);
        String txHash = rpc.sendRawTransaction(Numeric.toHexString(signed));
        if (txHash == null || !txHash.startsWith("0x")) throw new Exception("send failed: " + txHash);
        return txHash;
    }
}
