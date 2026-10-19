package com.eurobuddha.ethwallet.eth;

import android.os.Handler;

import org.json.JSONObject;
import com.eurobuddha.comms.NodeApi;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Collections;
import java.util.List;

/**
 * The in-app Ethereum wallet. The key is derived from the Minima node seed via
 * {@code seedrandom modifier:ethbridge} — the SAME derivation the bridge MiniDapp uses, so the same
 * node yields the same ETH address (interop). The deterministic key is re-derived per session and
 * never persisted; the node's seed phrase is the only backup. An explicit imported key is also supported.
 */
public final class EthWallet {

    public interface Cb {
        void ok(String address);
        void err(String msg);
        /** The node derived a key for a DIFFERENT address than the one previously pinned. */
        void addressChanged(String pinned, String derived);
    }

    /** A node seedrandom reply must be exactly 32 bytes of hex — see {@link #deriveFromNode}. */
    private static final java.util.regex.Pattern KEY64 = java.util.regex.Pattern.compile("[0-9a-fA-F]{64}");

    private volatile Credentials creds;
    private long derivationGeneration;
    private volatile boolean imported;   // true if the key was imported rather than seed-derived

    public boolean ready() { return creds != null; }
    public boolean isImported() { return imported; }
    public Credentials creds() { return creds; }
    public String address() { Credentials current = creds; return current == null ? null : current.getAddress(); }

    /** The raw ETH private key as 0x + 64 hex (32-byte, zero-padded), or null if no wallet. Used for
     *  recovery / import elsewhere. Works for both seed-derived and imported keys (both populate creds). */
    public String privateKeyHex() {
        Credentials current = creds;
        if (current == null) return null;
        return org.web3j.utils.Numeric.toHexStringWithPrefixZeroPadded(current.getEcKeyPair().getPrivateKey(), 64);
    }

    /**
     * Derive the ETH key from the node seed (deterministic, not stored).
     *
     * @param expectedAddr the address this node derived last time, or null on first ever derivation.
     *
     * Two guards, both of which must pass BEFORE the key is adopted:
     *
     * 1. The reply must be exactly 64 hex chars. {@code Credentials.create} accepts any hex length —
     *    a reply of "0x01" yields private key 1, a famous address that is swept within seconds — so
     *    a truncated or malformed reply must never become a live key.
     * 2. The resulting address must match {@code expectedAddr}. This is the control that catches a
     *    reseeded node, a spoofed IPC responder, or a node bug silently moving the user to a
     *    different wallet while their funds sit at the old address.
     *
     * The {@link #creds} field is only assigned once both pass, so a rejected key is never briefly live.
     */
    public void deriveFromNode(NodeApi node, final Handler ui, final String expectedAddr, final Cb cb) {
        final long generation;
        synchronized (this) { generation = ++derivationGeneration; }
        node.cmd("seedrandom modifier:ethbridge", new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) {
                final Credentials candidate;
                try {
                    if (reply == null || !Boolean.TRUE.equals(reply.opt("status"))) throw new IllegalArgumentException();
                    JSONObject response = reply.optJSONObject("response");
                    Object key = response == null ? null : response.opt("seedrandom");
                    if (!(key instanceof String)) throw new IllegalArgumentException();
                    candidate = checkedCredentials((String) key);
                } catch (Exception invalid) {
                    deliver(generation, ui, () -> cb.err("Node returned an invalid Ethereum key. Not using it."));
                    return;
                }
                deliver(generation, ui, () -> {
                    String address = candidate.getAddress();
                    if (expectedAddr != null && !expectedAddr.equalsIgnoreCase(address)) {
                        cb.addressChanged(expectedAddr, address);
                        return;
                    }
                    creds = candidate;
                    imported = false;
                    cb.ok(address);
                });
            }
            @Override public void onError(String ignored) {
                deliver(generation, ui, () -> cb.err("Ethereum key derivation failed. Retry when the node is ready."));
            }
        });
    }

    /** Delivery and adoption share one generation check, including queued UI callbacks. */
    private void deliver(long generation, Handler ui, Runnable callback) {
        post(ui, () -> {
            synchronized (EthWallet.this) {
                if (generation != derivationGeneration) return;
                ++derivationGeneration; // A request can complete only once.
                callback.run();
            }
        });
    }

    /** AtomiX's exact width and secp256k1 scalar checks, shared by node and explicit import. */
    private static Credentials checkedCredentials(String raw) {
        if (raw == null) throw new IllegalArgumentException("Invalid Ethereum key");
        String hex = raw.trim();
        if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
        if (!KEY64.matcher(hex).matches()) throw new IllegalArgumentException("Invalid Ethereum key");
        BigInteger scalar = new BigInteger(hex, 16);
        if (scalar.signum() <= 0 || scalar.compareTo(org.web3j.crypto.Sign.CURVE_PARAMS.getN()) >= 0)
            throw new IllegalArgumentException("Invalid Ethereum key");
        return Credentials.create("0x" + hex);
    }

    /** A valid explicit import supersedes every pending node derivation. */
    public synchronized void importKey(String hexPriv) {
        Credentials candidate = checkedCredentials(hexPriv);
        ++derivationGeneration;
        creds = candidate;
        imported = true;
    }

    public synchronized void clear() { ++derivationGeneration; creds = null; imported = false; }

    // ---- balances (synchronous; call off the main thread) ----

    public BigInteger ethBalanceWei(EthRpc rpc) throws IOException {
        Credentials current = creds;
        if (current == null) return BigInteger.ZERO;
        return rpc.getBalance(current.getAddress());
    }

    /** ERC20 balanceOf(owner) raw (un-scaled by decimals). */
    public BigInteger erc20BalanceRaw(EthRpc rpc, String tokenAddress) throws IOException {
        Credentials current = creds;
        if (current == null) return BigInteger.ZERO;
        return erc20BalanceRaw(rpc, tokenAddress, current.getAddress());
    }

    public static BigInteger erc20BalanceRaw(EthRpc rpc, String tokenAddress, String owner) throws IOException {
        Function fn = new Function("balanceOf",
                Collections.singletonList(new Address(owner)),
                Collections.singletonList(new TypeReference<Uint256>() {}));
        String data = FunctionEncoder.encode(fn);
        String ret = rpc.ethCall(tokenAddress, data);
        if (ret == null || ret.length() < 3) return BigInteger.ZERO;
        List<Type> out = FunctionReturnDecoder.decode(ret, fn.getOutputParameters());
        if (out.isEmpty()) return BigInteger.ZERO;
        return (BigInteger) out.get(0).getValue();
    }

    /** Format a raw integer amount with the given decimals to a trimmed decimal string. */
    public static String format(BigInteger raw, int decimals, int maxFrac) {
        if (raw == null) return "0";
        java.math.BigDecimal v = new java.math.BigDecimal(raw, decimals);
        java.math.BigDecimal r = v.setScale(maxFrac, java.math.RoundingMode.DOWN).stripTrailingZeros();
        String s = r.toPlainString();
        return s.isEmpty() ? "0" : s;
    }

    private static void post(Handler ui, Runnable r) { if (ui != null) ui.post(r); else r.run(); }
}
