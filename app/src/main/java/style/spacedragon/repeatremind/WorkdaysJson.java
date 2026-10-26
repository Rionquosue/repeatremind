package style.spacedragon.repeatremind;

import org.json.JSONException;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.Map;

/** Stores Workdays with Android's org.json, keeping Workdays itself free of Android types. */
final class WorkdaysJson {
    private WorkdaysJson() { }

    static JSONObject toJson(Workdays work) throws JSONException {
        JSONObject shifts = new JSONObject();
        for (Map.Entry<LocalDate, Workdays.Shift> entry : work.shifts.entrySet()) {
            shifts.put(entry.getKey().toString(), shift(entry.getValue()));
        }
        return new JSONObject().put("mode", work.mode.name()).put("weekdays", work.weekdays)
                .put("regular", shift(work.regular)).put("shifts", shifts).put("reminder", work.reminder)
                .put("prepMinutes", work.prepMinutes).put("commuteMinutes", work.commuteMinutes)
                .put("reminderMinutes", work.reminderMinutes).put("lastReminder", work.lastReminder);
    }

    static Workdays fromJson(JSONObject json) throws JSONException {
        Workdays work = new Workdays();
        work.mode = Workdays.Mode.valueOf(json.getString("mode"));
        work.weekdays = json.getInt("weekdays");
        work.regular = shift(json.getJSONObject("regular"));
        JSONObject shifts = json.optJSONObject("shifts");
        if (shifts != null) {
            Iterator<String> keys = shifts.keys();
            while (keys.hasNext()) {
                String key = keys.next(); work.shifts.put(LocalDate.parse(key), shift(shifts.getJSONObject(key)));
            }
        }
        work.reminder = json.optBoolean("reminder", true);
        work.prepMinutes = json.getInt("prepMinutes");
        work.commuteMinutes = json.getInt("commuteMinutes");
        work.reminderMinutes = json.getInt("reminderMinutes");
        work.lastReminder = json.optLong("lastReminder", 0);
        return work;
    }

    private static JSONObject shift(Workdays.Shift shift) throws JSONException {
        return new JSONObject().put("start", shift.start).put("end", shift.end)
                .put("breakStart", shift.breakStart).put("breakEnd", shift.breakEnd);
    }

    private static Workdays.Shift shift(JSONObject json) throws JSONException {
        Workdays.Shift shift = new Workdays.Shift(json.getInt("start"), json.getInt("end"));
        shift.breakStart = json.optInt("breakStart", -1);
        shift.breakEnd = json.optInt("breakEnd", -1);
        return shift;
    }
}
