package style.spacedragon.repeatremind;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;

final class TaskStore {
    static final String PREFERENCES = "repeatremind";
    /** Missed-day history lives in its own file, written only when it changes. */
    private static final String HISTORY = "repeatremind_history";
    private final SharedPreferences preferences, history;

    TaskStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        history = context.getSharedPreferences(HISTORY, Context.MODE_PRIVATE);
    }

    List<Task> load() {
        try {
            JSONArray data = new JSONArray(preferences.getString("tasks", "[]"));
            List<Task> tasks = new ArrayList<>(data.length());
            for (int i = 0; i < data.length(); i++) {
                Task task = Task.fromJson(data.getJSONObject(i));
                String text = history.getString(key(task.id), null);
                if (text != null) {
                    task.readHistory(text);
                    task.savedHistory = text;
                }
                // Legacy history from inside the task stays unsaved, so the next save moves it here.
                task.pruneCompleted();
                tasks.add(task);
            }
            return tasks;
        } catch (JSONException | IllegalArgumentException e) {
            // Never turn a read failure into an empty list that a later save could overwrite.
            throw new IllegalStateException("Stored tasks could not be read", e);
        }
    }

    void save(List<Task> tasks) { save(tasks, true, null, false); }

    /** Writes tasks and/or workdays in one commit; history is written only for tasks whose history changed. */
    void save(List<Task> tasks, boolean saveTasks, Workdays work, boolean saveWork) {
        if (!saveTasks && !saveWork) return;
        try {
            SharedPreferences.Editor edit = preferences.edit();
            if (saveTasks) {
                JSONArray data = new JSONArray();
                for (Task task : tasks) data.put(task.toJson());
                edit.putString("tasks", data.toString());
                saveHistory(tasks);
            }
            if (saveWork) edit.putString("work", work == null ? null : WorkdaysJson.toJson(work).toString());
            if (!edit.commit()) throw new IllegalStateException("Changes could not be saved");
        } catch (JSONException e) {
            throw new IllegalStateException("Changes could not be saved", e);
        }
    }

    private void saveHistory(List<Task> tasks) {
        SharedPreferences.Editor edit = history.edit();
        boolean changed = false;
        Set<String> kept = new HashSet<>();
        for (Task task : tasks) {
            String key = key(task.id), text = task.historyText();
            kept.add(key);
            String stored = task.savedHistory != null ? task.savedHistory : history.getString(key, "");
            task.savedHistory = text;
            if (text.equals(stored)) continue;
            if (text.isEmpty()) edit.remove(key); else edit.putString(key, text);
            changed = true;
        }
        for (String key : history.getAll().keySet()) {
            if (!kept.contains(key)) { edit.remove(key); changed = true; }
        }
        if (changed && !edit.commit()) throw new IllegalStateException("History could not be saved");
    }

    private static String key(int id) { return "h" + id; }

    int nextId(List<Task> tasks) {
        int next = preferences.getInt("next_id", 1);
        for (Task task : tasks) next = Math.max(next, task.id + 1);
        if (!preferences.edit().putInt("next_id", next + 1).commit()) {
            throw new IllegalStateException("Task ID could not be saved");
        }
        return next;
    }

    /** The saved work schedule, or null before workdays are set up. */
    Workdays workdays() {
        String data = preferences.getString("work", null);
        if (data == null) return null;
        try {
            Workdays work = WorkdaysJson.fromJson(new JSONObject(data));
            work.validate();
            return work;
        } catch (JSONException | IllegalArgumentException e) {
            throw new IllegalStateException("Stored workdays could not be read", e);
        }
    }

    void workdays(Workdays work) { save(null, false, work, true); }

    int theme() { return preferences.getInt("theme", 0); }
    void theme(int theme) { preferences.edit().putInt("theme", theme).apply(); }

    int[] customColours() {
        int[] defaults = Palette.DEFAULT_CUSTOM;
        return new int[]{preferences.getInt("custom_background", defaults[0]),
                preferences.getInt("custom_button", defaults[1]),
                preferences.getInt("custom_highlight", defaults[2]),
                preferences.getInt("custom_text", defaults[3])};
    }

    void customColours(int[] colours) {
        preferences.edit().putInt("custom_background", colours[0]).putInt("custom_button", colours[1])
                .putInt("custom_highlight", colours[2]).putInt("custom_text", colours[3])
                .putInt("theme", 3).apply();
    }
}
