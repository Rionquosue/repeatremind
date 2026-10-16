package style.spacedragon.repeatremind;

import org.json.JSONException;
import org.json.JSONObject;
import java.time.LocalDate;

final class Task {
    int id;
    String title = "";
    String reminder = "";
    boolean hasReminder;
    boolean enabled = true;
    long nextDue;
    Schedule schedule = new Schedule();

    JSONObject toJson() throws JSONException {
        Schedule s = schedule;
        return new JSONObject().put("id", id).put("title", title).put("reminder", reminder)
                .put("hasReminder", hasReminder).put("enabled", enabled).put("nextDue", nextDue)
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
        Schedule s = t.schedule;
        s.kind = Schedule.Kind.valueOf(json.getString("kind"));
        s.start = LocalDate.parse(json.getString("start"));
        s.weekdays = json.getInt("weekdays");
        s.monthDays = json.getInt("monthDays");
        s.day = json.getInt("day");
        s.month = json.getInt("month");
        s.hour = json.getInt("hour");
        s.minute = json.getInt("minute");
        if (validate) s.validate();
        return t;
    }
}
