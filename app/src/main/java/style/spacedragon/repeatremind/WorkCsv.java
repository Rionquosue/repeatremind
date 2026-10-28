package style.spacedragon.repeatremind;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/** One CSV row per worked day, for importing into a spreadsheet. */
final class WorkCsv {
    private WorkCsv() { }

    static final String HEADER = "Date,Weekday,First in,Last out,Punched hours,Break hours,Worked hours,"
            + "Productive hours,Unproductive hours,Productive %,Work periods";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT);

    /** Days from first to last inclusive; a running period counts up to now and leaves Last out blank. */
    static String format(WorkLog log, LocalDate first, LocalDate last, long now, ZoneId zone) {
        StringBuilder csv = new StringBuilder(HEADER).append("\r\n");
        for (var entry : log.days.subMap(first, true, last, true).entrySet()) {
            var work = log.work(entry.getKey());
            if (work.isEmpty()) continue;
            WorkLog.Totals totals = log.totals(entry.getKey(), now);
            WorkLog.Period end = work.get(work.size() - 1);
            long productiveBase = totals.worked();
            csv.append(entry.getKey()).append(',')
                    .append(entry.getKey().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US)).append(',')
                    .append(stamp(work.get(0).start, zone)).append(',')
                    .append(end.open() ? "" : stamp(end.end, zone)).append(',')
                    .append(hours(totals.punched)).append(',').append(hours(totals.breaks)).append(',')
                    .append(hours(totals.worked())).append(',').append(hours(totals.productive)).append(',')
                    .append(hours(totals.unproductive())).append(',')
                    .append(productiveBase == 0 ? "0" : Math.round(100.0 * totals.productive / productiveBase)).append(',')
                    .append(work.size()).append("\r\n");
        }
        return csv.toString();
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

    private static String stamp(long seconds, ZoneId zone) {
        return Instant.ofEpochSecond(seconds).atZone(zone).format(STAMP);
    }
}
