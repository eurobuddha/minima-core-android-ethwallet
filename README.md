# ETH Wallet (native Android)

A standalone native Android **Ethereum ERC20 wallet** whose key is **derived from your Minima node's seed** — the
same Ethereum address the [minimaSwap](https://github.com/eurobuddha/minima-core-android-minimaswap)/AtomiX HTLC
swaps use — so it needs no separate backup. Mainnet only. Package `com.eurobuddha.ethwallet`.

## Two ways to get the key (chosen on first run)

1. **Pair with your node** — derive the key via the node command `seedrandom modifier:ethbridge` (the same modifier
   the upstream bridge/AtomiX use). The PRNG lives inside Minima Core, so the key is re-derived each session and
   never stored; it needs the node installed, running, write-enabled, and this app enabled in **Minima Core → Apps**.
   The reply must be exactly 64 hex characters, and the **derived address is pinned on first use** — if the node ever
   derives a different address (a reset or restored-from-another-seed node, or something impersonating the node) the
   wallet refuses to adopt the new key and tells you your funds are still at the old address.
2. **Import a key** — paste a `0x` + 64-hex private key (e.g. exported from AtomiX). It's stored **encrypted** in a
   Keystore-backed `EncryptedSharedPreferences` (AES-256), and the wallet runs node-free.

> The encrypted store is excluded from cloud backup and device transfer, and `allowBackup` is off. Its Tink keyset is
> sealed by a device-bound Keystore key that never leaves the phone, so a *restored* copy could never be decrypted —
> it would only crash the app. If the store is ever found unreadable (restore, Keystore reset, lockscreen change) the
> wallet discards it, says so, and asks you to re-import. **A node-derived wallet is unaffected — it stores nothing.**

## Features

- **Assets dashboard** — native ETH balance + every ERC20 in your token list, each on a big card with its currency
  icon (Trust Wallet CDN by EIP-55 checksummed address, lettered-disc fallback), pull to **Refresh**.
- **Send** — token picker, recipient with **QR scan** and MAX, a live gas preview, and **Low / Medium / High fee
  tiers** (×1.0 / 1.3 / 1.7 of the network gas price) with a fee + gwei estimate before you confirm. Recipients are
  strictly hex-validated and **EIP-55 checksum-verified**, and a send whose gas estimate the node rejects is aborted
  rather than broadcast. The confirm screen shows the **full 42-character recipient address** — never a truncated
  one, which is what makes address-poisoning attacks work. Gas price and gas limit come from the RPC, so both are
  bounded: a warning above 500 gwei / 400k gas, and a hard refusal to sign above 1500 gwei / 1M gas.
- **Receive** — your address + QR (same address on every EVM network).
- **Add token** — paste an ERC20 contract address; symbol + decimals are read from the chain (`symbol()`/`decimals()`).
  Seeded with USDT / USDC / DAI / WETH; remove via long-press.
- **Settings** — export the private key (explicit warning **and** a device-credential confirmation; the reveal is
  `FLAG_SECURE` and the clipboard self-clears after 60s), override the RPC endpoint, re-open your last transaction on
  Etherscan, switch key source.
- **View on Etherscan** — keyless deep links for your address, a token, or a broadcast tx (no Etherscan API key).

## How it works

Ethereum is an embedded **web3j** wallet (crypto/ABI/RLP only; JSON-RPC over `HttpURLConnection` with a keyless
public-node fallback chain). Transactions are **signed locally** (legacy EIP-155) and broadcast via
`eth_sendRawTransaction`; the node is only ever used to derive the key (node-paired mode). History is viewed on
Etherscan rather than indexed in-app.

## Build

Requires a **JDK 17/21** (the Android Studio JBR works):

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleRelease
```

Install, then (node-paired mode only) enable **ETH Wallet** in Minima Core → Apps.

## Releases

Versioned APKs are published to the [PandaApps catalog](https://github.com/eurobuddha/minima-core-apks)
(`apks.json`). Current: **v0.2.2**.
