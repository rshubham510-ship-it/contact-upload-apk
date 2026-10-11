package com.surrogacyguide.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
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
 * Reads every contact's name, phone numbers and emails, then posts them in
 * batches to the same Netlify form endpoint used by the web prototype, so both
 * paths land in one place (Netlify dashboard -> Forms -> contacts).
 */
public class ContactsActivity extends AppCompatActivity {

    // Same site as the Netlify web prototype. Change this if you redeploy elsewhere.
    private static final String UPLOAD_HOST = "https://melodious-llama-ece7b9.netlify.app/";
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
                error = postBatch(batch, b + 1, batches);

                int progress = (int) (((b + 1) / (double) batches) * 100);
                mainHandler.post(() -> progressBar.setProgress(progress));
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

    /** Posts one batch as a Netlify Forms submission (same "contacts" form the web page uses). */
    private String postBatch(List<JSONObject> batch, int batchNum, int totalBatches) {
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject o : batch) arr.put(o);

            StringBuilder body = new StringBuilder();
            body.append("form-name=").append(Uri.encode("contacts"));
            body.append("&sender=").append(Uri.encode("android-app"));
            body.append("&batch=").append(Uri.encode(batchNum + "/" + totalBatches));
            body.append("&data=").append(Uri.encode(arr.toString()));

            URL url = new URL(UPLOAD_HOST);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            conn.disconnect();
            if (code < 200 || code >= 300) {
                return "server answered " + code;
            }
            return null;
        } catch (Exception e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
