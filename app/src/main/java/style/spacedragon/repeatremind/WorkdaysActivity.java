package style.spacedragon.repeatremind;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONException;
import org.json.JSONObject;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Regular weekly hours, or per-day shift times, plus the before-work reminder. */
public final class WorkdaysActivity extends BaseActivity {
    private static final int DAYS_PER_PAGE = 14;
    private Workdays work;
    private boolean existing;
    private int daysShown = DAYS_PER_PAGE;
    private LinearLayout regularFields, shiftFields, shiftDays;
    private EditText prep, commute, remind;
    private CheckBox reminderCheck;
    private TextView preview;
    private DateTimeFormatter time;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Workdays saved = new TaskStore(this).workdays();
            existing = saved != null;
            if (state != null && state.containsKey("draft")) {
                work = WorkdaysJson.fromJson(new JSONObject(state.getString("draft")));
                daysShown = state.getInt("days", DAYS_PER_PAGE);
            } else {
                work = saved == null ? new Workdays() : saved.copy();
            }
            render();
        } catch (JSONException | RuntimeException e) {
            failOpen("Workdays could not be opened.");
        }
    }

    private void render() {
        time = timeFormat();
        LinearLayout root = column();
        root.addView(header("Workdays", button("Cancel", this::finish)));
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = column();
        form.setPadding(dp(24), 0, dp(24), dp(24));
        scroll.addView(form);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        form.addView(label("Schedule type"));
        RadioGroup type = new RadioGroup(this);
        RadioButton regular = radio("Regular schedule · e.g. office");
        RadioButton shifts = radio("Shift work or irregular · e.g. retail");
        type.addView(regular); type.addView(shifts); form.addView(type);
        type.check(work.mode == Workdays.Mode.SHIFTS ? shifts.getId() : regular.getId());
        type.setOnCheckedChangeListener((group, checked) -> {
            work.mode = checked == shifts.getId() ? Workdays.Mode.SHIFTS : Workdays.Mode.REGULAR;
            updateMode();
        });

        regularFields = column();
        regularFields.addView(label("Working days"));
        regularFields.addView(weekdayToggles(work.weekdays, bits -> work.weekdays = bits));
        LinearLayout regularShift = column();
        fillShift(regularShift, work.regular);
        regularFields.addView(regularShift);
        form.addView(regularFields);

        shiftFields = column();
        shiftFields.addView(label("Shifts"));
        shiftFields.addView(text("Enter your times for each day you work. Days without times are off.",
                13, palette.secondary));
        shiftDays = column();
        shiftFields.addView(shiftDays);
        renderShiftDays();
        shiftFields.addView(button("Show " + DAYS_PER_PAGE + " more days", () -> {
            daysShown += DAYS_PER_PAGE; renderShiftDays();
        }));
        form.addView(shiftFields);

        form.addView(label("Prep time (minutes)"));
        prep = minutes("Prep time", work.prepMinutes); form.addView(prep);
        form.addView(label("Commute time (minutes)"));
        commute = minutes("Commute time", work.commuteMinutes); form.addView(commute);
        form.addView(label("Remind me this many minutes before prep"));
        remind = minutes("Reminder", work.reminderMinutes); form.addView(remind);
        reminderCheck = checkBox(R.string.work_reminder, work.reminder);
        form.addView(reminderCheck);
        preview = text("", 13, palette.secondary);
        form.addView(preview);

        root.addView(actions(existing ? "Remove" : null, this::confirmRemove, "Save workdays", this::save));
        updateMode(); install(root);
        root.setFocusableInTouchMode(true); root.requestFocus();
    }

    private EditText minutes(String name, int value) {
        EditText input = input("Minutes", String.valueOf(value), 3, false);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setContentDescription(name + " in minutes");
        onTextChanged(input, this::updatePreview);
        return input;
    }

    private void updateMode() {
        boolean regular = work.mode == Workdays.Mode.REGULAR;
        regularFields.setVisibility(regular ? View.VISIBLE : View.GONE);
        shiftFields.setVisibility(regular ? View.GONE : View.VISIBLE);
        updatePreview();
    }

    /** Start/end buttons and an optional break, editing the shift in place. */
    private void fillShift(LinearLayout container, Workdays.Shift shift) {
        container.removeAllViews();
        LinearLayout times = new LinearLayout(this);
        times.setPadding(0, dp(12), 0, 0);
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, -2, 1);
        first.setMarginEnd(dp(8));
        times.addView(timeButton("Start", () -> shift.start, value -> shift.start = value), first);
        times.addView(timeButton("End", () -> shift.end, value -> shift.end = value), new LinearLayout.LayoutParams(0, -2, 1));
        container.addView(times);
        CheckBox hasBreak = checkBox(R.string.work_break, shift.hasBreak());
        container.addView(hasBreak);
        if (shift.hasBreak()) {
            LinearLayout breaks = new LinearLayout(this);
            LinearLayout.LayoutParams from = new LinearLayout.LayoutParams(0, -2, 1);
            from.setMarginEnd(dp(8));
            breaks.addView(timeButton("Break from", () -> shift.breakStart, value -> shift.breakStart = value), from);
            breaks.addView(timeButton("Until", () -> shift.breakEnd, value -> shift.breakEnd = value),
                    new LinearLayout.LayoutParams(0, -2, 1));
            container.addView(breaks);
        }
        hasBreak.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                // Start with a 30-minute break halfway through the shift.
                shift.breakStart = (shift.start + shift.length() / 2 / 30 * 30) % 1440;
                shift.breakEnd = (shift.breakStart + 30) % 1440;
            } else {
                shift.breakStart = -1; shift.breakEnd = -1;
            }
            container.post(() -> fillShift(container, shift));
        });
    }

    private Button timeButton(String name, IntSupplier get, IntConsumer set) {
        Button[] view = new Button[1];
        view[0] = button(name + " · " + WorkText.time(get.getAsInt(), time), () ->
                new TimePickerDialog(this, (picker, hour, minute) -> {
                    set.accept(hour * 60 + minute);
                    view[0].setText(name + " · " + WorkText.time(get.getAsInt(), time));
                    updatePreview();
                }, get.getAsInt() / 60, get.getAsInt() % 60,
                        android.text.format.DateFormat.is24HourFormat(this)).show());
        return view[0];
    }

    private void renderShiftDays() {
        shiftDays.removeAllViews();
        LocalDate today = LocalDate.now();
        DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("EEE, MMM d");
        for (int i = 0; i < daysShown; i++) {
            LocalDate date = today.plusDays(i);
            Workdays.Shift shift = work.shifts.get(date);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, 0);
            row.addView(text(date.format(dateFormat), 16, palette.text), new LinearLayout.LayoutParams(0, -2, 1));
            Button times = button(shift == null ? "Off" : WorkText.range(shift, time), () -> editDay(date));
            times.setBackground(background(shift == null ? palette.surface : palette.highlight, 12, true));
            times.setTextColor(shift == null ? palette.text : palette.highlightText);
            times.setContentDescription(date.format(dateFormat) + ", " + (shift == null ? "off" : times.getText()));
            row.addView(times, new LinearLayout.LayoutParams(0, -2, 1.4f));
            shiftDays.addView(row);
        }
    }

    private void editDay(LocalDate date) {
        Workdays.Shift saved = work.shifts.get(date);
        Map.Entry<LocalDate, Workdays.Shift> previous = work.shifts.lowerEntry(date);
        // New days start from the most recent earlier shift, so repeated hours need no re-entry.
        Workdays.Shift draft = saved != null ? saved.copy()
                : previous != null ? previous.getValue().copy() : work.regular.copy();
        LinearLayout box = column();
        box.setPadding(dp(24), dp(4), dp(24), 0);
        fillShift(box, draft);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(date.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))).setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save shift", (d, which) -> {
                    try {
                        draft.validate();
                        work.shifts.put(date, draft); renderShiftDays(); updatePreview();
                    } catch (IllegalArgumentException e) { message(e.getMessage()); }
                });
        if (saved != null) dialog.setNeutralButton("Day off", (d, which) -> {
            work.shifts.remove(date); renderShiftDays(); updatePreview();
        });
        dialog.show();
    }

    /** Reads the minute fields; a blank or invalid entry becomes -1 and fails validation. */
    private void capture() {
        work.prepMinutes = minutes(prep);
        work.commuteMinutes = minutes(commute);
        work.reminderMinutes = minutes(remind);
        work.reminder = reminderCheck.isChecked();
    }

    private static int minutes(EditText field) {
        try { return Integer.parseInt(field.getText().toString().trim()); }
        catch (NumberFormatException e) { return -1; }
    }

    private void updatePreview() {
        if (preview == null) return;
        int prepTime = minutes(prep), commuteTime = minutes(commute), remindTime = minutes(remind);
        var upcoming = work.shifts.ceilingEntry(LocalDate.now());
        Workdays.Shift example = work.mode == Workdays.Mode.REGULAR ? work.regular
                : upcoming != null ? upcoming.getValue() : work.shifts.isEmpty() ? null : work.shifts.lastEntry().getValue();
        if (example == null || prepTime < 0 || commuteTime < 0 || remindTime < 0) { preview.setText(""); return; }
        int leave = example.start - commuteTime;
        int ready = leave - prepTime;
        DateTimeFormatter format = time;
        preview.setText("For a " + WorkText.time(example.start, format) + " start: reminder at "
                + WorkText.time(Math.floorMod(ready - remindTime, 1440), format) + ", get ready at "
                + WorkText.time(Math.floorMod(ready, 1440), format) + ", leave at "
                + WorkText.time(Math.floorMod(leave, 1440), format) + ".");
        preview.setPadding(0, dp(4), 0, dp(8));
    }

    private void save() {
        if (isFinishing()) return; // A second queued tap.
        capture();
        try {
            work.validate();
            Reminders.update(this, false, (data, now) -> {
                // Keep delivery state from any reminder that fired while this screen was open.
                work.lastReminder = data.work == null ? 0 : data.work.lastReminder;
                data.work = work; data.workChanged = true;
                Reminders.clear(this, Reminders.WORK_NOTIFICATION);
            });
            finish();
        } catch (IllegalArgumentException e) {
            message(e.getMessage());
        } catch (RuntimeException e) { error(e); }
    }

    private void confirmRemove() {
        new AlertDialog.Builder(this).setTitle("Remove workdays?")
                .setMessage("Your schedule, shifts and before-work reminder will be deleted.")
                .setNegativeButton("Cancel", null).setPositiveButton("Remove", (dialog, which) -> {
                    if (isFinishing()) return;
                    try {
                        Reminders.update(this, false, (data, now) -> {
                            data.work = null; data.workChanged = true;
                            Reminders.clear(this, Reminders.WORK_NOTIFICATION);
                        });
                        finish();
                    } catch (RuntimeException e) { error(e); }
                }).show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (prep == null) return;
        capture();
        state.putInt("days", daysShown);
        try { state.putString("draft", WorkdaysJson.toJson(work).toString()); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }
}
