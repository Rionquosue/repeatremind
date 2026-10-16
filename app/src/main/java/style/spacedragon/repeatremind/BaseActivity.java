package style.spacedragon.repeatremind;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

abstract class BaseActivity extends Activity {
    Palette palette;

    @Override protected void onCreate(Bundle state) {
        palette = new Palette(new TaskStore(this).theme());
        setTheme(palette.theme);
        super.onCreate(state);
        getWindow().setStatusBarColor(palette.background);
        getWindow().setNavigationBarColor(Build.VERSION.SDK_INT >= 27 ? palette.background : android.graphics.Color.BLACK);
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
