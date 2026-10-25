package style.spacedragon.repeatremind;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

/** Schedule data, labels and date-level recurrence helpers; plain JDK, no Android. */
public final class ScheduleTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static int assertions;

    public static void main(String[] args) {
        Locale.setDefault(Locale.US);
        copyAndCompare();
        validation();
        labels();
        dates();
        instants();
        System.out.println("PASS: " + assertions + " schedule assertions");
    }

    private static void copyAndCompare() {
        Schedule s = schedule(Schedule.Kind.YEARLY);
        s.weekdays = 5; s.monthDays = 3; s.day = 12; s.month = 7; s.hour = 18; s.minute = 45;
        Schedule copy = s.copy();
        check(copy != s && copy.sameAs(s) && s.sameAs(copy), "Copy matches every field");
        copy.minute = 46;
        check(!copy.sameAs(s), "Minute change detected");
        copy = s.copy(); copy.start = s.start.plusDays(1);
        check(!copy.sameAs(s), "Start change detected");
        copy = s.copy(); copy.kind = Schedule.Kind.MONTHLY;
        check(!copy.sameAs(s), "Kind change detected");
        copy = s.copy(); copy.weekdays = 6;
        check(!copy.sameAs(s), "Weekday change detected");
    }

    private static void validation() {
        schedule(Schedule.Kind.EVERY_DAY).validate(); assertions++;
        Schedule s = schedule(Schedule.Kind.EVERY_DAY); s.hour = 24; invalid(s, "Hour 24");
        s = schedule(Schedule.Kind.EVERY_DAY); s.minute = -1; invalid(s, "Minute -1");
        s = schedule(Schedule.Kind.EVERY_DAY); s.day = 32; invalid(s, "Day 32");
        s = schedule(Schedule.Kind.EVERY_DAY); s.month = 13; invalid(s, "Month 13");
        s = schedule(Schedule.Kind.EVERY_DAY); s.start = null; invalid(s, "Missing start");
        s = schedule(Schedule.Kind.SELECTED_DAYS); s.weekdays = 128; invalid(s, "Weekday bit 128");
        s = schedule(Schedule.Kind.MONTH_DAYS); s.monthDays = 8; invalid(s, "Month-day bit 8");
        s = schedule(Schedule.Kind.YEARLY); s.month = 2; s.day = 30; invalid(s, "February 30");
        s = schedule(Schedule.Kind.YEARLY); s.month = 2; s.day = 29; s.validate(); assertions++;
        // Weekday and month-day bits are only checked for the kinds that use them.
        s = schedule(Schedule.Kind.EVERY_DAY); s.weekdays = 0; s.monthDays = 0; s.validate(); assertions++;
    }

    private static void labels() {
        Schedule s = schedule(Schedule.Kind.EVERY_DAY);
        equal(ScheduleText.describe(s), "Every day");
        s = schedule(Schedule.Kind.SELECTED_DAYS); s.weekdays = 1 | 4 | 64;
        equal(ScheduleText.describe(s), "MON · WED · SUN");
        s = schedule(Schedule.Kind.WEEKLY);
        equal(ScheduleText.describe(s), "Weekly · Thursday");
        s = schedule(Schedule.Kind.BIWEEKLY);
        equal(ScheduleText.describe(s), "Every 14 days · from Jan 1, 2026");
        s = schedule(Schedule.Kind.MONTH_DAYS); s.monthDays = 1 | 4;
        equal(ScheduleText.describe(s), "Monthly · 1st, last day");
        s = schedule(Schedule.Kind.MONTHLY); s.day = 15;
        equal(ScheduleText.describe(s), "Monthly · day 15");
        s.day = 30;
        equal(ScheduleText.describe(s), "Monthly · day 30 (or last day)");
        s = schedule(Schedule.Kind.YEARLY); s.month = 2; s.day = 29;
        equal(ScheduleText.describe(s), "Yearly · February 29 (28 in other years)");
        s.day = 14;
        equal(ScheduleText.describe(s), "Yearly · February 14");
    }

    private static void dates() {
        Schedule s = schedule(Schedule.Kind.WEEKLY);
        check(Recurrence.matches(s, date("2026-01-08")) && !Recurrence.matches(s, date("2026-01-09")), "Weekly match");
        check(!Recurrence.matches(s, date("2025-12-25")), "Nothing before start");
        equal(Recurrence.nextDate(s, date("2026-01-02")), date("2026-01-08"), "Next weekly date");
        equal(Recurrence.nextDate(s, date("2026-01-08")), date("2026-01-08"), "Next date is inclusive");
        equal(Recurrence.nextDate(s, date("2025-06-01")), date("2026-01-01"), "Next date clamps to start");
        equal(Recurrence.previousDate(s, date("2026-01-14")), date("2026-01-08"), "Previous weekly date");
        equal(Recurrence.previousDate(s, date("2026-01-08")), date("2026-01-08"), "Previous date is inclusive");
        check(Recurrence.previousDate(s, date("2025-12-31")) == null, "No previous date before start");
    }

    private static void instants() {
        Schedule s = schedule(Schedule.Kind.EVERY_DAY);
        equal(Recurrence.previousOrAt(s, at("2026-01-05T09:00:00Z"), UTC), at("2026-01-05T09:00:00Z"), "At reminder time");
        equal(Recurrence.previousOrAt(s, at("2026-01-05T08:59:00Z"), UTC), at("2026-01-04T09:00:00Z"), "Before today's time");
        check(Recurrence.previousOrAt(s, at("2026-01-01T08:00:00Z"), UTC) == null, "Nothing before first occurrence");
        equal(Recurrence.nextAfter(s, at("2026-01-05T08:59:00Z"), UTC), at("2026-01-05T09:00:00Z"), "Later today");
        equal(Recurrence.nextAfter(s, at("2026-01-05T09:00:00Z"), UTC), at("2026-01-06T09:00:00Z"), "Strictly after");
        s = schedule(Schedule.Kind.SELECTED_DAYS); s.weekdays = 1; // Mondays
        equal(Recurrence.previousOrAt(s, at("2026-01-12T08:00:00Z"), UTC), at("2026-01-05T09:00:00Z"), "Previous Monday");
        equal(Recurrence.nextAfter(s, at("2026-01-12T10:00:00Z"), UTC), at("2026-01-19T09:00:00Z"), "Next Monday");
    }

    private static Schedule schedule(Schedule.Kind kind) {
        Schedule s = new Schedule(); s.kind = kind; s.start = LocalDate.of(2026, 1, 1); return s;
    }
    private static LocalDate date(String value) { return LocalDate.parse(value); }
    private static Instant at(String value) { return Instant.parse(value); }
    private static void invalid(Schedule s, String message) {
        try { s.validate(); throw new AssertionError("Accepted: " + message); }
        catch (IllegalArgumentException expected) { assertions++; }
    }
    private static void equal(Object actual, Object expected) { equal(actual, expected, "Label"); }
    private static void equal(Object actual, Object expected, String message) {
        check(expected.equals(actual), message + ": expected " + expected + " but was " + actual);
    }
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
}
