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
- **Enable reminder** is checked by default; enter notification text and select a local reminder time.
  Unchecking it turns off all reminders for that task.
  Blank notification text defaults to the task name. Tasks can exist without a notification.
- Tap **Completed** in the list or expanded notification. The list button highlights when
  complete; tap it again to undo. Completion resets at local midnight when the next scheduled
  date begins, independently of reminder time. Completing an upcoming occurrence early skips its reminder.
- **Missed days** shows only past scheduled dates that remain incomplete, newest first.
  Completion history is saved locally and catches up after the app has been closed. Today's
  unfinished task is not yet missed. Unselected dates and paused days are excluded. Ignore only
  silences notifications, so ignored days still count if incomplete. Completing an active weekly
  or monthly occurrence late updates that occurrence's saved outcome. Deleting a task deletes its history.
  Tracking starts when this version first opens each existing task; older releases did not
  record historical outcomes, so earlier missed dates cannot be reconstructed.
- Enable **Send additional reminders if not complete** and choose a wait in minutes, hours,
  or days (1 minute to 365 days). Each delivered reminder starts another wait. Regular calendar
  occurrences keep their original schedule; a new occurrence replaces an older unfinished one.
- **Ignore** in the list or expanded notification silences all reminders for that task until
  **Resume** is selected, without marking it complete. The editor also has an Ignore checkbox.
  Swiping away a notification or opening it does not stop follow-ups. Undoing Completed
  resumes follow-ups for an occurrence that has already started, after the configured wait.
- Tap a task to edit, pause, or delete it. **Theme** selects Light, Dark, Colourblind, or Custom.
  Custom has native RGB pickers, hex fields, and a preview for background, button, highlight,
  and text colours. Button and highlight labels automatically use contrasting black or white.
  Selected weekdays remain on one line inside rounded highlights.
- The start date is the earliest allowed occurrence. All dates use the phone's native local time.
- **Temporary task** runs for a set number of days, weeks, months or years counted from the start
  date, then disables itself. The list shows its last day and then **Ended**. Missed days stop at
  the last day. To run it again, lengthen it or move the start date, then re-enable it.
- The **Workdays** tab sets up a before-work reminder. Choose a **regular schedule** (e.g. office) and
  select the days you work, or **shift work** (e.g. retail) and enter start and end times for each day;
  days without times are off. The tab prompts you when no shifts are entered for the next 7 days.
  Shifts can run past midnight and have an optional break inside the shift. Set **Prep time**,
  **Commute time** and how many minutes before prep to be reminded: the reminder goes off at
  shift start − commute − prep − reminder. It fires once per shift and is skipped once the shift starts.

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

One AlarmManager alarm is scheduled for the earliest base reminder or follow-up. The receiver posts all due
notifications and schedules the next occurrence, then exits. It does not poll or keep a process
running. Reboot, clock/timezone changes, and app updates reschedule reminders. A late alarm
or reboot produces at most one catch-up notification per task, not a backlog. A skipped local
DST time moves forward by the gap; a repeated hour generates one occurrence, using its first offset.
Completion, Ignore, and pending follow-ups persist across process exits and reboot. Existing saved
tasks migrate automatically with additional reminders switched off. Very short follow-up intervals
can be delayed by Android's idle-mode alarm limits, even with precise reminder access.

Android/OEM restrictions still apply. Force-stopping the app blocks its alarms and boot receiver
until you launch it again. Notification access, channel settings, Do Not Disturb and manufacturer
battery controls can silence or delay delivery. Revoking exact-alarm access cancels existing exact
alarms and stops the app; open it again to resume approximate scheduling, or restore access.
Uninstalling or clearing app data removes all tasks. There is no external synchronization.

## Build

Open in Android Studio, or use the checked-in Gradle wrapper and Android SDK. The repository's
AGP 9.4.1 / Gradle 9.6.0 configuration and JetBrains JDK 21 daemon criteria are preserved;
Gradle can download the configured daemon JDK. App source/bytecode compatibility remains Java 17.

```sh
sdkmanager 'platforms;android-36' 'build-tools;36.0.0'
./gradlew recurrenceTest reminderStateTest colourTest scheduleTest workdaysTest lintDebug assembleDebug assembleRelease assembleAndroidTest
```

Set `ANDROID_HOME` or a local untracked `local.properties` containing `sdk.dir=/path/to/sdk`.
The debug APK is `app/build/outputs/apk/debug/app-debug.apk` and can be installed directly.
The size-optimized release APK is `app/build/outputs/apk/release/app-release-unsigned.apk`;
sign it with your own private key before distributing. Never commit signing keys.
The GitHub Actions workflow tests both variants and publishes only the optimized unsigned APK
on the repository's **Releases** page. Debug APKs are not uploaded or published.
Release tags follow `versionName`, starting at **V0.1.1**. Increase `versionName` and `versionCode`
for each new release; retries of the same commit can replace its asset. The internal versionCode
continues increasing to permit upgrades despite restarting the displayed version number.
Manual workflow runs on main also publish; pull requests only build and test.
Sign `RepeatRemind-release-unsigned.apk` with your own key before installing.
No signing keys are added to the repository.
The build downloads tools only on the development machine; the installed app has no network access.

`recurrenceTest` runs over 21,000 assertions without Android or third-party test libraries,
covering selected days, leap years, short months, start dates, strict 14-day intervals and DST.
`reminderStateTest` checks completion, Ignore/Resume, interval validation, missed alarms,
stale notification actions, local midnight/DST rollover, saved completion and missed history,
clock changes, and unchanged recurrence dates for every schedule. `colourTest` checks hex input
and readable label contrast over a grid of sRGB colours. `scheduleTest` checks schedule copying,
validation, list labels, and the date-level recurrence helpers. `workdaysTest` checks shift and
break validation, overnight shifts, reminder lead times across midnight and DST, per-day shifts,
one delivery per shift, and workday labels. `reminderStateTest` also covers temporary tasks.
The framework-only `SmokeInstrumentation` additionally exercises editor persistence, list
buttons, weekday highlights, missed-history filtering, custom themes, actual notification actions,
permissions, and OS alarm delivery on a disposable device.
CI runs these device tests on an Android 15 emulator before publishing the release.

The calendar/hourglass launcher icon is a small native vector with an adaptive-icon wrapper.
