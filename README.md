# RepeatRemind

A small, standalone Android reminder app. Native Java and Android framework views;
no runtime libraries, accounts, ads, analytics, network access or background service.
Requires Android 8.0 or newer. Tasks are stored only in the app's private local preferences.
Android cloud backup and device transfer are disabled.

## Use

- Tap **+** to add a task. An empty list shows only an arrow and the add button below the header.
- Choose every day, selected weekdays, weekly, bi-weekly, monthly, or yearly.
- Weekly repeats from the start date. Strict biweekly repeats every **14 local calendar days**
  from the start date, across month, year and daylight-saving boundaries.
- The biweekly calendar alternative lets you select any combination of the **1st**, **15th**,
  and **last day** of each month. These are independent monthly dates, not 14-day intervals.
- Monthly dates clamp to the last day of short months without changing the requested day
  for later months. February 29 yearly reminders use February 28 outside leap years.
- Enable **Add reminder**, enter notification text, and select a local reminder time.
  Blank notification text defaults to the task name. Tasks can exist without a notification.
- Tap a task to edit, pause, or delete it. **Theme** selects Light, Dark, or Colourblind.
  Selected weekdays have checkmarks as well as highlighted backgrounds.
- The start date is the earliest allowed occurrence. All dates use the phone's native local time.

## Permissions and delivery

The manifest declares exactly three permissions:

| Permission | Purpose |
| --- | --- |
| `POST_NOTIFICATIONS` | Display reminders; requested on Android 13+. |
| `RECEIVE_BOOT_COMPLETED` | Automatically restore reminders after reboot; no permission prompt. |
| `SCHEDULE_EXACT_ALARM` | User-controlled “Alarms & reminders” access on Android 12+, for the selected time. |

After saving the first reminder, enable notification access and precise reminders using the
buttons above the list. If precise access is declined, reminders still use approximate alarms;
Android may delay them. The app never requests network, contacts, location, shared storage,
wake-lock, battery exemption, or foreground-service permissions.

One AlarmManager alarm is scheduled for the earliest due task. The receiver posts all due
notifications and schedules the next occurrence, then exits. It does not poll or keep a process
running. Reboot, clock/timezone changes, and app updates reschedule reminders. A late alarm
or reboot produces at most one catch-up notification per task, not a backlog. A skipped local
DST time moves forward by the gap; a repeated hour generates one occurrence, using its first offset.

Android/OEM restrictions still apply. Force-stopping the app blocks its alarms and boot receiver
until you launch it again. Notification access, channel settings, Do Not Disturb and manufacturer
battery controls can silence or delay delivery. Revoking exact-alarm access cancels existing exact
alarms and stops the app; open it again to resume approximate scheduling, or restore access.
Uninstalling or clearing app data removes all tasks. There is no external synchronization.

## Build

Open in Android Studio, or use JDK 17 and the Android SDK:

```sh
sdkmanager 'platforms;android-36' 'build-tools;35.0.0'
./gradlew recurrenceTest lintDebug assembleDebug assembleRelease
```

Set `ANDROID_HOME` or a local untracked `local.properties` containing `sdk.dir=/path/to/sdk`.
The debug APK is `app/build/outputs/apk/debug/app-debug.apk` and can be installed directly.
The size-optimized release APK is `app/build/outputs/apk/release/app-release-unsigned.apk`;
sign it with your own private key before distributing. Never commit signing keys.
The GitHub Actions workflow builds both APKs on pushes to main and uploads them as artifacts.
After checks pass, it also publishes both APKs on the repository's **Releases** page.
Release tags use `build-<run number>.<attempt>` so retries get their own release.
Manual workflow runs on main also publish; pull requests only build and test.
Download `RepeatRemind-debug.apk` for direct installation, or sign the optimized
`RepeatRemind-release-unsigned.apk` with your own key. No signing keys are added to the repository.
The build downloads tools only on the development machine; the installed app has no network access.

`recurrenceTest` runs over 21,000 assertions without Android or third-party test libraries,
covering selected days, leap years, short months, start dates, strict 14-day intervals and DST.

The calendar/hourglass launcher icon is a small native vector with an adaptive-icon wrapper.
