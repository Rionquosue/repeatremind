package style.spacedragon.repeatremind;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.LocalDate;
import java.util.Map;

/**
 * Work log storage: one key per day, plus the job list and the job new work goes to. Written in one commit, and
 * only when a toggle or edit changes something; picking a job is saved with the next change.
 */
final class WorkLogStore {
    private static final String FILE = "repeatremind_worklog";
    private final SharedPreferences preferences;

    WorkLogStore(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    WorkLog load() {
        WorkLog log = new WorkLog();
        try {
            log.selected = preferences.getInt("job", WorkLog.FIRST_JOB);
            log.readJobs(preferences.getString("jobs", null));
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
        boolean selectionChanged = preferences.getInt("job", WorkLog.FIRST_JOB) != log.selected;
        if (log.dirty.isEmpty() && !log.jobsDirty && !selectionChanged) return;
        SharedPreferences.Editor edit = preferences.edit();
        if (log.jobsDirty) edit.putString("jobs", log.jobsText());
        if (selectionChanged) edit.putInt("job", log.selected);
        for (LocalDate day : log.dirty) {
            var periods = log.periods(day);
            if (periods.isEmpty()) edit.remove("d" + day); else edit.putString("d" + day, WorkLog.text(periods));
        }
        if (!edit.commit()) throw new IllegalStateException("Work hours could not be saved");
        log.dirty.clear();
        log.jobsDirty = false;
    }
}
