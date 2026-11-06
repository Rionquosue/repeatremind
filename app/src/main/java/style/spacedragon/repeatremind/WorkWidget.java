package style.spacedragon.repeatremind;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.RemoteViews;

/**
 * A small home screen punch clock: Punch in/out, productive and break for the job chosen on the widget, else
 * the schedule's job, or the tracker's job without a schedule. Its buttons share the work notification's receiver, and it is redrawn with it.
 */
public final class WorkWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        WorkControls.refresh(context);
    }

    static boolean present(Context context) { return ids(context).length > 0; }

    private static int[] ids(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        return manager == null ? new int[0] : manager.getAppWidgetIds(new ComponentName(context, WorkWidget.class));
    }

    /** Redraws placed work widgets from a log already loaded by the caller. */
    static void update(Context context, WorkLog log, int job) {
        int[] ids = ids(context);
        if (ids.length > 0) AppWidgetManager.getInstance(context).updateAppWidget(ids, views(context, log, job));
    }

    static RemoteViews views(Context context, WorkLog log, int job) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_work);
        boolean working = log.working(job);
        boolean productive = log.running(WorkLog.Kind.PRODUCTIVE, job), onBreak = log.running(WorkLog.Kind.BREAK, job);
        String state = state(log, job);
        views.setTextViewText(R.id.work_state, WorkLog.shortName(log.jobName(job)) + " · " + state);
        views.setContentDescription(R.id.work_job, log.jobName(job) + ", " + state + ". Tap to change job");
        // The job and state open a short list of jobs to switch to.
        Intent choose = new Intent(context, WidgetJobActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        views.setOnClickPendingIntent(R.id.work_job, PendingIntent.getActivity(context, 0, choose,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        // The system counts up from the punch-in, so the widget is never redrawn just to keep time.
        var open = log.openWork(job);
        if (working) {
            long elapsed = System.currentTimeMillis() - open.getValue().start * 1000;
            views.setChronometer(R.id.work_clock, SystemClock.elapsedRealtime() - elapsed, null, true);
            views.setViewVisibility(R.id.work_clock, android.view.View.VISIBLE);
        } else {
            views.setChronometer(R.id.work_clock, SystemClock.elapsedRealtime(), null, false);
            views.setViewVisibility(R.id.work_clock, android.view.View.GONE);
        }
        button(context, views, R.id.work_punch, WorkControls.PUNCH, job, working,
                working ? R.drawable.ic_work_punch_out : R.drawable.ic_work_punch_in, true,
                working ? "Punch out" : "Punch in");
        button(context, views, R.id.work_productive, WorkControls.PRODUCTIVE, job, productive,
                productive ? R.drawable.ic_work_stop : R.drawable.ic_work_productive,
                productive || log.canStart(WorkLog.Kind.PRODUCTIVE, job), productive ? "Stop productive" : "Start productive");
        button(context, views, R.id.work_break, WorkControls.BREAK, job, onBreak,
                onBreak ? R.drawable.ic_work_stop : R.drawable.ic_work_break,
                onBreak || log.canStart(WorkLog.Kind.BREAK, job), onBreak ? "Stop lunch/break" : "Start lunch/break");
        return views;
    }

    static String state(WorkLog log, int job) {
        return !log.working(job) ? "Off" : log.running(WorkLog.Kind.PRODUCTIVE, job) ? "Productive"
                : log.running(WorkLog.Kind.BREAK, job) ? "On break" : "In";
    }

    /** The job chosen on the widget while it is still active, else the job the work controls use. */
    static int job(WorkLogStore store, WorkLog log, Workdays work) {
        int chosen = store.widgetJob();
        return log.activeJobs().contains(chosen) || log.working(chosen) ? chosen : WorkControls.job(log, work);
    }

    /** A button that highlights while its state runs and dims while it cannot apply; dimmed taps do nothing. */
    private static void button(Context context, RemoteViews views, int id, String action, int job, boolean on,
            int icon, boolean available, String description) {
        views.setImageViewResource(id, icon);
        views.setInt(id, "setBackgroundResource", on ? R.drawable.widget_work_button_on : R.drawable.widget_work_button);
        views.setInt(id, "setImageAlpha", available ? 255 : 80);
        views.setContentDescription(id, description);
        Intent intent = new Intent(context, WorkActionReceiver.class).setAction(action).putExtra("job", job);
        views.setOnClickPendingIntent(id, PendingIntent.getBroadcast(context, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }
}
