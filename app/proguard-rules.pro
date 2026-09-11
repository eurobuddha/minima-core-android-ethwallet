# R8 / ProGuard rules.
#
# Shrinking is ON for release. Without it the APK carries all of web3j and its transitive
# dependencies whole (17.9 MB of dex for ~2k lines of source), which leaves no room for a second
# chain's crypto library. These rules keep the parts that are reached reflectively and would
# otherwise be stripped or renamed — R8 removes them silently and the failure only shows up at
# runtime, mid-transaction, so err towards keeping.

# ---- Crashes should stay readable -------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,EnclosingMethod,InnerClasses
-renamesourcefileattribute SourceFile

# ---- BouncyCastle -----------------------------------------------------------------------------
# The secp256k1 provider is looked up by algorithm NAME through the JCA, so nothing references
# these classes directly. Strip them and signing dies at runtime.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
# The JDK-internal provider variants BC probes for are absent on Android; that is expected.
-dontwarn javax.naming.**
-dontwarn java.awt.**

# ---- web3j ------------------------------------------------------------------------------------
# Keep the WHOLE abi package, deliberately.
#
# The first pass at these rules kept only `abi.datatypes` + TypeReference, and R8 responded by
# removing FunctionEncoder and FunctionReturnDecoder outright (mapping.txt: R8$$REMOVED$$CLASS$$199
# and $$200) — almost certainly inlining them, since proguard-android-optimize enables the
# optimizer. Those two build ERC20 transfer calldata and decode balanceOf/decimals/symbol, so if the
# optimizer ever got that inlining wrong the failure is a wrong transfer, not a crash. Not a bet
# worth taking for a few KB. Keep them whole and let the optimizer work everywhere else.
-keep class org.web3j.abi.** { *; }

# ...and, critically, OUR anonymous subclasses of it.
#
# Every ERC20 read is written `new TypeReference<Uint256>() {}` (EthWallet.erc20BalanceRaw,
# TokenStore.fetchDecimals/fetchSymbol). TypeReference.getType() recovers the type argument via
# getClass().getGenericSuperclass() — so the ANONYMOUS SUBCLASS, which lives in this app's own
# package and is therefore fair game for the obfuscator, must keep its generic signature and must
# not be merged away.
#
# Caught on-device: with only the rules above, native ETH balance (no TypeReference) read fine while
# every single ERC20 balance rendered as "—". refresh() catches per-token exceptions, so the failure
# was completely silent — no crash, nothing in logcat. mapping.txt showed the cause:
# `EthWallet$2 -> l0.f` renamed, `TokenStore$1`/`$2` gone entirely.
-keep,includedescriptorclasses class * extends org.web3j.abi.TypeReference { *; }
-keep,includedescriptorclasses class * extends org.web3j.abi.datatypes.Type { *; }
-keepclassmembers class * extends org.web3j.abi.TypeReference { <init>(...); }
-keep class org.web3j.crypto.** { *; }
-keep class org.web3j.rlp.** { *; }
-keep class org.web3j.utils.** { *; }
-dontwarn org.web3j.**
# web3j drags optional deps we do not ship (okhttp/jackson/rxjava live in modules we exclude).
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn com.fasterxml.jackson.**
-dontwarn io.reactivex.**
-dontwarn org.slf4j.**

# ---- Minima IPC -------------------------------------------------------------------------------
# minimaapi.aar is a vendored binary whose broadcast payloads are keyed on class/field names.
-keep class org.minimarex.minimaapi.** { *; }
-dontwarn org.minimarex.minimaapi.**

# ---- AndroidX Security / Tink -----------------------------------------------------------------
# Tink resolves key managers by protobuf type URL through reflection. KeyVault self-heals a broken
# store, so an R8-induced failure here would look like a spurious "secure storage was reset".
-keep class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**

# ---- ZXing ------------------------------------------------------------------------------------
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**

# ---- This app ---------------------------------------------------------------------------------
# BuildConfig.VERSION_NAME is read for the header line.
-keep class com.eurobuddha.ethwallet.BuildConfig { *; }
