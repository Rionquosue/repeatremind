package style.spacedragon.repeatremind;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.RemoteViews;

/**
 * A resizable home screen list of the repeating tasks, each with Completed and Ignore. It has no update period:
 * the list is redrawn after each reminder pass, which also runs at midnight while a task widget exists.
 */
public final class TaskWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) manager.updateAppWidget(id, views(context, id));
    }

    private static RemoteViews views(Context context, int id) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_tasks);
        Intent service = new Intent(context, TaskWidgetService.class)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        service.setData(Uri.parse(service.toUri(Intent.URI_INTENT_SCHEME))); // One adapter per widget.
        views.setRemoteAdapter(R.id.widget_list, service);
        views.setEmptyView(R.id.widget_list, R.id.widget_empty);
        // Rows fill in the action and task; on Android 12+ a fill-in needs a mutable template.
        PendingIntent template = PendingIntent.getBroadcast(context, 0, new Intent(context, TaskActionReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
        views.setPendingIntentTemplate(R.id.widget_list, template);
        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class)
                .putExtra("room", MainActivity.REMINDERS), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_title, open);
        return views;
    }

    static boolean present(Context context) { return ids(context).length > 0; }

    private static int[] ids(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        return manager == null ? new int[0] : manager.getAppWidgetIds(new ComponentName(context, TaskWidget.class));
    }

    /** Asks placed task widgets to reload their rows; nothing happens when none is placed. */
    static void refresh(Context context) {
        int[] ids = ids(context);
        if (ids.length > 0) AppWidgetManager.getInstance(context).notifyAppWidgetViewDataChanged(ids, R.id.widget_list);
    }
}
