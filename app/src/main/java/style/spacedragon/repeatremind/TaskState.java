package style.spacedragon.repeatremind;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Calendar occurrence and follow-up state; independent of Android for deterministic tests. */
class TaskState {
    static final int MAX_FOLLOW_UP_MINUTES = 525600;
    static final int MAX_TEMPORARY_LENGTH = 999;
    enum Span {
        DAYS(ChronoUnit.DAYS), WEEKS(ChronoUnit.WEEKS), MONTHS(ChronoUnit.MONTHS), YEARS(ChronoUnit.YEARS);
        final ChronoUnit unit;
        Span(ChronoUnit unit) { this.unit = unit; }
    }
    Schedule schedule = new Schedule();
    boolean hasReminder = true;
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
    // Past scheduled days are kept only when missed (false); completion is kept only for the
    // current or a future occurrence (true), so the map grows with missed days alone.
    final NavigableMap<LocalDate, Boolean> history = new TreeMap<>();
    LocalDate historyThrough;
    // A temporary task runs for a set length from its start date, then disables itself.
    boolean temporary;
    int temporaryLength = 1;
    Span temporarySpan = Span.MONTHS;

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
        if (temporary && (temporarySpan == null || temporaryLength < 1 || temporaryLength > MAX_TEMPORARY_LENGTH)) {
            throw new IllegalArgumentException("Temporary length must be between 1 and " + MAX_TEMPORARY_LENGTH);
        }
    }

    /** First local date the task no longer runs, or null when it is not temporary. */
    LocalDate endDate() {
        return temporary ? schedule.start.plus(temporaryLength, temporarySpan.unit) : null;
    }

    boolean expired(LocalDate today) {
        return temporary && !today.isBefore(endDate());
    }

    boolean remindersActive() { return hasReminder && enabled && !ignored; }

    long nextAlarm() {
        if (!remindersActive()) return Long.MAX_VALUE;
        return !completed && followUpEnabled && followUpAt > 0
                ? Math.min(nextDue, followUpAt) : nextDue;
    }

    /** The next base reminder worth waking for; a completed occurrence skips to the one after it. */
    long nextReminder(ZoneId zone) {
        if (!remindersActive()) return Long.MAX_VALUE;
        if (completed && occurrenceDate != null
                && !Instant.ofEpochMilli(nextDue).atZone(zone).toLocalDate().isAfter(occurrenceDate)) {
            Instant dayEnd = occurrenceDate.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1);
            return Recurrence.nextAfter(schedule, dayEnd, zone).toEpochMilli();
        }
        return nextDue;
    }

    /** The pending follow-up, which needs no exact alarm. */
    long nextFollowUp() {
        return remindersActive() && !completed && followUpEnabled && followUpAt > 0 ? followUpAt : Long.MAX_VALUE;
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
            // Earlier releases did not track outcomes, so no missed days can be reconstructed.
            historyThrough = today;
            return true;
        }
        if (!historyThrough.isBefore(today)) return false;
        if (enabled) {
            LocalDate end = endDate();
            LocalDate until = end != null && end.isBefore(today) ? end : today;
            for (LocalDate date = historyThrough; date.isBefore(until); date = date.plusDays(1)) {
                if (Recurrence.matches(schedule, date) && !history.containsKey(date)
                        && !(date.equals(occurrenceDate) && completed)) {
                    history.put(date, false);
                }
            }
        }
        historyThrough = today;
        pruneCompleted();
        return true;
    }

    /** Completed past days are not missed, so they need no record. */
    void pruneCompleted() {
        if (historyThrough != null) history.headMap(historyThrough).values().removeIf(Boolean::booleanValue);
    }

    /** Compact storage form: comma-separated ISO dates, completed ones prefixed with '+'. */
    String historyText() {
        StringBuilder text = new StringBuilder();
        for (var entry : history.entrySet()) {
            if (text.length() > 0) text.append(',');
            if (entry.getValue()) text.append('+');
            text.append(entry.getKey());
        }
        return text.toString();
    }

    void readHistory(String text) {
        history.clear();
        if (text.isEmpty()) return;
        for (String part : text.split(",")) {
            boolean done = part.startsWith("+");
            history.put(LocalDate.parse(done ? part.substring(1) : part), done);
        }
    }

    /** Consume at most one base reminder or one follow-up, even after a long offline period. */
    Update update(Instant now, ZoneId zone, boolean clockChanged) {
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
        if (enabled && expired(today)) {
            enabled = false;
            followUpAt = 0;
            result.clearNotification = true;
            result.changed = true;
        }
        LocalDate currentDate = Recurrence.previousDate(schedule, today);
        if (currentDate != null && currentDate.isAfter(occurrenceDate)) {
            beginOccurrence(currentDate);
            result.clearNotification = true;
            result.changed = true;
        }
        if (clockChanged) {
            Instant latest = Recurrence.previousOrAt(schedule, now, zone);
            LocalDate latestDate = latest == null ? null : latest.atZone(zone).toLocalDate();
            long due = latest != null && (latestDate.isAfter(occurrenceDate)
                    || (latestDate.equals(occurrenceDate) && !occurrenceStarted))
                    ? latest.toEpochMilli() : Recurrence.nextAfter(schedule, now, zone).toEpochMilli();
            if (due != nextDue) { nextDue = due; result.changed = true; }
            // Only a clock moved backwards leaves a follow-up further away than its interval.
            if (followUpAt > millis + followUpMinutes * 60000L) { resetFollowUp(now); result.changed = true; }
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
        if (historyThrough != null && occurrenceDate.isBefore(historyThrough)) {
            // A late weekly or monthly completion clears that day's missed record; undo restores it.
            if (value) history.remove(occurrenceDate); else history.put(occurrenceDate, false);
        } else if (value) {
            history.put(occurrenceDate, true);
        } else history.remove(occurrenceDate); // An unfinished current/future date is not a missed record yet.
        resetFollowUp(now);
        return true;
    }

    /** Completes a past day that has already been recorded as missed, e.g. from a notification after midnight. */
    boolean completeMissed(LocalDate date) {
        if (date == null || !Boolean.FALSE.equals(history.get(date))) return false;
        history.remove(date);
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
        temporary = settings.temporary;
        temporaryLength = settings.temporaryLength;
        temporarySpan = settings.temporarySpan;
        validate();
        if (scheduleChanged || nextDue == 0) resetSchedule(now, zone);
        else if (reminderChanged) resetFollowUp(now);
    }
}
