package style.spacedragon.repeatremind;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;

final class Reminders {
    private static final String CHANNEL = "task_reminders";

    private Reminders() { }

    static boolean allowed(Context context) {
        return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                && context.getSystemService(NotificationManager.class).areNotificationsEnabled()
                && channelAllowed(context);
    }

    private static boolean channelAllowed(Context context) {
        NotificationChannel channel = context.getSystemService(NotificationManager.class)
                .getNotificationChannel(CHANNEL);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    static void createChannel(Context context) {
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Locally scheduled repeating task reminders");
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    static boolean precise(Context context) {
        return Build.VERSION.SDK_INT < 31
                || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    /** One OS alarm for the entire app; the process can be killed between occurrences. */
    static void reconcile(Context context, boolean clockChanged) {
        createChannel(context);
        TaskStore store = new TaskStore(context);
        List<Task> tasks = store.load();
        Instant now = Instant.now();
        ZoneId zone = ZoneId.systemDefault();
        long earliest = Long.MAX_VALUE;
        boolean changed = false;
        List<Task> due = new ArrayList<>();
        for (Task task : tasks) {
            TaskState.Update update = task.update(now, zone, clockChanged);
            changed |= update.changed;
            if (update.clearNotification || !task.remindersActive() || task.completed) clear(context, task.id);
            if (update.notify) due.add(task);
            earliest = Math.min(earliest, task.nextAlarm());
        }
        if (changed) store.save(tasks);
        if (allowed(context)) for (Task task : due) show(context, task);
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent operation = PendingIntent.getBroadcast(context, 0,
                new Intent(context, ReminderReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (earliest == Long.MAX_VALUE) alarms.cancel(operation);
        else {
            try {
                if (precise(context)) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, earliest, operation);
                } else {
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, earliest, operation);
                }
            } catch (SecurityException ignored) {
                // Special access may be revoked while this method is running.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, earliest, operation);
            }
        }
    }

    static void clear(Context context, int id) {
        context.getSystemService(NotificationManager.class).cancel(id);
    }

    static boolean setCompleted(Context context, int id, long occurrence, boolean value) {
        reconcile(context, false);
        TaskStore store = new TaskStore(context);
        List<Task> tasks = store.load();
        for (Task task : tasks) {
            if (task.id == id && task.setCompleted(occurrence, value, Instant.now())) {
                store.save(tasks); clear(context, id); reconcile(context, false); return true;
            }
        }
        return false;
    }

    static boolean setIgnored(Context context, int id, long occurrence, boolean value) {
        reconcile(context, false);
        TaskStore store = new TaskStore(context);
        List<Task> tasks = store.load();
        for (Task task : tasks) {
            if (task.id != id || (occurrence != -1 && task.occurrenceId != occurrence)) continue;
            task.setIgnored(value, Instant.now());
            store.save(tasks); clear(context, id); reconcile(context, false); return true;
        }
        return false;
    }

    private static Notification.Action action(Context context, Task task, String action, int title, int icon) {
        Intent intent = new Intent(context, TaskActionReceiver.class).setAction(action)
                .setData(Uri.parse("repeatremind://task/" + task.id + "/" + task.occurrenceId))
                .putExtra("task_id", task.id).putExtra("occurrence_id", task.occurrenceId);
        PendingIntent pending = PendingIntent.getBroadcast(context, task.id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(Icon.createWithResource(context, icon),
                context.getString(title), pending).build();
    }

    private static void show(Context context, Task task) {
        Intent intent = new Intent(context, MainActivity.class)
                .putExtra("task_id", task.id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(context, task.id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(task.title)
                .setContentText(task.reminder).setStyle(new Notification.BigTextStyle().bigText(task.reminder))
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(false)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(action(context, task, TaskActionReceiver.COMPLETE, R.string.completed,
                        android.R.drawable.checkbox_on_background))
                .addAction(action(context, task, TaskActionReceiver.IGNORE, R.string.ignore,
                        android.R.drawable.ic_menu_close_clear_cancel))
                .setCategory(Notification.CATEGORY_REMINDER).build();
        try {
            context.getSystemService(NotificationManager.class).notify(task.id, notification);
        } catch (SecurityException ignored) {
            // Notification permission can be revoked between the check and posting.
        }
    }
}
