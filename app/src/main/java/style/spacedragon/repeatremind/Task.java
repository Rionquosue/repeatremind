package style.spacedragon.repeatremind;

import org.json.JSONException;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.Map;

final class Task extends TaskState {
    int id;
    String title = "";
    String reminder = "";

    JSONObject toJson() throws JSONException {
        Schedule s = schedule;
        JSONObject outcomes = new JSONObject();
        for (Map.Entry<LocalDate, Boolean> entry : history.entrySet()) {
            outcomes.put(entry.getKey().toString(), entry.getValue());
        }
        return new JSONObject().put("id", id).put("title", title).put("reminder", reminder)
                .put("hasReminder", hasReminder).put("enabled", enabled).put("nextDue", nextDue)
                .put("followUpEnabled", followUpEnabled).put("followUpMinutes", followUpMinutes)
                .put("completed", completed).put("ignored", ignored).put("followUpAt", followUpAt)
                .put("occurrenceDate", occurrenceDate == null ? "" : occurrenceDate.toString())
                .put("occurrenceId", occurrenceId).put("occurrenceStarted", occurrenceStarted)
                .put("history", outcomes).put("historyThrough", historyThrough == null ? "" : historyThrough.toString())
                .put("kind", s.kind.name()).put("start", s.start.toString())
                .put("weekdays", s.weekdays).put("monthDays", s.monthDays)
                .put("day", s.day).put("month", s.month).put("hour", s.hour).put("minute", s.minute);
    }

    static Task fromJson(JSONObject json) throws JSONException {
        return fromJson(json, true);
    }

    static Task fromJson(JSONObject json, boolean validate) throws JSONException {
        Task t = new Task();
        t.id = json.getInt("id");
        t.title = json.getString("title");
        t.reminder = json.getString("reminder");
        t.hasReminder = json.getBoolean("hasReminder");
        t.enabled = json.getBoolean("enabled");
        t.nextDue = json.getLong("nextDue");
        t.followUpEnabled = json.optBoolean("followUpEnabled", false);
        t.followUpMinutes = json.optInt("followUpMinutes", 60);
        t.completed = json.optBoolean("completed", false);
        t.ignored = json.optBoolean("ignored", false);
        t.followUpAt = json.optLong("followUpAt", 0);
        t.occurrenceId = json.optLong("occurrenceId", 0);
        t.occurrenceStarted = json.optBoolean("occurrenceStarted", false);
        String date = json.optString("occurrenceDate", "");
        t.occurrenceDate = date.isEmpty() ? null : LocalDate.parse(date);
        String through = json.optString("historyThrough", "");
        t.historyThrough = through.isEmpty() ? null : LocalDate.parse(through);
        JSONObject outcomes = json.optJSONObject("history");
        if (outcomes != null) {
            Iterator<String> keys = outcomes.keys();
            while (keys.hasNext()) {
                String key = keys.next(); t.history.put(LocalDate.parse(key), outcomes.getBoolean(key));
            }
        }
        Schedule s = t.schedule;
        s.kind = Schedule.Kind.valueOf(json.getString("kind"));
        s.start = LocalDate.parse(json.getString("start"));
        s.weekdays = json.getInt("weekdays");
        s.monthDays = json.getInt("monthDays");
        s.day = json.getInt("day");
        s.month = json.getInt("month");
        s.hour = json.getInt("hour");
        s.minute = json.getInt("minute");
        if (validate) t.validate();
        return t;
    }
}
