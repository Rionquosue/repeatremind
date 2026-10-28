package style.spacedragon.repeatremind;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Punch clock: work periods, with productive and break periods inside them. Times are epoch seconds.
 * Independent of Android for deterministic tests.
 */
final class WorkLog {
    enum Kind { WORK, PRODUCTIVE, BREAK }

    /** End of a period that is still running. */
    static final long OPEN = 0;
    /** Dragging a handle moves it in five-minute steps; the time picker gives minute precision. */
    static final long STEP = 5 * 60;
    static final long MIN_LENGTH = 60;
    private static final long DAY = 24 * 60 * 60;

    static final class Period {
        final Kind kind;
        long start, end;

        Period(Kind kind, long start, long end) { this.kind = kind; this.start = start; this.end = end; }

        boolean open() { return end == OPEN; }

        long end(long now) { return open() ? Math.max(start, now) : end; }

        long length(long now) { return end(now) - start; }
    }

    /** Hours for one day, in seconds. Worked excludes breaks; unproductive is worked time that was not productive. */
    static final class Totals {
        long punched, breaks, productive;

        long worked() { return Math.max(0, punched - breaks); }

        long unproductive() { return Math.max(0, worked() - productive); }
    }

    /** Periods by the local date their work period started; a shift past midnight stays on its first day. */
    final TreeMap<LocalDate, List<Period>> days = new TreeMap<>();
    /** Days changed since the last save, so storage rewrites only those. */
    final Set<LocalDate> dirty = new TreeSet<>();

    // ---- Punch clock ----

    /** The running work period and the day it belongs to, or null. */
    Map.Entry<LocalDate, Period> openWork() {
        for (var day : days.descendingMap().entrySet()) {
            for (Period period : day.getValue()) {
                if (period.kind == Kind.WORK && period.open()) return new AbstractMap.SimpleImmutableEntry<>(day.getKey(), period);
            }
        }
        return null;
    }

    boolean working() { return openWork() != null; }

    boolean running(Kind kind) {
        var work = openWork();
        if (work == null) return false;
        for (Period period : days.get(work.getKey())) {
            if (period.kind == kind && period.open()) return true;
        }
        return false;
    }

    void punchIn(long now, ZoneId zone) {
        if (working()) return;
        LocalDate day = date(now, zone);
        add(day, new Period(Kind.WORK, now, OPEN));
    }

    /** Ends the running work period and anything running inside it. */
    void punchOut(long now) {
        var work = openWork();
        if (work == null) return;
        for (Period period : days.get(work.getKey())) {
            if (period.open()) period.end = Math.max(period.start, now);
        }
        dirty.add(work.getKey());
    }

    /** Starts productive or break time, punching in first if needed; the two never run together. */
    void start(Kind kind, long now, ZoneId zone) {
        if (kind == Kind.WORK) { punchIn(now, zone); return; }
        punchIn(now, zone);
        var work = openWork();
        List<Period> periods = days.get(work.getKey());
        for (Period period : periods) {
            if (period.kind == kind && period.open()) return;
        }
        stop(kind == Kind.PRODUCTIVE ? Kind.BREAK : Kind.PRODUCTIVE, now);
        add(work.getKey(), new Period(kind, Math.max(now, work.getValue().start), OPEN));
    }

    void stop(Kind kind, long now) {
        var work = openWork();
        if (work == null) return;
        for (Period period : days.get(work.getKey())) {
            if (period.kind == kind && period.open()) {
                period.end = Math.max(period.start, now);
                dirty.add(work.getKey());
            }
        }
    }

    // ---- Queries ----

    Totals totals(LocalDate day, long now) {
        Totals totals = new Totals();
        for (Period period : days.getOrDefault(day, Collections.emptyList())) {
            long length = period.length(now);
            switch (period.kind) {
                case WORK -> totals.punched += length;
                case BREAK -> totals.breaks += length;
                case PRODUCTIVE -> totals.productive += length;
            }
        }
        return totals;
    }

    List<Period> periods(LocalDate day) { return days.getOrDefault(day, Collections.emptyList()); }

    List<Period> work(LocalDate day) {
        List<Period> work = new ArrayList<>();
        for (Period period : periods(day)) if (period.kind == Kind.WORK) work.add(period);
        return work;
    }

    /** The work period on this day that contains the given period: the last one starting at or before it. */
    Period parent(LocalDate day, Period child, long now) {
        Period parent = null;
        for (Period work : work(day)) if (work.start <= child.start) parent = work;
        return parent;
    }

    /** Productive and break periods inside a work period, in time order. */
    List<Period> children(LocalDate day, Period work, long now) {
        List<Period> children = new ArrayList<>();
        for (Period period : periods(day)) {
            if (period.kind != Kind.WORK && parent(day, period, now) == work) children.add(period);
        }
        return children;
    }

    // ---- Editing ----

    /** Rounds to the nearest five-minute step. */
    static long snap(long time) { return Math.floorDiv(time + STEP / 2, STEP) * STEP; }

