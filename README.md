# Traffic Volume Survey Tool: Android app

A native Android shell around the existing web app. The web app (`app/src/main/assets/www/index.html`)
is bundled **unchanged**, so counting, PCE/PCU, peak hour factor, charts, CSV import/export and the
PDF report all behave exactly as on the web. It works fully offline and needs no network permission.

## What the shell adds
| Web app behaviour | How the APK handles it |
|---|---|
| Saved data (`localStorage`) | WebView DOM storage on a fixed secure origin, so data persists between launches |
| CSV / PNG download buttons | Saved to the phone's **Downloads** folder (toast confirms) |
| "Select CSV Files" import | Android file picker, multiple files allowed |
| PDF report (pop-up + `window.print()`) | Opens in an in-app report screen; Android's print dialog appears, choose **Save as PDF** |
| Phone back button | Steps back through the app's tabs like the browser, closes the report first, then exits |
| Dark / light theme | Status and navigation bars follow the theme |
| Rotation, keyboard | Handled without reloading, so a running count is never lost |
| Service worker, install button | Not needed; the app is installed from the APK |

## Build the APK

**Option A: GitHub (no setup).** Push this folder to a GitHub repo. The *Build APK* workflow in
`.github/workflows` runs automatically. Download `TrafficSurvey-apk` from the run's *Artifacts*.

**Option B: Android Studio.** *File > Open* this folder, let it sync, then
*Build > Build Bundle(s) / APK(s) > Build APK(s)* (choose the **release** variant).

**Option C: command line.** Install JDK 17, the Android SDK and Gradle 8.7, then run `./build.sh`.
Output: `app/build/outputs/apk/release/app-release.apk`.

## Install
Copy the APK to the phone, open it, and allow "install unknown apps" for your file manager or browser
when asked. Requires Android 10 or newer.

## Notes
- `keystore/trafficsurvey.jks` is a personal signing key (password `trafficsurvey`) committed on purpose so
  that every build is signed the same way and new versions install over old ones. For Google Play, create
  your own key and change `signingConfigs` in `app/build.gradle`.
- To update the app after changing the web app, replace the files in `app/src/main/assets/www/`
  (keep `index.html`, `fonts/`, `vendor/`, `icons/`), raise `versionCode` in `app/build.gradle`, and rebuild.
- The native code is in `app/src/main/java/com/trafficsurvey/app/MainActivity.java` and
  `app/src/main/assets/android-shim.js`.
