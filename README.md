# ThinkView Kiosk

Minimal Android kiosk app for the Lenovo ThinkSmart View (CD-18781Y). Loads a single configured
URL in a fullscreen Mozilla GeckoView, with mDNS resolution for `.local` hostnames, silent
self-update from GitHub Releases, and device-owner privileges so installs don't prompt.

## What it does

- Single fullscreen `GeckoView` (Mozilla's embedded Firefox engine — bundled because the
  CD-18781Y's stock WebView is Chromium 61 from 2017 and can't run modern JavaScript).
- URL comes from `Intent.getStringExtra("url")`, then `Intent.getData()` (VIEW intents work),
  falling back to persisted `SharedPreferences`.
- mDNS resolver for `.local` hostnames — Android 8.1 doesn't have one natively.
- Declares `android.intent.category.HOME` so `cmd package set-home-activity` works.
- `BootReceiver` starts `MainActivity` on `BOOT_COMPLETED` and schedules a 12-hour
  update-check `AlarmManager`.
- `UpdateChecker` polls GitHub Releases and silently installs newer APKs via `PackageInstaller`
  (requires device-owner, set during provisioning).
- Intercepts BACK so a stray gesture can't drop the user out of the kiosk.
- `usesCleartextTraffic="true"` — local Home Assistant is the whole point.

## Build

```powershell
.\Scripts\build-kiosk-apk.ps1
```

Driven by Gradle (the Mozilla GeckoView dep is published only via Maven AAR, so a direct
javac+d8 toolchain isn't viable). First run downloads Gradle 8.9 + AGP 8.7 + GeckoView (~250 MB
total) into `~/.gradle`. Subsequent builds are incremental (~10 s).

Output: `ThinkViewInit/Resources/APKs/thinkview-kiosk.apk` (~57 MB), debug-signed against
`Tools/debug.keystore`.

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
