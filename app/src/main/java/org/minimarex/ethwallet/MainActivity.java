package org.minimarex.ethwallet;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.minimarex.comms.NodeApi;
import org.minimarex.comms.QrUtil;
import org.minimarex.ethwallet.eth.EthNet;
import org.minimarex.ethwallet.eth.EthRpc;
import org.minimarex.ethwallet.eth.EthTx;
import org.minimarex.ethwallet.eth.EthWallet;
import org.minimarex.ethwallet.eth.TokenStore;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minima ETH Wallet — a standalone ERC20 wallet sharing minimaSwap's address. The key is either
 * derived from the local Minima node (`seedrandom modifier:ethbridge`, the same address as minimaSwap)
 * or imported once and kept in a Keystore-backed encrypted store. Mainnet only; activity is viewed on
 * Etherscan via deep links (keyless).
 */
public class MainActivity extends AppCompatActivity implements NodeApi.PairingListener {

    private static final String PREFS = "ethwallet";
    private static final String ETHERSCAN = "https://etherscan.io/";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private android.content.SharedPreferences prefs;
    private EthNet net;
    private EthRpc rpc;
    private final EthWallet wallet = new EthWallet();
    private TokenStore tokens;
    private KeyVault vault;
    private NodeApi node;

    private LinearLayout root, pairingBanner;
    private ScrollView scroller;
    private boolean modalOpen = false;
    private boolean paired = false;

    private String ethAddr, ethBal = "—", ethErr;
    private final Map<String, String> tokenBals = new LinkedHashMap<>();   // symbol -> formatted balance
    private long lastUpdate = 0;

