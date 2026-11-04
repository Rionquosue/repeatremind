package style.spacedragon.repeatremind;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.time.LocalDate;

public final class TaskActionReceiver extends BroadcastReceiver {
    static final String COMPLETE = "style.spacedragon.repeatremind.COMPLETE";
    static final String IGNORE = "style.spacedragon.repeatremind.IGNORE";
    /** From the task widget: set completed or ignored to "value", like the app's toggles. */
    static final String WIDGET_COMPLETE = "style.spacedragon.repeatremind.WIDGET_COMPLETE";
    static final String WIDGET_IGNORE = "style.spacedragon.repeatremind.WIDGET_IGNORE";

    @Override public void onReceive(Context context, Intent intent) {
        int id = intent.getIntExtra("task_id", 0);
        if (WIDGET_IGNORE.equals(intent.getAction())) {
            if (id > 0) Reminders.setIgnored(context, id, -1, intent.getBooleanExtra("value", true));
            return;
        }
        long occurrence = intent.getLongExtra("occurrence_id", -1);
        if (id <= 0 || occurrence < 1) return;
        String date = intent.getStringExtra("occurrence_date");
        if (COMPLETE.equals(intent.getAction()) || WIDGET_COMPLETE.equals(intent.getAction())) {
            boolean value = !WIDGET_COMPLETE.equals(intent.getAction()) || intent.getBooleanExtra("value", true);
            Reminders.setCompleted(context, id, occurrence, date == null ? null : LocalDate.parse(date), value);
        }
        else if (IGNORE.equals(intent.getAction())) Reminders.setIgnored(context, id, occurrence, true);
    }
}
