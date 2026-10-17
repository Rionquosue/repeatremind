package style.spacedragon.repeatremind;

import android.Manifest;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
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
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

public final class MainActivity extends BaseActivity {
    private int themeMode;
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
        themeMode = new TaskStore(this).theme();
        openNotification(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); openNotification(intent);
    }

    private void openNotification(Intent intent) {
        int id = intent.getIntExtra("task_id", 0);
        if (id != 0) {
            intent.removeExtra("task_id");
            startActivity(new Intent(this, TaskEditorActivity.class).putExtra("task_id", id));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (themeMode != new TaskStore(this).theme()) { recreate(); return; }
        resumed = true;
        getSharedPreferences("repeatremind", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(taskChanges);
        refresh.run();
    }

    @Override protected void onPause() {
        resumed = false;
        refreshHandler.removeCallbacks(refresh);
        getSharedPreferences("repeatremind", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(taskChanges);
        super.onPause();
    }

    private void render() {
        List<Task> tasks = new TaskStore(this).load();
        LinearLayout root = column();
        root.addView(header("RepeatRemind", button("Theme", this::chooseTheme)));
        boolean reminders = tasks.stream().anyMatch(Task::remindersActive);
        if (reminders && (!Reminders.allowed(this) || !Reminders.precise(this))) {
            LinearLayout notice = column();
            notice.setPadding(dp(24), dp(4), dp(24), dp(12));
            boolean notifications = !Reminders.allowed(this);
            TextView message = text(notifications ? "Notifications are off."
                    : "Allow precise reminders to deliver at your chosen time.", 14, palette.secondary);
            message.setPadding(0, 0, 0, dp(8)); notice.addView(message);
            notice.addView(button(notifications ? "Enable notifications" : "Allow precise reminders",
                    notifications ? this::requestNotifications : this::requestPrecise));
            root.addView(notice);
        }
        FrameLayout body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        if (tasks.isEmpty()) {
            body.addView(new EmptyArrow(), new FrameLayout.LayoutParams(-1, -1));
        } else {
            tasks.sort(Comparator.comparingLong(Task::nextAlarm));
            ListView list = new ListView(this);
            list.setClipToPadding(false);
            list.setPadding(dp(20), dp(8), dp(20), dp(112));
            list.setDivider(new ColorDrawable(Color.TRANSPARENT)); list.setDividerHeight(dp(12));
            list.setAdapter(new TaskAdapter(tasks));
            list.setOnItemClickListener((parent, view, position, id) ->
                    startActivity(new Intent(this, TaskEditorActivity.class)
                            .putExtra("task_id", tasks.get(position).id)));
            body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        }
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
        install(root);
        // Refresh calendar state only while visible, including tasks with no notification.
        refreshHandler.removeCallbacks(refresh);
        long next = tasks.stream().mapToLong(t -> t.nextDue).filter(t -> t > 0).min().orElse(Long.MAX_VALUE);
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
                card = column(); card.setPadding(dp(18), dp(18), dp(18), dp(18));
                card.setBackground(background(palette.surface, 16, false));
                TextView title = text("", 19, palette.text);
                title.setTypeface(null, Typeface.BOLD); card.addView(title);
                for (int i = 0; i < 2; i++) {
                    TextView line = text("", 14, palette.secondary);
                    line.setPadding(0, dp(7), 0, 0); card.addView(line);
                }
                LinearLayout controls = new LinearLayout(MainActivity.this);
                controls.setGravity(Gravity.CENTER_VERTICAL);
                controls.setPadding(0, dp(8), 0, 0);
                CheckBox complete = new CheckBox(MainActivity.this);
                complete.setText(R.string.completed); complete.setTextColor(palette.text);
                complete.setButtonTintList(new ColorStateList(
                        new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                        new int[]{palette.highlight, palette.secondary}));
                complete.setMinHeight(dp(48));
                controls.addView(complete, new LinearLayout.LayoutParams(0, -2, 1));
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
                    .format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.is24HourFormat(MainActivity.this)
                            ? "EEE, MMM d · HH:mm" : "EEE, MMM d · h:mm a"));
            ((TextView) card.getChildAt(2)).setText(detail);
            LinearLayout controls = (LinearLayout) card.getChildAt(3);
            CheckBox complete = (CheckBox) controls.getChildAt(0);
            complete.setOnCheckedChangeListener(null);
            complete.setChecked(task.completed);
            complete.setContentDescription("Completed: " + task.title);
            complete.setOnCheckedChangeListener((view, checked) -> {
                try { Reminders.setCompleted(MainActivity.this, task.id, task.occurrenceId, checked); refresh.run(); }
                catch (RuntimeException e) { error(e); }
            });
            Button ignore = (Button) controls.getChildAt(1);
            ignore.setVisibility(task.hasReminder ? View.VISIBLE : View.GONE);
            ignore.setText(task.ignored ? "Resume" : getString(R.string.ignore));
            ignore.setContentDescription((task.ignored ? "Resume reminders: " : "Ignore reminders: ") + task.title);
            ignore.setOnClickListener(view -> {
                try { Reminders.setIgnored(MainActivity.this, task.id, -1, !task.ignored); refresh.run(); }
                catch (RuntimeException e) { error(e); }
            });
            card.setOnClickListener(view -> startActivity(new Intent(MainActivity.this, TaskEditorActivity.class)
                    .putExtra("task_id", task.id)));
            return card;
        }
    }

    private void chooseTheme() {
        new AlertDialog.Builder(this).setTitle("Colour theme")
                .setSingleChoiceItems(new String[]{"Light", "Dark", "Colourblind (grayscale)"},
                        new TaskStore(this).theme(), (dialog, which) -> {
                            new TaskStore(this).theme(which); dialog.dismiss(); recreate();
                        }).setNegativeButton("Cancel", null).show();
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

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        try { Reminders.reconcile(this, false); render(); }
        catch (RuntimeException e) { error(e); }
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
