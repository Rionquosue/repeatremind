package style.spacedragon.repeatremind;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Punch clock, editing rules, storage text and CSV with a fixed clock; plain JDK, no Android. */
public final class WorkLogTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 28);
    private static int assertions;

    public static void main(String[] args) {
        punchClock();
        totals();
        editing();
        adding();
        overnight();
        storage();
        csv();
        System.out.println("PASS: " + assertions + " work log assertions");
    }

    private static void punchClock() {
        WorkLog log = new WorkLog();
        check(!log.working() && !log.running(WorkLog.Kind.PRODUCTIVE), "Starts stopped");
        log.punchIn(at("09:00"), UTC);
        log.punchIn(at("09:05"), UTC);
        check(log.working() && log.work(DAY).size() == 1, "Punching in twice keeps one work period");
        log.start(WorkLog.Kind.PRODUCTIVE, at("09:10"), UTC);
        check(log.running(WorkLog.Kind.PRODUCTIVE), "Productive running");
        log.start(WorkLog.Kind.BREAK, at("10:00"), UTC);
        check(log.running(WorkLog.Kind.BREAK) && !log.running(WorkLog.Kind.PRODUCTIVE), "Break stops productive");
        log.start(WorkLog.Kind.PRODUCTIVE, at("10:30"), UTC);
        check(log.running(WorkLog.Kind.PRODUCTIVE) && !log.running(WorkLog.Kind.BREAK), "Productive stops break");
        log.stop(WorkLog.Kind.PRODUCTIVE, at("11:00"));
        check(!log.running(WorkLog.Kind.PRODUCTIVE) && log.working(), "Stop productive keeps working");
        log.start(WorkLog.Kind.BREAK, at("12:00"), UTC);
        log.punchOut(at("12:30"));
        check(!log.working() && !log.running(WorkLog.Kind.BREAK), "Punch out ends everything");
        check(log.periods(DAY).stream().noneMatch(WorkLog.Period::open), "No period left running");
        check(log.dirty.contains(DAY), "Changed day is marked for saving");

        log = new WorkLog();
        log.start(WorkLog.Kind.PRODUCTIVE, at("08:00"), UTC);
        check(log.working() && log.running(WorkLog.Kind.PRODUCTIVE), "Starting productive punches in");
        log.stop(WorkLog.Kind.BREAK, at("08:30"));
        log.punchOut(at("09:00"));
        log.punchOut(at("09:30"));
        check(log.work(DAY).get(0).end == at("09:00"), "Punching out twice changes nothing");
    }

    private static void totals() {
        WorkLog log = new WorkLog();
        log.punchIn(at("09:00"), UTC);
        log.start(WorkLog.Kind.PRODUCTIVE, at("09:00"), UTC);
        log.start(WorkLog.Kind.BREAK, at("12:00"), UTC);
        log.stop(WorkLog.Kind.BREAK, at("12:30"));
        WorkLog.Totals open = log.totals(DAY, at("13:00"));
        check(open.punched == hours(4) && open.breaks == minutes(30) && open.productive == hours(3)
                && open.worked() == minutes(210) && open.unproductive() == minutes(30), "Running totals count up to now");
        log.punchOut(at("17:00"));
        WorkLog.Totals day = log.totals(DAY, at("20:00"));
        check(day.punched == hours(8) && day.worked() == minutes(450) && day.unproductive() == minutes(270),
                "Closed totals stop at punch out");
        check(log.totals(DAY.plusDays(1), at("20:00")).punched == 0, "Empty day");
    }

    private static void editing() {
        check(WorkLog.snap(at("09:02")) == at("09:00") && WorkLog.snap(at("09:03")) == at("09:05"), "Snaps to five minutes");
        WorkLog log = sample();
        WorkLog.Period work = log.work(DAY).get(0);
        WorkLog.Period productive = log.children(DAY, work, 0).get(0);
        WorkLog.Period lunch = log.children(DAY, work, 0).get(1);
        // Work cannot shrink past what it contains, or move into the previous day.
        check(log.clamp(DAY, work, true, at("11:00"), 0, UTC) == at("09:30"), "Work start stops at its first child");
        check(log.clamp(DAY, work, true, LocalDate.of(2026, 10, 27).atStartOfDay(UTC).toEpochSecond(), 0, UTC)
                == DAY.atStartOfDay(UTC).toEpochSecond(), "Work start stays on its day");
        check(log.clamp(DAY, work, false, at("12:00"), 0, UTC) == at("13:00"), "Work end stops at its last child");
        check(log.clamp(DAY, work, false, at("18:00"), 0, UTC) == at("18:00"), "Work end can grow");
        // Children stay inside work and never overlap.
        check(log.clamp(DAY, productive, true, at("08:00"), 0, UTC) == at("09:00"), "Productive stays inside work");
        check(log.clamp(DAY, productive, false, at("12:45"), 0, UTC) == at("12:00"), "Productive cannot overlap lunch");
        check(log.clamp(DAY, lunch, true, at("11:00"), 0, UTC) == at("11:30"), "Lunch cannot overlap productive");
        check(log.clamp(DAY, lunch, false, at("17:30"), 0, UTC) == at("17:00"), "Lunch stays inside work");
        check(log.clamp(DAY, lunch, true, at("13:30"), 0, UTC) == at("12:59"), "Keeps at least a minute");
        log.dirty.clear();
        check(log.move(DAY, productive, false, at("11:00"), 0, UTC) == at("11:00") && productive.end == at("11:00")
                && log.dirty.contains(DAY), "Move applies and marks the day");
        // A second work period blocks the first from growing into it.
        WorkLog.Period evening = log.addWork(DAY, 0, UTC);
        check(evening.start == at("17:00") && evening.end == at("18:00"), "Next work starts where the last ended");
        check(log.clamp(DAY, work, false, at("19:00"), 0, UTC) == at("17:00"), "Work periods never overlap");
        check(log.clamp(DAY, evening, true, at("16:00"), 0, UTC) == at("17:00"), "Later work cannot move into earlier");
        // A running end is fixed; its start can move up to a minute before now.
        WorkLog running = new WorkLog();
        running.punchIn(at("09:00"), UTC);
        WorkLog.Period open = running.work(DAY).get(0);
        check(running.clamp(DAY, open, false, at("08:00"), at("10:00"), UTC) == WorkLog.OPEN, "Running end is fixed");
        check(running.clamp(DAY, open, true, at("11:00"), at("10:00"), UTC) == at("09:59"), "Running start stays before now");
        // Removing work removes its children.
        log.remove(DAY, work, 0);
        check(log.work(DAY).size() == 1 && log.periods(DAY).size() == 1, "Removing work removes its children");
        log.remove(DAY, evening, 0);
        check(!log.days.containsKey(DAY) && log.periods(DAY).isEmpty(), "An emptied day is dropped");
    }

    private static void adding() {
        WorkLog log = new WorkLog();
        WorkLog.Period work = log.addWork(DAY, 0, UTC);
        check(work.start == at("09:00") && work.end == at("17:00"), "First work period defaults to 9 to 5");
        WorkLog.Period productive = log.addInside(DAY, work, WorkLog.Kind.PRODUCTIVE, 0);
        check(productive.start == at("09:00") && productive.end == at("10:00"), "Child fills the largest gap, up to an hour");
        log.move(DAY, productive, false, at("16:30"), 0, UTC);
        WorkLog.Period lunch = log.addInside(DAY, work, WorkLog.Kind.BREAK, 0);
        check(lunch.start == at("16:30") && lunch.end == at("17:00"), "Child fits the remaining gap");
        try { log.addInside(DAY, work, WorkLog.Kind.BREAK, 0); throw new AssertionError("Added to a full period"); }
        catch (IllegalArgumentException expected) { assertions++; }
        WorkLog running = new WorkLog();
        running.punchIn(at("09:00"), UTC);
        try { running.addWork(DAY, at("10:00"), UTC); throw new AssertionError("Added work while punched in"); }
        catch (IllegalArgumentException expected) { assertions++; }
    }

    private static void overnight() {
        WorkLog log = new WorkLog();
        log.punchIn(at("22:00"), UTC);
        log.start(WorkLog.Kind.PRODUCTIVE, Instant.parse("2026-10-29T01:00:00Z").getEpochSecond(), UTC);
        log.punchOut(Instant.parse("2026-10-29T06:00:00Z").getEpochSecond());
        check(log.days.size() == 1 && log.periods(DAY).size() == 2, "A night shift stays on the day it started");
        check(log.totals(DAY, 0).punched == hours(8) && log.totals(DAY, 0).productive == hours(5), "Night shift totals");
    }

    private static void storage() {
        WorkLog log = sample();
        log.punchIn(at("18:00"), UTC);
        String text = WorkLog.text(log.periods(DAY));
        check(text.startsWith("W" + at("09:00") + "-" + at("17:00") + ",P") && text.endsWith("-0"), "Compact text, open end as 0");
        WorkLog copy = new WorkLog();
        copy.read(DAY, text);
        check(WorkLog.text(copy.periods(DAY)).equals(text) && copy.working(), "Text round-trips, including a running period");
        copy.read(DAY.plusDays(1), "");
        check(!copy.days.containsKey(DAY.plusDays(1)), "Empty text adds no day");
        try { copy.read(DAY, "X1-2"); throw new AssertionError("Accepted an unknown period"); }
        catch (IllegalArgumentException expected) { assertions++; }
    }

    private static void csv() {
        WorkLog log = sample();
        log.days.put(DAY.plusDays(2), new java.util.ArrayList<>(List.of(
                new WorkLog.Period(WorkLog.Kind.WORK, Instant.parse("2026-10-30T22:00:00Z").getEpochSecond(), WorkLog.OPEN))));
        long now = Instant.parse("2026-10-31T01:30:00Z").getEpochSecond();
        String[] rows = WorkCsv.format(log, DAY, DAY.plusDays(2), now, UTC).split("\r\n");
        check(rows.length == 3 && rows[0].equals(WorkCsv.HEADER), "Header and one row per worked day");
        check(rows[1].equals("2026-10-28,Wednesday,2026-10-28 09:00,2026-10-28 17:00,8.00,1.00,7.00,2.00,5.00,29,1"),
                "Day row: " + rows[1]);
        check(rows[2].equals("2026-10-30,Friday,2026-10-30 22:00,,3.50,0.00,3.50,0.00,3.50,0,1"), "Running row: " + rows[2]);
        check(WorkCsv.format(log, DAY.plusDays(5), DAY.plusDays(6), now, UTC).equals(WorkCsv.HEADER + "\r\n"), "Empty range");
        check(WorkCsv.clock(minutes(450)).equals("7:30") && WorkCsv.clock(hours(26)).equals("26:00"), "Display clock");
    }

    /** 9:00–17:00 work with productive 9:30–11:30 and lunch 12:00–13:00. */
    private static WorkLog sample() {
        WorkLog log = new WorkLog();
        WorkLog.Period work = log.addWork(DAY, 0, UTC);
        WorkLog.Period productive = log.addInside(DAY, work, WorkLog.Kind.PRODUCTIVE, 0);
        productive.start = at("09:30"); productive.end = at("11:30");
        WorkLog.Period lunch = log.addInside(DAY, work, WorkLog.Kind.BREAK, 0);
        lunch.start = at("12:00"); lunch.end = at("13:00");
        log.days.get(DAY).sort(java.util.Comparator.comparingLong(p -> p.start));
        return log;
    }

    private static long at(String time) { return Instant.parse("2026-10-28T" + time + ":00Z").getEpochSecond(); }
    private static long hours(long h) { return h * 3600; }
    private static long minutes(long m) { return m * 60; }
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
}
