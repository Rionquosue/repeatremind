package style.spacedragon.repeatremind;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

public final class MainActivity extends BaseActivity {
    private int selectedTab;
    private boolean resumed;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = () -> {
        if (!resumed) return;
        try { Reminders.reconcile(this, false); render(); }
        catch (RuntimeException e) { error(e); }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener taskChanges = (prefs, key) -> {
        if ("tasks".equals(key) && resumed) {
            refreshHandler.removeCallbacks(refresh);
            refreshHandler.post(refresh);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) selectedTab = state.getInt("tab", 0);
        openNotification(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); openNotification(intent);
    }

    private void openNotification(Intent intent) {
        int id = intent.getIntExtra("task_id", 0);
        if (id != 0) {
            intent.removeExtra("task_id");
            edit(id);
        }
    }

    private void edit(int id) {
        startActivity(new Intent(this, TaskEditorActivity.class).putExtra("task_id", id));
    }

    @Override protected void onResume() {
        super.onResume();
        TaskStore store = new TaskStore(this);
        Palette current = new Palette(store.theme(), store.customColours());
        if (palette.mode != current.mode || palette.background != current.background || palette.button != current.button
                || palette.highlight != current.highlight || palette.text != current.text) { recreate(); return; }
        resumed = true;
        getSharedPreferences(TaskStore.PREFERENCES, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(taskChanges);
        refresh.run();
    }

    @Override protected void onPause() {
        resumed = false;
        refreshHandler.removeCallbacks(refresh);
        getSharedPreferences(TaskStore.PREFERENCES, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(taskChanges);
        super.onPause();
    }

    private void render() {
        List<Task> tasks = new TaskStore(this).load();
        LinearLayout root = column();
        root.addView(header("RepeatRemind", button("Theme", this::chooseTheme)));
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(20), 0, dp(20), dp(8));
        for (int i = 0; i < 2; i++) {
            final int tab = i;
            Button tabButton = button(i == 0 ? "Tasks" : "Missed days", () -> { selectedTab = tab; render(); });
            boolean selected = selectedTab == i;
            tabButton.setSelected(selected);
            tabButton.setBackground(background(selected ? palette.highlight : palette.surface, 12, true));
            tabButton.setTextColor(selected ? palette.highlightText : palette.text);
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1);
            if (i == 0) cell.setMarginEnd(dp(8));
            tabs.addView(tabButton, cell);
        }
        root.addView(tabs);
        boolean reminders = tasks.stream().anyMatch(Task::remindersActive);
        boolean notifications = !Reminders.allowed(this);
        if (selectedTab == 0 && reminders && (notifications || !Reminders.precise(this))) {
            LinearLayout notice = column();
            notice.setPadding(dp(24), dp(4), dp(24), dp(12));
            TextView message = text(notifications ? "Notifications are off."
                    : "Allow precise reminders to deliver at your chosen time.", 14, palette.secondary);
            message.setPadding(0, 0, 0, dp(8)); notice.addView(message);
            notice.addView(button(notifications ? "Enable notifications" : "Allow precise reminders",
                    notifications ? this::requestNotifications : this::requestPrecise));
            root.addView(notice);
        }
        FrameLayout body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        if (selectedTab == 1) {
            renderMissed(body, tasks);
        } else if (tasks.isEmpty()) {
            body.addView(new EmptyArrow(), new FrameLayout.LayoutParams(-1, -1));
        } else {
            tasks.sort(Comparator.comparingLong(Task::nextAlarm));
            // Rows handle their own clicks, so the list needs no item listener.
            cardList(body, 112).setAdapter(new TaskAdapter(tasks));
        }
        if (selectedTab == 0) {
        Button add = button(tasks.isEmpty() ? "+  Add repeating task" : "+",
                () -> startActivity(new Intent(this, TaskEditorActivity.class)));
        add.setContentDescription("Add repeating task");
        add.setTextSize(tasks.isEmpty() ? 15 : 32);
        add.setElevation(dp(3));
        add.setBackground(background(palette.button, tasks.isEmpty() ? 20 : 32, false));
        FrameLayout.LayoutParams floating = new FrameLayout.LayoutParams(tasks.isEmpty() ? -2 : dp(64), dp(64),
                Gravity.BOTTOM | Gravity.END);
        floating.setMargins(dp(24), dp(24), dp(24), dp(24));
        body.addView(add, floating);
        }
        install(root);
        // Refresh calendar state only while visible, including tasks with no notification.
        refreshHandler.removeCallbacks(refresh);
        long next = tasks.stream().mapToLong(t -> t.nextDue).filter(t -> t > 0).min().orElse(Long.MAX_VALUE);
        if (!tasks.isEmpty()) next = Math.min(next, LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli());
        if (resumed && next != Long.MAX_VALUE) {
            refreshHandler.postDelayed(refresh, Math.max(1000, next - System.currentTimeMillis() + 100));
        }
    }

    /** Recycles native rows so a long task list does not allocate a view per saved task. */
    private final class TaskAdapter extends BaseAdapter {
        private final List<Task> tasks;
        TaskAdapter(List<Task> tasks) { this.tasks = tasks; }
        @Override public int getCount() { return tasks.size(); }
        @Override public Task getItem(int position) { return tasks.get(position); }
        @Override public long getItemId(int position) { return tasks.get(position).id; }
        @Override public boolean hasStableIds() { return true; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout card;
            if (recycled == null) {
                card = card(18);
                TextView title = text("", 19, palette.text);
                title.setTypeface(null, Typeface.BOLD); card.addView(title);
                for (int i = 0; i < 2; i++) {
                    TextView line = text("", 14, palette.secondary);
                    line.setPadding(0, dp(7), 0, 0); card.addView(line);
                }
                LinearLayout controls = new LinearLayout(MainActivity.this);
                controls.setGravity(Gravity.CENTER_VERTICAL);
                controls.setPadding(0, dp(8), 0, 0);
                Button complete = button(getString(R.string.completed), () -> { });
                LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, -2, 1);
                space.setMarginEnd(dp(12)); controls.addView(complete, space);
                controls.addView(button("", () -> { }));
                card.addView(controls);
            } else card = (LinearLayout) recycled;
            Task task = getItem(position);
            TextView title = (TextView) card.getChildAt(0);
            title.setText(task.title);
            title.setPaintFlags(task.completed ? title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                    : title.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            ((TextView) card.getChildAt(1)).setText(ScheduleText.describe(task.schedule));
            boolean followUp = task.followUpAt > 0 && task.followUpAt < task.nextDue;
            String detail = task.ignored ? "Reminders ignored" : task.completed ? "Completed · " + task.occurrenceDate
                    : !task.enabled ? "Paused" : !task.hasReminder ? "No reminder"
                    : (followUp ? "Follow-up · " : "Next · ")
                    + Instant.ofEpochMilli(followUp ? task.followUpAt : task.nextDue).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("EEE, MMM d · " + timePattern()));
            ((TextView) card.getChildAt(2)).setText(detail);
            LinearLayout controls = (LinearLayout) card.getChildAt(3);
            Button complete = (Button) controls.getChildAt(0);
            complete.setSelected(task.completed);
            complete.setBackground(background(task.completed ? palette.highlight : palette.button, 14, false));
            complete.setTextColor(task.completed ? palette.highlightText : palette.buttonText);
            complete.setContentDescription((task.completed ? "Completed, tap to undo: " : "Mark completed: ") + task.title);
            complete.setOnClickListener(view -> {
                try { Reminders.setCompleted(MainActivity.this, task.id, task.occurrenceId, !task.completed); }
                catch (RuntimeException e) { error(e); }
            });
            Button ignore = (Button) controls.getChildAt(1);
            ignore.setVisibility(task.hasReminder ? View.VISIBLE : View.GONE);
            ignore.setText(task.ignored ? "Resume" : getString(R.string.ignore));
            ignore.setContentDescription((task.ignored ? "Resume reminders: " : "Ignore reminders: ") + task.title);
            ignore.setOnClickListener(view -> {
                try { Reminders.setIgnored(MainActivity.this, task.id, -1, !task.ignored); }
                catch (RuntimeException e) { error(e); }
            });
            card.setOnClickListener(view -> edit(task.id));
            return card;
        }
    }

