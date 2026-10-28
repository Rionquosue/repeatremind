package style.spacedragon.repeatremind;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/** CSV for importing work hours into a spreadsheet: a daily summary, or every recorded period. */
final class WorkCsv {
    private WorkCsv() { }

    static final String DAILY_HEADER = "Date,Weekday,Job,First in,Last out,Punched hours,Break hours,Worked hours,"
            + "Productive hours,Unproductive hours,Productive %,Worked (h:mm),Work periods";
    static final String PERIODS_HEADER = "Date,Weekday,Job,Type,Start,End,Minutes,Hours";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT);
    private static final String[] TYPES = {"Work", "Productive", "Break"};

    /**
     * One row per day and job, from first to last inclusive, for one job or ALL_JOBS. A running period counts up to
     * now and leaves Last out blank.
     */
    static String daily(WorkLog log, LocalDate first, LocalDate last, int job, long now, ZoneId zone) {
        StringBuilder csv = new StringBuilder(DAILY_HEADER).append("\r\n");
        for (LocalDate day : log.days.subMap(first, true, last, true).keySet()) {
            for (int dayJob : log.jobsOn(day)) {
                if (job != WorkLog.ALL_JOBS && dayJob != job) continue;
                WorkLog.Period firstIn = null, lastOut = null;
                int periods = 0;
                for (WorkLog.Period work : log.work(day)) {
                    if (work.job != dayJob) continue;
                    if (firstIn == null) firstIn = work;
                    lastOut = work;
                    periods++;
                }
                WorkLog.Totals totals = log.totals(day, now, dayJob);
                long worked = totals.worked();
                csv.append(day).append(',').append(weekday(day)).append(',').append(cell(log.jobName(dayJob))).append(',')
                        .append(stamp(firstIn.start, zone)).append(',')
                        .append(lastOut.open() ? "" : stamp(lastOut.end, zone)).append(',')
                        .append(hours(totals.punched)).append(',').append(hours(totals.breaks)).append(',')
                        .append(hours(worked)).append(',').append(hours(totals.productive)).append(',')
                        .append(hours(totals.unproductive())).append(',')
                        .append(worked == 0 ? "0" : Math.round(100.0 * totals.productive / worked)).append(',')
                        .append(clock(worked)).append(',').append(periods).append("\r\n");
            }
        }
        return csv.toString();
    }

    /** One row per recorded period, in time order; a running period has a blank End. */
    static String periods(WorkLog log, LocalDate first, LocalDate last, int job, long now, ZoneId zone) {
        StringBuilder csv = new StringBuilder(PERIODS_HEADER).append("\r\n");
        for (LocalDate day : log.days.subMap(first, true, last, true).keySet()) {
            for (WorkLog.Period period : log.periods(day)) {
                int periodJob = log.jobOf(day, period, now);
                if (job != WorkLog.ALL_JOBS && periodJob != job) continue;
                long length = period.length(now);
                csv.append(day).append(',').append(weekday(day)).append(',').append(cell(log.jobName(periodJob))).append(',')
                        .append(TYPES[period.kind.ordinal()]).append(',').append(stamp(period.start, zone)).append(',')
                        .append(period.open() ? "" : stamp(period.end, zone)).append(',')
                        .append(length / 60).append(',').append(hours(length)).append("\r\n");
            }
        }
        return csv.toString();
    }

    /**
     * Quotes a text cell when it holds a comma, quote or line break, and prefixes an apostrophe when a spreadsheet
     * could read its first character as the start of a formula.
     */
    static String cell(String text) {
        String safe = !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0 ? "'" + text : text;
        return safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")
                ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }

    /** Decimal hours with two places, which spreadsheets sum directly. */
    static String hours(long seconds) {
        return String.format(Locale.ROOT, "%.2f", seconds / 3600.0);
    }

    /** H:MM for display; hours may exceed 24. */
    static String clock(long seconds) {
        long minutes = seconds / 60;
        return minutes / 60 + ":" + String.format(Locale.ROOT, "%02d", minutes % 60);
    }

    private static String weekday(LocalDate day) { return day.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US); }

    private static String stamp(long seconds, ZoneId zone) {
        return Instant.ofEpochSecond(seconds).atZone(zone).format(STAMP);
    }
}
