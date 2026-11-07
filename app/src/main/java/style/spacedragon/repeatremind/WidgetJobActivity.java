package style.spacedragon.repeatremind;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.os.Bundle;
import java.util.List;

/**
 * The work widget's job list: a dialog over the home screen, with no window of its own, that sets which job
 * the widget's buttons act on. Picking one saves it and redraws the widget; nothing else changes.
 */
public final class WidgetJobActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WorkLogStore store = new WorkLogStore(this);
        WorkLog log;
        try { log = store.load(); }
        catch (IllegalStateException e) { finish(); return; }
        Workdays work;
        try { work = new TaskStore(this).workdays(); } catch (IllegalStateException e) { work = null; }
        int shown = WorkWidget.job(store, log, work);
        List<Integer> jobs = log.activeJobs();
        String[] names = new String[jobs.size()];
        for (int i = 0; i < names.length; i++) names[i] = log.jobName(jobs.get(i)) + " · " + WorkWidget.state(log, jobs.get(i));
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        new AlertDialog.Builder(this, night ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Job on the widget")
                .setSingleChoiceItems(names, jobs.indexOf(shown), (dialog, which) -> {
                    try { store.widgetJob(jobs.get(which)); }
                    catch (IllegalStateException ignored) { /* The widget keeps its job. */ }
                    WorkControls.refresh(this);
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(dialog -> finish())
                .show();
    }
}
