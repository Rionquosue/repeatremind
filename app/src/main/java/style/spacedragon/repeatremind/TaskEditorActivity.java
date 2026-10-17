package style.spacedragon.repeatremind;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONException;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

public final class TaskEditorActivity extends BaseActivity {
    private Task task;
    private EditText title, reminderText, intervalInput;
    private Switch reminderSwitch, enabledSwitch, followUpSwitch;
    private CheckBox ignoredSwitch;
    private boolean originalIgnored;
    private Spinner repeat, month, intervalUnit;
    private NumberPicker day;
    private LinearLayout weekdays, biweekly, calendarDays, monthFields, reminderFields, followUpFields;
    private RadioButton strict, calendar;
    private Button startDate, time;
    private final CheckBox[] markers = new CheckBox[3];
    private static final String[] REPEATS = {"Every day", "Daily (select days)", "Weekly",
            "Bi-weekly", "Monthly", "Yearly"};

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            if (state != null && state.containsKey("draft")) {
                task = Task.fromJson(new JSONObject(state.getString("draft")), false);
            } else {
                int id = getIntent().getIntExtra("task_id", 0);
                task = id == 0 ? new Task() : new TaskStore(this).load().stream()
                        .filter(t -> t.id == id).findFirst().orElse(null);
                if (task == null) { finish(); return; }
            }
            originalIgnored = state == null ? task.ignored : state.getBoolean("originalIgnored", task.ignored);
            render();
        } catch (JSONException | RuntimeException e) {
            new AlertDialog.Builder(this).setMessage("This task could not be opened.")
                    .setPositiveButton("Close", (dialog, which) -> finish()).show();
        }
    }

    private void render() {
        Schedule s = task.schedule;
        LinearLayout root = column();
        root.addView(header(task.id == 0 ? "New task" : "Edit task", button("Cancel", this::finish)));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout form = column();
        form.setPadding(dp(24), 0, dp(24), dp(24));
        scroll.addView(form);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        form.addView(label("Task"));
        title = input("Task name", task.title, 160, false); form.addView(title);
        form.addView(label("Repeat schedule"));
        repeat = spinner(REPEATS); form.addView(repeat);

        weekdays = column();
        HorizontalScrollView dayScroll = new HorizontalScrollView(this);
        LinearLayout dayRow = new LinearLayout(this);
        dayRow.setPadding(0, dp(12), 0, dp(4));
        for (int i = 0; i < 7; i++) {
            final int bit = 1 << i;
            Button toggle = button("", () -> { });
            toggle.setMinWidth(0); toggle.setMinimumWidth(0); toggle.setTextSize(11);
            toggle.setPadding(dp(4), 0, dp(4), 0);
            final String name = ScheduleText.DAYS[i];
            updateDay(toggle, name, (s.weekdays & bit) != 0);
            toggle.setOnClickListener(v -> {
                s.weekdays ^= bit;
                updateDay(toggle, name, (s.weekdays & bit) != 0);
            });
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(dp(48), dp(52));
            cell.setMarginEnd(dp(4)); dayRow.addView(toggle, cell);
        }
        dayScroll.addView(dayRow); weekdays.addView(dayScroll); form.addView(weekdays);

        biweekly = column();
        RadioGroup mode = new RadioGroup(this);
        strict = new RadioButton(this); strict.setId(View.generateViewId());
        strict.setText(R.string.strict_biweekly); strict.setTextColor(palette.text);
        calendar = new RadioButton(this); calendar.setId(View.generateViewId());
        calendar.setText(R.string.calendar_biweekly); calendar.setTextColor(palette.text);
        mode.addView(strict); mode.addView(calendar); biweekly.addView(mode);
        calendarDays = column();
        String[] names = {"1st", "15th", "Last day of month"};
        for (int i = 0; i < 3; i++) {
            CheckBox check = new CheckBox(this); check.setText(names[i]);
            check.setMinHeight(dp(48)); check.setTextColor(palette.text);
            check.setChecked((s.monthDays & (1 << i)) != 0);
            markers[i] = check; calendarDays.addView(check);
        }
        biweekly.addView(calendarDays); form.addView(biweekly);
        mode.check(s.kind == Schedule.Kind.MONTH_DAYS ? calendar.getId() : strict.getId());
        mode.setOnCheckedChangeListener((group, checked) -> updateSchedule());

        monthFields = column();
        String[] months = new String[12];
        for (int i = 0; i < 12; i++) months[i] = Month.of(i + 1)
                .getDisplayName(TextStyle.FULL, Locale.getDefault());
        month = spinner(months); month.setSelection(s.month - 1); monthFields.addView(month);
        monthFields.addView(label("Day of month"));
        day = new NumberPicker(this);
        day.setMinValue(1); day.setMaxValue(31); day.setValue(s.day); day.setWrapSelectorWheel(false);
        day.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        day.setContentDescription("Day of month");
        monthFields.addView(day, new LinearLayout.LayoutParams(-1, dp(144)));
        monthFields.addView(text("Days beyond a month’s length use its last day.", 13, palette.secondary));
        form.addView(monthFields);

        form.addView(label("Start date"));
        startDate = button(s.start.format(ScheduleText.dateFormat()), () -> {
            LocalDate date = s.start;
            new DatePickerDialog(this, (picker, year, monthIndex, dateOfMonth) -> {
                s.start = LocalDate.of(year, monthIndex + 1, dateOfMonth);
                startDate.setText(s.start.format(ScheduleText.dateFormat()));
            }, date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth()).show();
        });
        form.addView(startDate);

        reminderSwitch = new Switch(this);
        reminderSwitch.setText(R.string.add_reminder); reminderSwitch.setTextSize(17);
        reminderSwitch.setTextColor(palette.text); reminderSwitch.setMinHeight(dp(64));
        reminderSwitch.setChecked(task.hasReminder); form.addView(reminderSwitch);
        reminderFields = column();
        reminderFields.addView(label("Notification text"));
        reminderText = input("Defaults to task name", task.reminder, 1000, true);
        reminderFields.addView(reminderText);
        reminderFields.addView(label("Remind at"));
        time = button(formatTime(), () -> new TimePickerDialog(this, (picker, hour, minute) -> {
            s.hour = hour; s.minute = minute; time.setText(formatTime());
        }, s.hour, s.minute, android.text.format.DateFormat.is24HourFormat(this)).show());
        reminderFields.addView(time);

        followUpSwitch = new Switch(this);
        followUpSwitch.setText(R.string.additional_reminders); followUpSwitch.setTextColor(palette.text);
        followUpSwitch.setMinHeight(dp(72)); followUpSwitch.setChecked(task.followUpEnabled);
        reminderFields.addView(followUpSwitch);
        followUpFields = column();
        followUpFields.addView(label("Wait between reminders"));
        LinearLayout intervalRow = new LinearLayout(this);
        intervalRow.setGravity(Gravity.CENTER_VERTICAL);
        int unit = task.followUpMinutes > 0 && task.followUpMinutes % 1440 == 0 ? 2
                : task.followUpMinutes > 0 && task.followUpMinutes % 60 == 0 ? 1 : 0;
        intervalInput = input("Interval", String.valueOf(task.followUpMinutes / (unit == 2 ? 1440 : unit == 1 ? 60 : 1)), 6, false);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        intervalInput.setContentDescription("Wait between reminders");
        intervalRow.addView(intervalInput, new LinearLayout.LayoutParams(0, -2, 1));
        intervalUnit = spinner(new String[]{"Minutes", "Hours", "Days"});
        intervalUnit.setSelection(unit); intervalUnit.setContentDescription("Reminder interval unit");
        intervalRow.addView(intervalUnit, new LinearLayout.LayoutParams(0, -2, 1));
        followUpFields.addView(intervalRow);
        followUpFields.addView(text("Repeat until Completed or Ignore. Android may delay reminders while idle.",
                13, palette.secondary));
        followUpFields.setVisibility(task.followUpEnabled ? View.VISIBLE : View.GONE);
        reminderFields.addView(followUpFields);
        followUpSwitch.setOnCheckedChangeListener((button, checked) ->
                followUpFields.setVisibility(checked ? View.VISIBLE : View.GONE));
        ignoredSwitch = new CheckBox(this);
        ignoredSwitch.setText(R.string.ignore_reminders); ignoredSwitch.setTextColor(palette.text);
        ignoredSwitch.setMinHeight(dp(64)); ignoredSwitch.setChecked(task.ignored);
        reminderFields.addView(ignoredSwitch);
        form.addView(reminderFields);
        reminderFields.setVisibility(task.hasReminder ? View.VISIBLE : View.GONE);
        reminderSwitch.setOnCheckedChangeListener((button, checked) ->
                reminderFields.setVisibility(checked ? View.VISIBLE : View.GONE));

        enabledSwitch = new Switch(this);
        enabledSwitch.setText(R.string.task_enabled); enabledSwitch.setTextColor(palette.text);
        enabledSwitch.setMinHeight(dp(56)); enabledSwitch.setChecked(task.enabled);
        if (task.id != 0) form.addView(enabledSwitch);

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(dp(24), dp(12), dp(24), dp(16));
        if (task.id != 0) {
            Button delete = button("Delete", this::confirmDelete);
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, dp(52), 1);
            space.setMarginEnd(dp(12)); actions.addView(delete, space);
        }
        actions.addView(button("Save task", this::save), new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(actions);

        repeat.setSelection(indexOf(s.kind));
        repeat.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateSchedule();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        month.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                s.month = position + 1; updateDayLimit();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        updateSchedule(); install(root);
        root.setFocusableInTouchMode(true); root.requestFocus();
    }

    private EditText input(String hint, String value, int limit, boolean multiLine) {
        EditText input = new EditText(this);
        input.setHint(hint); input.setText(value); input.setTextColor(palette.text);
        input.setHintTextColor(palette.secondary); input.setTextSize(17);
        input.setMinHeight(dp(52)); input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | (multiLine ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        input.setSingleLine(!multiLine); input.setGravity(Gravity.TOP | Gravity.START);
        input.setPadding(dp(12), dp(14), dp(12), dp(14));
        input.setBackground(background(palette.surface, 12, true));
        return input;
    }

    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, recycled, parent);
                v.setTextColor(palette.text); v.setTextSize(16); return v;
            }
            @Override public View getDropDownView(int position, View recycled, ViewGroup parent) {
                TextView v = (TextView) super.getDropDownView(position, recycled, parent);
                v.setTextColor(palette.text); v.setBackgroundColor(palette.surface);
                v.setMinHeight(dp(48)); v.setGravity(Gravity.CENTER_VERTICAL);
                return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter); spinner.setMinimumHeight(dp(52));
        return spinner;
    }

    private void updateDay(Button toggle, String name, boolean selected) {
        toggle.setText(selected ? name + "\n✓" : name);
        toggle.setSelected(selected);
        toggle.setTextColor(selected ? palette.highlightText : palette.text);
        toggle.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        toggle.setContentDescription(name + (selected ? ", selected" : ", not selected"));
        toggle.setBackground(background(selected ? palette.highlight : palette.surface, 10, true));
    }

    private int indexOf(Schedule.Kind kind) {
        return switch (kind) {
            case EVERY_DAY -> 0; case SELECTED_DAYS -> 1; case WEEKLY -> 2;
            case BIWEEKLY, MONTH_DAYS -> 3; case MONTHLY -> 4; case YEARLY -> 5;
        };
    }

    private void updateSchedule() {
        int index = repeat.getSelectedItemPosition();
        task.schedule.kind = switch (index) {
            case 1 -> Schedule.Kind.SELECTED_DAYS; case 2 -> Schedule.Kind.WEEKLY;
            case 3 -> calendar.isChecked() ? Schedule.Kind.MONTH_DAYS : Schedule.Kind.BIWEEKLY;
            case 4 -> Schedule.Kind.MONTHLY; case 5 -> Schedule.Kind.YEARLY;
            default -> Schedule.Kind.EVERY_DAY;
        };
        weekdays.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        biweekly.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
        calendarDays.setVisibility(calendar.isChecked() ? View.VISIBLE : View.GONE);
        monthFields.setVisibility(index >= 4 ? View.VISIBLE : View.GONE);
        month.setVisibility(index == 5 ? View.VISIBLE : View.GONE);
        updateDayLimit();
    }

    private void updateDayLimit() {
        int max = task.schedule.kind == Schedule.Kind.YEARLY ? Month.of(task.schedule.month).maxLength() : 31;
        day.setMaxValue(max);
    }

    private String formatTime() {
        return LocalTime.of(task.schedule.hour, task.schedule.minute).format(DateTimeFormatter.ofPattern(
                android.text.format.DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm a"));
    }

    private void capture() {
        task.title = title.getText().toString().trim();
        task.reminder = reminderText.getText().toString().trim();
        task.hasReminder = reminderSwitch.isChecked(); task.enabled = enabledSwitch.isChecked();
        task.followUpEnabled = followUpSwitch.isChecked();
        task.followUpMinutes = intervalMinutes(); task.ignored = ignoredSwitch.isChecked();
        task.schedule.day = day.getValue(); task.schedule.month = month.getSelectedItemPosition() + 1;
        task.schedule.monthDays = 0;
        for (int i = 0; i < 3; i++) if (markers[i].isChecked()) task.schedule.monthDays |= 1 << i;
    }

    private int intervalMinutes() {
        try {
            long value = Long.parseLong(intervalInput.getText().toString().trim());
            value *= intervalUnit.getSelectedItemPosition() == 2 ? 1440 : intervalUnit.getSelectedItemPosition() == 1 ? 60 : 1;
            return value >= 1 && value <= TaskState.MAX_FOLLOW_UP_MINUTES ? (int) value : 0;
        } catch (NumberFormatException e) { return 0; }
    }

    private void save() {
        capture();
        if (task.title.isEmpty()) { title.setError("Enter a task name"); title.requestFocus(); return; }
        if (task.followUpEnabled && task.followUpMinutes == 0) {
            reminderSwitch.setChecked(true);
            intervalInput.setError("Enter an interval from 1 minute to 365 days");
            intervalInput.requestFocus(); return;
        }
        try {
            task.validate();
            if (task.hasReminder && task.reminder.isEmpty()) task.reminder = task.title;
            Reminders.reconcile(this, false);
            TaskStore store = new TaskStore(this);
            List<Task> tasks = store.load();
            Task current;
            if (task.id == 0) {
                current = new Task(); current.id = store.nextId(tasks); tasks.add(current);
            } else {
                current = tasks.stream().filter(t -> t.id == task.id).findFirst()
                        .orElseThrow(() -> new IllegalStateException("This task was deleted while it was being edited"));
            }
            Instant now = Instant.now();
            current.configureFrom(task, now, ZoneId.systemDefault());
            current.title = task.title; current.reminder = task.reminder;
            // Preserve a notification action received while this form was open.
            if (task.ignored != originalIgnored) current.setIgnored(task.ignored, now);
            store.save(tasks);
            Reminders.clear(this, current.id); Reminders.reconcile(this, false); finish();
        } catch (IllegalArgumentException e) {
            new AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton("OK", null).show();
        } catch (RuntimeException e) { error(e); }
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this).setTitle("Delete task?").setMessage(task.title)
                .setNegativeButton("Cancel", null).setPositiveButton("Delete", (dialog, which) -> {
                    try {
                        TaskStore store = new TaskStore(this); List<Task> tasks = store.load();
                        tasks.removeIf(t -> t.id == task.id); store.save(tasks);
                        Reminders.clear(this, task.id); Reminders.reconcile(this, false); finish();
                    } catch (RuntimeException e) { error(e); }
                }).show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (title == null) return;
        capture();
        state.putBoolean("originalIgnored", originalIgnored);
        try { state.putString("draft", task.toJson().toString()); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }
}
