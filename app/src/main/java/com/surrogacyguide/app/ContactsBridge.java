package com.surrogacyguide.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JS bridge for the My Donors page. Callable from JavaScript as window.DB.
 *
 * Calls run on the WebView's JS bridge thread, not the UI thread, so the bulk
 * contact read never janks the screen.
 *
 * Tags and details live on the phone only, keyed by the last 10 digits of the
 * number, so they survive contact id changes and re-syncs.
 */
public class ContactsBridge {

    private static final String PREFS = "book";

    private final Context ctx;
    private final SharedPreferences prefs;

    public ContactsBridge(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = this.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String phone) {
        if (phone == null) return "";
        String d = phone.replaceAll("[^0-9]", "");
        if (d.length() > 10) d = d.substring(d.length() - 10);
        return d;
    }

    @JavascriptInterface
    public String getRole() {
        return ctx.getSharedPreferences("gate", Context.MODE_PRIVATE).getString("role", "");
    }

    @JavascriptInterface
    public String getTags() {
        return prefs.getString("tags", "{}");
    }

    @JavascriptInterface
    public void setTag(String phone, String persona) {
        try {
            JSONObject all = new JSONObject(prefs.getString("tags", "{}"));
            String k = key(phone);
            JSONObject row = all.optJSONObject(k);
            if (row == null) row = new JSONObject();
            if (persona == null || persona.isEmpty()) row.remove("p");
            else row.put("p", persona);
            all.put(k, row);
            prefs.edit().putString("tags", all.toString()).apply();
        } catch (Exception ignored) { }
    }

    @JavascriptInterface
    public void setDetail(String phone, String field, String value) {
        try {
            JSONObject all = new JSONObject(prefs.getString("tags", "{}"));
            String k = key(phone);
            JSONObject row = all.optJSONObject(k);
            if (row == null) row = new JSONObject();
            if (value == null || value.isEmpty()) row.remove(field);
            else row.put(field, value);
            all.put(k, row);
            prefs.edit().putString("tags", all.toString()).apply();
        } catch (Exception ignored) { }
    }

    @JavascriptInterface
    public void share(String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent wa = new Intent(i);
        wa.setPackage("com.whatsapp");
        try { ctx.startActivity(wa); }
        catch (Exception e) { ctx.startActivity(Intent.createChooser(i, "Share")); }
    }

    @JavascriptInterface
    public void call(String number) {
        try {
            Intent i = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) { }
    }

    /** Bulk phonebook read. One pass, then three lookups for note, birthday and label. */
    @JavascriptInterface
    public String getContacts() {
        Map<String, JSONObject> people = new LinkedHashMap<>();
        ContentResolver cr = ctx.getContentResolver();

        String[] P = {
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
        };
        Cursor c = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, P,
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    String name = c.getString(1);
                    String num = c.getString(2);
                    if (id == null || num == null) continue;
                    String k = key(num);
                    if (k.length() < 7) continue;
                    JSONObject o = people.get(id);
                    if (o == null) {
                        o = new JSONObject();
                        o.put("id", id);
                        o.put("name", name == null ? "" : name);
                        o.put("phones", new JSONArray());
                        people.put(id, o);
                    }
                    JSONArray ph = o.getJSONArray("phones");
                    boolean dup = false;
                    for (int i = 0; i < ph.length(); i++) {
                        if (k.equals(key(ph.getString(i)))) { dup = true; break; }
                    }
                    if (!dup) ph.put(num);
                }
            } catch (Exception ignored) {
            } finally { c.close(); }
        }

        addNotes(cr, people);
        addBirthdays(cr, people);
        addLabels(cr, people);

        List<JSONObject> list = new ArrayList<>(people.values());
        Collections.sort(list, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) {
                return a.optString("name").compareToIgnoreCase(b.optString("name"));
            }
        });
        JSONArray out = new JSONArray();
        for (JSONObject o : list) out.put(o);
        return out.toString();
    }

    private void addNotes(ContentResolver cr, Map<String, JSONObject> people) {
        String[] N = {ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.Note.NOTE};
        Cursor c = cr.query(ContactsContract.Data.CONTENT_URI, N,
                ContactsContract.Data.MIMETYPE + "=?",
                new String[]{ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE}, null);
        if (c == null) return;
        try {
            while (c.moveToNext()) {
                JSONObject o = people.get(c.getString(0));
                if (o != null) o.put("note", c.getString(1) == null ? "" : c.getString(1));
            }
        } catch (Exception ignored) {
        } finally { c.close(); }
    }

    private void addBirthdays(ContentResolver cr, Map<String, JSONObject> people) {
        String[] B = {ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.Event.START_DATE};
        Cursor c = cr.query(ContactsContract.Data.CONTENT_URI, B,
                ContactsContract.Data.MIMETYPE + "=? AND " + ContactsContract.CommonDataKinds.Event.TYPE + "=?",
                new String[]{ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
                        String.valueOf(ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)}, null);
        if (c == null) return;
        try {
            while (c.moveToNext()) {
                JSONObject o = people.get(c.getString(0));
                if (o != null) o.put("bday", c.getString(1) == null ? "" : c.getString(1));
            }
        } catch (Exception ignored) {
        } finally { c.close(); }
    }

    private void addLabels(ContentResolver cr, Map<String, JSONObject> people) {
        String[] G = {ContactsContract.Data.CONTACT_ID, ContactsContract.Groups.TITLE};
        Cursor c = cr.query(ContactsContract.Data.CONTENT_URI, G,
                ContactsContract.Data.MIMETYPE + "=?",
                new String[]{ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE}, null);
        if (c == null) return;
        try {
            while (c.moveToNext()) {
                JSONObject o = people.get(c.getString(0));
                String t = c.getString(1);
                if (o != null && t != null && !t.startsWith("My Contacts")) o.put("label", t);
            }
        } catch (Exception ignored) {
        } finally { c.close(); }
    }
}
