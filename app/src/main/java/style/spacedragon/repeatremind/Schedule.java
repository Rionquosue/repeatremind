package style.spacedragon.repeatremind;

import java.time.LocalDate;

/** Android-independent recurrence data. Weekday bits are Monday=1 through Sunday=64. */
final class Schedule {
    enum Kind { EVERY_DAY, SELECTED_DAYS, WEEKLY, BIWEEKLY, MONTH_DAYS, MONTHLY, YEARLY }

    Kind kind = Kind.EVERY_DAY;
    LocalDate start = LocalDate.now();
    int weekdays = 31;
    int monthDays = 7; // 1st=1, 15th=2, last=4
    int day = start.getDayOfMonth();
    int month = start.getMonthValue();
    int hour = 9;
    int minute = 0;

    void validate() {
        if (kind == null || start == null || hour < 0 || hour > 23 || minute < 0
                || minute > 59 || day < 1 || day > 31 || month < 1 || month > 12) {
            throw new IllegalArgumentException("Invalid schedule");
        }
        if (kind == Kind.SELECTED_DAYS && (weekdays < 1 || weekdays > 127)) {
            throw new IllegalArgumentException("Select at least one weekday");
        }
        if (kind == Kind.MONTH_DAYS && (monthDays < 1 || monthDays > 7)) {
            throw new IllegalArgumentException("Select at least one monthly day");
        }
        // February 29 is supported and clamps to February 28 outside leap years.
        if (kind == Kind.YEARLY && day > java.time.Month.of(month).maxLength()) {
            throw new IllegalArgumentException("That month does not have this day");
        }
    }
}
