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
import java.util.List;

/** One day of work as a candle graph: work in the first column, productive, unproductive and break time in the second. */
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
            Button row = button(name(period) + " · " + range(period, now)
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
        items.add("Delete");
        new AlertDialog.Builder(this).setTitle(name(period) + " · " + range(period, now()))
                .setItems(items.toArray(new String[0]), (dialog, which) -> {
                    String item = items.get(which);
                    if (item.equals("Change start")) pickTime(period, true);
                    else if (item.equals("Change end")) pickTime(period, false);
                    else confirmDelete(period);
                }).setNegativeButton("Cancel", null).show();
    }

    private void confirmDelete(WorkLog.Period period) {
        String message = period.kind == WorkLog.Kind.WORK
                ? "This also deletes the productive and break time inside it." : null;
        new AlertDialog.Builder(this).setTitle("Delete " + name(period).toLowerCase() + "?").setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> { log.remove(day, period, now()); save(); }).show();
    }

    /** Minute-precise entry with the system time picker; the result is fitted to the neighbouring periods. */
    private void pickTime(WorkLog.Period period, boolean start) {
        ZonedDateTime current = local(start ? period.start : period.end);
        new TimePickerDialog(this, (picker, hour, minute) -> {
            LocalDateTime picked = current.toLocalDate().atTime(hour, minute);
            long requested = picked.atZone(zone()).toEpochSecond();
            // An end picked before the start means the next morning, e.g. a night shift.
            if (!start && requested <= period.start) requested += 24 * 3600;
            long applied = log.move(day, period, start, requested, now(), zone());
            if (applied != requested) {
                Toast.makeText(this, "Adjusted to " + clock(applied) + " to fit the other periods", Toast.LENGTH_LONG).show();
            }
            save();
        }, current.getHour(), current.getMinute(), android.text.format.DateFormat.is24HourFormat(this)).show();
    }

    private void addWork() {
        try { log.addWork(day, now(), zone()); save(); }
        catch (IllegalArgumentException e) { message(e.getMessage()); }
    }

    private void addInside(WorkLog.Kind kind) {
        List<WorkLog.Period> work = log.work(day);
        if (work.isEmpty()) { message("Add a work period first"); return; }
        if (work.size() == 1) { addInside(work.get(0), kind); return; }
        String[] names = new String[work.size()];
        for (int i = 0; i < names.length; i++) names[i] = "Work · " + range(work.get(i), now());
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

        Handle(WorkLog.Period period, boolean start) { this.period = period; this.start = start; }
    }

    /**
     * Two candle columns on a time axis. Dragging a handle moves its edge in five-minute steps; tapping a
     * handle opens the time picker; tapping a bar opens its options.
     */
    private final class Candle extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
                label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Handle> handles = new ArrayList<>();
        private final List<RectF> bars = new ArrayList<>();
        private final List<WorkLog.Period> barPeriods = new ArrayList<>();
        private final RectF rect = new RectF();
        private final int slop;
        private long rangeStart, rangeEnd;
        private Handle active;
        private WorkLog.Period tapped;
        private float downY;
        private boolean moved;

        Candle(Context context) {
            super(context);
            slop = ViewConfiguration.get(context).getScaledTouchSlop();
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        private float hourHeight() { return dp(60); }
        private float top() { return dp(14); }

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

        private float axis() { return dp(56); }
        private float columnWidth() { return (getWidth() - axis() - dp(12) * 3) / 2f; }
        private float column(int index) { return axis() + dp(12) + index * (columnWidth() + dp(12)); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            handles.clear(); bars.clear(); barPeriods.clear();
            long now = now();
            label.setColor(palette.secondary);
            label.setTextSize(dp(12));
            stroke.setStyle(Paint.Style.STROKE);
            for (long hour = rangeStart; hour <= rangeEnd; hour += 3600) {
                float y = y(hour);
                stroke.setColor(palette.border); stroke.setStrokeWidth(dp(0.5f)); stroke.setAlpha(90);
                canvas.drawLine(axis(), y, getWidth(), y, stroke);
                canvas.drawText(clock(hour), 0, y + dp(4), label);
            }
            stroke.setAlpha(255);
            float w = columnWidth(), radius = dp(8);
            for (WorkLog.Period work : log.work(day)) {
                drawBar(canvas, work, column(0), w, palette.button, false, now);
                // The second column shows the work period as unproductive, with productive and break time on top.
                rect.set(column(1), y(work.start), column(1) + w, y(work.end(now)));
                fill.setColor(palette.surface);
                canvas.drawRoundRect(rect, radius, radius, fill);
                stroke.setColor(palette.border); stroke.setStrokeWidth(dp(1));
                canvas.drawRoundRect(rect, radius, radius, stroke);
                for (WorkLog.Period child : log.children(day, work, now)) {
                    drawBar(canvas, child, column(1), w,
                            child.kind == WorkLog.Kind.PRODUCTIVE ? palette.highlight : breakColour(), true, now);
                }
            }
            if (log.periods(day).stream().anyMatch(WorkLog.Period::open) && now >= rangeStart && now <= rangeEnd) {
                stroke.setColor(palette.text); stroke.setStrokeWidth(dp(1.5f));
                canvas.drawLine(axis(), y(now), getWidth(), y(now), stroke);
            }
            for (Handle handle : handles) {
                fill.setColor(palette.background);
                canvas.drawCircle(handle.x, handle.y, dp(9), fill);
                stroke.setColor(palette.text); stroke.setStrokeWidth(dp(2));
                canvas.drawCircle(handle.x, handle.y, dp(9), stroke);
            }
        }

        private void drawBar(Canvas canvas, WorkLog.Period period, float left, float width, int colour,
                boolean outlined, long now) {
            float top = y(period.start), bottom = Math.max(y(period.end(now)), top + dp(2));
            RectF bar = new RectF(left, top, left + width, bottom);
            fill.setColor(colour);
            canvas.drawRoundRect(bar, dp(8), dp(8), fill);
            if (outlined) {
                stroke.setColor(palette.border); stroke.setStrokeWidth(dp(1));
                canvas.drawRoundRect(bar, dp(8), dp(8), stroke);
            }
            bars.add(bar); barPeriods.add(period);
            // Start handles sit left of centre and end handles right, so adjacent edges never hide each other.
            Handle start = new Handle(period, true);
            start.x = left + width * .3f; start.y = top;
            handles.add(start);
            if (!period.open()) {
                Handle end = new Handle(period, false);
                end.x = left + width * .7f; end.y = bottom;
                handles.add(end);
            }
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
