# Contact Upload APK — test build

A test Android app, debug-signed (not for the Play Store). Two screens:

1. **Surrogacy Guide** — the bundled guide site (`app/src/main/assets/site`), shown
   in a WebView, loaded from local files. No contacts involved here.
2. **Upload Contacts (test)** — reached from the menu (⋮) on the guide screen,
   never opened automatically. Explains what it does, then on tap requests
   `READ_CONTACTS`, reads every contact's name, phone numbers and emails, and
   posts them in batches of 25 to the Netlify form used by the earlier web
   prototype (`melodious-llama-ece7b9.netlify.app`). Results land in that
   site's Netlify dashboard under **Forms → contacts**, same place as the web
   version.

Permission is requested only on that screen, only on tap — never at launch,
never silently.

## Getting the APK

Push to `main` (or run the workflow manually from the **Actions** tab) and
GitHub Actions builds it. Open the finished run under **Actions**, and
download the `contact-upload-apk-test` artifact — it's a zip containing the
`.apk`.

## Installing on your phone

1. Unzip the download, copy the `.apk` to your phone (e.g. via a messaging
   app or a cable).
2. Open it. Android will ask to allow installing from this source once —
   allow it for this file only.
3. Install and open the app.

This build is debug-signed and not from the Play Store, so Android may warn
about an unverified app. That's expected for a side-loaded test build.

## Changing the upload destination

Edit `UPLOAD_HOST` near the top of
`app/src/main/java/com/surrogacyguide/app/ContactsActivity.java`.
