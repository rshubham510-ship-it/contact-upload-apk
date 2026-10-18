package com.surrogacyguide.app;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;

/**
 * The app is gated behind READ_CONTACTS: until the user allows contacts, a
 * full-screen panel covers the app and nothing is usable. Deny it and the
 * panel stays until they allow.
 *
 * External links (WhatsApp, insurers, PDFs) are handed to the OS, so the
 * WhatsApp button actually opens the WhatsApp app instead of loading wa.me
 * inside the WebView. app:// links open the contacts screen.
 *
 * Contacts sync silently on launch and daily in the background via WorkManager.
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "gate";
    private static final String KEY_BATTERY_ASKED = "battery_asked";

    private WebView webView;
    private ActivityResultLauncher<String> permissionLauncher;

    private View gate;
    private TextView gateBody;
    private boolean askedOnce = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        webView = findViewById(R.id.webView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);

        // Anything that is not a local file:// page gets handed to the OS.
        // app:// opens the contacts screen. wa.me goes to WhatsApp.
        // https goes to the browser.
        webView.setWebViewClient(new WebViewClient() {
            private boolean handle(Uri uri) {
                String scheme = uri.getScheme() == null ? "" : uri.getScheme();
                if (scheme.equals("app")) {
                    startActivity(new Intent(MainActivity.this, ContactsActivity.class));
                    return true;
                }
                if (scheme.equals("file")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handle(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handle(Uri.parse(url));
            }
        });

        SwipeRefreshLayout swipe = findViewById(R.id.swipeRefresh);
        swipe.setOnRefreshListener(() -> {
            webView.reload();
            swipe.setRefreshing(false);
        });

        webView.loadUrl("file:///android_asset/site/donor.html");

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        onPermissionGranted();
                    } else {
                        askedOnce = true;
                        updateGateMessage();
                    }
                });

        if (AutoSync.hasPermission(this)) {
            onPermissionGranted();
        } else {
            showGate();
            requestContacts();
        }
    }

    // ---------- permission gate ----------

    private void onPermissionGranted() {
        hideGate();
        AutoSync.scheduleDaily(this);
        AutoSync.scheduleNow(this, true);
        maybeAskBattery();
    }

    private void requestContacts() {
        boolean permanentlyDenied = askedOnce
                && !shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS);
        if (permanentlyDenied) {
            openAppSettings();
        } else {
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS);
        }
    }

    private void showGate() {
        if (gate != null) return;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.WHITE);
        int pad = dp(32);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Set up Donor Book");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Color.parseColor("#1A1A1A"));
        box.addView(title);

        gateBody = new TextView(this);
        gateBody.setTextSize(16);
        gateBody.setGravity(Gravity.CENTER);
        gateBody.setTextColor(Color.parseColor("#444444"));
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dp(18);
        box.addView(gateBody, bodyLp);

        Button btn = new Button(this);
        btn.setText("Allow contacts");
        btn.setAllCaps(false);
        btn.setTextSize(17);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(30);
        box.addView(btn, btnLp);
        btn.setOnClickListener(v -> requestContacts());

        gate = box;
        addContentView(gate, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        updateGateMessage();
    }

    private void updateGateMessage() {
        if (gateBody == null) return;
        gateBody.setText(askedOnce
                ? "Donor Book cannot continue without your contacts.\n\n"
                  + "Tap Allow contacts below, then choose Allow."
                : "Donor Book reads your contacts so it can build your list of donors, "
                  + "clinics and parents, and share a profile on WhatsApp in one tap.\n\n"
                  + "Nothing is posted publicly.");
    }

    private void hideGate() {
        if (gate != null && gate.getParent() instanceof ViewGroup) {
            ((ViewGroup) gate.getParent()).removeView(gate);
        }
        gate = null;
        gateBody = null;
    }

    private void openAppSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception ignored) { }
    }

    /** Asks once for the battery-optimization exemption, which keeps background sync alive. */
    private void maybeAskBattery() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (p.getBoolean(KEY_BATTERY_ASKED, false)) return;
        p.edit().putBoolean(KEY_BATTERY_ASKED, true).apply();

        new Handler(getMainLooper()).postDelayed(() -> {
            try {
                Intent i = new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception ignored) { }
        }, 1200);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Covers the case where the user granted permission from Settings.
        if (gate != null && AutoSync.hasPermission(this)) {
            onPermissionGranted();
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
