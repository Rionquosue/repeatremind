package style.spacedragon.repeatremind;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** Plain JDK tests: no test framework or Android runtime needed. */
public final class RecurrenceTest {
    private static int assertions;
    private static final ZoneId UTC = ZoneId.of("UTC");

    public static void main(String[] args) {
        Schedule s = schedule(Schedule.Kind.EVERY_DAY);
        expect(s, "2026-10-16T08:59:59Z", "2026-10-16T09:00:00Z");
        expect(s, "2026-10-16T09:00:00Z", "2026-10-17T09:00:00Z");
        s.start = LocalDate.of(2027, 1, 1);
        expect(s, "2026-10-16T10:00:00Z", "2027-01-01T09:00:00Z");

        s = schedule(Schedule.Kind.SELECTED_DAYS); s.weekdays = 31;
        expect(s, "2026-10-16T09:00:00Z", "2026-10-19T09:00:00Z");
        s.weekdays = 64;
        expect(s, "2026-10-16T10:00:00Z", "2026-10-18T09:00:00Z");
        s.weekdays = 0; expectInvalid(s);

        s = schedule(Schedule.Kind.WEEKLY); s.start = LocalDate.of(2026, 10, 12);
        expect(s, "2026-10-16T10:00:00Z", "2026-10-19T09:00:00Z");

        s = schedule(Schedule.Kind.BIWEEKLY); s.start = LocalDate.of(2026, 1, 31);
        expect(s, "2026-01-31T09:00:00Z", "2026-02-14T09:00:00Z");
        expect(s, "2026-02-14T09:00:00Z", "2026-02-28T09:00:00Z");
        expect(s, "2026-02-28T09:00:00Z", "2026-03-14T09:00:00Z");
        expect(s, "2027-01-01T00:00:00Z", "2027-01-02T09:00:00Z");

        s = schedule(Schedule.Kind.MONTH_DAYS); s.monthDays = 7;
        expect(s, "2026-02-01T09:00:00Z", "2026-02-15T09:00:00Z");
        expect(s, "2026-02-15T09:00:00Z", "2026-02-28T09:00:00Z");
        expect(s, "2026-02-28T09:00:00Z", "2026-03-01T09:00:00Z");
        s.monthDays = 4;
        expect(s, "2028-02-15T09:00:00Z", "2028-02-29T09:00:00Z");
        s.monthDays = 0; expectInvalid(s);

        s = schedule(Schedule.Kind.MONTHLY); s.day = 31;
        expect(s, "2026-01-31T09:00:00Z", "2026-02-28T09:00:00Z");
        expect(s, "2026-02-28T09:00:00Z", "2026-03-31T09:00:00Z");
        expect(s, "2026-03-31T09:00:00Z", "2026-04-30T09:00:00Z");
        expect(s, "2028-01-31T09:00:00Z", "2028-02-29T09:00:00Z");
        s.day = 30;
        expect(s, "2026-02-28T09:00:00Z", "2026-03-30T09:00:00Z");

        s = schedule(Schedule.Kind.YEARLY); s.month = 2; s.day = 29;
        expect(s, "2026-01-01T00:00:00Z", "2026-02-28T09:00:00Z");
        expect(s, "2027-03-01T00:00:00Z", "2028-02-29T09:00:00Z");
        expect(s, "2028-02-29T09:00:00Z", "2029-02-28T09:00:00Z");
        s.month = 4; s.day = 31; expectInvalid(s);
        s.month = 12; s.day = 31;
        expect(s, "2026-12-31T09:00:00Z", "2027-12-31T09:00:00Z");

        ZoneId toronto = ZoneId.of("America/Toronto");
        s = schedule(Schedule.Kind.EVERY_DAY); s.hour = 2; s.minute = 30;
        check(Recurrence.nextAfter(s, Instant.parse("2026-03-08T06:00:00Z"), toronto)
                .equals(Instant.parse("2026-03-08T07:30:00Z")), "DST gap moves forward");
        s.hour = 1;
        check(Recurrence.nextAfter(s, Instant.parse("2026-11-01T04:00:00Z"), toronto)
                .equals(Instant.parse("2026-11-01T05:30:00Z")), "DST overlap uses first offset");
        check(Recurrence.nextAfter(s, Instant.parse("2026-11-01T05:30:00Z"), toronto)
                .equals(Instant.parse("2026-11-02T06:30:00Z")), "No second notification in repeated hour");
        s.hour = 9; s.minute = 0;
        check(Recurrence.nextAfter(s, Instant.parse("2026-10-16T12:00:00Z"), toronto)
                .equals(Instant.parse("2026-10-16T13:00:00Z")), "Local timezone honored");

        // Exhaust every weekday selection over a leap year, comparing to calendar weekday values.
        for (int bits = 1; bits < 128; bits++) {
            s = schedule(Schedule.Kind.SELECTED_DAYS); s.weekdays = bits;
            Instant cursor = Instant.parse("2028-01-01T00:00:00Z");
            for (int i = 0; i < 55; i++) {
                Instant next = Recurrence.nextAfter(s, cursor, UTC);
                LocalDate date = next.atZone(UTC).toLocalDate();
                check(next.isAfter(cursor), "Recurrence must advance");
                check((bits & (1 << (date.getDayOfWeek().getValue() - 1))) != 0,
                        "Only selected weekdays");
                check(ChronoUnit.DAYS.between(cursor.atZone(UTC).toLocalDate(), date) <= 7,
                        "Selected weekdays never skip a week");
                cursor = next;
            }
        }
        // Prove clamping never changes the requested monthly day after a short month.
        for (int requested = 28; requested <= 31; requested++) {
            s = schedule(Schedule.Kind.MONTHLY); s.day = requested;
            Instant cursor = Instant.parse("2027-01-01T00:00:00Z");
            for (int i = 0; i < 48; i++) {
                Instant next = Recurrence.nextAfter(s, cursor, UTC);
                LocalDate date = next.atZone(UTC).toLocalDate();
                check(date.getDayOfMonth() == Math.min(requested, date.lengthOfMonth()), "Month clamping");
                if (i > 0) check(ChronoUnit.MONTHS.between(cursor.atZone(UTC).toLocalDate().withDayOfMonth(1),
                        date.withDayOfMonth(1)) == 1, "Exactly one occurrence per month");
                cursor = next;
            }
        }
        s = schedule(Schedule.Kind.BIWEEKLY);
        Instant cursor = Recurrence.nextAfter(s, Instant.parse("2026-01-01T00:00:00Z"), toronto);
        for (int i = 0; i < 100; i++) {
            Instant next = Recurrence.nextAfter(s, cursor, toronto);
            check(ChronoUnit.DAYS.between(cursor.atZone(toronto).toLocalDate(),
                    next.atZone(toronto).toLocalDate()) == 14, "14 local days across DST/year boundaries");
            check(next.atZone(toronto).getHour() == 9, "Biweekly time stays local"); cursor = next;
        }
        System.out.println("PASS: " + assertions + " recurrence assertions");
    }

    private static Schedule schedule(Schedule.Kind kind) {
        Schedule s = new Schedule(); s.kind = kind; s.start = LocalDate.of(2026, 1, 1); return s;
    }
    private static void expect(Schedule s, String after, String expected) {
        Instant actual = Recurrence.nextAfter(s, Instant.parse(after), UTC);
        check(actual.equals(Instant.parse(expected)), expected + " != " + actual);
    }
    private static void expectInvalid(Schedule s) {
        try { Recurrence.nextAfter(s, Instant.EPOCH, UTC); throw new AssertionError("Invalid schedule accepted"); }
        catch (IllegalArgumentException expected) { assertions++; }
    }
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
}
