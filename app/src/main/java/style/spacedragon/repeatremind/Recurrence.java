package style.spacedragon.repeatremind;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

final class Recurrence {
    private Recurrence() { }

    static LocalDate previousDate(Schedule s, LocalDate date) {
        for (int i = 0; i <= 366 && !date.isBefore(s.start); i++, date = date.minusDays(1)) {
            if (matches(s, date)) return date;
        }
        return null;
    }

    static LocalDate nextDate(Schedule s, LocalDate date) {
        if (date.isBefore(s.start)) date = s.start;
        for (int i = 0; i <= 366; i++, date = date.plusDays(1)) {
            if (matches(s, date)) return date;
        }
        throw new IllegalStateException("No recurrence within a year");
    }

    static Instant previousOrAt(Schedule s, Instant at, ZoneId zone) {
        s.validate();
        LocalDate date = previousDate(s, at.atZone(zone).toLocalDate());
        // Today's occurrence may not have reached its time yet.
        if (date != null && at(s, date, zone).isAfter(at)) date = previousDate(s, date.minusDays(1));
        return date == null ? null : at(s, date, zone);
    }

    /** Strictly after the instant supplied; always uses local calendar days, never 24h multiples. */
    static Instant nextAfter(Schedule s, Instant after, ZoneId zone) {
        s.validate();
        // A valid schedule has at least one occurrence within 366 calendar days.
        LocalDate date = nextDate(s, after.atZone(zone).toLocalDate());
        if (!at(s, date, zone).isAfter(after)) date = nextDate(s, date.plusDays(1));
        return at(s, date, zone);
    }

    /** atZone moves a DST gap forward and uses the first offset in a repeated hour. */
    private static Instant at(Schedule s, LocalDate date, ZoneId zone) {
        return date.atTime(s.hour, s.minute).atZone(zone).toInstant();
    }

    static boolean matches(Schedule s, LocalDate date) {
        if (date.isBefore(s.start)) return false;
        int day = date.getDayOfMonth();
        return switch (s.kind) {
            case EVERY_DAY -> true;
            case SELECTED_DAYS -> (s.weekdays & (1 << (date.getDayOfWeek().getValue() - 1))) != 0;
            case WEEKLY -> ChronoUnit.DAYS.between(s.start, date) % 7 == 0;
            case BIWEEKLY -> ChronoUnit.DAYS.between(s.start, date) % 14 == 0;
            case MONTH_DAYS -> ((s.monthDays & 1) != 0 && day == 1)
                    || ((s.monthDays & 2) != 0 && day == 15)
                    || ((s.monthDays & 4) != 0 && day == date.lengthOfMonth());
            case MONTHLY -> day == Math.min(s.day, date.lengthOfMonth());
            case YEARLY -> date.getMonthValue() == s.month
                    && day == Math.min(s.day, date.lengthOfMonth());
        };
    }
}
