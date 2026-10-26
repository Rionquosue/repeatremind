package style.spacedragon.repeatremind;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Labels for workdays; the caller supplies the 12/24-hour time format. */
final class WorkText {
    private WorkText() { }

    static String time(int minutes, DateTimeFormatter format) {
        return LocalTime.ofSecondOfDay(minutes * 60L).format(format);
    }

    static String range(Workdays.Shift shift, DateTimeFormatter format) {
        return time(shift.start, format) + "–" + time(shift.end, format)
                + (shift.end <= shift.start ? " next day" : "");
    }

    /** Shift hours, with the break when there is one. */
    static String hours(Workdays.Shift shift, DateTimeFormatter format) {
        return range(shift, format) + (shift.hasBreak()
                ? " · Break " + time(shift.breakStart, format) + "–" + time(shift.breakEnd, format) : "");
    }

    static String lead(Workdays work) {
        return "Prep " + work.prepMinutes + " min · Commute " + work.commuteMinutes + " min · Reminder "
                + work.reminderMinutes + " min before prep";
    }

    /** Notification body: when to start getting ready, leave, and the shift itself. */
    static String plan(Workdays work, Workdays.Occurrence shift, ZoneId zone, DateTimeFormatter format) {
        List<String> parts = new ArrayList<>();
        if (work.prepMinutes > 0) parts.add("Start getting ready at " + at(shift.prepAt, zone, format));
        if (work.commuteMinutes > 0) parts.add("Leave by " + at(shift.leaveAt, zone, format));
        parts.add("Shift " + hours(shift.shift, format));
        return String.join(" · ", parts);
    }

    private static String at(Instant instant, ZoneId zone, DateTimeFormatter format) {
        return instant.atZone(zone).format(format);
    }
}
