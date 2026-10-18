package style.spacedragon.repeatremind;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** State transitions use a fixed clock; no sleeps, Android, or external test framework. */
public final class ReminderStateTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static int assertions;

    public static void main(String[] args) {
        deliveryAndCompletion();
        earlyCompletion();
        ignoreAndResume();
        disabledReminders();
        lateDeliveryAndOverlap();
        editsAndStaleActions();
        clockChangesAndMigration();
        midnightAndHistory();
        scheduleHistory();
        allSchedules();
        validation();
        System.out.println("PASS: " + assertions + " reminder state assertions");
    }

    private static void deliveryAndCompletion() {
        TaskState t = task();
        t.update(at("2026-01-01T08:00:00Z"), UTC, false);
        equal(t.nextAlarm(), "2026-01-01T09:00:00Z", "Initial base alarm");
        long occurrence = t.occurrenceId;
        check(update(t, "2026-01-01T09:00:00Z").notify, "First occurrence notifies");
        check(!update(t, "2026-01-01T09:00:00Z").notify, "No duplicate on reconciliation");
        equal(t.followUpAt, "2026-01-01T09:15:00Z", "Configured interval");
        equal(t.nextDue, "2026-01-02T09:00:00Z", "Base cadence preserved");
        check(!update(t, "2026-01-01T09:14:59Z").notify, "No premature follow-up");
        check(update(t, "2026-01-01T09:15:00Z").notify, "Follow-up notifies");
        equal(t.followUpAt, "2026-01-01T09:30:00Z", "Follow-up repeats");
        check(t.setCompleted(occurrence, true, at("2026-01-01T09:16:00Z")), "Complete current token");
        check(t.completed && t.followUpAt == 0, "Completion clears pending follow-up");
        equal(t.nextAlarm(), "2026-01-02T09:00:00Z", "Only next regular alarm remains");
        check(!update(t, "2026-01-01T12:00:00Z").notify, "No follow-ups after completion");
        check(t.setCompleted(occurrence, false, at("2026-01-01T12:01:00Z")), "Can uncheck completion");
        equal(t.followUpAt, "2026-01-01T12:16:00Z", "Undo waits a full interval");
        t.setCompleted(occurrence, true, at("2026-01-01T12:02:00Z"));
        TaskState.Update next = update(t, "2026-01-02T09:00:00Z");
        check(next.notify && next.clearNotification && !t.completed, "New occurrence resets completion");
        check(t.occurrenceId != occurrence, "New occurrence has a distinct token");
    }

    private static void earlyCompletion() {
        TaskState t = task(); update(t, "2026-01-01T08:00:00Z");
        t.setCompleted(t.occurrenceId, true, at("2026-01-01T08:01:00Z"));
        check(!update(t, "2026-01-01T09:00:00Z").notify, "Early completion skips upcoming reminder");
        check(t.completed && t.followUpAt == 0, "Early completion remains checked for that occurrence");
        check(update(t, "2026-01-02T09:00:00Z").notify && !t.completed, "Next day becomes incomplete");
    }

    private static void ignoreAndResume() {
        TaskState t = task(); update(t, "2026-01-01T08:00:00Z"); update(t, "2026-01-01T09:00:00Z");
        long token = t.occurrenceId;
        check(t.ignoreOccurrence(token, at("2026-01-01T09:02:00Z")), "Ignore action accepted");
        check(t.ignored && !t.completed && t.followUpAt == 0, "Ignore silences without completing");
        check(t.nextAlarm() == Long.MAX_VALUE, "Ignored task needs no OS wake-up");
        check(!update(t, "2026-02-02T12:00:00Z").notify && t.ignored && !t.completed,
                "Ignore persists across normal recurrence dates");
        t.setIgnored(false, at("2026-02-02T12:00:00Z"));
        equal(t.nextAlarm(), "2026-02-02T12:15:00Z", "Resume starts a new interval");
        check(update(t, "2026-02-02T12:15:00Z").notify, "Resumed follow-up fires");
        check(!t.ignoreOccurrence(token, at("2026-02-02T12:16:00Z")) && !t.ignored, "Stale Ignore rejected");
    }

    private static void disabledReminders() {
        for (int mode = 0; mode < 3; mode++) {
            TaskState t = task();
            if (mode == 0) t.hasReminder = false;
            if (mode == 1) t.enabled = false;
            if (mode == 2) t.followUpEnabled = false;
            update(t, "2026-01-01T08:00:00Z");
            check(update(t, "2026-01-01T09:00:00Z").notify == (mode == 2), "Base reminder gating");
            check(t.followUpAt == 0, "No unwanted follow-ups");
            if (mode != 2) check(t.nextAlarm() == Long.MAX_VALUE, "No background alarm without reminders");
            t.setCompleted(t.occurrenceId, true, at("2026-01-01T10:00:00Z"));
            update(t, "2026-01-02T09:00:00Z");
            check(!t.completed, "Tasks without reminders still reset completion");
        }
    }

    private static void lateDeliveryAndOverlap() {
        TaskState t = task(); update(t, "2026-01-01T08:00:00Z"); update(t, "2026-01-01T09:00:00Z");
        check(update(t, "2026-01-01T15:00:00Z").notify, "A delayed follow-up coalesces");
        equal(t.followUpAt, "2026-01-01T15:15:00Z", "Delay counted from actual delivery");
        check(!update(t, "2026-01-01T15:00:00Z").notify, "No follow-up backlog");
        check(update(t, "2026-03-12T11:00:00Z").notify, "One catch-up after long downtime");
        check(t.occurrenceDate.equals(LocalDate.of(2026, 3, 12)), "Catch-up uses latest scheduled occurrence");
        check(!update(t, "2026-03-12T11:00:00Z").notify, "No repeated catch-up");
        t.followUpAt = at("2026-03-13T09:00:00Z").toEpochMilli();
        check(update(t, "2026-03-13T09:00:00Z").notify, "Base and follow-up coincide in one notification");
        equal(t.followUpAt, "2026-03-13T09:15:00Z", "New period owns the next follow-up");
    }

    private static void editsAndStaleActions() {
        TaskState t = task(); update(t, "2026-01-01T08:00:00Z"); update(t, "2026-01-01T09:00:00Z");
        TaskState settings = task();
        long pending = t.followUpAt;
        t.configureFrom(settings, at("2026-01-01T09:10:00Z"), UTC);
        check(t.followUpAt == pending, "Unrelated edits preserve follow-up deadline");
        t.setCompleted(t.occurrenceId, true, at("2026-01-01T09:11:00Z"));
        long token = t.occurrenceId;
        t.configureFrom(settings, at("2026-01-01T09:12:00Z"), UTC);
        check(t.completed && t.occurrenceId == token, "Unrelated edits preserve completion");
        settings.schedule.hour = 10;
        t.configureFrom(settings, at("2026-01-01T09:13:00Z"), UTC);
        check(t.completed && t.occurrenceId != token, "Time edits preserve the day's completion and replace the token");
        check(!t.setCompleted(token, true, at("2026-01-01T09:14:00Z")), "Stale Complete rejected");
        equal(t.nextDue, "2026-01-01T10:00:00Z", "Edited reminder time");
        t.setCompleted(t.occurrenceId, false, at("2026-01-01T09:15:00Z"));
        update(t, "2026-01-01T10:00:00Z");
        settings.followUpMinutes = 120;
        t.configureFrom(settings, at("2026-01-01T10:10:00Z"), UTC);
        equal(t.followUpAt, "2026-01-01T12:10:00Z", "Interval edit restarts wait");
        settings.followUpEnabled = false;
        t.configureFrom(settings, at("2026-01-01T10:11:00Z"), UTC);
        check(t.followUpAt == 0, "Disabling additional reminders clears wait");
    }

    private static void midnightAndHistory() {
        TaskState t = task(); update(t, "2026-01-01T08:00:00Z");
        t.setCompleted(t.occurrenceId, true, at("2026-01-01T08:01:00Z"));
        update(t, "2026-01-01T23:59:59Z");
        check(t.completed, "Completion lasts through the local day");
        long token = t.occurrenceId;
        TaskState.Update midnight = update(t, "2026-01-02T00:00:00Z");
        check(!t.completed && t.occurrenceDate.equals(LocalDate.of(2026, 1, 2)),
                "Daily completion rolls over at midnight before reminder time");
        check(midnight.clearNotification && !midnight.notify && t.followUpAt == 0,
                "Midnight clears old state without delivering early");
        check(Boolean.TRUE.equals(t.history.get(LocalDate.of(2026, 1, 1))), "Completed day is retained");
        check(!t.setCompleted(token, true, at("2026-01-02T00:01:00Z")), "Yesterday's action rejected after midnight");
        update(t, "2026-01-04T07:00:00Z");
        check(!t.completed && !t.occurrenceStarted, "Opening before reminder after downtime resets state");
        check(Boolean.FALSE.equals(t.history.get(LocalDate.of(2026, 1, 2)))
                && Boolean.FALSE.equals(t.history.get(LocalDate.of(2026, 1, 3))), "Every missed day survives downtime");
        check(!t.history.containsKey(LocalDate.of(2026, 1, 4)), "Today is not yet a missed day");
        int count = t.history.size(); update(t, "2026-01-04T08:00:00Z");
        check(t.history.size() == count, "History reconciliation is idempotent");
        check(update(t, "2026-01-04T09:00:00Z").notify, "Today's reminder retains its time");

        ZoneId local = ZoneId.of("America/New_York");
        t = task(); t.update(at("2026-03-07T20:00:00Z"), local, false);
        t.setCompleted(t.occurrenceId, true, at("2026-03-07T20:01:00Z"));
        t.update(at("2026-03-08T04:59:59Z"), local, false);
        check(t.completed, "UTC midnight does not reset a local day");
        t.update(at("2026-03-08T05:00:00Z"), local, false);
        check(!t.completed, "Local midnight reset before DST transition");
        t.setCompleted(t.occurrenceId, true, at("2026-03-08T06:00:00Z"));
        t.update(at("2026-03-09T04:00:00Z"), local, false);
        check(!t.completed && Boolean.TRUE.equals(t.history.get(LocalDate.of(2026, 3, 8))),
                "History and rollover handle a 23-hour day");
        long current = t.occurrenceId;
        t.update(at("2026-03-08T22:00:00Z"), local, true);
        check(t.occurrenceId == current, "Clock moving backward does not reopen old dates");
        t.update(at("2026-03-09T05:00:00Z"), local, true);
        check(t.occurrenceId == current, "Returning clock forward does not duplicate rollover");

        t = task(); t.hasReminder = false; update(t, "2026-01-01T12:00:00Z");
        t.setCompleted(t.occurrenceId, true, at("2026-01-01T12:01:00Z"));
        update(t, "2026-01-02T00:00:00Z");
        check(!t.completed && t.nextAlarm() == Long.MAX_VALUE && t.history.size() == 1,
                "No-reminder tasks roll over and track history without alarms");

        // Migration preserves known outcomes, but cannot reconstruct data that was never recorded.
        t = task(); t.occurrenceDate = LocalDate.of(2025, 12, 31); t.occurrenceId = 9; t.completed = true;
        t.nextDue = at("2026-01-01T09:00:00Z").toEpochMilli();
        update(t, "2026-01-04T08:00:00Z");
        check(!t.completed && t.history.size() == 1 && Boolean.TRUE.equals(t.history.get(LocalDate.of(2025, 12, 31))),
                "Migrating a stuck completion resets it without inventing missed history");
        t = task(); t.occurrenceDate = LocalDate.of(2026, 1, 2); t.occurrenceId = 3; t.completed = true;
        t.nextDue = at("2026-01-02T09:00:00Z").toEpochMilli();
        update(t, "2026-01-01T19:00:00Z");
        check(t.completed && t.occurrenceDate.equals(LocalDate.of(2026, 1, 1)),
                "Legacy task created after reminder time assigns completion to today");
        update(t, "2026-01-02T00:00:00Z");
        check(!t.completed && Boolean.TRUE.equals(t.history.get(LocalDate.of(2026, 1, 1))),
                "Legacy next-reminder completion now rolls over at midnight");
    }

    private static void scheduleHistory() {
        TaskState t = task(); t.schedule.kind = Schedule.Kind.SELECTED_DAYS; t.schedule.weekdays = 31;
        update(t, "2026-01-01T08:00:00Z"); update(t, "2026-01-05T00:00:00Z");
        check(t.history.size() == 2 && !t.history.containsKey(LocalDate.of(2026, 1, 3)),
                "Unselected weekend days are not missed");
        t = task(); t.enabled = false;
        update(t, "2026-01-01T08:00:00Z"); update(t, "2026-01-05T00:00:00Z");
        check(t.history.isEmpty(), "Paused dates are excluded");
        t.enabled = true; update(t, "2026-01-07T00:00:00Z");
        check(t.history.size() == 2 && t.history.firstKey().equals(LocalDate.of(2026, 1, 5)), "Resume starts tracking again");
        t = task(); t.ignored = true;
        update(t, "2026-01-01T08:00:00Z"); update(t, "2026-01-03T00:00:00Z");
        check(t.history.size() == 2 && t.history.values().stream().noneMatch(Boolean::booleanValue),
                "Ignore silences reminders without falsely completing days");

        t = task(); t.schedule.kind = Schedule.Kind.WEEKLY;
        update(t, "2026-01-01T08:00:00Z"); update(t, "2026-01-02T00:00:00Z");
        check(Boolean.FALSE.equals(t.history.get(LocalDate.of(2026, 1, 1))), "Incomplete weekly day is missed after that day ends");
        t.setCompleted(t.occurrenceId, true, at("2026-01-02T08:00:00Z"));
        check(Boolean.TRUE.equals(t.history.get(LocalDate.of(2026, 1, 1))), "Late completion updates the active occurrence's history");
        t.setCompleted(t.occurrenceId, false, at("2026-01-02T08:01:00Z"));
        check(Boolean.FALSE.equals(t.history.get(LocalDate.of(2026, 1, 1))), "Undoing a past completion restores a missed record");
        t.setCompleted(t.occurrenceId, true, at("2026-01-02T08:02:00Z"));
        update(t, "2026-01-07T23:59:59Z"); check(t.completed, "Weekly completion remains until the next scheduled date");
        update(t, "2026-01-08T00:00:00Z"); check(!t.completed, "Weekly completion resets at scheduled midnight");

        TaskState future = task(); future.schedule.kind = Schedule.Kind.WEEKLY;
        update(future, "2026-01-02T08:00:00Z");
        future.setCompleted(future.occurrenceId, true, at("2026-01-02T08:01:00Z"));
        future.setCompleted(future.occurrenceId, false, at("2026-01-02T08:02:00Z"));
        TaskState edited = task(); edited.schedule.kind = Schedule.Kind.MONTHLY; edited.schedule.day = 15;
        future.configureFrom(edited, at("2026-01-02T08:03:00Z"), UTC);
        update(future, "2026-01-10T08:00:00Z");
        check(!future.history.containsKey(LocalDate.of(2026, 1, 8)), "Undo and reschedule do not leave a canceled future day marked missed");

        for (Schedule.Kind kind : Schedule.Kind.values()) {
            t = task(); t.schedule.kind = kind; t.schedule.day = 31; t.schedule.month = 12;
            update(t, "2026-01-01T00:00:00Z"); update(t, "2029-01-01T00:00:00Z");
            int expected = 0;
            for (LocalDate day = LocalDate.of(2026, 1, 1); day.isBefore(LocalDate.of(2029, 1, 1)); day = day.plusDays(1)) {
                if (Recurrence.matches(t.schedule, day)) {
                    expected++; check(Boolean.FALSE.equals(t.history.get(day)), "Offline missed record for " + kind + " " + day);
                }
            }
            check(t.history.size() == expected, "No extra dates for " + kind);
        }
    }

    private static void clockChangesAndMigration() {
        TaskState t = task();
        t.nextDue = at("2026-01-01T09:00:00Z").toEpochMilli();
        check(update(t, "2026-01-01T09:00:01Z").notify && t.occurrenceId == 1,
                "Old nextDue-only task migrates and delivers");
        long token = t.occurrenceId;
        t.setCompleted(token, true, at("2026-01-01T09:01:00Z"));
        check(!t.update(at("2026-01-01T10:00:00Z"), ZoneId.of("Europe/Paris"), true).notify,
                "Timezone change does not replay completed local occurrence");
        check(t.completed && t.occurrenceId == token, "Timezone preserves completion");
        check(t.update(at("2026-01-03T10:00:00Z"), UTC, true).notify && !t.completed,
                "Forward clock adjustment starts latest occurrence");
        t = task(); update(t, "2026-01-01T08:00:00Z");
        check(t.update(at("2026-01-01T09:01:00Z"), UTC, true).notify,
                "Clock change crossing pending same-day reminder still delivers");
        check(!t.update(at("2026-01-01T09:02:00Z"), UTC, true).notify,
                "Repeated clock broadcast does not duplicate base reminder");
        check(Recurrence.previousOrAt(t.schedule, at("2025-12-31T23:00:00Z"), UTC) == null,
                "No occurrence before start date");
    }

    private static void allSchedules() {
        for (Schedule.Kind kind : Schedule.Kind.values()) {
            TaskState t = task(); t.schedule.kind = kind; t.schedule.day = 31;
            t.schedule.month = 12; t.schedule.monthDays = 7;
            update(t, "2026-01-01T00:00:00Z");
            // Completions and follow-ups must never shift any of the seven calendar schedules.
            for (int i = 0; i < 36; i++) {
                Instant due = Instant.ofEpochMilli(t.nextDue);
                check(t.update(due, UTC, false).notify, "Base delivery for " + kind);
                Instant afterFollowUp = due.plusSeconds(15 * 60);
                check(t.update(afterFollowUp, UTC, false).notify, "Follow-up for " + kind);
                t.setCompleted(t.occurrenceId, true, afterFollowUp);
                check(t.followUpAt == 0, "Completion clears follow-up for " + kind);
                check(t.nextDue == Recurrence.nextAfter(t.schedule, due, UTC).toEpochMilli(),
                        "Calendar cadence unchanged for " + kind);
                check(Recurrence.previousOrAt(t.schedule, due, UTC).equals(due), "Previous occurrence inverse for " + kind);
            }
        }
    }

    private static void validation() {
        for (int value : new int[]{0, -1, 525601, Integer.MAX_VALUE}) {
            TaskState t = task(); t.followUpMinutes = value;
            try { t.validate(); throw new AssertionError("Invalid interval accepted"); }
            catch (IllegalArgumentException expected) { assertions++; }
        }
        TaskState t = task(); t.followUpMinutes = 525600;
        update(t, "2026-01-01T08:00:00Z"); update(t, "2026-01-01T09:00:00Z");
        equal(t.followUpAt, "2027-01-01T09:00:00Z", "Long intervals use 64-bit arithmetic");
        equal(t.nextAlarm(), "2026-01-02T09:00:00Z", "Base recurrence wins over longer interval");
    }

    private static TaskState task() {
        TaskState t = new TaskState(); t.hasReminder = true; t.followUpEnabled = true; t.followUpMinutes = 15;
        t.schedule.start = LocalDate.of(2026, 1, 1); return t;
    }
    private static Instant at(String value) { return Instant.parse(value); }
    private static TaskState.Update update(TaskState t, String now) { return t.update(at(now), UTC, false); }
    private static void equal(long actual, String expected, String message) {
        check(actual == at(expected).toEpochMilli(), message + ": " + Instant.ofEpochMilli(actual));
    }
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
}
