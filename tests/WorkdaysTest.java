package style.spacedragon.repeatremind;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Work schedules and the before-work reminder with a fixed clock; plain JDK, no Android. */
public final class WorkdaysTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final DateTimeFormatter H24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US);
    private static int assertions;

    public static void main(String[] args) {
        shifts();
        validation();
        regularReminders();
        shiftReminders();
        delivery();
        labels();
        System.out.println("PASS: " + assertions + " workday assertions");
    }

    private static void shifts() {
        Workdays.Shift day = new Workdays.Shift(9 * 60, 17 * 60);
        check(day.length() == 480 && !day.hasBreak(), "Day shift length");
        Workdays.Shift night = new Workdays.Shift(22 * 60, 6 * 60);
        check(night.length() == 480, "Overnight shift wraps midnight");
        night.breakStart = 2 * 60; night.breakEnd = 2 * 60 + 30;
        night.validate(); assertions++;
        Workdays.Shift copy = night.copy();
        check(copy != night && copy.start == night.start && copy.end == night.end
                && copy.breakStart == night.breakStart && copy.breakEnd == night.breakEnd, "Shift copy");
        copy.breakEnd = 3 * 60;
        check(night.breakEnd == 2 * 60 + 30, "Shift copy is independent");
    }

    private static void validation() {
        Workdays.Shift same = new Workdays.Shift(540, 540); invalid(same, "Start equals end");
        Workdays.Shift outside = new Workdays.Shift(540, 1020);
        outside.breakStart = 1020; outside.breakEnd = 1050; invalid(outside, "Break after shift");
        outside.breakStart = 540; outside.breakEnd = 570; invalid(outside, "Break at shift start");
        outside.breakStart = 720; outside.breakEnd = 700; invalid(outside, "Break ends before it starts");
        outside.breakStart = 720; outside.breakEnd = -1; invalid(outside, "Break missing end");
        Workdays.Shift range = new Workdays.Shift(1440, 60); invalid(range, "Minute 1440");

        Workdays work = regular();
        work.weekdays = 0; invalid(work, "No working days");
        work = regular(); work.weekdays = 0; work.mode = Workdays.Mode.SHIFTS;
        work.validate(); assertions++; // Weekdays only matter for a regular schedule.
        work = regular(); work.prepMinutes = -1; invalid(work, "Negative prep");
        work = regular(); work.commuteMinutes = Workdays.MAX_LEAD_MINUTES + 1; invalid(work, "Commute too long");
        work = regular(); work.reminderMinutes = Workdays.MAX_LEAD_MINUTES; work.validate(); assertions++;
        work = shiftWork(); work.shifts.put(LocalDate.of(2026, 1, 5), new Workdays.Shift(600, 600));
        invalid(work, "Entered shift is validated");
    }

    private static void regularReminders() {
        Workdays work = regular(); // Monday to Friday, 9:00, 15 + 30 + 45 minutes of lead
        Workdays.Occurrence next = work.nextReminder(at("2026-01-02T06:00:00Z"), UTC); // Friday
        equal(next.remindAt, "2026-01-02T07:30:00Z", "Reminder before prep and commute");
        equal(next.prepAt, "2026-01-02T07:45:00Z", "Prep starts before commute");
        equal(next.leaveAt, "2026-01-02T08:15:00Z", "Leave after prep");
        equal(next.startAt, "2026-01-02T09:00:00Z", "Shift start");
        next = work.nextReminder(at("2026-01-02T07:30:00Z"), UTC);
        equal(next.remindAt, "2026-01-05T07:30:00Z", "Weekend skipped");
        check(work.nextAlarm(at("2026-01-02T07:30:00Z"), UTC) == at("2026-01-05T07:30:00Z").toEpochMilli(),
                "Alarm is the next reminder");
        work.reminder = false;
        check(work.nextAlarm(at("2026-01-02T06:00:00Z"), UTC) == Long.MAX_VALUE, "Disabled reminder sets no alarm");

        // A long lead moves the reminder onto the previous day.
        work = regular(); work.regular = new Workdays.Shift(60, 9 * 60); work.prepMinutes = 120;
        next = work.nextReminder(at("2026-01-04T12:00:00Z"), UTC); // Sunday noon
        equal(next.remindAt, "2026-01-04T22:00:00Z", "Lead crosses midnight");
        check(next.date.equals(LocalDate.of(2026, 1, 5)), "Reminder belongs to Monday's shift");

        ZoneId toronto = ZoneId.of("America/Toronto");
        work = regular();
        next = work.nextReminder(at("2026-03-08T00:00:00Z"), toronto); // Spring forward on Sunday
        equal(next.startAt, "2026-03-09T13:00:00Z", "Local start after DST change");
        equal(next.remindAt, "2026-03-09T11:30:00Z", "Lead counted in real minutes");
    }

    private static void shiftReminders() {
        Workdays work = shiftWork();
        check(work.nextReminder(at("2026-01-01T00:00:00Z"), UTC) == null, "No shifts, no reminder");
        check(work.nextAlarm(at("2026-01-01T00:00:00Z"), UTC) == Long.MAX_VALUE, "No shifts, no alarm");
        work.shifts.put(LocalDate.of(2026, 1, 3), new Workdays.Shift(14 * 60, 22 * 60));
        work.shifts.put(LocalDate.of(2026, 1, 7), new Workdays.Shift(6 * 60, 14 * 60));
        check(work.shiftOn(LocalDate.of(2026, 1, 5)) == null, "Days without times are off");
        Workdays.Occurrence next = work.nextReminder(at("2026-01-01T00:00:00Z"), UTC);
        equal(next.remindAt, "2026-01-03T12:30:00Z", "First entered shift");
        next = work.nextReminder(next.remindAt, UTC);
        equal(next.remindAt, "2026-01-07T04:30:00Z", "Per-day start times");
        check(work.nextReminder(next.remindAt, UTC) == null, "Nothing after the last entered shift");
    }

    private static void delivery() {
        Workdays work = regular();
        check(work.update(at("2026-01-02T07:29:59Z"), UTC, false).notify == null, "Not early");
        Workdays.Update update = work.update(at("2026-01-02T07:30:00Z"), UTC, false);
        check(update.notify != null && update.changed, "Delivers at reminder time");
        check(work.update(at("2026-01-02T07:31:00Z"), UTC, false).notify == null, "Delivers once");
        // A late alarm still reminds while the shift has not started, but not afterwards.
        update = work.update(at("2026-01-05T08:59:00Z"), UTC, false);
        check(update.notify != null && update.notify.date.equals(LocalDate.of(2026, 1, 5)), "Late but before start");
        work = regular();
        check(work.update(at("2026-01-05T09:00:00Z"), UTC, false).notify == null, "Not once the shift starts");

        work = regular();
        work.update(at("2026-01-02T07:30:00Z"), UTC, false);
        check(work.update(at("2026-01-02T07:30:00Z"), UTC, true).notify == null, "Clock change does not repeat");
        work.lastReminder = at("2027-01-01T00:00:00Z").toEpochMilli(); // Clock had been set far ahead
        update = work.update(at("2026-01-05T07:30:00Z"), UTC, true);
        check(update.notify != null, "Clock moved back resets delivery state");

        work = regular(); work.reminder = false;
        check(work.update(at("2026-01-02T07:30:00Z"), UTC, false).notify == null, "Disabled reminder is silent");

        work = shiftWork();
        work.shifts.put(LocalDate.of(2025, 11, 1), new Workdays.Shift(540, 1020));
        work.shifts.put(LocalDate.of(2026, 1, 1), new Workdays.Shift(540, 1020));
        update = work.update(at("2026-01-05T00:00:00Z"), UTC, false);
        check(update.changed && work.shifts.size() == 1, "Old shifts pruned, recent ones kept");

        Workdays copy = work.copy();
        copy.shifts.get(LocalDate.of(2026, 1, 1)).start = 600;
        check(work.shifts.get(LocalDate.of(2026, 1, 1)).start == 540, "Copy is independent");
        work.lastReminder = 123;
        check(work.copy().lastReminder == 0, "Copy leaves delivery state with the stored schedule");
    }

    private static void labels() {
        Workdays.Shift shift = new Workdays.Shift(9 * 60, 17 * 60);
        same(WorkText.range(shift, H24), "09:00–17:00");
        same(WorkText.range(new Workdays.Shift(22 * 60, 6 * 60), H24), "22:00–06:00 next day");
        same(WorkText.hours(shift, H24), "09:00–17:00");
        shift.breakStart = 12 * 60; shift.breakEnd = 12 * 60 + 30;
        same(WorkText.hours(shift, H24), "09:00–17:00 · Break 12:00–12:30");
        same(ScheduleText.weekdays(31), "MON · TUE · WED · THU · FRI");
        Workdays work = regular();
        same(WorkText.lead(work), "Prep 30 min · Commute 45 min · Reminder 15 min before prep");
        Workdays.Occurrence next = work.nextReminder(at("2026-01-02T06:00:00Z"), UTC);
        same(WorkText.plan(work, next, UTC, H24), "Start getting ready at 07:45 · Leave by 08:15 · Shift 09:00–17:00");
        work.prepMinutes = 0; work.commuteMinutes = 0; work.regular = shift;
        next = work.nextReminder(at("2026-01-02T06:00:00Z"), UTC);
        same(WorkText.plan(work, next, UTC, H24), "Shift 09:00–17:00 · Break 12:00–12:30");
    }

    private static Workdays regular() {
        Workdays work = new Workdays();
        work.prepMinutes = 30; work.commuteMinutes = 45; work.reminderMinutes = 15;
        return work;
    }
    private static Workdays shiftWork() {
        Workdays work = regular(); work.mode = Workdays.Mode.SHIFTS; return work;
    }
    private static Instant at(String value) { return Instant.parse(value); }
    private static void invalid(Workdays.Shift shift, String message) {
        try { shift.validate(); throw new AssertionError("Accepted: " + message); }
        catch (IllegalArgumentException expected) { assertions++; }
    }
    private static void invalid(Workdays work, String message) {
        try { work.validate(); throw new AssertionError("Accepted: " + message); }
        catch (IllegalArgumentException expected) { assertions++; }
    }
    private static void equal(Instant actual, String expected, String message) {
        check(actual != null && actual.equals(at(expected)), message + ": expected " + expected + " but was " + actual);
    }
    private static void same(String actual, String expected) {
        check(expected.equals(actual), "Expected \"" + expected + "\" but was \"" + actual + "\"");
    }
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
}
