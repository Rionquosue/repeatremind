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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class Reminders {
    private static final String CHANNEL = "task_reminders";
    /** Task IDs start at 1, so 0 is free for the before-work reminder. */
    static final int WORK_NOTIFICATION = 0;
    private static final int EXACT_ALARM = 0, FOLLOW_UP_ALARM = 1;
    private static boolean channelReady;

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

    /** Creates the channel once per process; recreating it on every reconcile costs an IPC each time. */
    static void ensureChannel(Context context) {
        if (channelReady) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Locally scheduled repeating task reminders");
            manager.createNotificationChannel(channel);
        }
        channelReady = true;
    }

    static boolean precise(Context context) {
        return Build.VERSION.SDK_INT < 31
                || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    /** Everything one pass loaded, so callers can render without reading storage again. */
    static final class Data {
        final List<Task> tasks;
        Workdays work;
        boolean tasksChanged, workChanged;

        Data(List<Task> tasks, Workdays work) { this.tasks = tasks; this.work = work; }

        Task task(int id) {
            for (Task task : tasks) if (task.id == id) return task;
            return null;
        }
    }

    /** A change applied once stored state is up to date; it marks what it changed on the data. */
    interface Change { void apply(Data data, Instant now); }

    static Data reconcile(Context context, boolean clockChanged) { return update(context, clockChanged, null); }

    /**
     * One load, update, change, save and schedule pass. Alarms: an exact one for base reminders and
     * work, and an inexact one for follow-ups, which only runs while notifications can be shown.
     * The process can be killed between occurrences.
     */
    static Data update(Context context, boolean clockChanged, Change change) {
        TaskStore store = new TaskStore(context);
        Data data = new Data(store.load(), store.workdays());
        Instant now = Instant.now();
        ZoneId zone = ZoneId.systemDefault();
        Set<Task> due = new LinkedHashSet<>();
        Workdays.Occurrence[] workDue = {null};
        refresh(context, data, now, zone, clockChanged, due, workDue);
        if (change != null) {
            Workdays before = data.work;
            change.apply(data, now);
            if (data.work != before) workDue[0] = null; // A reminder due for replaced settings is stale.
            // update() is idempotent at one instant, so this only brings the changed state up to date.
            refresh(context, data, now, zone, false, due, workDue);
        }
        store.save(data.tasks, data.tasksChanged, data.work, data.workChanged);
        boolean allowed = allowed(context);
        if (allowed) {
            ensureChannel(context);
            for (Task task : due) {
                if (data.tasks.contains(task) && task.remindersActive() && !task.completed) show(context, task, now, zone);
            }
            if (workDue[0] != null && data.work != null && data.work.reminder) showWork(context, data.work, workDue[0]);
        }
        long exact = Long.MAX_VALUE, followUp = Long.MAX_VALUE;
        for (Task task : data.tasks) {
            exact = Math.min(exact, task.nextReminder(zone));
            if (allowed) followUp = Math.min(followUp, task.nextFollowUp());
        }
        if (data.work != null) exact = Math.min(exact, data.work.nextAlarm(now, zone));
        schedule(context, EXACT_ALARM, exact, true);
        schedule(context, FOLLOW_UP_ALARM, followUp < exact ? followUp : Long.MAX_VALUE, false);
        return data;
    }

    private static void refresh(Context context, Data data, Instant now, ZoneId zone, boolean clockChanged,
            Set<Task> due, Workdays.Occurrence[] workDue) {
        for (Task task : data.tasks) {
            TaskState.Update update = task.update(now, zone, clockChanged);
            data.tasksChanged |= update.changed;
            if (update.clearNotification) clear(context, task.id);
            if (update.notify) due.add(task);
        }
        if (data.work != null) {
            Workdays.Update update = data.work.update(now, zone, clockChanged);
            data.workChanged |= update.changed;
            if (update.notify != null) workDue[0] = update.notify;
        }
    }

    private static void schedule(Context context, int code, long at, boolean exact) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent operation = PendingIntent.getBroadcast(context, code,
                new Intent(context, ReminderReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (at == Long.MAX_VALUE) { alarms.cancel(operation); return; }
        try {
            if (exact && precise(context)) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation);
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation);
            }
        } catch (SecurityException ignored) {
            // Special access may be revoked while this method is running.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation);
        }
    }

    static void clear(Context context, int id) {
        context.getSystemService(NotificationManager.class).cancel(id);
    }

    /** A date is given for actions that may arrive after the occurrence has rolled over. */
    static Data setCompleted(Context context, int id, long occurrence, LocalDate date, boolean value) {
        return update(context, false, (data, now) -> {
            Task task = data.task(id);
            if (task == null) return;
            if (task.setCompleted(occurrence, value, now) || (value && task.completeMissed(date))) {
                data.tasksChanged = true;
                clear(context, id);
            }
        });
    }

    /** An occurrence of -1 applies to the task regardless of which occurrence is current. */
    static Data setIgnored(Context context, int id, long occurrence, boolean value) {
        return update(context, false, (data, now) -> {
            Task task = data.task(id);
            if (task == null) return;
            if (occurrence == -1) task.setIgnored(value, now);
            else if (!task.ignoreOccurrence(occurrence, now)) return;
            data.tasksChanged = true;
            if (value) clear(context, id);
        });
    }

    private static void showWork(Context context, Workdays work, Workdays.Occurrence shift) {
        ZoneId zone = ZoneId.systemDefault();
        DateTimeFormatter time = DateTimeFormatter.ofPattern(BaseActivity.timePattern(context));
        String text = WorkText.plan(work, shift, zone, time);
        Intent intent = new Intent(context, MainActivity.class)
                .putExtra("tab", MainActivity.WORK_TAB).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(context, WORK_NOTIFICATION, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long untilStart = shift.startAt.toEpochMilli() - System.currentTimeMillis();
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Work at " + shift.startAt.atZone(zone).format(time))
                .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setTimeoutAfter(Math.max(1, untilStart)).setCategory(Notification.CATEGORY_REMINDER).build();
        try {
            context.getSystemService(NotificationManager.class).notify(WORK_NOTIFICATION, notification);
        } catch (SecurityException ignored) {
            // Notification permission can be revoked between the check and posting.
        }
    }

    private static Notification.Action action(Context context, Task task, String action, int title, int icon) {
        Intent intent = new Intent(context, TaskActionReceiver.class).setAction(action)
                .setData(Uri.parse("repeatremind://task/" + task.id + "/" + task.occurrenceId))
                .putExtra("task_id", task.id).putExtra("occurrence_id", task.occurrenceId)
                .putExtra("occurrence_date", task.occurrenceDate.toString());
        PendingIntent pending = PendingIntent.getBroadcast(context, task.id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(Icon.createWithResource(context, icon),
                context.getString(title), pending).build();
    }

    private static void show(Context context, Task task, Instant now, ZoneId zone) {
        // Opens the editor on top of whatever is open, so an unsaved draft underneath survives.
        Intent intent = new Intent(context, TaskEditorActivity.class).putExtra("task_id", task.id);
        // The notification is stale once the next occurrence begins, so it removes itself then.
        LocalDate next = Recurrence.nextDate(task.schedule, task.occurrenceDate.plusDays(1));
        LocalDate end = task.endDate();
        if (end != null && end.isBefore(next)) next = end;
        long stale = next.atStartOfDay(zone).toInstant().toEpochMilli() - now.toEpochMilli();
        PendingIntent open = PendingIntent.getActivity(context, task.id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(task.title)
                .setContentText(task.reminder).setStyle(new Notification.BigTextStyle().bigText(task.reminder))
                .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(Math.max(1, stale))
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
