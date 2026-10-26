package style.spacedragon.repeatremind;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.time.LocalDate;

public final class TaskActionReceiver extends BroadcastReceiver {
    static final String COMPLETE = "style.spacedragon.repeatremind.COMPLETE";
    static final String IGNORE = "style.spacedragon.repeatremind.IGNORE";

    @Override public void onReceive(Context context, Intent intent) {
        int id = intent.getIntExtra("task_id", 0);
        long occurrence = intent.getLongExtra("occurrence_id", -1);
        if (id <= 0 || occurrence < 1) return;
        String date = intent.getStringExtra("occurrence_date");
        if (COMPLETE.equals(intent.getAction())) {
            Reminders.setCompleted(context, id, occurrence, date == null ? null : LocalDate.parse(date), true);
        }
        else if (IGNORE.equals(intent.getAction())) Reminders.setIgnored(context, id, occurrence, true);
    }
}
