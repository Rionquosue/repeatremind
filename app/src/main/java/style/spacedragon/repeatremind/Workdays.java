package style.spacedragon.repeatremind;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Work schedule and its before-work reminder; independent of Android for deterministic tests. */
final class Workdays {
    enum Mode { REGULAR, SHIFTS }

    static final int MAX_LEAD_MINUTES = 720;
    /** Entered shifts older than this are pruned; they no longer affect reminders. */
    static final int KEEP_PAST_DAYS = 31;

    /** Times are minutes from midnight. An end at or before the start finishes the next day. */
    static final class Shift {
        int start, end;
        int breakStart = -1, breakEnd = -1;

        Shift(int start, int end) { this.start = start; this.end = end; }

        boolean hasBreak() { return breakStart >= 0; }

        int length() { return offset(end) == 0 ? 1440 : offset(end); }

        /** Minutes after the shift start, wrapping past midnight. */
        int offset(int minute) { return Math.floorMod(minute - start, 1440); }

        Shift copy() {
            Shift copy = new Shift(start, end);
            copy.breakStart = breakStart; copy.breakEnd = breakEnd;
            return copy;
        }

        boolean sameAs(Shift other) {
            return other != null && start == other.start && end == other.end
                    && breakStart == other.breakStart && breakEnd == other.breakEnd;
        }

        void validate() {
            if (!minute(start) || !minute(end)) throw new IllegalArgumentException("Invalid shift time");
            if (start == end) throw new IllegalArgumentException("Shift start and end must differ");
            if (breakStart < 0 && breakEnd < 0) return;
            if (!minute(breakStart) || !minute(breakEnd)) throw new IllegalArgumentException("Invalid break time");
            int from = offset(breakStart), to = offset(breakEnd);
            if (from == 0 || to <= from || to >= length()) {
                throw new IllegalArgumentException("Break must start and end inside the shift");
            }
        }

        private static boolean minute(int value) { return value >= 0 && value < 1440; }
    }

    /** One worked day and the instants derived from its start. */
    static final class Occurrence {
        final LocalDate date;
        final Shift shift;
        final Instant remindAt, prepAt, leaveAt, startAt;

        Occurrence(Workdays work, LocalDate date, Shift shift, ZoneId zone) {
            this.date = date; this.shift = shift;
            startAt = date.atTime(LocalTime.ofSecondOfDay(shift.start * 60L)).atZone(zone).toInstant();
            leaveAt = startAt.minusSeconds(work.commuteMinutes * 60L);
            prepAt = leaveAt.minusSeconds(work.prepMinutes * 60L);
            remindAt = prepAt.minusSeconds(work.reminderMinutes * 60L);
        }
    }

    static final class Update {
        boolean changed;
        Occurrence notify;
    }

    Mode mode = Mode.REGULAR;
    int weekdays = 31; // Monday=1 through Sunday=64, as in Schedule
    Shift regular = new Shift(9 * 60, 17 * 60);
    final NavigableMap<LocalDate, Shift> shifts = new TreeMap<>();
    boolean reminder = true;
    int prepMinutes = 30;
    int commuteMinutes = 30;
    int reminderMinutes = 15;
    long lastReminder;

    void validate() {
        if (mode == null) throw new IllegalArgumentException("Choose a schedule type");
        if (mode == Mode.REGULAR && (weekdays < 1 || weekdays > 127)) {
            throw new IllegalArgumentException("Select at least one working day");
        }
        regular.validate();
        for (Shift shift : shifts.values()) shift.validate();
        lead(prepMinutes, "Prep time"); lead(commuteMinutes, "Commute time"); lead(reminderMinutes, "Reminder time");
    }

    private static void lead(int minutes, String name) {
        if (minutes < 0 || minutes > MAX_LEAD_MINUTES) {
            throw new IllegalArgumentException(name + " must be between 0 and " + MAX_LEAD_MINUTES + " minutes");
        }
    }

    Shift shiftOn(LocalDate date) {
        if (mode == Mode.SHIFTS) return shifts.get(date);
        return (weekdays & (1 << (date.getDayOfWeek().getValue() - 1))) != 0 ? regular : null;
    }

    /** First worked day on or after the date, within a year; null when none is entered. */
    Occurrence nextShift(LocalDate from, ZoneId zone) {
        if (mode == Mode.SHIFTS) {
            var entry = shifts.ceilingEntry(from);
            return entry == null ? null : new Occurrence(this, entry.getKey(), entry.getValue(), zone);
        }
        for (int i = 0; i <= 366; i++, from = from.plusDays(1)) {
            Shift shift = shiftOn(from);
            if (shift != null) return new Occurrence(this, from, shift, zone);
        }
        return null;
    }

    /** Reminder instants rise with the shift date, because the lead time is the same every day. */
    Occurrence nextReminder(Instant after, ZoneId zone) {
        // The total lead is at most 36 hours, so a later reminder belongs to a shift no earlier than yesterday.
        LocalDate date = after.atZone(zone).toLocalDate().minusDays(1);
        for (int i = 0; i < 400; i++) {
            Occurrence next = nextShift(date, zone);
            if (next == null) return null;
            if (next.remindAt.isAfter(after)) return next;
            date = next.date.plusDays(1);
        }
        return null;
    }

    long nextAlarm(Instant now, ZoneId zone) {
        if (!reminder) return Long.MAX_VALUE;
        Occurrence next = nextReminder(now, zone);
        return next == null ? Long.MAX_VALUE : next.remindAt.toEpochMilli();
    }

    /** Delivers at most the latest due reminder, and only while its shift has not started. */
    Update update(Instant now, ZoneId zone, boolean clockChanged) {
        Update result = new Update();
        long millis = now.toEpochMilli();
        if (clockChanged && lastReminder > millis) { lastReminder = millis - 1; result.changed = true; }
        LocalDate today = now.atZone(zone).toLocalDate();
        if (shifts.headMap(today.minusDays(KEEP_PAST_DAYS)).size() > 0) {
            shifts.headMap(today.minusDays(KEEP_PAST_DAYS)).clear();
            result.changed = true;
        }
        if (!reminder) return result;
        Occurrence due = null;
        for (LocalDate date = today.minusDays(1); !date.isAfter(today.plusDays(2)); date = date.plusDays(1)) {
            Shift shift = shiftOn(date);
            if (shift == null) continue;
            Occurrence candidate = new Occurrence(this, date, shift, zone);
            if (!candidate.remindAt.isAfter(now) && candidate.startAt.isAfter(now)
                    && candidate.remindAt.toEpochMilli() > lastReminder) due = candidate;
        }
        if (due != null) {
            lastReminder = due.remindAt.toEpochMilli();
            result.notify = due;
            result.changed = true;
        }
        return result;
    }

    /** Copies settings for editing; delivery state stays with the stored schedule. */
    Workdays copy() {
        Workdays copy = new Workdays();
        copy.mode = mode; copy.weekdays = weekdays; copy.regular = regular.copy();
        for (var entry : shifts.entrySet()) copy.shifts.put(entry.getKey(), entry.getValue().copy());
        copy.reminder = reminder; copy.prepMinutes = prepMinutes; copy.commuteMinutes = commuteMinutes;
        copy.reminderMinutes = reminderMinutes; copy.lastReminder = lastReminder;
        return copy;
    }
}
