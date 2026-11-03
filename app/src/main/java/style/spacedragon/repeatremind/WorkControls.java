package style.spacedragon.repeatremind;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * A silent, media-style notification with punch, productive and break buttons for the schedule's job. It shows
 * from shortly before each shift until the shift ends, and for as long as that job stays punched in. It is
 * redrawn only when something changes: a shift edge alarm, a button, or an edit in the app.
 */
final class WorkControls {
    private static final String CHANNEL = "work_controls";
    /** Task notifications use positive ids and the before-work reminder uses 0. */
    static final int NOTIFICATION = -1;
    static final String PUNCH = "style.spacedragon.repeatremind.PUNCH";
    static final String PRODUCTIVE = "style.spacedragon.repeatremind.PRODUCTIVE";
    static final String BREAK = "style.spacedragon.repeatremind.BREAK";
    private static boolean channelReady;

    private WorkControls() { }

    /** Reads the schedule, then shows, updates or removes the notification. */
    static void refresh(Context context) {
        Workdays work;
        try { work = new TaskStore(context).workdays(); }
        catch (IllegalStateException e) { work = null; }
        update(context, work);
    }

    static void update(Context context, Workdays work) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (work == null || !work.controls || !allowed(context, manager)) { manager.cancel(NOTIFICATION); return; }
        WorkLog log;
        try { log = new WorkLogStore(context).load(); }
        catch (IllegalStateException e) { manager.cancel(NOTIFICATION); return; }
        ZoneId zone = ZoneId.systemDefault();
        Workdays.Occurrence shift = work.controlsShift(Instant.now(), zone);
        int job = job(log, work);
        if (shift == null && !log.working(job)) { manager.cancel(NOTIFICATION); return; }
        ensureChannel(context, manager);
        try { manager.notify(NOTIFICATION, build(context, log, job, shift, zone)); }
        catch (SecurityException ignored) { /* Notification permission can be revoked between the check and posting. */ }
    }

    private static boolean allowed(Context context, NotificationManager manager) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled()
                && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    /** The schedule's job, or the first active job if that one was archived. */
    static int job(WorkLog log, Workdays work) {
        List<Integer> active = log.activeJobs();
        return active.contains(work.job) || log.working(work.job) ? work.job : active.get(0);
    }

    private static Notification build(Context context, WorkLog log, int job, Workdays.Occurrence shift, ZoneId zone) {
        DateTimeFormatter time = DateTimeFormatter.ofPattern(BaseActivity.timePattern(context));
        long now = System.currentTimeMillis() / 1000;
        boolean working = log.working(job);
        boolean productive = log.running(WorkLog.Kind.PRODUCTIVE, job), onBreak = log.running(WorkLog.Kind.BREAK, job);
        String state = !working ? "Off" : productive ? "Productive" : onBreak ? "On break" : "Punched in";
        var open = log.openWork(job);
        LocalDate day = open != null ? open.getKey() : LocalDate.now(zone);
        String hours = shift == null ? "Past the scheduled shift"
                : "Shift " + shift.startAt.atZone(zone).format(time) + "–" + shift.endAt.atZone(zone).format(time);
        String text = hours + " · Worked " + WorkCsv.clock(log.totals(day, now, job).worked());
        // Only the buttons that apply now; the compact and lock screen views show them all.
        List<Notification.Action> actions = new ArrayList<>();
        actions.add(action(context, PUNCH, job, working ? "Punch out" : "Punch in",
                working ? R.drawable.ic_work_punch_out : R.drawable.ic_work_punch_in));
        if (productive) actions.add(action(context, PRODUCTIVE, job, "Stop productive", R.drawable.ic_work_stop));
        else if (log.canStart(WorkLog.Kind.PRODUCTIVE, job)) {
            actions.add(action(context, PRODUCTIVE, job, "Start productive", R.drawable.ic_work_productive));
        }
        if (onBreak) actions.add(action(context, BREAK, job, "Stop lunch/break", R.drawable.ic_work_stop));
        else if (log.canStart(WorkLog.Kind.BREAK, job)) {
            actions.add(action(context, BREAK, job, "Start lunch/break", R.drawable.ic_work_break));
        }
        int[] compact = new int[actions.size()];
        for (int i = 0; i < compact.length; i++) compact[i] = i;
        Intent intent = new Intent(context, MainActivity.class)
                .putExtra("room", MainActivity.WORK).putExtra("work_room", WorkRoom.TRACKER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openApp = PendingIntent.getActivity(context, NOTIFICATION, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_room_work)
                .setContentTitle(log.jobName(job) + " · " + state).setContentText(text)
                .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(compact))
                .setContentIntent(openApp).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(working)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_STATUS);
        // The system counts up from the punch-in, so the notification never needs redrawing to stay current.
        if (working) builder.setWhen(open.getValue().start * 1000).setUsesChronometer(true);
        else if (shift != null) builder.setTimeoutAfter(Math.max(1, shift.endAt.toEpochMilli() - now * 1000));
        for (Notification.Action action : actions) builder.addAction(action);
        return builder.build();
    }

    private static Notification.Action action(Context context, String action, int job, String title, int icon) {
        Intent intent = new Intent(context, WorkActionReceiver.class).setAction(action).putExtra("job", job);
        PendingIntent pending = PendingIntent.getBroadcast(context, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(Icon.createWithResource(context, icon), title, pending).build();
    }

    /** Low importance: no sound, vibration or pop-up, but shown in full on the lock screen. */
    private static void ensureChannel(Context context, NotificationManager manager) {
        if (channelReady) return;
        if (manager.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    context.getString(R.string.work_controls_channel), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Punch, productive and break buttons during scheduled work hours");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            manager.createNotificationChannel(channel);
        }
        channelReady = true;
    }
}