    private static final class MissedDay {
        final Task task;
        final LocalDate date;
        MissedDay(Task task, LocalDate date) { this.task = task; this.date = date; }
    }

    private void renderMissed(FrameLayout body, List<Task> tasks) {
        List<MissedDay> missed = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (Task task : tasks) {
            for (Map.Entry<LocalDate, Boolean> entry : task.history.headMap(today, false).entrySet()) {
                if (!entry.getValue()) missed.add(new MissedDay(task, entry.getKey()));
            }
        }
        missed.sort(Comparator.comparing((MissedDay day) -> day.date).reversed()
                .thenComparing(day -> day.task.title));
        if (missed.isEmpty()) {
            TextView empty = text("No missed days", 17, palette.secondary);
            empty.setGravity(Gravity.CENTER); body.addView(empty, new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        ListView list = cardList(body, 20);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return missed.size(); }
            @Override public Object getItem(int position) { return missed.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                LinearLayout card;
                if (recycled == null) {
                    card = card(16);
                    card.addView(text("", 18, palette.text));
                    TextView date = text("", 14, palette.secondary); date.setPadding(0, dp(7), 0, 0); card.addView(date);
                } else card = (LinearLayout) recycled;
                MissedDay day = missed.get(position);
                ((TextView) card.getChildAt(0)).setText(day.task.title);
                ((TextView) card.getChildAt(1)).setText(day.date.format(DateTimeFormatter.ofPattern("EEE, MMM d, uuuu")));
                return card;
            }
        });
        list.setOnItemClickListener((parent, view, position, id) -> edit(missed.get(position).task.id));
    }

    private ListView cardList(FrameLayout body, int bottomPadding) {
        ListView list = new ListView(this);
        list.setClipToPadding(false);
        list.setPadding(dp(20), dp(8), dp(20), dp(bottomPadding));
        list.setDivider(new ColorDrawable(Color.TRANSPARENT)); list.setDividerHeight(dp(12));
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        return list;
    }

    private void chooseTheme() {
        new AlertDialog.Builder(this).setTitle("Colour theme")
                .setSingleChoiceItems(new String[]{"Light", "Dark", "Colourblind (grayscale)", "Custom"},
                        new TaskStore(this).theme(), (dialog, which) -> {
                            dialog.dismiss();
                            if (which == 3) startActivity(new Intent(this, CustomThemeActivity.class));
                            else { new TaskStore(this).theme(which); recreate(); }
                        }).setNegativeButton("Cancel", null).show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putInt("tab", selectedTab);
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
                && (!getPreferences(MODE_PRIVATE).getBoolean("asked", false)
                || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("asked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }

    private void requestPrecise() {
        if (Build.VERSION.SDK_INT >= 31) {
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private final class EmptyArrow extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        EmptyArrow() { super(MainActivity.this); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setColor(palette.secondary); paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2)); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            float x = rtl ? dp(100) : getWidth() - dp(100);
            float y = getHeight() - dp(105);
            path.reset();
            path.moveTo(x + (rtl ? dp(70) : -dp(70)), y - dp(100));
            path.quadTo(x, y - dp(94), x, y);
            path.moveTo(x - dp(12), y - dp(16)); path.lineTo(x, y); path.lineTo(x + dp(12), y - dp(16));
            canvas.drawPath(path, paint);
        }
    }
}
