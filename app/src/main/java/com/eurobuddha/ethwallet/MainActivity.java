package com.eurobuddha.ethwallet;

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
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.comms.QrUtil;
import com.eurobuddha.ethwallet.eth.EthNet;
import com.eurobuddha.ethwallet.eth.EthRpc;
import com.eurobuddha.ethwallet.eth.EthTx;
import com.eurobuddha.ethwallet.eth.EthWallet;
import com.eurobuddha.ethwallet.eth.TokenStore;
import com.eurobuddha.ethwallet.eth.IconLoader;
import org.web3j.crypto.Keys;
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
    /** Pref holding the address this node derived last time — see {@link #deriveNow}. */
    private static final String PIN = "derivedAddr";
    private static final String ETHERSCAN = "https://etherscan.io/";
    /** A well-formed Ethereum address. Checked before ANY value leaves this wallet — see confirmSend. */
    private static final java.util.regex.Pattern ADDR = java.util.regex.Pattern.compile("^0x[0-9a-fA-F]{40}$");

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
    private ActivityResultLauncher<android.content.Intent> authLauncher;
    private Runnable pendingAuthAction;         // run once the device credential is confirmed

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        net = EthNet.MAINNET;
        rpc = new EthRpc(prefs.getString("rpc", net.defaultRpc));
        tokens = new TokenStore(prefs);
        // NB: the KeyVault is NOT built here. Opening the Keystore-backed store can fail (a restored
        // backup leaves a keyset the device can no longer decrypt), and a node-paired wallet never
        // touches it — building it eagerly turned that into a crash loop on every launch.

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
        authLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            Runnable action = pendingAuthAction;
            pendingAuthAction = null;
            if (action == null) return;
            if (r.getResultCode() == RESULT_OK) action.run();
            else toast("Cancelled");
        });

        render();
        startWallet();
    }

    @Override protected void onResume() {
        super.onResume();
        // Re-check enablement after the user returns from Minima Core -> Apps. Without this the
        // pairing banner tells you to do something that then never takes effect until a restart.
        if (node != null && !wallet.ready()) retryNode();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (node != null) node.onDestroy();
        io.shutdownNow();
    }

    /** True once this Activity is gone — never touch views or show dialogs past this point. */
    private boolean gone() { return isFinishing() || isDestroyed(); }

    /** Post to the UI thread, dropping the work if the Activity died while the IO task ran. */
    private void onUi(Runnable r) { ui.post(() -> { if (!gone()) r.run(); }); }

    /** The secure store, opened on first use only. May be unavailable — always check {@link KeyVault#available()}. */
    private KeyVault vault() {
        if (vault == null) vault = new KeyVault(this);
        return vault;
    }

    /**
     * Confirm the device credential (PIN/pattern/password/biometric) before a sensitive action.
     * Devices with no secure lockscreen have nothing to check against, so the action runs directly —
     * the caller is still responsible for an explicit warning step.
     */
    private void authThen(String title, String detail, Runnable action) {
        android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        android.content.Intent i = (km == null || !km.isDeviceSecure())
                ? null : km.createConfirmDeviceCredentialIntent(title, detail);
        if (i == null) { action.run(); return; }
        pendingAuthAction = action;
        authLauncher.launch(i);
    }

    // ---- wallet source / startup ----

    private void startWallet() {
        String src = prefs.getString("source", null);
        if ("import".equals(src)) {
            KeyVault v = vault();
            String key = v.available() ? v.loadKey() : null;
            if (key != null) { wallet.importKey(key); ethAddr = wallet.address(); render(); refresh(); return; }
            // The stored key is gone — the secure store was reset (restored backup / Keystore change)
            // or could not be opened at all. Say so plainly instead of silently re-prompting.
            if (v.wasReset() || !v.available())
                ethErr = "Secure storage was reset by the device, so the imported key is gone. "
                       + "Re-import it, or pair with your node.";
            prefs.edit().remove("source").apply();
        }
        if ("node".equals(src)) { connectNode(); return; }
        sourcePicker();
    }

    /**
     * Ensure the IPC exists and that we end up with a key. The first call constructs {@link NodeApi},
     * whose REGISTER reply drives {@link #onEnabled}; later calls have no register to wait for, so they
     * must derive directly. Missing that second branch left "Switch wallet source -> Pair with node"
     * a silent no-op that stranded the app on "Setting up wallet…" until it was force-quit.
     */
    private void connectNode() {
        if (node == null) { node = new NodeApi(this, this); return; }
        if (paired && !wallet.ready()) deriveNow();
    }

    /** Ask the node for the key. Doubles as the pairing retry: a successful reply proves we're enabled,
     *  and NodeApi routes the not-enabled reply back through {@link #onEnabled}. */
    private void deriveNow() {
        if (node == null) return;
        // The address this node derived last time. Null on the very first derivation.
        final String pinned = prefs.getString(PIN, null);
        wallet.deriveFromNode(node, ui, pinned, new EthWallet.Cb() {
            @Override public void ok(String address) {
                if (gone()) return;
                paired = true;
                pairingBanner.setVisibility(View.GONE);
                prefs.edit().putString(PIN, address).apply();   // pin on first success, re-affirm after
                ethAddr = address; ethErr = null; render(); refresh();
            }
            @Override public void err(String msg) {
                if (gone()) return;
                ethErr = NodeApi.ERR_NOT_ENABLED.equals(msg)
                        ? "Not enabled yet — turn on “ETH Wallet” in Minima Core → Apps, then tap the banner."
                        : msg;
                render();
            }
            @Override public void addressChanged(String was, String now) {
                if (gone()) return;
                addressChangedDialog(was, now);
            }
        });
    }

    /**
     * The node derived a different address than last time. The key has NOT been adopted. Either the
     * seed genuinely changed (node reset/restored from a different phrase) — in which case the old
     * funds are stranded and the user needs to know — or something is impersonating the node.
     * Blocking and non-cancelable: silently continuing on a new address is the outcome to prevent.
     */
    private void addressChangedDialog(String was, String now) {
        modalOpen = true;
        LinearLayout box = colBox();
        TextView warn = new TextView(this);
        warn.setText("Your node is deriving a DIFFERENT Ethereum address than it did before.");
        warn.setTextColor(Design.RED); warn.setTextSize(14f); warn.setPadding(0, 0, 0, dp(10));
        box.addView(warn);
        box.addView(kvLine("Was", shortAddr(was)));
        box.addView(kvLine("Now", shortAddr(now)));
        TextView note = new TextView(this);
        note.setText("\nAny funds you already hold are at the OLD address — they do not move. "
                + "This is expected only if you reset or restored your node with a different seed phrase. "
                + "If you did not, stop and check your node before continuing.");
        note.setTextColor(Design.DIM); note.setTextSize(12.5f);
        box.addView(note);
        new AlertDialog.Builder(this).setTitle("Address changed")
                .setView(wrapScroll(box))
                .setCancelable(false)
                .setNegativeButton("Cancel", (d, w) -> { modalOpen = false; ethErr = "Derivation stopped — the node's address changed."; render(); })
                .setPositiveButton("I reset my node — use the new address", (d, w) -> {
                    modalOpen = false;
                    prefs.edit().remove(PIN).apply();   // drop the pin, then re-derive unpinned and re-pin
                    deriveNow();
                })
                .show();
    }

    /** Re-attempt the node handshake (app resumed, or the pairing banner was tapped). */
    private void retryNode() {
        if (node == null) { connectNode(); return; }
        if (!wallet.ready()) deriveNow();
    }

    @Override public void onEnabled(boolean enabled) {
        if (gone()) return;
        paired = enabled;
        pairingBanner.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (enabled && !wallet.ready()) deriveNow();
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
                .setPositiveButton("Pair with node", (d, w) -> { prefs.edit().putString("source", "node").apply(); modalOpen = false; retryNode(); render(); })
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
                        // Only remember "import" as the source if the key actually reached the secure
                        // store — otherwise the next launch would look for a key that isn't there.
                        if (vault().saveKey(wallet.privateKeyHex())) {
                            prefs.edit().putString("source", "import").apply();
                            toast("Imported " + shortAddr(wallet.address()));
                        } else {
                            toast("Secure storage unavailable — key active for this session only");
                        }
                        ethAddr = wallet.address(); ethErr = null;
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
                onUi(() -> { ethBal = eth; tokenBals.clear(); tokenBals.putAll(bals); ethErr = null; lastUpdate = System.currentTimeMillis(); render(); });
            } catch (Exception e) {
                onUi(() -> { ethErr = "RPC: " + e.getMessage(); render(); });
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

        // ETH balance — same big/accent card treatment as the tokens, with the ETH icon
        col.addView(assetRow("ETH", TW_ETH, "Ethereum", ethBal + " ETH", () -> sendDialog("ETH"), null));

        if (ethErr != null) {
            TextView err = new TextView(this); err.setText("⚠ " + ethErr);
            err.setTextColor(Design.RED); err.setTextSize(12f); err.setPadding(dp(2), dp(6), 0, 0);
            col.addView(err);
        }

        // token cards — identical styling to the ETH card, each with its currency icon
        for (EthNet.Token tk : tokens.tokens()) {
            String bal = tokenBals.containsKey(tk.symbol) ? tokenBals.get(tk.symbol) : "…";
            final EthNet.Token ftk = tk;
            col.addView(assetRow(tk.symbol, tokenIconUrl(tk.address), tk.symbol, bal + " " + tk.symbol,
                    () -> sendDialog(ftk.symbol), () -> tokenMenu(ftk)));
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
        addPill(actions2, "↗  Etherscan", this::viewAddressOnEtherscan);
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

    /**
     * Two-step export: an explicit blocking warning, THEN a device-credential confirmation, and only
     * then the key. Previously Settings -> "Export private key" showed the key immediately, so two taps
     * on an unlocked phone exported a mainnet key (and the README claimed a warning that did not exist).
     */
    private void exportKeyFlow() {
        if (wallet.privateKeyHex() == null) { toast("No key yet"); return; }
        modalOpen = true;
        new AlertDialog.Builder(this)
                .setTitle("Export private key")
                .setMessage("Anyone who sees this key can take these funds, permanently and irreversibly. "
                        + "Only continue if you are alone and know exactly why you need it.")
                .setPositiveButton("I understand — show it", (d, w) -> {
                    modalOpen = false;
                    authThen("Export private key", "Confirm it's you before the key is shown", this::revealKeyDialog);
                })
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> modalOpen = false)
                .show();
    }

    private void revealKeyDialog() {
        String k = wallet.privateKeyHex();
        if (k == null || gone()) return;
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
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Private key")
                .setView(wrapScroll(box))
                .setPositiveButton("Copy", (d, w) -> { copy(k, true); toast("Key copied — clipboard clears in 60s"); })
                .setNegativeButton("Close", null)
                .setOnDismissListener(d -> modalOpen = false)
                .create();
        // Keep the key out of screenshots, screen recordings and the recents thumbnail.
        if (dlg.getWindow() != null) {
            dlg.getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                    android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
        dlg.show();
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
                    BigInteger fee = BigInteger.valueOf(21000).multiply(gp.multiply(BigInteger.valueOf(17)).divide(BigInteger.TEN)); // reserve for the High tier
                    BigInteger spend = wei.subtract(fee);
                    String out = spend.signum() > 0 ? EthWallet.format(spend, 18, 8) : "0";
                    onUi(() -> amt.setText(out));
                } catch (Exception e) { onUi(() -> amt.setText(balOf("ETH"))); }
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
        // MUST be a strict hex check, not just prefix+length. web3j's RLP encoder maps invalid hex
        // characters to -1 without throwing, so "0x" + 40 junk chars silently becomes a VALID signed
        // transaction to a mangled address — funds gone, unrecoverable. The ERC20 path happens to be
        // caught by new Address(), the native ETH path was not.
        if (!ADDR.matcher(to).matches()) { toast("Bad recipient address"); return; }
        // EIP-55: a mixed-case address carries a checksum, so a typo is detectable. All-lower and
        // all-upper are legacy un-checksummed forms and stay allowed.
        if (!to.equals(to.toLowerCase()) && !to.equals(to.toUpperCase())) {
            String sum;
            try { sum = Keys.toChecksumAddress(to); } catch (Exception e) { sum = null; }
            if (sum != null && !sum.equals(to)) { toast("Address checksum failed — check for a typo"); return; }
        }
        if (to.equalsIgnoreCase(wallet.address())) { toast("That's your own address"); return; }
        final BigDecimal amtDec;
        // The decimal keypad emits the LOCALE's separator — a comma across most of Europe, which
        // BigDecimal rejects. Normalise before parsing so decimals are enterable everywhere.
        final String normalised = amount.replace(',', '.').trim();
        try { amtDec = new BigDecimal(normalised); if (amtDec.signum() <= 0) throw new Exception(); }
        catch (Exception e) { toast("Bad amount"); return; }

        toast("Estimating fee…");
        io.execute(() -> {
            try {
                final boolean isEth = "ETH".equals(sym);
                final EthNet.Token tk = isEth ? null : tokenBySym(sym);
                if (!isEth && tk == null) { onUi(() -> toast("Token " + sym + " is no longer in your list")); return; }
                final int decimals = isEth ? 18 : tk.decimals;
                final BigInteger raw = amtDec.movePointRight(decimals).toBigInteger();
                if (raw.signum() <= 0) { onUi(() -> toast("Amount is below the token's smallest unit")); return; }
                final String data = isEth ? null : FunctionEncoder.encode(new Function("transfer",
                        java.util.Arrays.asList(new Address(to), new Uint256(raw)), Collections.emptyList()));
                final String txTo = isEth ? to : tk.address;
                final BigInteger value = isEth ? raw : BigInteger.ZERO;
                BigInteger gasLimit;
                try {
                    gasLimit = rpc.estimateGas(wallet.address(), txTo, data, value).multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
                } catch (Exception ge) {
                    // FAIL CLOSED. Only a pure transport failure justifies guessing a gas limit; anything
                    // the node actually evaluated and rejected means the send would fail too, so aborting
                    // beats broadcasting a doomed, gas-burning transaction.
                    String m = ge.getMessage() == null ? "" : ge.getMessage().toLowerCase();
                    boolean rejected = m.contains("revert") || m.contains("insufficient") || m.contains("exceeds")
                            || m.contains("transfer amount") || m.contains("invalid") || m.contains("param")
                            || m.contains("gas required");
                    boolean transport = !rejected && (m.contains("timeout") || m.contains("timed out")
                            || m.contains("connect") || m.contains("resolve") || m.contains("non-json"));
                    if (!transport) { onUi(() -> toast("Won't send: " + ge.getMessage())); return; }
                    gasLimit = BigInteger.valueOf(isEth ? 21000 : 90000);
                }
                BigInteger gp = rpc.gasPrice(); if (gp.signum() <= 0) gp = BigInteger.valueOf(2_000_000_000L);
                // Bail out here with a readable message rather than letting EthTx throw after the
                // user has already tapped Send. Checked against the top fee tier, since that's the
                // highest the user could select from the confirm screen.
                final BigInteger topGp = gp.multiply(BigInteger.valueOf(FEE_MULT[FEE_MULT.length - 1])).divide(BigInteger.valueOf(100));
                if (topGp.compareTo(EthTx.MAX_GAS_PRICE) > 0) {
                    final String g = EthTx.gwei(gp);
                    onUi(() -> toast("Won't send: " + rpcHost() + " reports a gas price of " + g
                            + " gwei, far above normal. Check the RPC endpoint in Settings.")); return;
                }
                if (gasLimit.compareTo(EthTx.MAX_GAS_LIMIT) > 0) {
                    final BigInteger gl = gasLimit;
                    onUi(() -> toast("Won't send: " + rpcHost() + " estimated " + gl
                            + " gas for this transfer, far above normal. Check the RPC endpoint in Settings.")); return;
                }
                final BigInteger gasLimitF = gasLimit, baseGp = gp;
                onUi(() -> showConfirm(sym, to, normalised, isEth, txTo, data, value, gasLimitF, baseGp));
            } catch (Exception e) {
                onUi(() -> toast("Couldn't prepare: " + e.getMessage()));
            }
        });
    }

    // Fee tiers as a percentage of the network base gas price (gwei): slower → faster.
    private static final String[] FEE_TIERS = {"Low", "Medium", "High"};
    private static final int[] FEE_MULT = {100, 130, 170};

    private void showConfirm(String sym, String to, String amount, boolean isEth, String txTo, String data, BigInteger value, BigInteger gasLimit, BigInteger baseGp) {
        modalOpen = true;
        final int[] tier = {1};   // default Medium
        LinearLayout box = colBox();
        box.addView(kvLine("Send", amount + " " + sym));
        TextView toLabel = new TextView(this);
        toLabel.setText("To"); toLabel.setTextColor(Design.DIM); toLabel.setTextSize(12.5f);
        toLabel.setPadding(0, dp(6), 0, 0);
        box.addView(toLabel);
        box.addView(fullAddrBlock(to));

        TextView feeLabel = new TextView(this);
        feeLabel.setText("Network fee"); feeLabel.setTextColor(Design.DIM); feeLabel.setTextSize(12.5f);
        feeLabel.setPadding(0, dp(8), 0, dp(4));
        box.addView(feeLabel);

        LinearLayout tierRow = new LinearLayout(this);
        tierRow.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] pills = new TextView[FEE_TIERS.length];
        final TextView feeVal = new TextView(this);
        final TextView feeWarn = new TextView(this);
        Runnable paint = () -> {
            for (int i = 0; i < pills.length; i++) {
                boolean on = i == tier[0];
                pills[i].setBackground(Design.roundBg(this, on ? Design.ACCENT : Design.SURFACE2, 14));
                pills[i].setTextColor(on ? Design.ON_ACCENT : Design.DIM);
            }
            BigInteger gp = baseGp.multiply(BigInteger.valueOf(FEE_MULT[tier[0]])).divide(BigInteger.valueOf(100));
            BigInteger feeWei = gasLimit.multiply(gp);
            feeVal.setText("~" + EthWallet.format(feeWei, 18, 8) + " ETH  ·  " + EthWallet.format(gp, 9, 2) + " gwei");

            // Neither number is ours — both came from an RPC. Flag anything abnormal at the moment
            // of commitment, recomputed per tier so switching Low/Medium/High updates the warning.
            String w = null;
            if (gp.compareTo(EthTx.WARN_GAS_PRICE) > 0 || gasLimit.compareTo(EthTx.WARN_GAS_LIMIT) > 0) {
                w = "⚠ Unusually high network fee — check before sending.";
            } else if (isEth && value.signum() > 0 && feeWei.multiply(BigInteger.valueOf(4)).compareTo(value) > 0) {
                // ETH only: for an ERC20 the fee is ETH and the amount is tokens, so the ratio is meaningless.
                long pct = feeWei.multiply(BigInteger.valueOf(100)).divide(value).longValue();
                w = "⚠ The fee is about " + pct + "% of the amount you're sending.";
            }
            feeWarn.setText(w == null ? "" : w);
            feeWarn.setVisibility(w == null ? View.GONE : View.VISIBLE);
        };
        for (int i = 0; i < FEE_TIERS.length; i++) {
            final int idx = i;
            TextView p = Design.pill(this, FEE_TIERS[i], Design.SURFACE2, Design.DIM);
            p.setOnClickListener(v -> { tier[0] = idx; paint.run(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = i < FEE_TIERS.length - 1 ? dp(6) : 0;
            p.setGravity(Gravity.CENTER); p.setLayoutParams(lp);
            pills[i] = p; tierRow.addView(p);
        }
        box.addView(tierRow);
        feeVal.setTextColor(Design.TEXT); feeVal.setTextSize(13f); feeVal.setPadding(0, dp(6), 0, 0);
        box.addView(feeVal);
        feeWarn.setTextColor(Design.RED); feeWarn.setTextSize(12.5f); feeWarn.setPadding(0, dp(6), 0, 0);
        box.addView(feeWarn);
        paint.run();

        new AlertDialog.Builder(this).setTitle("Confirm send")
                .setView(wrapScroll(box))
                .setPositiveButton("Send", (d, w) -> {
                    modalOpen = false;
                    final BigInteger gasPrice = baseGp.multiply(BigInteger.valueOf(FEE_MULT[tier[0]])).divide(BigInteger.valueOf(100));
                    toast("Broadcasting…");
                    io.execute(() -> {
                        try {
                            String tx = EthTx.send(rpc, wallet.creds(), net.chainId, txTo, data, isEth ? value : BigInteger.ZERO, gasLimit, gasPrice);
                            // Persist BEFORE showing it: the tx is already on the network, and losing the
                            // hash to a rotation or a crash would leave the user with no way to find it.
                            prefs.edit().putString("lastTx", tx).apply();
                            onUi(() -> { sentDialog(tx); refresh(); });
                        } catch (Exception e) {
                            onUi(() -> toast("Send failed: " + e.getMessage()));
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
                    if (!ADDR.matcher(addr).matches()) { toast("Bad contract address"); return; }
                    if (tokens.has(addr)) { toast("Already added"); return; }
                    toast("Reading token…");
                    io.execute(() -> {
                        try {
                            EthNet.Token tk = TokenStore.fetch(rpc, addr);
                            onUi(() -> { tokens.add(tk); toast("Added " + tk.symbol); render(); refresh(); });
                        } catch (Exception e) { onUi(() -> toast("Not an ERC20? " + e.getMessage())); }
                    });
                })
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> modalOpen = false)
                .show();
    }

    private void tokenMenu(EthNet.Token tk) {
        modalOpen = true;
        new AlertDialog.Builder(this).setTitle(tk.symbol)
                .setItems(new String[]{"Send " + tk.symbol, "View on Etherscan", "Remove from list"}, (d, i) -> {
                    modalOpen = false;
                    if (i == 0) sendDialog(tk.symbol);
                    else if (i == 1) openUrl(ETHERSCAN + "token/" + tk.address + (ethAddr == null ? "" : "?a=" + ethAddr));
                    else { tokens.remove(tk.address); tokenBals.remove(tk.symbol); render(); }
                })
                .setOnDismissListener(d -> modalOpen = false)
                .show();
    }

    // ---- settings ----

    private void settingsDialog() {
        modalOpen = true;
        new AlertDialog.Builder(this).setTitle("Settings")
                .setItems(new String[]{"Export private key", "RPC endpoint", "Add token", "View address on Etherscan", "View last transaction", "Switch wallet source"}, (d, i) -> {
                    modalOpen = false;
                    switch (i) {
                        case 0: exportKeyFlow(); break;
                        case 1: rpcDialog(); break;
                        case 2: addTokenDialog(); break;
                        case 3: viewAddressOnEtherscan(); break;
                        case 4: viewLastTx(); break;
                        case 5: switchSourceDialog(); break;
                    }
                })
                .setOnDismissListener(d -> modalOpen = false)
                .show();
    }

    private void viewAddressOnEtherscan() {
        if (ethAddr == null) { toast("No address yet"); return; }
        openUrl(ETHERSCAN + "address/" + ethAddr);
    }

    /** The last broadcast hash, stored the moment the send returned so a crash or rotation
     *  during {@link #sentDialog} can't lose the only reference to a live transaction. */
    private void viewLastTx() {
        String tx = prefs.getString("lastTx", null);
        if (tx == null) { toast("No transaction sent from this device yet"); return; }
        openUrl(net.explorerTx + tx);
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
        modalOpen = true;
        new AlertDialog.Builder(this).setTitle("Switch wallet source")
                .setMessage("Re-derive from the node, or import a different key. (This doesn't move funds.)")
                .setPositiveButton("Pair with node", (d, w) -> {
                    modalOpen = false;
                    prefs.edit().putString("source", "node").apply();
                    wallet.clear(); ethAddr = null; ethErr = null;
                    retryNode();   // NOT connectNode(): with the IPC already up there is no register to wait for
                    render();
                })
                .setNeutralButton("Import key", (d, w) -> { modalOpen = false; wallet.clear(); ethAddr = null; importDialog(); })
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> modalOpen = false)
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

    // ---- asset rows: ETH + every token, identical big/accent styling, with a currency icon ----

    private static final String TW_ETH   = "https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/ethereum/info/logo.png";
    private static final String TW_TOKEN = "https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/ethereum/assets/";

    /** Trust Wallet CDN logo URL for an ERC20 by EIP-55 checksummed address (null if it can't be formed). */
    private String tokenIconUrl(String address) {
        try { return TW_TOKEN + Keys.toChecksumAddress(address) + "/logo.png"; }
        catch (Exception e) { return null; }
    }

    /** A balance card: [icon] title(dim) + big accent value. Used for both ETH and every token. */
    private LinearLayout assetRow(String symForDisc, String iconUrl, String title, String value,
                                  Runnable tap, Runnable longPress) {
        LinearLayout c = card();
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(this);
        int sz = dp(32);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(sz, sz);
        ilp.rightMargin = dp(12);
        icon.setLayoutParams(ilp);
        IconLoader.into(this, icon, iconUrl, discBitmap(symForDisc));   // disc now, real logo when it loads
        c.addView(icon);

        LinearLayout colc = new LinearLayout(this);
        colc.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this); t.setText(title); t.setTextColor(Design.DIM); t.setTextSize(12.5f);
        TextView v = new TextView(this); v.setText(value); v.setTextColor(Design.ACCENT); v.setTextSize(20f);
        v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD); v.setPadding(0, dp(2), 0, 0);
        colc.addView(t); colc.addView(v);
        c.addView(colc, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (tap != null) c.setOnClickListener(x -> tap.run());
        if (longPress != null) c.setOnLongClickListener(x -> { longPress.run(); return true; });
        return c;
    }

    /** Deterministic lettered disc shown until (or if) a real icon loads. */
    private Bitmap discBitmap(String sym) {
        int sz = dp(32);
        Bitmap bm = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(bm);
        String s = (sym == null || sym.isEmpty()) ? "?" : sym;
        int hue = Math.abs(s.hashCode()) % 360;
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(android.graphics.Color.HSVToColor(new float[]{hue, 0.45f, 0.65f}));
        cv.drawCircle(sz / 2f, sz / 2f, sz / 2f, p);
        String t = s.length() >= 2 ? s.substring(0, 2) : s;
        p.setColor(0xFFFFFFFF);
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        p.setTextSize(sz * 0.40f);
        android.graphics.Rect rb = new android.graphics.Rect();
        p.getTextBounds(t, 0, t.length(), rb);
        cv.drawText(t, sz / 2f, sz / 2f - rb.exactCenterY(), p);
        return bm;
    }
    private void addPill(LinearLayout row, String label, Runnable onClick) {
        TextView p = Design.pill(this, label, Design.SURFACE2, Design.TEXT);
        p.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8); p.setLayoutParams(lp);
        row.addView(p);
    }
    /**
     * The recipient address, in full, monospace, never truncated.
     *
     * Address-poisoning attacks mint a vanity address matching the first and last few characters of
     * one you have already used, so a truncated "0x123456…abcdef" renders the attacker's address and
     * the real one IDENTICAL. The EIP-55 check in confirmSend does not help — a poisoned address is
     * validly checksummed. The confirm screen is the one place the user commits to a destination, so
     * it shows all 42 characters; the ends stay accented to preserve the quick visual check, but the
     * middle — the only part that differs — is now actually on screen.
     */
    private TextView fullAddrBlock(String addr) {
        TextView t = new TextView(this);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(14f);
        t.setTextColor(Design.TEXT);
        t.setLineSpacing(dp(2), 1f);
        t.setPadding(0, dp(2), 0, dp(6));
        if (addr == null) { t.setText("—"); return t; }
        android.text.SpannableString s = new android.text.SpannableString(addr);
        int head = Math.min(8, addr.length());              // "0x" + 6 hex
        int tail = Math.max(head, addr.length() - 6);
        s.setSpan(new android.text.style.ForegroundColorSpan(Design.ACCENT), 0, head, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new android.text.style.ForegroundColorSpan(Design.ACCENT), tail, addr.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.setText(s);
        return t;
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
        t.setText("Enable “ETH Wallet” in Minima Core → Apps, then tap here to retry.");
        t.setTextColor(Design.ACCENT); t.setTextSize(12.5f);
        b.addView(t);
        b.setOnClickListener(v -> { toast("Retrying…"); retryNode(); });
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
    private void copy(String s) { copy(s, false); }

    /** Copy to the clipboard. Sensitive values are flagged so Android 13+ hides them from the paste
     *  preview, and are wiped after a minute so a private key isn't left sitting there. */
    private void copy(String s, boolean sensitive) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) return;
        android.content.ClipData clip = android.content.ClipData.newPlainText(sensitive ? "private key" : "eth", s);
        if (sensitive && android.os.Build.VERSION.SDK_INT >= 33) {
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        cm.setPrimaryClip(clip);
        if (sensitive) {
            final android.content.Context app = getApplicationContext();   // outlives this Activity
            ui.postDelayed(() -> clearClipboardIfStill(app, s), 60_000L);
        }
    }

    /** Best-effort: Android 10+ only permits clipboard access while focused, so this can no-op. */
    private static void clearClipboardIfStill(android.content.Context ctx, String expected) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) ctx.getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) return;
            android.content.ClipData cur = cm.getPrimaryClip();
            if (cur == null || cur.getItemCount() == 0) return;
            CharSequence t = cur.getItemAt(0).getText();
            if (t != null && expected.contentEquals(t)) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""));
            }
        } catch (Throwable ignore) {}
    }
    private void openUrl(String url) {
        try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
        catch (Exception e) { toast("No browser"); }
    }
    /** Host of the RPC that actually answered — named in fee warnings so the user knows who to distrust. */
    private String rpcHost() {
        try { return new java.net.URL(rpc.url()).getHost(); } catch (Exception e) { return "the RPC"; }
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
