package style.spacedragon.repeatremind;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.NumberPicker;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Framework-only device smoke tests. Run on a disposable emulator, not a personal phone. */
public final class SmokeInstrumentation extends Instrumentation {
    private int checks;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Bundle status = new Bundle();
        status.putString("class", getClass().getName()); status.putString("test", "appSmoke");
        status.putInt("numtests", 1); status.putInt("current", 1); sendStatus(1, status);
        Context context = getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("repeatremind", Context.MODE_PRIVATE);
        String originalTasks = preferences.getString("tasks", "[]");
        int originalTheme = preferences.getInt("theme", 0);
        int originalNext = preferences.getInt("next_id", 1);
        int resultCode = Activity.RESULT_CANCELED;
        try {
            Set<String> permissions = new HashSet<>(Arrays.asList(context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions));
            check(permissions.equals(Set.of("android.permission.POST_NOTIFICATIONS",
                    "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.SCHEDULE_EXACT_ALARM")),
                    "Exactly the three intended permissions");
            shell("pm grant " + context.getPackageName() + " android.permission.POST_NOTIFICATIONS");
            shell("appops set " + context.getPackageName() + " SCHEDULE_EXACT_ALARM allow");
            check(Reminders.allowed(context), "Notification access granted");
            check(Reminders.precise(context), "Exact alarm access granted");

            TaskStore store = new TaskStore(context);
            store.save(new ArrayList<>()); store.theme(0);
            Activity main = startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            check(findText(main, "+  Add repeating task") != null, "Empty add button");
            screenshot("smoke-empty.png");
            ActivityMonitor monitor = addMonitor(TaskEditorActivity.class.getName(), null, false);
            runOnMainSync(() -> findText(main, "+  Add repeating task").performClick());
            Activity editor = waitForMonitorWithTimeout(monitor, 15000); removeMonitor(monitor);
            check(editor != null, "Editor opened"); waitForIdleSync();
            runOnMainSync(() -> {
                EditText title = (EditText) flatten(editor).stream().filter(v -> v instanceof EditText
                        && "Task name".contentEquals(((EditText) v).getHint())).findFirst().orElseThrow();
                title.setText("Monthly smoke test");
                Spinner repeat = flatten(editor).stream().filter(v -> v instanceof Spinner)
                        .map(v -> (Spinner) v).filter(v -> v.getAdapter().getCount() == 6).findFirst().orElseThrow();
                check(repeat.getAdapter().getCount() == 6, "All schedule options available");
                repeat.setSelection(4);
            });
            waitForIdleSync();
            runOnMainSync(() -> {
                flatten(editor).stream().filter(v -> v instanceof NumberPicker)
                        .map(v -> (NumberPicker) v).findFirst().orElseThrow().setValue(31);
                ((Switch) findText(editor, "Add reminder")).setChecked(true);
                EditText notification = (EditText) flatten(editor).stream().filter(v -> v instanceof EditText
                        && "Defaults to task name".contentEquals(((EditText) v).getHint())).findFirst().orElseThrow();
                notification.setText("Custom notification text");
            });
            waitForIdleSync(); screenshot("smoke-editor.png");
            runOnMainSync(() -> {
                findText(editor, "Save task").performClick();
            });
            waitForIdleSync();
            List<Task> tasks = store.load();
            check(tasks.size() == 1, "Task saved");
            Task task = tasks.get(0);
            check(task.schedule.kind == Schedule.Kind.MONTHLY && task.schedule.day == 31, "Monthly day persisted");
            check(task.reminder.equals("Custom notification text"), "Custom notification text persisted");
            check(task.nextDue > System.currentTimeMillis(), "Future alarm persisted");
            check(findText(main, "+") != null, "Compact plus button after save");

            // Both tasks due at once must post in a single wake-up, even with the activity closed.
            runOnMainSync(main::finish); waitForIdleSync();
            task.nextDue = System.currentTimeMillis() - 1000;
            Task second = Task.fromJson(task.toJson()); second.id = store.nextId(tasks);
            second.title = "Second simultaneous task";
            tasks.add(second); store.save(tasks);
            new ReminderReceiver().onReceive(context, new Intent());
            NotificationManager notifications = context.getSystemService(NotificationManager.class);
            check(notifications.getActiveNotifications().length == 2, "Both simultaneous reminders delivered");
            check(Arrays.stream(notifications.getActiveNotifications()).anyMatch(n ->
                    "Custom notification text".contentEquals(n.getNotification().extras.getCharSequence("android.text"))),
                    "Notification contains custom text");
            tasks = store.load();
            check(tasks.stream().allMatch(t -> t.nextDue > System.currentTimeMillis()), "Late alarms advance");
            long next = tasks.get(0).nextDue;
            Reminders.reconcile(context, false);
            check(store.load().get(0).nextDue == next, "Repeated reconciliation does not skip a future occurrence");

            // Exercise the real OS delivery path, with no activity running.
            notifications.cancelAll();
            tasks = store.load(); tasks.remove(1);
            tasks.get(0).nextDue = System.currentTimeMillis() + 3000; store.save(tasks);
            Reminders.reconcile(context, false);
            long timeout = System.currentTimeMillis() + 20000;
            while (notifications.getActiveNotifications().length == 0 && System.currentTimeMillis() < timeout) {
                Thread.sleep(250);
            }
            check(notifications.getActiveNotifications().length == 1, "AlarmManager delivered after activity closed");
            notifications.cancelAll();
            for (int theme = 0; theme < 3; theme++) {
                store.theme(theme);
                Activity themed = startActivitySync(new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                waitForIdleSync();
                check(findText(themed, "Monthly smoke test") != null, "Theme " + theme + " renders tasks");
                screenshot("smoke-theme-" + theme + ".png");
                runOnMainSync(themed::finish); waitForIdleSync();
            }
            result.putString("stream", "PASS: " + checks + " Android smoke checks\n");
            resultCode = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + failure + "\n" + android.util.Log.getStackTraceString(failure));
        } finally {
            context.getSystemService(NotificationManager.class).cancelAll();
            preferences.edit().putString("tasks", originalTasks).putInt("theme", originalTheme)
                    .putInt("next_id", originalNext).commit();
            Reminders.reconcile(context, false);
        }
        status.putString("stream", result.getString("stream"));
        sendStatus(resultCode == Activity.RESULT_OK ? 0 : -2, status);
        finish(resultCode, result);
    }

    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor fd = getUiAutomation().executeShellCommand(command);
             java.io.InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            stream.readAllBytes();
        }
    }
    private void screenshot(String filename) throws Exception {
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) return;
        try (java.io.OutputStream output = new java.io.FileOutputStream(
                new java.io.File(getTargetContext().getCacheDir(), filename))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } finally { bitmap.recycle(); }
    }
    private List<View> flatten(Activity activity) {
        List<View> views = new ArrayList<>(); collect(activity.getWindow().getDecorView(), views); return views;
    }
    private void collect(View view, List<View> all) {
        all.add(view);
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), all);
        }
    }
    private TextView findText(Activity activity, String value) {
        return flatten(activity).stream().filter(v -> v instanceof TextView)
                .map(v -> (TextView) v).filter(v -> value.contentEquals(v.getText())).findFirst().orElse(null);
    }
    private void check(boolean condition, String description) {
        checks++; if (!condition) throw new AssertionError(description);
    }
}
