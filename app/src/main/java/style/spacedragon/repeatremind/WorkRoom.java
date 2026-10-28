package style.spacedragon.repeatremind;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/** The work tracker room: punch clock, a calendar of worked days, and the work schedule. */
final class WorkRoom {
    static final int TRACKER = 0, DETAILS = 1, SCHEDULE = 2;
    static final String[] NAMES = {"Tracker", "Details", "Schedule"};
    static final int EXPORT_REQUEST = 7;
    /** A day of this many hours or more gets the largest circle. */
    private static final double FULL_DAY_HOURS = 10;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM uuuu");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, MMM d");

    private final MainActivity a;
    private YearMonth month = YearMonth.now();
    private WorkLog log;
    private LocalDate exportFrom, exportTo;

    WorkRoom(MainActivity activity) { a = activity; }

    /** True while the tracker shows a running clock, so the screen refreshes each minute. */
    boolean ticking(int sub) { return sub == TRACKER && log != null && log.working(); }

    void render(FrameLayout body, int sub, Workdays schedule) {
        log = new WorkLogStore(a).load();
        ScrollView scroll = new ScrollView(a);
        LinearLayout content = a.column();
        content.setPadding(a.dp(20), a.dp(8), a.dp(20), a.dp(24));
        scroll.addView(content);
        body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        switch (sub) {
            case DETAILS -> details(content);
            case SCHEDULE -> schedule(content, schedule);
            default -> tracker(content);
        }
    }

    // ---- Tracker ----

    private void tracker(LinearLayout content) {
        boolean working = log.working();
        content.addView(toggle(working, "Punch out", "Punch in", () -> {
            if (log.working()) log.punchOut(now()); else log.punchIn(now(), zone());
        }), a.spaced());
        content.addView(toggle(log.running(WorkLog.Kind.PRODUCTIVE), "Stop productive", "Start productive", () -> {
            if (log.running(WorkLog.Kind.PRODUCTIVE)) log.stop(WorkLog.Kind.PRODUCTIVE, now());
            else log.start(WorkLog.Kind.PRODUCTIVE, now(), zone());
        }), a.spaced());
        content.addView(toggle(log.running(WorkLog.Kind.BREAK), "Stop lunch/break", "Start lunch/break", () -> {
            if (log.running(WorkLog.Kind.BREAK)) log.stop(WorkLog.Kind.BREAK, now());
            else log.start(WorkLog.Kind.BREAK, now(), zone());
        }), a.spaced());

        // A shift that runs past midnight keeps showing the day it started.
        var open = log.openWork();
        LocalDate day = open != null ? open.getKey() : LocalDate.now();
        WorkLog.Totals totals = log.totals(day, now());
        LinearLayout card = a.card(18);
        TextView title = a.text(day.equals(LocalDate.now()) ? "Today" : day.format(DAY), 19, a.palette.text);
        title.setTypeface(null, Typeface.BOLD); card.addView(title);
        a.line(card, open != null ? "Punched in since " + Instant.ofEpochSecond(open.getValue().start).atZone(zone())
                .format(a.timeFormat()) : "Not punched in");
        a.line(card, "Worked " + WorkCsv.clock(totals.worked()) + " · Break " + WorkCsv.clock(totals.breaks));
        a.line(card, "Productive " + WorkCsv.clock(totals.productive)
                + " · Unproductive " + WorkCsv.clock(totals.unproductive()));
        content.addView(card, a.spaced());
        content.addView(a.button("View day", () -> openDay(day)));
    }

    /** A toggle highlights in the selection colour while its state is on. */
    private Button toggle(boolean on, String onLabel, String offLabel, Runnable change) {
        Button toggle = a.button(on ? onLabel : offLabel, () -> {
            change.run();
            save();
        });
        toggle.setSelected(on);
        toggle.setTextSize(17);
        toggle.setMinHeight(a.dp(64));
        toggle.setBackground(a.background(on ? a.palette.highlight : a.palette.button, 16, false));
        toggle.setTextColor(on ? a.palette.highlightText : a.palette.buttonText);
        return toggle;
    }

    private void save() {
        try { new WorkLogStore(a).save(log); }
        catch (RuntimeException e) { a.error(e); }
        a.rerender();
    }

    // ---- Details: calendar ----

    private void details(LinearLayout content) {
        LinearLayout nav = new LinearLayout(a);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        Button previous = a.button("‹", () -> { month = month.minusMonths(1); a.rerender(); });
        previous.setContentDescription("Previous month");
        Button next = a.button("›", () -> { month = month.plusMonths(1); a.rerender(); });
        next.setContentDescription("Next month");
        TextView label = a.text(month.format(MONTH), 18, a.palette.text);
        label.setGravity(Gravity.CENTER); label.setTypeface(null, Typeface.BOLD);
        nav.addView(previous, new LinearLayout.LayoutParams(a.dp(56), a.dp(48)));
        nav.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        nav.addView(next, new LinearLayout.LayoutParams(a.dp(56), a.dp(48)));
        content.addView(nav, a.spaced());

        LinearLayout names = new LinearLayout(a);
        for (int i = 0; i < 7; i++) {
            TextView name = a.text(DayOfWeek.of(i + 1).getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    12, a.palette.secondary);
            name.setGravity(Gravity.CENTER);
            names.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        }
        content.addView(names);

        long now = now();
        LocalDate today = LocalDate.now();
        LocalDate first = month.atDay(1);
        int lead = first.getDayOfWeek().getValue() - 1;
        long monthWorked = 0, monthProductive = 0;
        int worked = 0;
        LinearLayout row = null;
        for (int cell = 0; cell < lead + month.lengthOfMonth(); cell++) {
            if (cell % 7 == 0) {
                row = new LinearLayout(a);
                content.addView(row);
            }
            if (cell < lead) {
                row.addView(new TextView(a), new LinearLayout.LayoutParams(0, a.dp(56), 1));
                continue;
            }
            LocalDate date = first.plusDays(cell - lead);
            WorkLog.Totals totals = log.totals(date, now);
            if (totals.punched > 0) {
                worked++;
                monthWorked += totals.worked();
                monthProductive += totals.productive;
            }
            row.addView(dayCell(date, totals, date.equals(today)), new LinearLayout.LayoutParams(0, a.dp(56), 1));
        }
        if (row != null) while (row.getChildCount() < 7) row.addView(new TextView(a), new LinearLayout.LayoutParams(0, a.dp(56), 1));

        LinearLayout summary = a.card(16);
        summary.addView(a.text(worked == 0 ? "No work recorded this month"
                : "Worked " + WorkCsv.clock(monthWorked) + " over " + worked + (worked == 1 ? " day" : " days"),
                17, a.palette.text));
        if (worked > 0) {
            a.line(summary, "Productive " + WorkCsv.clock(monthProductive) + " · "
                    + (monthWorked == 0 ? 0 : Math.round(100.0 * monthProductive / monthWorked)) + "% of worked time");
        }
        LinearLayout.LayoutParams gap = a.spaced();
        gap.topMargin = a.dp(12);
        content.addView(summary, gap);
        content.addView(a.button("Export CSV", this::export));
    }

    /** A day number with a circle sized by hours worked; tapping opens the day. */
    private Button dayCell(LocalDate date, WorkLog.Totals totals, boolean today) {
        Button cell = new Button(a);
        cell.setText(String.valueOf(date.getDayOfMonth()));
        cell.setTextSize(12);
        cell.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        cell.setPadding(0, a.dp(2), 0, 0);
        cell.setAllCaps(false);
        cell.setMinWidth(0); cell.setMinimumWidth(0); cell.setMinHeight(0); cell.setMinimumHeight(0);
        cell.setTextColor(today ? a.palette.text : a.palette.secondary);
        if (today) cell.setTypeface(null, Typeface.BOLD);
        double hours = totals.worked() / 3600.0;
        float fraction = totals.punched == 0 ? 0 : (float) Math.max(0.15, Math.min(1, hours / FULL_DAY_HOURS));
        cell.setBackground(new Dot(fraction, today));
        cell.setContentDescription(date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")) + ", "
                + (totals.punched == 0 ? "no work" : WorkCsv.clock(totals.worked()) + " worked"));
        cell.setOnClickListener(v -> openDay(date));
        return cell;
    }

    /** A circle in the lower part of a calendar cell; its diameter grows with the hours worked. */
    private final class Dot extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float fraction;
        private final boolean today;

        Dot(float fraction, boolean today) { this.fraction = fraction; this.today = today; }

        @Override public void draw(Canvas canvas) {
            float cx = getBounds().exactCenterX(), cy = getBounds().top + a.dp(34);
            float largest = a.dp(19);
            if (fraction > 0) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(a.palette.highlight);
                canvas.drawCircle(cx, cy, largest * fraction, paint);
            }
            if (today) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(a.dp(1.5f));
                paint.setColor(a.palette.border);
                canvas.drawCircle(cx, cy, largest, paint);
            }
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private void openDay(LocalDate date) {
        a.launch(new Intent(a, WorkDayActivity.class).putExtra("date", date.toString()));
    }

    // ---- CSV export ----

    private void export() {
        new AlertDialog.Builder(a).setTitle("Export CSV")
                .setItems(new String[]{month.format(MONTH), "All recorded days"}, (dialog, which) -> {
                    if (which == 0) { exportFrom = month.atDay(1); exportTo = month.atEndOfMonth(); }
                    else if (log.days.isEmpty()) { a.message("No work recorded yet"); return; }
                    else { exportFrom = log.days.firstKey(); exportTo = log.days.lastKey(); }
                    String name = "RepeatRemind work " + (which == 0 ? month.toString() : exportFrom + " to " + exportTo) + ".csv";
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("text/csv").putExtra(Intent.EXTRA_TITLE, name);
                    try { a.startActivityForResult(intent, EXPORT_REQUEST); }
                    catch (ActivityNotFoundException e) { a.message("No app is available to save files"); }
                }).setNegativeButton("Cancel", null).show();
    }

    /** Writes the chosen range where the user picked; UTF-8 with a BOM so spreadsheets read it correctly. */
    void exported(Uri uri) {
        if (exportFrom == null || uri == null) return;
        try (OutputStream out = a.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IllegalStateException("The file could not be opened");
            out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            out.write(WorkCsv.format(new WorkLogStore(a).load(), exportFrom, exportTo, now(), zone())
                    .getBytes(StandardCharsets.UTF_8));
            a.message("Exported work hours from " + exportFrom + " to " + exportTo);
        } catch (Exception e) {
            a.message("The CSV could not be saved: " + e.getMessage());
        }
    }

    void save(android.os.Bundle state) {
        state.putString("month", month.toString());
        if (exportFrom != null) { state.putString("exportFrom", exportFrom.toString()); state.putString("exportTo", exportTo.toString()); }
    }

    void restore(android.os.Bundle state) {
        month = YearMonth.parse(state.getString("month", YearMonth.now().toString()));
        String from = state.getString("exportFrom");
        if (from != null) { exportFrom = LocalDate.parse(from); exportTo = LocalDate.parse(state.getString("exportTo")); }
    }

    // ---- Schedule ----

    private void schedule(LinearLayout content, Workdays work) {
        if (work == null) {
            TextView intro = a.text("Set your working days or shifts, prep time and commute. A reminder goes off "
                    + "before you need to start getting ready.", 15, a.palette.secondary);
            intro.setPadding(a.dp(4), a.dp(8), a.dp(4), a.dp(16));
            content.addView(intro);
            content.addView(a.button("Set up schedule", this::editSchedule));
            return;
        }
        ZoneId zone = zone();
        LocalDate today = LocalDate.now();
        DateTimeFormatter time = a.timeFormat();
        LinearLayout summary = a.card(18);
        TextView title = a.text(work.mode == Workdays.Mode.REGULAR ? "Regular schedule" : "Shift work", 19, a.palette.text);
        title.setTypeface(null, Typeface.BOLD); summary.addView(title);
        if (work.mode == Workdays.Mode.REGULAR) {
            a.line(summary, ScheduleText.weekdays(work.weekdays));
            a.line(summary, WorkText.hours(work.regular, time));
        }
        a.line(summary, WorkText.lead(work));
        Workdays.Occurrence next = work.reminder ? work.nextReminder(Instant.now(), zone) : null;
        a.line(summary, !work.reminder ? "Before-work reminder off" : next == null ? "No upcoming reminder"
                : "Next reminder · " + next.remindAt.atZone(zone)
                .format(DateTimeFormatter.ofPattern("EEE, MMM d · " + a.timePattern())));
        content.addView(summary, a.spaced());
        if (work.mode == Workdays.Mode.SHIFTS && work.shifts.subMap(today, today.plusDays(7)).isEmpty()) {
            TextView prompt = a.text("No shifts entered for the next 7 days. Add your times for each day you work.",
                    15, a.palette.text);
            prompt.setPadding(a.dp(4), 0, a.dp(4), a.dp(8));
            content.addView(prompt);
            content.addView(a.button("Enter shifts", this::editSchedule), a.spaced());
        }
        content.addView(a.label("Next 14 days"));
        boolean any = false;
        for (int i = 0; i < 14; i++) {
            LocalDate date = today.plusDays(i);
            Workdays.Shift shift = work.shiftOn(date);
            if (shift == null) continue;
            any = true;
            LinearLayout day = a.card(14);
            day.addView(a.text(date.format(DAY), 17, a.palette.text));
            a.line(day, WorkText.hours(shift, time));
            content.addView(day, a.spaced());
        }
        if (!any) content.addView(a.text("No work days", 15, a.palette.secondary), a.spaced());
        content.addView(a.button("Edit schedule", this::editSchedule));
    }

    private void editSchedule() { a.launch(new Intent(a, WorkdaysActivity.class)); }

    private static long now() { return System.currentTimeMillis() / 1000; }

    private static ZoneId zone() { return ZoneId.systemDefault(); }
}
