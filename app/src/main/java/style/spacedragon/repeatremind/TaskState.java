package style.spacedragon.repeatremind;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Calendar occurrence and follow-up state; independent of Android for deterministic tests. */
class TaskState {
    static final int MAX_FOLLOW_UP_MINUTES = 525600;
    Schedule schedule = new Schedule();
    boolean hasReminder;
    boolean enabled = true;
    boolean followUpEnabled;
    int followUpMinutes = 60;
    boolean completed;
    boolean ignored;
    long nextDue;
    long followUpAt;
    LocalDate occurrenceDate;
    long occurrenceId;
    boolean occurrenceStarted;
    // One small date/boolean entry per tracked scheduled day; today's outcome stays editable.
    final NavigableMap<LocalDate, Boolean> history = new TreeMap<>();
    LocalDate historyThrough;

    static final class Update {
        boolean changed;
        boolean notify;
        boolean clearNotification;
    }

    void validate() {
        schedule.validate();
        if (followUpEnabled && (followUpMinutes < 1 || followUpMinutes > MAX_FOLLOW_UP_MINUTES)) {
            throw new IllegalArgumentException("Reminder interval must be between 1 minute and 365 days");
        }
    }

    boolean remindersActive() { return hasReminder && enabled && !ignored; }

    long nextAlarm() {
        if (!remindersActive()) return Long.MAX_VALUE;
        return !completed && followUpEnabled && followUpAt > 0
                ? Math.min(nextDue, followUpAt) : nextDue;
    }

    void resetSchedule(Instant now, ZoneId zone) {
        nextDue = Recurrence.nextAfter(schedule, now, zone).toEpochMilli();
        LocalDate today = now.atZone(zone).toLocalDate();
        beginOccurrence(Recurrence.nextDate(schedule, today));
        if (historyThrough == null) historyThrough = today;
    }

    private void beginOccurrence(LocalDate date) {
        occurrenceDate = date;
        occurrenceId++;
        occurrenceStarted = false;
        completed = history.getOrDefault(date, false);
        followUpAt = 0;
    }

    private boolean archiveBefore(LocalDate today) {
        if (historyThrough == null) {
            // Earlier releases did not track outcomes. Preserve known completion without inventing misses.
            if (completed && occurrenceDate != null) history.put(occurrenceDate, true);
            historyThrough = today;
            return true;
        }
        if (!historyThrough.isBefore(today)) return false;
        if (enabled) {
            for (LocalDate date = historyThrough; date.isBefore(today); date = date.plusDays(1)) {
                if (Recurrence.matches(schedule, date)) {
                    history.putIfAbsent(date, date.equals(occurrenceDate) && completed);
                }
            }
        }
        historyThrough = today;
        return true;
    }

    /** Consume at most one base reminder or one follow-up, even after a long offline period. */
    Update update(Instant now, ZoneId zone, boolean clockChanged) {
        validate();
        Update result = new Update();
        long millis = now.toEpochMilli();
        LocalDate today = now.atZone(zone).toLocalDate();
        if (nextDue == 0) {
            resetSchedule(now, zone);
            result.changed = true;
        } else if (occurrenceDate == null) {
            // Backward-compatible migration from the original nextDue-only storage.
            beginOccurrence(Instant.ofEpochMilli(nextDue).atZone(zone).toLocalDate());
            result.changed = true;
        }
        if (historyThrough == null && occurrenceDate.isAfter(today) && Recurrence.matches(schedule, today)) {
            // Older versions assigned today's button to tomorrow when created after reminder time.
            // Move that state onto today so its completion can roll over at the next midnight.
            occurrenceDate = today;
            occurrenceId++;
            occurrenceStarted = false;
            followUpAt = 0;
            result.changed = true;
        }
        result.changed |= archiveBefore(today);
        LocalDate currentDate = Recurrence.previousDate(schedule, today);
        if (currentDate != null && currentDate.isAfter(occurrenceDate)) {
            beginOccurrence(currentDate);
            result.clearNotification = true;
            result.changed = true;
        }
        if (clockChanged) {
            Instant latest = Recurrence.previousOrAt(schedule, now, zone);
            LocalDate latestDate = latest == null ? null : latest.atZone(zone).toLocalDate();
            nextDue = latest != null && (latestDate.isAfter(occurrenceDate)
                    || (latestDate.equals(occurrenceDate) && !occurrenceStarted))
                    ? latest.toEpochMilli() : Recurrence.nextAfter(schedule, now, zone).toEpochMilli();
            if (followUpAt > 0) resetFollowUp(now);
            result.changed = true;
        }
        if (nextDue <= millis) {
            long due = nextDue;
            Instant latest = Recurrence.previousOrAt(schedule, now, zone);
            if (latest != null) due = Math.max(due, latest.toEpochMilli());
            LocalDate date = Instant.ofEpochMilli(due).atZone(zone).toLocalDate();
            // A delayed alarm for yesterday cannot deliver against today's completion state.
            boolean firstDelivery = date.equals(occurrenceDate) && !occurrenceStarted;
            if (date.equals(occurrenceDate)) occurrenceStarted = true;
            nextDue = Recurrence.nextAfter(schedule, now, zone).toEpochMilli();
            if (firstDelivery && remindersActive() && !completed) {
                result.notify = true;
                resetFollowUp(now);
            }
            result.changed = true;
        }
        if (!remindersActive() || completed || !followUpEnabled) {
            if (followUpAt != 0) { followUpAt = 0; result.changed = true; }
        } else if (followUpAt > 0 && followUpAt <= millis && !result.notify) {
            result.notify = true;
            resetFollowUp(now);
            result.changed = true;
        }
        return result;
    }

    boolean setCompleted(long expectedOccurrence, boolean value, Instant now) {
        if (expectedOccurrence != occurrenceId) return false;
        completed = value;
        history.put(occurrenceDate, value);
        resetFollowUp(now);
        return true;
    }

    boolean ignoreOccurrence(long expectedOccurrence, Instant now) {
        if (expectedOccurrence != occurrenceId) return false;
        setIgnored(true, now);
        return true;
    }

    void setIgnored(boolean value, Instant now) {
        ignored = value;
        resetFollowUp(now);
    }

    private void resetFollowUp(Instant now) {
        followUpAt = remindersActive() && !completed && occurrenceStarted && followUpEnabled
                ? now.toEpochMilli() + followUpMinutes * 60000L : 0;
    }

    /** Edits preserve completion and pending follow-ups unless their relevant settings change. */
    void configureFrom(TaskState settings, Instant now, ZoneId zone) {
        boolean scheduleChanged = !schedule.sameAs(settings.schedule);
        boolean reminderChanged = hasReminder != settings.hasReminder || enabled != settings.enabled
                || followUpEnabled != settings.followUpEnabled || followUpMinutes != settings.followUpMinutes;
        schedule = settings.schedule.copy();
        hasReminder = settings.hasReminder;
        enabled = settings.enabled;
        followUpEnabled = settings.followUpEnabled;
        followUpMinutes = settings.followUpMinutes;
        validate();
        if (scheduleChanged || nextDue == 0) resetSchedule(now, zone);
        else if (reminderChanged) resetFollowUp(now);
    }
}
