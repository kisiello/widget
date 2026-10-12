# Armor 34 Pro — on-device MediaTek calculated-SoC widget (experimental source)

An Android **AppWidgetProvider** project that displays the MediaTek fuel-gauge's
calculated `soc` estimate on a resizable home-screen tile. **No computer/USB/ADB
connection is needed while the widget is running.** It makes its measurements
on the phone using a Shizuku UserService with Android's `shell` identity.

**This is a source project, not an APK. It has NOT been compiled or tested on
the Armor 34 Pro.** It is an implementation proposal; do not treat it as a
verified installable fix.

## Why Shizuku is needed

The Ulefone Armor 34 Pro build 1788353813 has:

- Public `/sys/class/power_supply/battery/capacity` incorrectly fixed at 50%.
- Internal MediaTek kernel logs `car[...] soc:73` or
  `[FGADC_intr_end]soc:7317` (73.17%).
- Unprivileged app access to `mtk-gauge` and `UI_SOC` denied by SELinux.

A regular widget can only see Android's faulty 50% value. This project uses
Shizuku (Android 11+ on-device wireless debugging) to execute `bugreportz -p`
with the shell UID, open the just-created report inside the shell-side service,
extract the newest `soc` reading, return only this number to the widget, and
**delete the temporary bugreport**. No battery control/calibration files are
modified.

### Important limitations

- **Shizuku needs to be started/restarted after reboot** on a stock unrooted
  device, typically by opening Shizuku on the phone and tapping Start.
- Shizuku's ability to invoke `bugreportz -p` from a UserService on the specific
  Ulefone build is **unverified**; ADB shell worked, but this is a different
  execution environment. If the action is blocked, the widget will show an
  error/stale data rather than guessing a value.
- Android schedules a refresh approximately every **15 minutes** through
  JobScheduler; power management may delay it. A full bugreport is more costly
  than sampling one sysfs property, so this cannot be a per-second gauge.
- The calculation is a model estimate and **not verified as true charge**.
  Android's original icon still shows 50%. Only a modified System UI could
  replace the built-in status-bar battery value.
- Generated bugreports can be sensitive. The shell-side reader opens them,
  extracts SoC, then deletes the newly generated archive. No reports are
  uploaded, and the application requests no Internet permission.

## Build a debug APK without installing Android Studio (GitHub Actions)

This archive includes `.github/workflows/build-apk.yml`. The workflow needs a
GitHub account, an Internet connection on the GitHub runner, and a repository
that contains the **contents** of this folder (including `.github/`).

1. Create a new GitHub repository.
2. Upload all the files and folders from this project into the repository root,
   preserving the hidden `.github/workflows/build-apk.yml` path.
3. In GitHub select **Actions → Build experimental Android widget APK → Run workflow**
   (or push to the default `main`/`master` branch).
4. If the build succeeds, open its completed run, download the artifact named
   `armor34-soc-widget-debug-apk`, and extract `app-debug.apk`.
5. Install the APK on the phone. Start Shizuku using Wireless debugging and
   grant permission inside the app before adding the widget.

**Not yet compiled or device-tested in this environment.** If the workflow
fails, check its `Assemble debug APK` step to see the compiler error. The widget's
on-device bugreport access also remains unverified on Armor 34 Pro.

## Build an APK locally using Android Studio

1. Open this folder as a project in **Android Studio**.
2. Let it download the Android SDK 35 platform, Android Gradle Plugin 8.7.3,
   and Shizuku API 13.1.5 dependencies.
3. Build **Build > Build APK(s)**, then install the debug APK on the phone.
4. Install official [Shizuku](https://shizuku.rikka.app/) and pair it using
   **Wireless debugging**, using only the phone.
5. Open Armor SoC, grant Shizuku permission, and tap **Add home-screen widget**.
6. Tap **Refresh from phone now**. Background refresh is requested every
   ~15 minutes as long as Shizuku and Android's scheduler allow it.

After the one-time installation, **no PC is required for runtime**.
A build from the project is required; this archive doesn't contain an APK.

## Debugging first on the phone

Using Shizuku's shell (e.g. [aShell You](https://github.com/DP-Hridayan/aShellYou)):

```sh
bugreportz -p
```

Expected protocol output ends with `OK:/data/.../bugreport-....zip`.
If this is denied on your build, the project will not obtain the same SoC by
this method. Do not assume Shizuku provides root: it normally runs as UID 2000.

## Code architecture

- `SocWidgetProvider`: real Android home-screen widget, marks stale samples.
- `MainActivity`: setup, permission, widget pinning, manual refresh.
- `SocRefreshJob`: Android JobScheduler periodic/background execution.
- `SocPoller`: binds Shizuku UserService and stores the new sample.
- `SocUserService`: shell-UID bugreport capture and streaming ZIP parser.
- `ISocReader.aidl`: on-device IPC for SoC results.

No PC bridge script is used or included in this variant.
