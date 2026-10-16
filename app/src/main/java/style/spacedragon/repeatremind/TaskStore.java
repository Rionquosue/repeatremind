package style.spacedragon.repeatremind;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.List;

final class TaskStore {
    private final SharedPreferences preferences;

    TaskStore(Context context) {
        preferences = context.getSharedPreferences("repeatremind", Context.MODE_PRIVATE);
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

    int theme() { return preferences.getInt("theme", 0); }
    void theme(int theme) { preferences.edit().putInt("theme", theme).apply(); }
}
