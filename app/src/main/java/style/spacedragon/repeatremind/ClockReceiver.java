package style.spacedragon.repeatremind;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives only the system broadcasts listed in the manifest; none need extra permissions. */
public final class ClockReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        // Notification block changes reschedule follow-ups, which only run while notifications can show.
        Reminders.reconcile(context, Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action));
    }
}
