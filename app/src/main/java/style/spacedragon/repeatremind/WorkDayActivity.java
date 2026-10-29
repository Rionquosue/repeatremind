package style.spacedragon.repeatremind;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One day of work as a candle graph: work, productive over unproductive time, and lunch/breaks in three columns. */
public final class WorkDayActivity extends BaseActivity {
    private static final String[] KIND_NAMES = {"Work", "Productive", "Lunch/break"};
    private LocalDate day;
    private WorkLog log;
    private Candle candle;
    private TextView summary;
    private LinearLayout rows;
    private DateTimeFormatter time;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            day = LocalDate.parse(getIntent().getStringExtra("date"));
            log = new WorkLogStore(this).load();
            render();
        } catch (RuntimeException e) {
            failOpen("This day could not be opened.");
        }
    }

    private void render() {
        time = timeFormat();
        LinearLayout root = column();
        root.addView(header(day.format(DateTimeFormatter.ofPattern("EEEE, MMM d")), button("Done", this::finish)));
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = column();
        content.setPadding(dp(20), 0, dp(20), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        summary = text("", 15, palette.secondary);
        summary.setPadding(dp(4), 0, dp(4), dp(8));
        content.addView(summary);
        content.addView(legend());
        candle = new Candle(this);
        candle.setContentDescription("Timeline of this day. Use the list of periods below to change times.");
        content.addView(candle, new LinearLayout.LayoutParams(-1, -2));
        content.addView(button("Add work period", this::addWork), spaced(12));
        content.addView(button("Add productive time", () -> addInside(WorkLog.Kind.PRODUCTIVE)), spaced(8));
        content.addView(button("Add lunch/break", () -> addInside(WorkLog.Kind.BREAK)), spaced(8));
        content.addView(label("Periods"));
        rows = column();
        content.addView(rows);
        install(root);
        refresh();
    }

    private LinearLayout.LayoutParams spaced(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        return params;
    }

    private LinearLayout legend() {
        LinearLayout legend = new LinearLayout(this);
        legend.setPadding(0, 0, 0, dp(8));
        int[] colours = {palette.button, palette.highlight, palette.surface, breakColour()};
        String[] names = {"Work", "Productive", "Unproductive", "Break"};
        for (int i = 0; i < 4; i++) {
            TextView key = text(names[i], 12, palette.secondary);
            key.setCompoundDrawablesRelative(swatch(colours[i]), null, null, null);
            key.setCompoundDrawablePadding(dp(4));
            legend.addView(key, new LinearLayout.LayoutParams(0, -2, 1));
        }
        return legend;
    }

    private android.graphics.drawable.Drawable swatch(int colour) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(colour); shape.setCornerRadius(dp(3)); shape.setStroke(dp(1), palette.border);
        shape.setBounds(0, 0, dp(12), dp(12));
        return shape;
    }

    private int breakColour() { return ColourMath.blend(palette.surface, palette.secondary, .45); }

    /** Updates the totals and the period list, and redraws the graph. */
    private void refresh() {
        long now = now();
        WorkLog.Totals totals = log.totals(day, now);
        summary.setText(totals.punched == 0 ? "No work recorded. Add a work period to start."
                : "Worked " + WorkCsv.clock(totals.worked()) + " · Productive " + WorkCsv.clock(totals.productive)
                + " · Unproductive " + WorkCsv.clock(totals.unproductive()) + " · Break " + WorkCsv.clock(totals.breaks));
        rows.removeAllViews();
        for (WorkLog.Period period : log.periods(day)) {
            boolean child = period.kind != WorkLog.Kind.WORK;
            Button row = button(name(period) + (log.jobs.size() > 1 ? " · " + log.jobName(period.job) : "") + " · " + range(period, now)
                    + " (" + WorkCsv.clock(period.length(now)) + ")", () -> options(period));
            row.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
            row.setBackground(background(child ? palette.surface : palette.button, 12, child));
            row.setTextColor(child ? palette.text : palette.buttonText);
            LinearLayout.LayoutParams params = spaced(8);
            params.setMarginStart(child ? dp(24) : 0);
            rows.addView(row, params);
        }
        candle.requestLayout();
        candle.invalidate();
    }

    private String name(WorkLog.Period period) { return KIND_NAMES[period.kind.ordinal()]; }

    private String range(WorkLog.Period period, long now) {
        return clock(period.start) + "–" + (period.open() ? "now" : clock(period.end));
    }

    private String clock(long seconds) { return local(seconds).format(time); }

    private ZonedDateTime local(long seconds) { return Instant.ofEpochSecond(seconds).atZone(zone()); }

    private void save() {
        try { new WorkLogStore(this).save(log); }
        catch (RuntimeException e) { error(e); }
        refresh();
    }

    // ---- Editing ----

    private void options(WorkLog.Period period) {
        List<String> items = new ArrayList<>(java.util.Collections.singletonList("Change start"));
        if (!period.open()) items.add("Change end");
        if (period.kind == WorkLog.Kind.WORK && log.activeJobs().size() > 1) items.add("Change job");
        items.add("Delete");
        new AlertDialog.Builder(this).setTitle(name(period) + " · " + range(period, now()))
                .setItems(items.toArray(new String[0]), (dialog, which) -> {
                    String item = items.get(which);
                    if (item.equals("Change start")) pickTime(period, true);
                    else if (item.equals("Change end")) pickTime(period, false);
                    else if (item.equals("Change job")) pickJob("Job for this work", period.job, job -> {
                        try { log.setJob(day, period, job, now()); save(); }
                        catch (IllegalArgumentException e) { message(e.getMessage()); }
                    });
                    else confirmDelete(period);
                }).setNegativeButton("Cancel", null).show();
    }

    private void confirmDelete(WorkLog.Period period) {
        String message = period.kind == WorkLog.Kind.WORK
                ? "This also deletes the productive and break time inside it." : null;
        new AlertDialog.Builder(this).setTitle("Delete " + name(period).toLowerCase() + "?").setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> { log.remove(day, period); save(); }).show();
    }

    /** Minute-precise entry with the system time picker; the result is fitted to the neighbouring periods. */
    private void pickTime(WorkLog.Period period, boolean start) {
        ZonedDateTime current = local(start ? period.start : period.end);
        new TimePickerDialog(this, (picker, hour, minute) -> {
            // Times are read on the day the period starts; an end at or before the start means the next day,
            // e.g. a night shift. Using the current end's date would turn a shortened night shift into 24 hours.
            LocalDate base = local(period.start).toLocalDate();
            LocalDateTime picked = base.atTime(hour, minute);
            if (!start && !picked.atZone(zone()).isAfter(local(period.start))) picked = picked.plusDays(1);
            long requested = picked.atZone(zone()).toEpochSecond();
            long applied = log.move(day, period, start, requested, now(), zone());
            if (applied != requested) {
                Toast.makeText(this, "Adjusted to " + clock(applied) + " to fit the other periods", Toast.LENGTH_LONG).show();
            }
            save();
        }, current.getHour(), current.getMinute(), android.text.format.DateFormat.is24HourFormat(this)).show();
    }

    private void addWork() {
        pickJob("Add work for which job?", WorkLog.ALL_JOBS, job -> {
            try { log.addWork(day, now(), zone(), job); save(); }
            catch (IllegalArgumentException e) { message(e.getMessage()); }
        });
    }

    /** Asks which active job to use, or uses the only one without asking. */
    private void pickJob(String title, int current, java.util.function.IntConsumer picked) {
        List<Integer> jobs = log.activeJobs();
        if (jobs.size() == 1 && current == WorkLog.ALL_JOBS) { picked.accept(jobs.get(0)); return; }
        String[] names = new String[jobs.size()];
        for (int i = 0; i < names.length; i++) names[i] = log.jobName(jobs.get(i));
        new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(names, jobs.indexOf(current), (dialog, which) -> {
                    dialog.dismiss();
                    picked.accept(jobs.get(which));
                }).setNegativeButton("Cancel", null).show();
    }

    private void addInside(WorkLog.Kind kind) {
        List<WorkLog.Period> work = log.work(day);
        if (work.isEmpty()) { message("Add a work period first"); return; }
        if (work.size() == 1) { addInside(work.get(0), kind); return; }
        String[] names = new String[work.size()];
        for (int i = 0; i < names.length; i++) names[i] = log.jobName(work.get(i).job) + " · " + range(work.get(i), now());
        new AlertDialog.Builder(this).setTitle("Add to which work period?")
                .setItems(names, (dialog, which) -> addInside(work.get(which), kind))
                .setNegativeButton("Cancel", null).show();
    }

    private void addInside(WorkLog.Period work, WorkLog.Kind kind) {
        try { log.addInside(day, work, kind, now()); save(); }
        catch (IllegalArgumentException e) { message(e.getMessage()); }
    }

    private static long now() { return System.currentTimeMillis() / 1000; }

    private static ZoneId zone() { return ZoneId.systemDefault(); }

    // ---- Graph ----

    /** A drag handle at one edge of a period. */
    private static final class Handle {
        final WorkLog.Period period;
        final boolean start;
        float x, y;
        int column;

        Handle(WorkLog.Period period, boolean start) { this.period = period; this.start = start; }
    }

    /**
     * Three candle columns on a time axis: work, productive (over unproductive work time) and breaks. Dragging a handle moves its edge in five-minute steps; tapping a
     * handle opens the time picker; tapping a bar opens its options.
     */
    private final class Candle extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
                label = new Paint(Paint.ANTI_ALIAS_FLAG), jobLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Handle> handles = new ArrayList<>();
        private final List<RectF> bars = new ArrayList<>();
        private final List<WorkLog.Period> barPeriods = new ArrayList<>();
        private final RectF rect = new RectF();
        private final int slop;
        private long rangeStart, rangeEnd;
        private Handle active;
        private List<Integer> lanes = java.util.Collections.singletonList(WorkLog.FIRST_JOB);
        private WorkLog.Period tapped;
        private float downY;
        private boolean moved;

        Candle(Context context) {
            super(context);
            slop = ViewConfiguration.get(context).getScaledTouchSlop();
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        private float hourHeight() { return dp(60); }
        private float top() { return dp(30); }

        /** Whole hours around the recorded periods, at least eight; 8:00–18:00 on an empty day. */
        private void computeRange() {
            long dayStart = WorkLog.dayStart(day, zone()), now = now();
            long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
            for (WorkLog.Period period : log.periods(day)) {
                first = Math.min(first, period.start);
                last = Math.max(last, period.end(now));
            }
            if (first == Long.MAX_VALUE) { first = dayStart + 8 * 3600; last = dayStart + 18 * 3600; }
            long startHour = Math.max(0, Math.floorDiv(first - dayStart, 3600) - 1);
            long endHour = Math.max(startHour + 8, Math.floorDiv(last - dayStart + 3599, 3600) + 1);
            rangeStart = dayStart + startHour * 3600;
            rangeEnd = dayStart + endHour * 3600;
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            // The range stays fixed while a handle is being dragged.
            if (active == null) computeRange();
            int height = Math.round(top() * 2 + (rangeEnd - rangeStart) / 3600f * hourHeight());
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), height);
        }

        private float y(long seconds) { return top() + (seconds - rangeStart) / 3600f * hourHeight(); }

        private long seconds(float y) { return rangeStart + Math.round((y - top()) / hourHeight() * 3600); }

        private static final int COLUMNS = 3;
        private final String[] titles = {"Work", "Productive", "Break"};

        private float axis() { return dp(56); }
        private float gap() { return dp(10); }
        private float columnWidth() { return (getWidth() - axis() - gap() * (COLUMNS + 1)) / COLUMNS; }
        private float column(int index) { return axis() + gap() + index * (columnWidth() + gap()); }
        private float handleRadius() { return dp(9); }

        /** Outline shades: the usual border, and two slightly different contrasts for candles whose handles overlap. */
        private int outline(int shade) {
            return shade == 0 ? palette.border : ColourMath.blend(palette.border, palette.text, shade == 1 ? .35 : .75);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            handles.clear(); bars.clear(); barPeriods.clear();
            long now = now();
            label.setColor(palette.secondary);
            label.setTextSize(dp(12));
            label.setTextAlign(Paint.Align.LEFT);
            stroke.setStyle(Paint.Style.STROKE);
            for (long hour = rangeStart; hour <= rangeEnd; hour += 3600) {
                float y = y(hour);
                stroke.setColor(palette.border); stroke.setStrokeWidth(dp(0.5f)); stroke.setAlpha(90);
                canvas.drawLine(axis(), y, getWidth(), y, stroke);
                canvas.drawText(clock(hour), 0, y + dp(4), label);
            }
            stroke.setAlpha(255);
            label.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < COLUMNS; i++) canvas.drawText(titles[i], column(i) + columnWidth() / 2, dp(16), label);

            // Candles: work; productive over unproductive work time; breaks in their own column.
            float radius = dp(8);
            // Jobs can overlap in time, so each column has a lane per job worked that day.
            lanes = log.jobsOn(day);
            if (lanes.isEmpty()) lanes = java.util.Collections.singletonList(WorkLog.FIRST_JOB);
            for (WorkLog.Period work : log.work(day)) {
                candle(work, 0, now);
                List<WorkLog.Period> children = log.children(day, work);
                // Unproductive time is the work period outside breaks; productive time is drawn over it.
                long from = work.start;
                for (WorkLog.Period child : children) {
                    if (child.kind != WorkLog.Kind.BREAK) continue;
                    track(canvas, from, child.start, work.job);
                    from = Math.max(from, child.end(now));
                }
                track(canvas, from, work.end(now), work.job);
                // The break column shows the work period faintly, for context.
                rect.set(lane(2, work.job), y(work.start), lane(2, work.job) + laneWidth(), y(work.end(now)));
                stroke.setColor(palette.border); stroke.setStrokeWidth(dp(1)); stroke.setAlpha(110);
                canvas.drawRoundRect(rect, radius, radius, stroke);
                stroke.setAlpha(255);
                for (WorkLog.Period child : children) candle(child, child.kind == WorkLog.Kind.PRODUCTIVE ? 1 : 2, now);
            }
            Map<WorkLog.Period, Integer> shades = spread();
            for (int i = 0; i < bars.size(); i++) {
                WorkLog.Period period = barPeriods.get(i);
                fill.setColor(period.kind == WorkLog.Kind.WORK ? palette.button
                        : period.kind == WorkLog.Kind.PRODUCTIVE ? palette.highlight : breakColour());
                canvas.drawRoundRect(bars.get(i), radius, radius, fill);
                int shade = shades.getOrDefault(period, 0);
                stroke.setColor(outline(shade)); stroke.setStrokeWidth(dp(shade == 0 ? 1 : 2));
                canvas.drawRoundRect(bars.get(i), radius, radius, stroke);
                // Work candles carry their job's name when there is more than one job and room for it.
                if (period.kind == WorkLog.Kind.WORK && log.jobs.size() > 1 && bars.get(i).height() > dp(40)) {
                    jobLabel.setColor(palette.buttonText);
                    jobLabel.setTextSize(dp(11));
                    jobLabel.setTextAlign(Paint.Align.CENTER);
                    String name = android.text.TextUtils.ellipsize(log.jobName(period.job), new android.text.TextPaint(jobLabel),
                            bars.get(i).width() - dp(8), android.text.TextUtils.TruncateAt.END).toString();
                    canvas.drawText(name, bars.get(i).centerX(), bars.get(i).top + dp(26), jobLabel);
                }
            }
            if (log.periods(day).stream().anyMatch(WorkLog.Period::open) && now >= rangeStart && now <= rangeEnd) {
                stroke.setColor(palette.text); stroke.setStrokeWidth(dp(1.5f));
                canvas.drawLine(axis(), y(now), getWidth(), y(now), stroke);
            }
            // Handles are one solid colour; an overlapping one gets a ring in its candle's outline shade.
            float r = handleRadius();
            for (Handle handle : handles) {
                int shade = shades.getOrDefault(handle.period, 0);
                if (shade != 0) {
                    fill.setColor(outline(shade));
                    canvas.drawCircle(handle.x, handle.y, r + dp(4), fill);
                    fill.setColor(palette.background);
                    canvas.drawCircle(handle.x, handle.y, r + dp(2), fill);
                }
                fill.setColor(palette.text);
                canvas.drawCircle(handle.x, handle.y, r, fill);
            }
        }

        /** Unproductive time in the productive column. */
        private void track(Canvas canvas, long from, long to, int job) {
            if (to <= from) return;
            rect.set(lane(1, job), y(from), lane(1, job) + laneWidth(), y(to));
            fill.setColor(palette.surface);
            canvas.drawRoundRect(rect, dp(8), dp(8), fill);
            stroke.setColor(palette.border); stroke.setStrokeWidth(dp(1));
            canvas.drawRoundRect(rect, dp(8), dp(8), stroke);
        }

        /** Records a candle and its handles, centred on the candle; a running period has no end handle. */
        private void candle(WorkLog.Period period, int column, long now) {
            float left = lane(column, period.job), top = y(period.start);
            float bottom = Math.max(y(period.end(now)), top + dp(2));
            bars.add(new RectF(left, top, left + laneWidth(), bottom));
            barPeriods.add(period);
            float centre = left + laneWidth() / 2;
            // Handles group by column and lane, since only handles in the same lane can collide.
            int group = column * 1000 + Math.max(0, lanes.indexOf(period.job));
            Handle start = new Handle(period, true);
            start.x = centre; start.y = top; start.column = group;
            handles.add(start);
            if (!period.open()) {
                Handle end = new Handle(period, false);
                end.x = centre; end.y = bottom; end.column = group;
                handles.add(end);
            }
        }

        private float laneGap() { return dp(4); }

        private float laneWidth() { return (columnWidth() - laneGap() * (lanes.size() - 1)) / lanes.size(); }

        private float lane(int column, int job) {
            return column(column) + Math.max(0, lanes.indexOf(job)) * (laneWidth() + laneGap());
        }

        /**
         * Moves handles that would overlap in a column apart sideways, and gives each period in such a group
         * its own outline shade, so a handle can be matched to its candle.
         */
        private Map<WorkLog.Period, Integer> spread() {
            Map<WorkLog.Period, Integer> shades = new HashMap<>();
            float apart = handleRadius() * 2 + dp(6);
            java.util.Set<Integer> groups = new java.util.TreeSet<>();
            for (Handle handle : handles) groups.add(handle.column);
            for (int group : groups) {
                List<Handle> inColumn = new ArrayList<>();
                for (Handle handle : handles) if (handle.column == group) inColumn.add(handle);
                inColumn.sort((a, b) -> Float.compare(a.y, b.y));
                for (int i = 0; i < inColumn.size(); ) {
                    int j = i + 1;
                    while (j < inColumn.size() && inColumn.get(j).y - inColumn.get(j - 1).y < apart) j++;
                    int size = j - i;
                    if (size > 1) {
                        for (int k = 0; k < size; k++) {
                            Handle handle = inColumn.get(i + k);
                            handle.x += (k - (size - 1) / 2f) * apart;
                            shades.putIfAbsent(handle.period, 1 + k % 2);
                        }
                    }
                    i = j;
                }
            }
            return shades;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX(), y = event.getY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    active = nearest(x, y);
                    tapped = null;
                    downY = y; moved = false;
                    if (active != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    } else {
                        for (int i = bars.size() - 1; i >= 0; i--) {
                            if (bars.get(i).contains(x, y)) { tapped = barPeriods.get(i); break; }
                        }
                    }
                    return active != null || tapped != null;
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(y - downY) > slop) moved = true;
                    if (active != null && moved) {
                        long to = WorkLog.snap(Math.max(rangeStart, Math.min(rangeEnd, seconds(y))));
                        log.move(day, active.period, active.start, to, now(), zone());
                        invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    if (active != null) {
                        Handle handle = active;
                        active = null;
                        if (moved) save(); else pickTime(handle.period, handle.start);
                    } else if (tapped != null && !moved) {
                        performClick();
                        options(tapped);
                    }
                    tapped = null;
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    if (active != null && moved) save();
                    active = null; tapped = null;
                    return true;
                }
                default -> { return super.onTouchEvent(event); }
            }
        }

        @Override public boolean performClick() { return super.performClick(); }

        private Handle nearest(float x, float y) {
            Handle best = null;
            float bestDistance = dp(26);
            for (Handle handle : handles) {
                float distance = (float) Math.hypot(handle.x - x, handle.y - y);
                if (distance < bestDistance) { best = handle; bestDistance = distance; }
            }
            return best;
        }
    }
}
