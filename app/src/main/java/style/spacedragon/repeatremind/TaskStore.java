package style.spacedragon.repeatremind;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class TaskStore {
    static final String PREFERENCES = "repeatremind";
    private final SharedPreferences preferences;

    TaskStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    List<Task> load() {
        try {
            JSONArray data = new JSONArray(preferences.getString("tasks", "[]"));
            List<Task> tasks = new ArrayList<>(data.length());
            for (int i = 0; i < data.length(); i++) tasks.add(Task.fromJson(data.getJSONObject(i)));
            return tasks;
        } catch (JSONException | IllegalArgumentException e) {
            // Never turn a read failure into an empty list that a later save could overwrite.
            throw new IllegalStateException("Stored tasks could not be read", e);
        }
    }

    void save(List<Task> tasks) {
        try {
            JSONArray data = new JSONArray();
            for (Task task : tasks) data.put(task.toJson());
            if (!preferences.edit().putString("tasks", data.toString()).commit()) {
                throw new IllegalStateException("Tasks could not be saved");
            }
        } catch (JSONException e) {
            throw new IllegalStateException("Tasks could not be saved", e);
        }
    }

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

    void workdays(Workdays work) {
        try {
            String data = work == null ? null : WorkdaysJson.toJson(work).toString();
            if (!preferences.edit().putString("work", data).commit()) {
                throw new IllegalStateException("Workdays could not be saved");
            }
        } catch (JSONException e) {
            throw new IllegalStateException("Workdays could not be saved", e);
        }
    }

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
