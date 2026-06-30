# Minima History (native Android)

A **node-only, persistent** transaction-history app for [Minima](https://minima.global). It mirrors the
local node's `history` into a **permanent local database** and shows it as a searchable list.

**Why:** the Minima node **prunes** its own history over time. This app captures it and **keeps it
forever** — a transaction stays in the local record even after the node drops it. The node forgets; this
app remembers.

It is **read-only** (never builds transactions) and **100% explorer-free** — every byte comes from the
local node over the broadcast-Intent IPC (`minimaapi`). No internet permission.

## How it works

- **Sync** = `history relevant:true max:25 offset:N`, paged newest-first into a SQLite DB keyed by
  `txpowid` (idempotent). It stops at the first already-stored txpow (caught up) or a short page (end of
  what the node retains); first run pages gently to the end (one-time backfill).
- **IPC-safe by design:** `history` is the heavy command that can overwhelm an un-hardened node, so sync
  is bounded (`max:25` ≈190 KB/page), incremental, debounced on `NEWBLOCK`, with a delay between pages —
  never an unbounded loop.
- **Direction + amount** come from the node's `details.difference` (net per-token effect): positive →
  received, negative → sent, zero → self.
- **The list is served from the local DB** — instant, offline, searchable. The node is touched only to sync.

## Scope

Relevant transactions only (`history relevant:true`): the wallet's default 64 addresses, any new
addresses, and contract transactions it's involved in. Captures the node's current retained history at
install + everything forward (history pruned *before* first sync is unrecoverable — node-only, no explorer).

## Build
Requires a **JDK 17/21** (the Android Studio JBR works):

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

Install, then enable **Minima History** in Minima Core → Apps to authorize the IPC.

## Releases
Versioned APKs + changelog: **[eurobuddha/minima-core-apks](https://github.com/eurobuddha/minima-core-apks)**
(tags `minima-history-v<version>`).

## Layout
- `org/minimarex/history/` — `MainActivity` (list + search + detail), `HistoryDb` (persistent txpowid-keyed
  SQLite), `HistoryEntry` (parser), `HistorySync` (bounded paged sync), `HistoryDesign`; reused `NodeApi`
  (IPC), `TokenMeta`, `Util`.
