# Traffic Volume Survey Tool: Android app

A native Android shell around the existing web app. The web app https://www.trafficcount.vercel.app/ , so counting, PCE/PCU, peak hour factor, charts, CSV import/export and the
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

## Install
| Download the apk from the "releases" section and install afterwards. Android only. |
