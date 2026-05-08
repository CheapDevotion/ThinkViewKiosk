# ThinkView Kiosk

Minimal Android WebView app that displays a single configured URL in fullscreen, immersive
mode. Built specifically because the off-the-shelf options (Fully Kiosk free, WallPanel) all
have programmatic-config gaps that make them awkward to drive from a one-button-flash workflow.

## What it does

- Single fullscreen `WebView` with JavaScript + DOM storage enabled.
- URL comes from `Intent.getStringExtra("url")` first, then `Intent.getData()` (so `VIEW`
  intents work), falling back to its own `SharedPreferences`.
- Whatever URL it finds gets persisted, so subsequent cold starts (boot autostart, foreground
  re-entry) reuse it.
- Declares `android.intent.category.HOME`, so `cmd package set-home-activity` works.
- `BootReceiver` listens for `BOOT_COMPLETED` and explicitly starts `MainActivity` — belt +
  braces alongside the HOME launcher dispatch.
- Intercepts BACK so a stray gesture can't drop the user out of the kiosk; in-page back-nav
  still works.
- `usesCleartextTraffic="true"` — Home Assistant on a local network is the whole point.

## Build

```powershell
.\Scripts\build-kiosk-apk.ps1
```

Bootstraps a project-local JDK + Android SDK on first run. ~600 MB one-time download. Output:
`ThinkViewInit/Resources/APKs/thinkview-kiosk.apk`, debug-signed.

## Set the URL after install

```
adb shell am start --es url "https://homeassistant.local:8123/lovelace/wallpanel" \
  -n com.thinkview.kiosk/.MainActivity
```

The URL is persisted on first launch — pass `--es url` once and it sticks across reboots.

## Auto-update

Optional. Enabled by checking "Enable silent auto-updates" in the WPF form before provisioning.

**How it works:**
- The kiosk app polls `https://github.com/{owner}/{repo}/releases/latest/download/manifest.json`
  on every boot and every 12 hours via `AlarmManager`.
- Manifest format (see `manifest.json`):
  ```json
  {"versionCode": 5, "apkUrl": "https://github.com/.../thinkview-kiosk.apk"}
  ```
- If `versionCode` is greater than the installed app's `versionCode`, the APK is downloaded
  and handed to `PackageInstaller`.
- With device-owner privileges (set during provisioning by `SetDeviceOwnerStep`), the install
  proceeds silently — no user tap.

**Releasing a new version:**

1. Bump `versionCode` in `KioskApp/AndroidManifest.xml`
2. `.\Scripts\build-kiosk-apk.ps1`
3. Create a new GitHub Release tagged `v<versionCode>`
4. Upload `ThinkViewInit/Resources/APKs/thinkview-kiosk.apk` and a `manifest.json` with the new
   `versionCode` to the release
5. Mark it "latest"

All your devices pick it up within 12 hours (or on next boot).

**Device-owner caveats:**
- Set device-owner ONLY on a device with no Google account. The provisioning pipeline runs
  debloat first which gets the device into the right state — but if you've added an account
  manually, factory-reset before re-provisioning.
- Once set, device-owner can't be removed without factory reset. That's actually what we want
  for a wall-mounted kiosk: nothing can override us.

## Setting the GitHub repo on an already-deployed device

```
adb shell am start \
  --es repo_owner "your-username" \
  --es repo_name  "your-repo" \
  -n com.thinkview.kiosk/.MainActivity
```

Repo config is persisted alongside the dashboard URL.
