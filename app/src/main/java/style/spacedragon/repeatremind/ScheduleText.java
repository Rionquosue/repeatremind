package style.spacedragon.repeatremind;

import java.time.DayOfWeek;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ScheduleText {
    private ScheduleText() { }
    static DateTimeFormatter dateFormat() {
        return DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.getDefault());
    }
    static final String[] DAYS = {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"};

    static String describe(Schedule s) {
        return switch (s.kind) {
            case EVERY_DAY -> "Every day";
            case SELECTED_DAYS -> selected(s.weekdays);
            case WEEKLY -> "Weekly · " + s.start.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.getDefault());
            case BIWEEKLY -> "Every 14 days · from " + s.start.format(dateFormat());
            case MONTH_DAYS -> monthDays(s.monthDays);
            case MONTHLY -> "Monthly · day " + s.day + (s.day > 28 ? " (or last day)" : "");
            case YEARLY -> "Yearly · " + Month.of(s.month).getDisplayName(TextStyle.FULL, Locale.getDefault())
                    + " " + s.day + (s.month == 2 && s.day == 29 ? " (28 in other years)" : "");
        };
    }

    /** " · until <last day>" for a temporary task, otherwise empty. */
    static String until(TaskState task) {
        return task.temporary ? " · until " + task.endDate().minusDays(1).format(dateFormat()) : "";
    }

    private static String selected(int bits) {
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < 7; i++) if ((bits & (1 << i)) != 0) labels.add(DAYS[i]);
        return String.join(" · ", labels);
    }

    private static String monthDays(int bits) {
        List<String> labels = new ArrayList<>();
        if ((bits & 1) != 0) labels.add("1st");
        if ((bits & 2) != 0) labels.add("15th");
        if ((bits & 4) != 0) labels.add("last day");
        return "Monthly · " + String.join(", ", labels);
    }
}
