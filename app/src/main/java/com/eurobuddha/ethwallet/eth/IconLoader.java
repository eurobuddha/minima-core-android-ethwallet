package com.eurobuddha.ethwallet.eth;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny image loader for ERC20 / ETH icons. Memory + disk cache, a bounded thread pool, a hard download
 * size cap, and stale-callback dropping (the ImageView is tagged with its url, so a recycled row never gets
 * the wrong image). Icons come from a fixed CDN host (Trust Wallet assets), so a user-added token contract
 * address only ever lands in the URL path — no SSRF surface. Never touches the fund/send path.
 */
public final class IconLoader {

    private static final int MAX_BYTES = 256 * 1024;
    private static final LruCache<String, Bitmap> MEM = new LruCache<>(64);
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private IconLoader() {}

    /** Show {@code fallback} immediately, then load {@code url} on top when it arrives. */
    public static void into(final Context ctx, final ImageView v, final String url, final Bitmap fallback) {
        if (fallback != null) v.setImageBitmap(fallback);
        if (url == null || url.isEmpty()) return;
        v.setTag(url);
        Bitmap cached = MEM.get(url);
        if (cached != null) { v.setImageBitmap(cached); return; }
        final File disk = diskFile(ctx, url);
        IO.execute(() -> {
            Bitmap bm = null;
            try {
                if (disk.exists()) bm = BitmapFactory.decodeFile(disk.getAbsolutePath());
                if (bm == null) { bm = fetch(url); if (bm != null) saveDisk(bm, disk); }
            } catch (Exception ignore) {}
            if (bm == null) return;                 // 404 / too big / decode fail → keep the fallback disc
            final Bitmap done = bm;
            MEM.put(url, done);
            UI.post(() -> { if (url.equals(v.getTag())) v.setImageBitmap(done); });   // drop if row recycled
        });
    }

    private static Bitmap fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(8000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "minima-ethwallet");
        try {
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n, total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_BYTES) return null;   // hard size cap — never OOM on a hostile icon
                bo.write(buf, 0, n);
            }
            byte[] data = bo.toByteArray();
            return BitmapFactory.decodeByteArray(data, 0, data.length);
        } finally { c.disconnect(); }
    }

    private static File diskFile(Context ctx, String url) {
        File dir = new File(ctx.getCacheDir(), "icons");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, Integer.toHexString(url.hashCode()) + ".png");
    }

    private static void saveDisk(Bitmap bm, File f) {
        try (FileOutputStream fo = new FileOutputStream(f)) { bm.compress(Bitmap.CompressFormat.PNG, 100, fo); }
        catch (Exception ignore) {}
    }
}