    private EditText pendingRecipient;          // filled by the QR scanner
    private ActivityResultLauncher<ScanOptions> scanLauncher;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        net = EthNet.MAINNET;
        rpc = new EthRpc(prefs.getString("rpc", net.defaultRpc));
        tokens = new TokenStore(prefs);
        vault = new KeyVault(this);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Design.BG);
        pairingBanner = buildPairingBanner();
        pairingBanner.setVisibility(View.GONE);
        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        root.addView(pairingBanner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroller, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        applyInsets();

        scanLauncher = registerForActivityResult(new ScanContract(), result -> {
            if (result.getContents() != null && pendingRecipient != null)
                pendingRecipient.setText(cleanAddr(result.getContents()));
        });

        render();
        startWallet();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (node != null) node.onDestroy();
        io.shutdownNow();
    }

    // ---- wallet source / startup ----

    private void startWallet() {
        String src = prefs.getString("source", null);
        if ("import".equals(src)) {
            String key = vault.loadKey();
            if (key != null) { wallet.importKey(key); ethAddr = wallet.address(); render(); refresh(); return; }
            // import chosen but no key stored → re-prompt
        }
        if ("node".equals(src)) { connectNode(); return; }
        sourcePicker();
    }

    private void connectNode() {
        if (node == null) node = new NodeApi(this, this);
        // onEnabled fires from the IPC register; deriveFromNode runs there
    }

    @Override public void onEnabled(boolean enabled) {
        paired = enabled;
        pairingBanner.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (enabled && !wallet.ready()) {
            wallet.deriveFromNode(node, ui, new EthWallet.Cb() {
                @Override public void ok(String address) { ethAddr = address; ethErr = null; render(); refresh(); }
                @Override public void err(String msg) { ethErr = msg; render(); }
            });
        }
        render();
    }

    private void sourcePicker() {
        modalOpen = true;
        LinearLayout box = colBox();
        TextView t = new TextView(this);
        t.setText("Choose how this wallet gets its Ethereum key. Either way it's the SAME address as minimaSwap.");
        t.setTextColor(Design.DIM); t.setTextSize(13f); t.setPadding(0, 0, 0, dp(12));
        box.addView(t);
        new AlertDialog.Builder(this)
                .setTitle("Set up wallet")
                .setView(wrapScroll(box))
                .setCancelable(false)
                .setPositiveButton("Pair with node", (d, w) -> { prefs.edit().putString("source", "node").apply(); modalOpen = false; connectNode(); render(); })
                .setNeutralButton("Import key", (d, w) -> { modalOpen = false; importDialog(); })
                .show();
    }

    private void importDialog() {
        modalOpen = true;
        LinearLayout box = colBox();
        TextView t = new TextView(this);
        t.setText("Paste the private key you exported from minimaSwap (0x + 64 hex). It's stored encrypted on this device only.");
        t.setTextColor(Design.DIM); t.setTextSize(13f); t.setPadding(0, 0, 0, dp(10));
        box.addView(t);
        EditText in = new EditText(this);
        in.setHint("0x…"); in.setTextColor(Design.TEXT); in.setHintTextColor(Design.DIM2);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        box.addView(in);
        new AlertDialog.Builder(this)
                .setTitle("Import private key")
                .setView(wrapScroll(box))
                .setPositiveButton("Import", (d, w) -> {
                    modalOpen = false;
                    String k = in.getText().toString().trim();
                    // A private key is EXACTLY 32 bytes — Credentials.create accepts any hex length and would
                    // silently produce a different (wrong) address, so validate the length/charset first.
                    if (!k.replaceFirst("^0x", "").matches("[0-9a-fA-F]{64}")) { toast("Key must be 0x + 64 hex chars"); importDialog(); return; }
                    try {
                        wallet.importKey(k);
                        vault.saveKey(wallet.privateKeyHex());
                        prefs.edit().putString("source", "import").apply();
                        ethAddr = wallet.address(); ethErr = null;
                        toast("Imported " + shortAddr(ethAddr));
                        render(); refresh();
                    } catch (Exception e) { toast("Invalid key"); sourcePicker(); }
                })
                .setNegativeButton("Back", (d, w) -> { modalOpen = false; sourcePicker(); })
                .setOnCancelListener(d -> { modalOpen = false; sourcePicker(); })
                .show();
    }

    // ---- balances ----

    private void refresh() {
        if (!wallet.ready()) return;
        io.execute(() -> {
            try {
                BigInteger wei = wallet.ethBalanceWei(rpc);
                String eth = EthWallet.format(wei, 18, 6);
                Map<String, String> bals = new LinkedHashMap<>();
                for (EthNet.Token tk : tokens.tokens()) {
                    try { bals.put(tk.symbol, EthWallet.format(wallet.erc20BalanceRaw(rpc, tk.address), tk.decimals, 6)); }
                    catch (Exception e) { bals.put(tk.symbol, "—"); }
                }
                ui.post(() -> { ethBal = eth; tokenBals.clear(); tokenBals.putAll(bals); ethErr = null; lastUpdate = System.currentTimeMillis(); render(); });
            } catch (Exception e) {
                ui.post(() -> { ethErr = "RPC: " + e.getMessage(); render(); });
            }
        });
    }

    // ---- render ----

    private void render() {
        if (modalOpen) return;
        LinearLayout col = colBox();

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = new TextView(this);
        brand.setText("ETH Wallet"); brand.setTextColor(Design.TEXT); brand.setTextSize(22f);
        brand.setTypeface(brand.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(brand, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(Design.pill(this, "Mainnet · real funds", Design.ACCENT, Design.ON_ACCENT));
        col.addView(header);

        TextView sub = new TextView(this);
        sub.setText("v" + BuildConfig.VERSION_NAME + "  ·  " + (wallet.isImported() ? "imported key" : "node-derived") + (lastUpdate > 0 ? "  ·  updated " + ago(lastUpdate) : ""));
        sub.setTextColor(Design.DIM2); sub.setTextSize(12f); sub.setPadding(0, dp(2), 0, dp(12));
        col.addView(sub);

        if (!wallet.ready()) {
            TextView w = new TextView(this);
            w.setText(ethErr != null ? "⚠ " + ethErr : "Setting up wallet…");
            w.setTextColor(ethErr != null ? Design.RED : Design.DIM); w.setTextSize(13f);
            col.addView(w);
            scroller.removeAllViews(); scroller.addView(col);
            return;
        }

        // address card → receive
        LinearLayout addrCard = card();
        TextView at = new TextView(this); at.setText("Your address  ·  tap to receive");
        at.setTextColor(Design.DIM); at.setTextSize(12.5f);
        TextView av = new TextView(this); av.setText(shortAddr(ethAddr));
        av.setTextColor(Design.TEXT); av.setTextSize(16f); av.setPadding(0, dp(3), 0, 0);
        addrCard.addView(at); addrCard.addView(av);
        addrCard.setOnClickListener(v -> receiveDialog());
        col.addView(addrCard);

        // ETH balance
        LinearLayout ethCard = card();
        TextView e1 = new TextView(this); e1.setText("Ethereum"); e1.setTextColor(Design.DIM); e1.setTextSize(12.5f);
        TextView e2 = new TextView(this); e2.setText(ethBal + " ETH"); e2.setTextColor(Design.ACCENT); e2.setTextSize(20f);
        e2.setTypeface(e2.getTypeface(), android.graphics.Typeface.BOLD); e2.setPadding(0, dp(3), 0, 0);
        ethCard.addView(e1); ethCard.addView(e2);
        ethCard.setOnClickListener(v -> sendDialog("ETH"));
        col.addView(ethCard);

        if (ethErr != null) {
            TextView err = new TextView(this); err.setText("⚠ " + ethErr);
            err.setTextColor(Design.RED); err.setTextSize(12f); err.setPadding(dp(2), dp(6), 0, 0);
            col.addView(err);
        }

        // token rows
        for (EthNet.Token tk : tokens.tokens()) {
            String bal = tokenBals.containsKey(tk.symbol) ? tokenBals.get(tk.symbol) : "…";
            LinearLayout r = card();
            r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
            TextView sym = new TextView(this); sym.setText(tk.symbol); sym.setTextColor(Design.TEXT); sym.setTextSize(15f);
            TextView amt = new TextView(this); amt.setText(bal); amt.setTextColor(Design.DIM); amt.setTextSize(15f); amt.setGravity(Gravity.END);
            r.addView(sym, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            r.addView(amt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            r.setOnClickListener(v -> sendDialog(tk.symbol));
            r.setOnLongClickListener(v -> { tokenMenu(tk); return true; });
            col.addView(r);
        }

        // actions
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL); actions.setPadding(0, dp(12), 0, 0);
        addPill(actions, "↑  Send", () -> sendDialog("ETH"));
        addPill(actions, "↓  Receive", this::receiveDialog);
        addPill(actions, "↻  Refresh", () -> { toast("Refreshing…"); refresh(); });
        col.addView(actions);

        LinearLayout actions2 = new LinearLayout(this);
        actions2.setOrientation(LinearLayout.HORIZONTAL); actions2.setPadding(0, dp(8), 0, 0);
        addPill(actions2, "+  Add token", this::addTokenDialog);
        addPill(actions2, "↗  Etherscan", () -> openUrl(ETHERSCAN + "address/" + ethAddr));
        addPill(actions2, "⚙  Settings", this::settingsDialog);
        col.addView(actions2);

        scroller.removeAllViews();
        scroller.addView(col);
    }

    // ---- receive / export ----

    private void receiveDialog() {
        if (ethAddr == null) return;
        modalOpen = true;
        LinearLayout box = colBox();
        Bitmap qr = QrUtil.qr(ethAddr, dp(220));
        if (qr != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(qr);
            iv.setBackgroundColor(0xFFFFFFFF);
            int pad = dp(8); iv.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(236), dp(236));
            ip.gravity = Gravity.CENTER_HORIZONTAL; ip.bottomMargin = dp(12); iv.setLayoutParams(ip);
            box.addView(iv);
        }
        TextView a = new TextView(this);
        a.setText(ethAddr); a.setTextColor(Design.TEXT); a.setTextSize(13f); a.setTextIsSelectable(true);
        a.setTypeface(android.graphics.Typeface.MONOSPACE);
        box.addView(a);
        TextView note = new TextView(this);
        note.setText("Same address on all EVM networks. Send only Ethereum-mainnet assets here.");
        note.setTextColor(Design.DIM2); note.setTextSize(11.5f); note.setPadding(0, dp(8), 0, 0);
        box.addView(note);
        new AlertDialog.Builder(this).setTitle("Receive")
                .setView(wrapScroll(box))
                .setPositiveButton("Copy", (d, w) -> { copy(ethAddr); toast("Address copied"); })
                .setNegativeButton("Close", null)
                .setOnDismissListener(d -> modalOpen = false)
                .show();
    }

    private void revealKeyDialog() {
        String k = wallet.privateKeyHex();
        if (k == null) return;
        modalOpen = true;
        LinearLayout box = colBox();
        TextView warn = new TextView(this);
        warn.setText("Anyone with this key controls these funds. Never share it.");
        warn.setTextColor(Design.RED); warn.setTextSize(12.5f); warn.setPadding(0, 0, 0, dp(10));
        box.addView(warn);
        TextView key = new TextView(this);
        key.setText(k); key.setTextColor(Design.TEXT); key.setTextSize(13f); key.setTextIsSelectable(true);
        key.setTypeface(android.graphics.Typeface.MONOSPACE);
        box.addView(key);
        new AlertDialog.Builder(this).setTitle("Private key")
                .setView(wrapScroll(box))
                .setPositiveButton("Copy", (d, w) -> { copy(k); toast("Key copied"); })
                .setNegativeButton("Close", null)
                .setOnDismissListener(d -> modalOpen = false)
                .show();
    }

    // ---- send ----

    private void sendDialog(String startSym) {
        if (!wallet.ready()) return;
        modalOpen = true;
        final List<String> syms = new ArrayList<>();
        syms.add("ETH");
        for (EthNet.Token t : tokens.tokens()) syms.add(t.symbol);
        final String[] chosen = { syms.contains(startSym) ? startSym : "ETH" };

        LinearLayout box = colBox();
        final TextView tokenLine = new TextView(this);
        tokenLine.setTextColor(Design.ACCENT); tokenLine.setTextSize(15f);
        tokenLine.setPadding(0, 0, 0, dp(8));
        final Runnable setTok = () -> tokenLine.setText("Token: " + chosen[0] + "  (tap to change · balance " + balOf(chosen[0]) + ")");
        setTok.run();
        tokenLine.setOnClickListener(v -> {
            String[] arr = syms.toArray(new String[0]);
            new AlertDialog.Builder(this).setTitle("Send which token")
                    .setItems(arr, (d, i) -> { chosen[0] = arr[i]; setTok.run(); }).show();
        });
        box.addView(tokenLine);

        final EditText to = new EditText(this);
        to.setHint("Recipient 0x…"); to.setTextColor(Design.TEXT); to.setHintTextColor(Design.DIM2);
        to.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        box.addView(to);
        TextView scan = Design.pill(this, "⌗  Scan QR", Design.SURFACE2, Design.TEXT);
        scan.setOnClickListener(v -> { pendingRecipient = to; scanLauncher.launch(new ScanOptions().setBeepEnabled(false).setOrientationLocked(false).setPrompt("Scan recipient address")); });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(8); scan.setLayoutParams(sp);
        box.addView(scan);

        final EditText amt = new EditText(this);
        amt.setHint("Amount"); amt.setTextColor(Design.TEXT); amt.setHintTextColor(Design.DIM2);
        amt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ap.topMargin = dp(8); amt.setLayoutParams(ap);
        box.addView(amt);
        TextView max = Design.pill(this, "MAX", Design.SURFACE2, Design.DIM);
        max.setOnClickListener(v -> {
            if (!"ETH".equals(chosen[0])) { amt.setText(balOf(chosen[0])); return; }   // ERC20: gas is paid in ETH
            toast("Reserving gas…");                                                    // ETH: leave enough for the fee
            io.execute(() -> {
                try {
                    BigInteger wei = wallet.ethBalanceWei(rpc);
                    BigInteger gp = rpc.gasPrice(); if (gp.signum() <= 0) gp = BigInteger.valueOf(2_000_000_000L);
                    BigInteger fee = BigInteger.valueOf(21000).multiply(gp.multiply(BigInteger.valueOf(13)).divide(BigInteger.TEN));
                    BigInteger spend = wei.subtract(fee);
                    String out = spend.signum() > 0 ? EthWallet.format(spend, 18, 8) : "0";
                    ui.post(() -> amt.setText(out));
                } catch (Exception e) { ui.post(() -> amt.setText(balOf("ETH"))); }
            });
        });
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mp.topMargin = dp(8); max.setLayoutParams(mp);
        box.addView(max);

        new AlertDialog.Builder(this).setTitle("Send")
                .setView(wrapScroll(box))
                .setPositiveButton("Review", (d, w) -> {
                    modalOpen = false;
                    confirmSend(chosen[0], to.getText().toString().trim(), amt.getText().toString().trim());
                })
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> modalOpen = false)
                .show();
    }

    private void confirmSend(String sym, String to, String amount) {
        if (!to.startsWith("0x") || to.length() != 42) { toast("Bad recipient address"); return; }
        final BigDecimal amtDec;
        try { amtDec = new BigDecimal(amount); if (amtDec.signum() <= 0) throw new Exception(); }
        catch (Exception e) { toast("Bad amount"); return; }

        toast("Estimating fee…");
        io.execute(() -> {
            try {
                final boolean isEth = "ETH".equals(sym);
                final EthNet.Token tk = isEth ? null : tokenBySym(sym);
                final int decimals = isEth ? 18 : tk.decimals;
                final BigInteger raw = amtDec.movePointRight(decimals).toBigInteger();
                if (raw.signum() <= 0) { ui.post(() -> toast("Amount is below the token's smallest unit")); return; }
                final String data = isEth ? null : FunctionEncoder.encode(new Function("transfer",
                        java.util.Arrays.asList(new Address(to), new Uint256(raw)), Collections.emptyList()));
                final String txTo = isEth ? to : tk.address;
                final BigInteger value = isEth ? raw : BigInteger.ZERO;
                BigInteger gasLimit;
                try {
                    gasLimit = rpc.estimateGas(wallet.address(), txTo, data, value).multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
                } catch (Exception ge) {
                    // A hard revert (insufficient balance/allowance) means the SEND would also fail — abort with
                    // the reason instead of broadcasting a doomed, gas-wasting tx. Fall back only on transient RPC.
                    String m = ge.getMessage() == null ? "" : ge.getMessage().toLowerCase();
                    if (m.contains("revert") || m.contains("insufficient") || m.contains("exceeds") || m.contains("transfer amount")) {
                        ui.post(() -> toast("Would fail: " + ge.getMessage())); return;
                    }
                    gasLimit = BigInteger.valueOf(isEth ? 21000 : 90000);
                }
                BigInteger gp = rpc.gasPrice(); if (gp.signum() <= 0) gp = BigInteger.valueOf(2_000_000_000L);
                final BigInteger gasLimitF = gasLimit;
                final BigInteger feeWei = gasLimit.multiply(gp.multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN));
                ui.post(() -> showConfirm(sym, to, amount, isEth, txTo, data, value, gasLimitF, EthWallet.format(feeWei, 18, 8)));
            } catch (Exception e) {
                ui.post(() -> toast("Couldn't prepare: " + e.getMessage()));
            }
        });
    }

    private void showConfirm(String sym, String to, String amount, boolean isEth, String txTo, String data, BigInteger value, BigInteger gasLimit, String feeEth) {
        modalOpen = true;
        LinearLayout box = colBox();
        box.addView(kvLine("Send", amount + " " + sym));
        box.addView(kvLine("To", shortAddr(to)));
        box.addView(kvLine("Est. fee", "~" + feeEth + " ETH"));
        new AlertDialog.Builder(this).setTitle("Confirm send")
                .setView(wrapScroll(box))
                .setPositiveButton("Send", (d, w) -> {
                    modalOpen = false;
                    toast("Broadcasting…");
                    io.execute(() -> {
                        try {
                            String tx = EthTx.send(rpc, wallet.creds(), net.chainId, txTo, data, isEth ? value : BigInteger.ZERO, gasLimit);
                            ui.post(() -> { sentDialog(tx); refresh(); });
                        } catch (Exception e) {
                            ui.post(() -> toast("Send failed: " + e.getMessage()));
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> modalOpen = false)
                .show();
    }

    private void sentDialog(String tx) {
        modalOpen = true;
        LinearLayout box = colBox();
        TextView t = new TextView(this);
        t.setText("Broadcast.\n\n" + tx); t.setTextColor(Design.TEXT); t.setTextSize(12.5f);
        t.setTypeface(android.graphics.Typeface.MONOSPACE); t.setTextIsSelectable(true);
        box.addView(t);
        new AlertDialog.Builder(this).setTitle("Sent")
                .setView(wrapScroll(box))
                .setPositiveButton("View on Etherscan", (d, w) -> openUrl(net.explorerTx + tx))
                .setNegativeButton("Close", null)
                .setOnDismissListener(d -> modalOpen = false)
                .show();
    }

    // ---- tokens ----

    private void addTokenDialog() {
        modalOpen = true;
        LinearLayout box = colBox();
        TextView t = new TextView(this);
        t.setText("Paste an ERC20 contract address. Symbol + decimals are read from the chain.");
        t.setTextColor(Design.DIM); t.setTextSize(13f); t.setPadding(0, 0, 0, dp(10));
        box.addView(t);
        EditText in = new EditText(this);
        in.setHint("0x… contract"); in.setTextColor(Design.TEXT); in.setHintTextColor(Design.DIM2);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        box.addView(in);
        new AlertDialog.Builder(this).setTitle("Add token")
                .setView(wrapScroll(box))
                .setPositiveButton("Add", (d, w) -> {
                    modalOpen = false;
                    String addr = in.getText().toString().trim();
                    if (!addr.startsWith("0x") || addr.length() != 42) { toast("Bad contract address"); return; }
                    if (tokens.has(addr)) { toast("Already added"); return; }
                    toast("Reading token…");
                    io.execute(() -> {
                        try {
                            EthNet.Token tk = TokenStore.fetch(rpc, addr);
                            ui.post(() -> { tokens.add(tk); toast("Added " + tk.symbol); render(); refresh(); });
                        } catch (Exception e) { ui.post(() -> toast("Not an ERC20? " + e.getMessage())); }
                    });
                })
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> modalOpen = false)
                .show();
    }

    private void tokenMenu(EthNet.Token tk) {
        new AlertDialog.Builder(this).setTitle(tk.symbol)
                .setItems(new String[]{"Send " + tk.symbol, "View on Etherscan", "Remove from list"}, (d, i) -> {
                    if (i == 0) sendDialog(tk.symbol);
                    else if (i == 1) openUrl(ETHERSCAN + "token/" + tk.address + "?a=" + ethAddr);
                    else { tokens.remove(tk.address); tokenBals.remove(tk.symbol); render(); }
                }).show();
    }

    // ---- settings ----

    private void settingsDialog() {
        new AlertDialog.Builder(this).setTitle("Settings")
                .setItems(new String[]{"Export private key", "RPC endpoint", "Add token", "View address on Etherscan", "Switch wallet source"}, (d, i) -> {
                    switch (i) {
                        case 0: revealKeyDialog(); break;
                        case 1: rpcDialog(); break;
                        case 2: addTokenDialog(); break;
                        case 3: openUrl(ETHERSCAN + "address/" + ethAddr); break;
                        case 4: switchSourceDialog(); break;
                    }
                }).show();
    }

    private void rpcDialog() {
        modalOpen = true;
        LinearLayout box = colBox();
        EditText in = new EditText(this);
        in.setText(rpc.url()); in.setTextColor(Design.TEXT);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(in);
        TextView note = new TextView(this);
        note.setText("Falls back to public keyless nodes automatically if this one fails.");
        note.setTextColor(Design.DIM2); note.setTextSize(11.5f); note.setPadding(0, dp(8), 0, 0);
        box.addView(note);
        new AlertDialog.Builder(this).setTitle("RPC endpoint")
                .setView(wrapScroll(box))
                .setPositiveButton("Save", (d, w) -> { modalOpen = false; String u = in.getText().toString().trim(); if (!u.isEmpty()) { prefs.edit().putString("rpc", u).apply(); rpc.setUrl(u); refresh(); } })
                .setNeutralButton("Default", (d, w) -> { modalOpen = false; prefs.edit().remove("rpc").apply(); rpc.setUrl(net.defaultRpc); refresh(); })
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> modalOpen = false)
                .show();
    }

    private void switchSourceDialog() {
        new AlertDialog.Builder(this).setTitle("Switch wallet source")
                .setMessage("Re-derive from the node, or import a different key. (This doesn't move funds.)")
                .setPositiveButton("Pair with node", (d, w) -> { prefs.edit().putString("source", "node").apply(); wallet.clear(); ethAddr = null; connectNode(); render(); })
                .setNeutralButton("Import key", (d, w) -> { wallet.clear(); ethAddr = null; importDialog(); })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- helpers ----

    private String balOf(String sym) { return "ETH".equals(sym) ? ethBal : (tokenBals.containsKey(sym) ? tokenBals.get(sym) : "0"); }
    private EthNet.Token tokenBySym(String sym) { for (EthNet.Token t : tokens.tokens()) if (t.symbol.equals(sym)) return t; return null; }

    private LinearLayout colBox() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(24));
        return c;
    }
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Design.roundBg(this, Design.SURFACE, 16));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10); c.setLayoutParams(lp);
        return c;
    }
    private void addPill(LinearLayout row, String label, Runnable onClick) {
        TextView p = Design.pill(this, label, Design.SURFACE2, Design.TEXT);
        p.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8); p.setLayoutParams(lp);
        row.addView(p);
    }
    private TextView kvLine(String k, String v) {
        TextView t = new TextView(this);
        t.setText(k + ":  " + v); t.setTextColor(Design.TEXT); t.setTextSize(14f); t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private ScrollView wrapScroll(View v) { ScrollView s = new ScrollView(this); s.addView(v); return s; }

    private LinearLayout buildPairingBanner() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackgroundColor(0xFF3A2A00);
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        TextView t = new TextView(this);
        t.setText("Enable “ETH Wallet” in Minima Core → Apps to derive your key from the node.");
        t.setTextColor(Design.ACCENT); t.setTextSize(12.5f);
        b.addView(t);
        return b;
    }

    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(0, sb.top, 0, sb.bottom);
            return insets;
        });
    }

    private int dp(int v) { return Design.dp(this, v); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private void copy(String s) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("eth", s));
    }
    private void openUrl(String url) {
        try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
        catch (Exception e) { toast("No browser"); }
    }
    private static String shortAddr(String a) {
        if (a == null) return "—";
        return a.length() > 14 ? a.substring(0, 8) + "…" + a.substring(a.length() - 6) : a;
    }
    private static String cleanAddr(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.startsWith("ethereum:")) s = s.substring("ethereum:".length());
        int at = s.indexOf('@'); if (at > 0) s = s.substring(0, at);   // EIP-681 chain suffix
        int q = s.indexOf('?'); if (q > 0) s = s.substring(0, q);
        return s.trim();
    }
    private static String ago(long t) {
        long s = (System.currentTimeMillis() - t) / 1000;
        return s < 60 ? s + "s ago" : (s / 60) + "m ago";
    }
}