    /**
     * The nearest allowed value for one edge of a period: work periods never overlap and always contain their
     * productive and break periods, which never overlap each other. A running end is not editable.
     */
    long clamp(LocalDate day, Period period, boolean start, long to, long now, ZoneId zone) {
        long low, high;
        if (period.kind == Kind.WORK) {
            Period before = null, after = null;
            for (Period other : work(day)) {
                if (other == period) continue;
                if (other.start < period.start) before = other; else if (after == null) after = other;
            }
            List<Period> children = children(day, period, now);
            if (start) {
                low = Math.max(before == null ? Long.MIN_VALUE : before.end(now), dayStart(day, zone));
                high = Math.min(period.end(now) - MIN_LENGTH, dayStart(day, zone) + DAY - 1);
                if (!children.isEmpty()) high = Math.min(high, children.get(0).start);
            } else {
                if (period.open()) return period.end;
                low = period.start + MIN_LENGTH;
                for (Period child : children) low = Math.max(low, child.end(now));
                high = Math.min(after == null ? Long.MAX_VALUE : after.start, period.start + DAY);
            }
        } else {
            Period work = parent(day, period, now);
            List<Period> siblings = children(day, work, now);
            int index = siblings.indexOf(period);
            Period before = index > 0 ? siblings.get(index - 1) : null;
            Period after = index + 1 < siblings.size() ? siblings.get(index + 1) : null;
            if (start) {
                low = Math.max(work.start, before == null ? Long.MIN_VALUE : before.end(now));
                high = period.end(now) - MIN_LENGTH;
            } else {
                if (period.open()) return period.end;
                low = period.start + MIN_LENGTH;
                high = Math.min(work.end(now), after == null ? Long.MAX_VALUE : after.start);
            }
        }
        return high < low ? (start ? period.start : period.end) : Math.max(low, Math.min(high, to));
    }

    /** Moves one edge to the nearest allowed value and returns it. */
    long move(LocalDate day, Period period, boolean start, long to, long now, ZoneId zone) {
        long value = clamp(day, period, start, to, now, zone);
        if (start) period.start = value; else period.end = value;
        days.get(day).sort(ORDER);
        dirty.add(day);
        return value;
    }

    /** Removes a period; removing a work period also removes what it contains. */
    void remove(LocalDate day, Period period, long now) {
        List<Period> periods = days.get(day);
        if (periods == null) return;
        if (period.kind == Kind.WORK) periods.removeAll(children(day, period, now));
        periods.remove(period);
        if (periods.isEmpty()) days.remove(day);
        dirty.add(day);
    }

    /** Adds a finished work period: 9:00–17:00 on an empty day, else an hour after the last one. */
    Period addWork(LocalDate day, long now, ZoneId zone) {
        List<Period> work = work(day);
        long start, end;
        if (work.isEmpty()) {
            start = dayStart(day, zone) + 9 * 3600;
            end = dayStart(day, zone) + 17 * 3600;
        } else {
            Period last = work.get(work.size() - 1);
            if (last.open()) throw new IllegalArgumentException("Punch out before adding another work period");
            start = last.end;
            end = start + 3600;
            if (start >= dayStart(day, zone) + DAY) throw new IllegalArgumentException("This day has no room for another work period");
        }
        Period period = new Period(Kind.WORK, start, end);
        add(day, period);
        return period;
    }

    /** Adds up to an hour of productive or break time in the largest free gap of a work period. */
    Period addInside(LocalDate day, Period work, Kind kind, long now) {
        long gapStart = 0, gapLength = 0, cursor = work.start;
        List<Period> children = children(day, work, now);
        List<Long> edges = new ArrayList<>();
        for (Period child : children) { edges.add(child.start); edges.add(child.end(now)); }
        edges.add(work.end(now)); edges.add(work.end(now));
        for (int i = 0; i < edges.size(); i += 2) {
            long length = edges.get(i) - cursor;
            if (length > gapLength) { gapLength = length; gapStart = cursor; }
            cursor = Math.max(cursor, edges.get(i + 1));
        }
        if (gapLength < MIN_LENGTH) throw new IllegalArgumentException("This work period has no free time left");
        Period period = new Period(kind, gapStart, gapStart + Math.min(gapLength, 3600));
        add(day, period);
        return period;
    }

    // ---- Storage form ----

    /** One day as text: kind letter, start and end seconds; e.g. "W1730000000-1730028800,P1730001000-1730004600". */
    static String text(List<Period> periods) {
        StringBuilder text = new StringBuilder();
        for (Period period : periods) {
            if (text.length() > 0) text.append(',');
            text.append(period.kind.name().charAt(0)).append(period.start).append('-').append(period.end);
        }
        return text.toString();
    }

    void read(LocalDate day, String text) {
        List<Period> periods = new ArrayList<>();
        for (String part : text.split(",")) {
            if (part.isEmpty()) continue;
            Kind kind = switch (part.charAt(0)) {
                case 'W' -> Kind.WORK;
                case 'P' -> Kind.PRODUCTIVE;
                case 'B' -> Kind.BREAK;
                default -> throw new IllegalArgumentException("Unknown period: " + part);
            };
            int dash = part.indexOf('-', 1);
            periods.add(new Period(kind, Long.parseLong(part.substring(1, dash)), Long.parseLong(part.substring(dash + 1))));
        }
        periods.sort(ORDER);
        if (!periods.isEmpty()) days.put(day, periods);
    }

    // ---- Helpers ----

    private static final Comparator<Period> ORDER = Comparator.comparingLong((Period p) -> p.start)
            .thenComparing(p -> p.kind != Kind.WORK);

    private void add(LocalDate day, Period period) {
        List<Period> periods = days.computeIfAbsent(day, d -> new ArrayList<>());
        periods.add(period);
        periods.sort(ORDER);
        dirty.add(day);
    }

    static LocalDate date(long seconds, ZoneId zone) { return Instant.ofEpochSecond(seconds).atZone(zone).toLocalDate(); }

    static long dayStart(LocalDate day, ZoneId zone) { return day.atStartOfDay(zone).toEpochSecond(); }
}
