package style.spacedragon.repeatremind;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

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
        beginOccurrence(Instant.ofEpochMilli(nextDue).atZone(zone).toLocalDate());
    }

    private void beginOccurrence(LocalDate date) {
        occurrenceDate = date;
        occurrenceId++;
        occurrenceStarted = false;
        completed = false;
        followUpAt = 0;
    }

    /** Consume at most one base reminder or one follow-up, even after a long offline period. */
    Update update(Instant now, ZoneId zone, boolean clockChanged) {
        validate();
        Update result = new Update();
        long millis = now.toEpochMilli();
        if (nextDue == 0) {
            resetSchedule(now, zone);
            result.changed = true;
        } else if (occurrenceDate == null) {
            // Backward-compatible migration from the original nextDue-only storage.
            beginOccurrence(Instant.ofEpochMilli(nextDue).atZone(zone).toLocalDate());
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
            if (!date.equals(occurrenceDate)) {
                beginOccurrence(date);
                result.clearNotification = true;
            }
            boolean firstDelivery = !occurrenceStarted;
            occurrenceStarted = true;
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
        if (value) completed = false;
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
