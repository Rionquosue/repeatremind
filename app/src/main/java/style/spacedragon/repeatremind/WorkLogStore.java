package style.spacedragon.repeatremind;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.LocalDate;
import java.util.Map;

/** Work log storage: one key per day, written only when a toggle or edit changes that day. */
final class WorkLogStore {
    private static final String FILE = "repeatremind_worklog";
    private final SharedPreferences preferences;

    WorkLogStore(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    WorkLog load() {
        WorkLog log = new WorkLog();
        try {
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                if (entry.getKey().startsWith("d") && entry.getValue() instanceof String text) {
                    log.read(LocalDate.parse(entry.getKey().substring(1)), text);
                }
            }
        } catch (RuntimeException e) {
            // Never turn a read failure into an empty log that a later save could overwrite.
            throw new IllegalStateException("Stored work hours could not be read", e);
        }
        return log;
    }

    void save(WorkLog log) {
        if (log.dirty.isEmpty()) return;
        SharedPreferences.Editor edit = preferences.edit();
        for (LocalDate day : log.dirty) {
            var periods = log.periods(day);
            if (periods.isEmpty()) edit.remove("d" + day); else edit.putString("d" + day, WorkLog.text(periods));
        }
        if (!edit.commit()) throw new IllegalStateException("Work hours could not be saved");
        log.dirty.clear();
    }
}
