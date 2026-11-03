package style.spacedragon.repeatremind;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.time.ZoneId;

/**
 * The work controls notification's buttons, each toggling one state of its job and then redrawing it, and its
 * ten-minute check that puts it back after a swipe.
 */
public final class WorkActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (WorkControls.CHECK.equals(action)) {
            // Redrawn only if it was swiped away; otherwise the next check is armed.
            if (WorkControls.showing(context)) WorkControls.scheduleCheck(context, true); else WorkControls.refresh(context);
            return;
        }
        int job = intent.getIntExtra("job", 0);
        try {
            WorkLogStore store = new WorkLogStore(context);
            WorkLog log = store.load();
            if (!log.jobs.containsKey(job)) return;
            long now = System.currentTimeMillis() / 1000;
            if (WorkControls.PUNCH.equals(action)) {
                if (log.working(job)) log.punchOut(job, now); else log.punchIn(job, now, ZoneId.systemDefault());
            } else if (WorkControls.PRODUCTIVE.equals(action)) {
                toggle(log, WorkLog.Kind.PRODUCTIVE, job, now);
            } else if (WorkControls.BREAK.equals(action)) {
                toggle(log, WorkLog.Kind.BREAK, job, now);
            }
            store.save(log);
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            // A stale button (the state changed elsewhere) or unreadable hours: the redraw below shows what is true.
        }
        WorkControls.refresh(context);
    }

    private static void toggle(WorkLog log, WorkLog.Kind kind, int job, long now) {
        if (log.running(kind, job)) log.stop(kind, job, now);
        else if (log.canStart(kind, job)) log.start(kind, job, now);
    }
}
