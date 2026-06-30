package org.minimarex.ethwallet.eth;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal Ethereum JSON-RPC client over HttpURLConnection — no okhttp, no web3j HTTP service.
 * All methods are SYNCHRONOUS and BLOCKING: call them off the main thread (the UI uses an executor).
 */
public final class EthRpc {

    /** Keyless mainnet endpoints to fall back to when the primary is down/rate-limited (verified live).
     *  A single public node returning a 5xx/HTML body must not block reads or broadcasts. */
    private static final String[] FALLBACKS = {
            "https://ethereum-rpc.publicnode.com",
            "https://eth.drpc.org",
            "https://1rpc.io/eth",
    };

    private volatile String url;             // the endpoint that last worked (sticky)
    private final java.util.List<String> endpoints = new java.util.ArrayList<>();

    public EthRpc(String url) { setUrl(url); }

    public synchronized void setUrl(String url) {
        this.url = url;
        endpoints.clear();
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        set.add(url);                                  // configured primary first
        for (String f : FALLBACKS) set.add(f);         // then the known-good keyless nodes
        endpoints.addAll(set);
    }

    public String url() { return url; }

    /**
     * Raw JSON-RPC call with endpoint fallback. Tries the active endpoint, then each fallback, on any
     * transport/parse failure (e.g. a 521/HTML body); the first that answers becomes the active one.
     */
    public Object call(String method, JSONArray params) throws IOException {
        StringBuilder errs = new StringBuilder();
        for (String ep : endpoints) {
            try {
                Object r = callOnce(ep, method, params);
                if (!ep.equals(url)) url = ep;         // stick to whichever responded
                return r;
            } catch (IOException e) {                   // try the next endpoint; keep each reason
                if (errs.length() > 0) errs.append("  |  ");
                errs.append(e.getMessage());
            }
        }
        throw new IOException(method + " failed on all RPCs: " + errs);
    }

    private Object callOnce(String url, String method, JSONArray params) throws IOException {
        HttpURLConnection c = null;
        try {
            JSONObject payload = new JSONObject()
                    .put("jsonrpc", "2.0").put("id", 1).put("method", method)
                    .put("params", params == null ? new JSONArray() : params);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            try (OutputStream os = c.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String body = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
            String trimmed = body == null ? "" : body.trim();
            // A healthy node answers with a JSON object; anything else (HTML error page, 5xx text,
            // captive portal, empty) gets reported with host+status+snippet so failures are diagnosable.
            if (!trimmed.startsWith("{")) {
                throw new IOException(host(url) + " HTTP " + code + " non-JSON: " + snippet(trimmed));
            }
            JSONObject resp = new JSONObject(trimmed);
            if (resp.has("error")) {
                JSONObject err = resp.optJSONObject("error");
                throw new IOException(host(url) + ": " + (err == null ? snippet(trimmed) : err.optString("message", snippet(trimmed))));
            }
            return resp.opt("result");
        } catch (JSONException e) {
            throw new IOException(host(url) + " parse error: " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String host(String url) {
        try { return new URL(url).getHost(); } catch (Exception e) { return url; }
    }
    private static String snippet(String s) {
        if (s == null || s.isEmpty()) return "(empty)";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    public String callStr(String method, JSONArray params) throws IOException {
        Object r = call(method, params);
        return r == null ? null : r.toString();
    }

    public JSONArray getLogs(JSONObject filter) throws IOException {
        Object r = call("eth_getLogs", new JSONArray().put(filter));
        return r instanceof JSONArray ? (JSONArray) r : new JSONArray();
    }

    public BigInteger getBalance(String address) throws IOException {
        return hexToBig(callStr("eth_getBalance", new JSONArray().put(address).put("latest")));
    }

    public BigInteger getTransactionCount(String address) throws IOException {
        return hexToBig(callStr("eth_getTransactionCount", new JSONArray().put(address).put("pending")));
    }

    public BigInteger blockNumber() throws IOException {
        return hexToBig(callStr("eth_blockNumber", new JSONArray()));
    }

    /** eth_call to a contract; returns the raw hex return data ("0x...."). */
    public String ethCall(String to, String data) throws IOException {
        try {
            JSONObject tx = new JSONObject().put("to", to).put("data", data);
            return callStr("eth_call", new JSONArray().put(tx).put("latest"));
        } catch (JSONException e) {
            throw new IOException("ETH eth_call build error: " + e.getMessage());
        }
    }

    public String sendRawTransaction(String signedHex) throws IOException {
        return callStr("eth_sendRawTransaction", new JSONArray().put(signedHex));
    }

    public BigInteger gasPrice() throws IOException {
        return hexToBig(callStr("eth_gasPrice", new JSONArray()));
    }

    /** eth_estimateGas for a draft tx (gas units). Throws on revert (e.g. insufficient token balance). */
    public BigInteger estimateGas(String from, String to, String data, BigInteger value) throws IOException {
        try {
            JSONObject tx = new JSONObject().put("from", from).put("to", to);
            if (data != null) tx.put("data", data);
            if (value != null && value.signum() > 0) tx.put("value", "0x" + value.toString(16));
            return hexToBig(callStr("eth_estimateGas", new JSONArray().put(tx)));
        } catch (JSONException e) {
            throw new IOException("estimateGas build error: " + e.getMessage());
        }
    }

    public static BigInteger hexToBig(String hex) {
        if (hex == null) return BigInteger.ZERO;
        hex = hex.trim();
        if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
        if (hex.isEmpty()) return BigInteger.ZERO;
        return new BigInteger(hex, 16);
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        try (InputStream in = is) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        }
    }
}
