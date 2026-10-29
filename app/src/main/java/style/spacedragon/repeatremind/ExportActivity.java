package style.spacedragon.repeatremind;

import android.app.DatePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Chooses what to export, then writes a CSV wherever the user picks with the system file picker. */
public final class ExportActivity extends BaseActivity {
    private static final int SAVE_REQUEST = 1;
    private static final int THIS_MONTH = 0, ALL_DAYS = 1, CHOOSE = 2;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM uuuu");
    private WorkLog log;
    private YearMonth month;
    private boolean periods;
    private int range = THIS_MONTH, job = WorkLog.ALL_JOBS;
    private LocalDate from, to;
    private Button fromButton, toButton;
    private LinearLayout chosenDates;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            log = new WorkLogStore(this).load();
            String shown = getIntent().getStringExtra("month");
            month = shown == null ? YearMonth.now() : YearMonth.parse(shown);
            from = month.atDay(1);
            to = month.atEndOfMonth();
            if (state != null) {
                periods = state.getBoolean("periods");
                range = state.getInt("range", THIS_MONTH);
                job = state.getInt("job", WorkLog.ALL_JOBS);
                from = LocalDate.parse(state.getString("from", from.toString()));
                to = LocalDate.parse(state.getString("to", to.toString()));
            }
            render();
        } catch (RuntimeException e) {
            failOpen("Work hours could not be read for export.");
        }
    }

    private void render() {
        LinearLayout root = column();
        root.addView(header("Export CSV", button("Cancel", this::finish)));
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = column();
        form.setPadding(dp(24), 0, dp(24), dp(24));
        scroll.addView(form);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        form.addView(label("Format"));
        form.addView(choices(new String[]{"Daily summary · one row per day and job",
                "Every period · one row per work, productive or break period"}, periods ? 1 : 0,
                which -> periods = which == 1));

        form.addView(label("Dates"));
        form.addView(choices(new String[]{month.format(MONTH), "All recorded days", "Choose dates"}, range, which -> {
            range = which;
            chosenDates.setVisibility(which == CHOOSE ? View.VISIBLE : View.GONE);
        }));
        chosenDates = new LinearLayout(this);
        fromButton = button("", () -> pickDate(true));
        toButton = button("", () -> pickDate(false));
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, -2, 1);
        first.setMarginEnd(dp(8));
        chosenDates.addView(fromButton, first);
        chosenDates.addView(toButton, new LinearLayout.LayoutParams(0, -2, 1));
        chosenDates.setVisibility(range == CHOOSE ? View.VISIBLE : View.GONE);
        form.addView(chosenDates);
        showDates();

        if (log.jobs.size() > 1) {
            form.addView(label("Job"));
            List<Integer> ids = new ArrayList<>(log.jobs.keySet());
            String[] names = new String[ids.size() + 1];
            names[0] = "All jobs";
            for (int i = 0; i < ids.size(); i++) names[i + 1] = log.jobName(ids.get(i));
            form.addView(choices(names, Math.max(0, ids.indexOf(job) + 1),
                    which -> job = which == 0 ? WorkLog.ALL_JOBS : ids.get(which - 1)));
        }

        form.addView(label("Recommended import settings"));
        form.addView(text("""
                • Character set / encoding: UTF-8 (Unicode)
                • Separator: comma; text delimiter: double quote ("); first row is the header
                • Dates are YYYY-MM-DD and times YYYY-MM-DD HH:MM (24-hour); set those columns to Date or \
                Date-time if asked
                • Hours use a dot for decimals (7.50). Where commas are the decimal mark, import with an \
                English (US) locale or set the decimal separator to "."
                • Google Sheets: File → Import → Upload, separator "Comma". Excel: Data → From Text/CSV. \
                LibreOffice: open the file and check the options above""", 14, palette.secondary));

        root.addView(actions(null, null, "Save CSV", this::chooseFile));
        install(root);
    }

    /** A vertical list of radio buttons; the change callback gets the chosen index. */
    private RadioGroup choices(String[] labels, int selected, java.util.function.IntConsumer changed) {
        RadioGroup group = new RadioGroup(this);
        List<RadioButton> buttons = new ArrayList<>();
        for (String text : labels) {
            RadioButton button = radio(text);
            buttons.add(button);
            group.addView(button);
        }
        group.check(buttons.get(Math.min(selected, buttons.size() - 1)).getId());
        group.setOnCheckedChangeListener((g, id) -> {
            for (int i = 0; i < buttons.size(); i++) if (buttons.get(i).getId() == id) changed.accept(i);
        });
        return group;
    }

    private void showDates() {
        fromButton.setText("From " + from.format(ScheduleText.dateFormat()));
        toButton.setText("To " + to.format(ScheduleText.dateFormat()));
    }

    private void pickDate(boolean start) {
        LocalDate current = start ? from : to;
        new DatePickerDialog(this, (picker, year, monthIndex, day) -> {
            LocalDate picked = LocalDate.of(year, monthIndex + 1, day);
            if (start) from = picked; else to = picked;
            showDates();
        }, current.getYear(), current.getMonthValue() - 1, current.getDayOfMonth()).show();
    }

    private LocalDate[] chosenRange() {
        return switch (range) {
            case ALL_DAYS -> log.days.isEmpty() ? null : new LocalDate[]{log.days.firstKey(), log.days.lastKey()};
            case CHOOSE -> new LocalDate[]{from, to};
            default -> new LocalDate[]{month.atDay(1), month.atEndOfMonth()};
        };
    }

    private void chooseFile() {
        LocalDate[] dates = chosenRange();
        if (dates == null) { message("No work recorded yet"); return; }
        if (dates[1].isBefore(dates[0])) { message("The end date is before the start date"); return; }
        String name = "RepeatRemind " + (periods ? "periods " : "daily ") + dates[0] + " to " + dates[1]
                + (job == WorkLog.ALL_JOBS ? "" : " " + log.jobName(job).replaceAll("[\\\\/:*?\"<>|]", "_")) + ".csv";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/csv").putExtra(Intent.EXTRA_TITLE, name);
        try { startActivityForResult(intent, SAVE_REQUEST); }
        catch (ActivityNotFoundException e) { message("No app is available to save files"); }
    }

    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (request != SAVE_REQUEST || result != RESULT_OK || intent == null || intent.getData() == null) return;
        write(intent.getData());
    }

    /** UTF-8 with a byte-order mark, so spreadsheets detect the encoding. */
    private void write(Uri uri) {
        LocalDate[] dates = chosenRange();
        long now = System.currentTimeMillis() / 1000;
        ZoneId zone = ZoneId.systemDefault();
        String csv = periods ? WorkCsv.periods(log, dates[0], dates[1], job, now, zone)
                : WorkCsv.daily(log, dates[0], dates[1], job, now, zone);
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IllegalStateException("The file could not be opened");
            out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            out.write(csv.getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "CSV saved", Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception e) {
            message("The CSV could not be saved: " + e.getMessage());
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("periods", periods);
        state.putInt("range", range);
        state.putInt("job", job);
        state.putString("from", from.toString());
        state.putString("to", to.toString());
    }
}
