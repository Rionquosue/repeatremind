package style.spacedragon.repeatremind;

import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Rows for the task widget, read from storage only when the widget asks for them. */
public final class TaskWidgetService extends RemoteViewsService {
    @Override public RemoteViewsFactory onGetViewFactory(Intent intent) { return new Rows(getApplicationContext()); }

    private static final class Rows implements RemoteViewsFactory {
        private final Context context;
        private List<Task> tasks = new ArrayList<>();

        Rows(Context context) { this.context = context; }

        @Override public void onCreate() { }

        @Override public void onDataSetChanged() {
            try {
                List<Task> loaded = new ArrayList<>(new TaskStore(context).load());
                loaded.sort(Comparator.comparingLong(Task::nextAlarm));
                tasks = loaded;
            } catch (RuntimeException e) {
                tasks = new ArrayList<>(); // Unreadable tasks show the empty view; the app reports the error.
            }
        }

        @Override public void onDestroy() { tasks.clear(); }

        @Override public int getCount() { return tasks.size(); }

        @Override public RemoteViews getViewAt(int position) {
            Task task = tasks.get(position);
            RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_task_row);
            row.setTextViewText(R.id.task_title, (task.completed ? "✓ " : "") + task.title);
            row.setTextViewText(R.id.task_detail, detail(task));
            row.setTextViewText(R.id.task_complete, task.completed ? "Undo" : context.getString(R.string.completed));
            row.setInt(R.id.task_complete, "setBackgroundResource",
                    task.completed ? R.drawable.widget_button_on : R.drawable.widget_background);
            row.setOnClickFillInIntent(R.id.task_complete, new Intent(TaskActionReceiver.WIDGET_COMPLETE)
                    .putExtra("task_id", task.id).putExtra("occurrence_id", task.occurrenceId)
                    .putExtra("occurrence_date", task.occurrenceDate.toString()).putExtra("value", !task.completed));
            row.setViewVisibility(R.id.task_ignore, task.hasReminder ? android.view.View.VISIBLE : android.view.View.GONE);
            // Ignore silences this occurrence only; Resume undoes it.
            row.setTextViewText(R.id.task_ignore, task.skipped ? "Resume" : context.getString(R.string.ignore));
            row.setOnClickFillInIntent(R.id.task_ignore, new Intent(TaskActionReceiver.WIDGET_IGNORE)
                    .putExtra("task_id", task.id).putExtra("value", !task.skipped)
                    .putExtra("occurrence_id", task.occurrenceId));
            return row;
        }

        /** The same status line as the app's task list. */
        private String detail(Task task) {
            ZoneId zone = ZoneId.systemDefault();
            boolean followUp = task.followUpAt > 0 && task.followUpAt < task.nextDue;
            if (task.skipped && !task.completed) return "Ignored for " + task.occurrenceDate;
            if (task.completed) return "Completed · " + task.occurrenceDate;
            if (!task.enabled) return task.expired(LocalDate.now()) ? "Ended" : "Paused";
            if (!task.hasReminder) return ScheduleText.describe(task.schedule);
            DateTimeFormatter dayTime = DateTimeFormatter.ofPattern("EEE, MMM d · " + BaseActivity.timePattern(context));
            return (followUp ? "Follow-up · " : "Next · ")
                    + Instant.ofEpochMilli(followUp ? task.followUpAt : task.nextDue).atZone(zone).format(dayTime);
        }

        @Override public RemoteViews getLoadingView() { return null; }

        @Override public int getViewTypeCount() { return 1; }

        @Override public long getItemId(int position) { return tasks.get(position).id; }

        @Override public boolean hasStableIds() { return true; }
    }
}
