package style.spacedragon.repeatremind;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.format.DateTimeFormatter;
import java.util.function.IntConsumer;

abstract class BaseActivity extends Activity {
    Palette palette;

    @Override protected void onCreate(Bundle state) {
        TaskStore store = new TaskStore(this);
        palette = new Palette(store.theme(), store.customColours());
        setTheme(palette.theme);
        super.onCreate(state);
        getWindow().setStatusBarColor(palette.background);
        getWindow().setNavigationBarColor(Build.VERSION.SDK_INT >= 27 ? palette.background : android.graphics.Color.BLACK);
        if (palette.mode == 3) {
            getWindow().getDecorView().setSystemUiVisibility(ColourMath.isLight(palette.background)
                    ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        }
    }

    int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    void install(View root) {
        root.setBackgroundColor(palette.background);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
    }

    TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        return view;
    }

    TextView label(String value) {
        TextView view = text(value, 14, palette.secondary);
        view.setPadding(0, dp(20), 0, dp(8));
        return view;
    }

    RippleDrawable background(int color, int radius, boolean stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(radius));
        if (stroke) shape.setStroke(dp(1), palette.border);
        return new RippleDrawable(ColorStateList.valueOf(0x28777777), shape, null);
    }

    Button button(String value, Runnable click) {
        Button button = new Button(this);
        button.setText(value); button.setTextSize(14); button.setAllCaps(false);
        button.setTextColor(palette.buttonText); button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setBackground(background(palette.button, 14, false));
        button.setPadding(dp(14), dp(8), dp(14), dp(8));
        button.setOnClickListener(v -> click.run());
        return button;
    }

    /** A rounded surface card; rows reuse it through ListView recycling. */
    LinearLayout card(int vertical) {
        LinearLayout card = column();
        card.setPadding(dp(18), dp(vertical), dp(18), dp(vertical));
        card.setBackground(background(palette.surface, 16, false));
        return card;
    }

    String timePattern() {
        return android.text.format.DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm a";
    }

    DateTimeFormatter timeFormat() { return DateTimeFormatter.ofPattern(timePattern()); }

    /** A single-line row of weekday toggles; bits use Monday=1 through Sunday=64. */
    HorizontalScrollView weekdayToggles(int bits, IntConsumer changed) {
        int[] selected = {bits};
        HorizontalScrollView dayScroll = new HorizontalScrollView(this);
        LinearLayout dayRow = new LinearLayout(this);
        dayRow.setPadding(0, dp(12), 0, dp(4));
        for (int i = 0; i < 7; i++) {
            final int bit = 1 << i;
            Button toggle = button("", () -> { });
            toggle.setMinWidth(0); toggle.setMinimumWidth(0); toggle.setTextSize(11);
            toggle.setPadding(dp(4), 0, dp(4), 0);
            toggle.setSingleLine(true); toggle.setGravity(Gravity.CENTER);
            toggle.setIncludeFontPadding(false);
            final String name = ScheduleText.DAYS[i];
            updateDay(toggle, name, (selected[0] & bit) != 0);
            toggle.setOnClickListener(v -> {
                selected[0] ^= bit;
                updateDay(toggle, name, (selected[0] & bit) != 0);
                changed.accept(selected[0]);
            });
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(dp(48), dp(52));
            cell.setMarginEnd(dp(4)); dayRow.addView(toggle, cell);
        }
        dayScroll.addView(dayRow);
        return dayScroll;
    }

    private void updateDay(Button toggle, String name, boolean selected) {
        toggle.setText(name);
        toggle.setSelected(selected);
        toggle.setTextColor(selected ? palette.highlightText : palette.text);
        toggle.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        toggle.setContentDescription(name + (selected ? ", selected" : ", not selected"));
        toggle.setBackground(background(selected ? palette.highlight : palette.surface, 10, true));
    }

    /** Text input styled to match the rest of the forms. */
    EditText input(String hint, String value, int limit, boolean multiLine) {
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

    CheckBox checkBox(int text, boolean checked) {
        CheckBox check = new CheckBox(this);
        check.setText(text); check.setTextColor(palette.text);
        check.setMinHeight(dp(56)); check.setChecked(checked);
        return check;
    }

    LinearLayout header(String title, View trailing) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(24), dp(12), dp(20), dp(12));
        TextView heading = text(title, 24, palette.text);
        heading.setTypeface(null, Typeface.BOLD);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (trailing != null) header.addView(trailing);
        return header;
    }

    void error(RuntimeException error) {
        new AlertDialog.Builder(this).setTitle("Could not update reminders")
                .setMessage(error.getMessage()).setPositiveButton("OK", null).show();
    }
}
