package style.spacedragon.repeatremind;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.time.ZoneId;

/** The work controls notification's buttons: each toggles one state of its job, then redraws the notification. */
public final class WorkActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        int job = intent.getIntExtra("job", 0);
        String action = intent.getAction();
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
