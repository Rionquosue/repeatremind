package style.spacedragon.repeatremind;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Locale;

/** Native RGB controls and hex fields; no colour-picker dependency. */
public final class CustomThemeActivity extends BaseActivity {
    private static final String[] LABELS = {"Background colour", "Button colour", "Highlight colour", "Text colour"};
    private final EditText[] fields = new EditText[4];
    private final Button[] swatches = new Button[4];
    private LinearLayout preview;
    private TextView sample;
    private int[] saved;
    private Button sampleButton, sampleHighlight;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        saved = new TaskStore(this).customColours();
        LinearLayout root = column();
        root.addView(header("Custom theme", button("Cancel", this::finish)));
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = column(); form.setPadding(dp(24), 0, dp(24), dp(24));
        scroll.addView(form); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        for (int i = 0; i < 4; i++) {
            final int index = i;
            form.addView(label(LABELS[i]));
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
            EditText field = new EditText(this);
            field.setSingleLine(true); field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
            field.setTextColor(palette.text); field.setHintTextColor(palette.secondary);
            field.setHint("#RRGGBB"); field.setContentDescription(LABELS[i]);
            field.setMinHeight(dp(52)); fields[i] = field;
            field.setText(state == null ? hex(saved[i]) : state.getString("colour" + i, hex(saved[i])));
            row.addView(field, new LinearLayout.LayoutParams(0, -2, 1));
            Button swatch = button("Pick", () -> pick(index)); swatches[i] = swatch;
            swatch.setContentDescription("Pick " + LABELS[i].toLowerCase(Locale.ROOT));
            row.addView(swatch, new LinearLayout.LayoutParams(dp(80), dp(52))); form.addView(row);
            onTextChanged(field, this::updatePreview);
        }
        form.addView(label("Preview"));
        preview = column(); preview.setPadding(dp(20), dp(20), dp(20), dp(20));
        sample = text("RepeatRemind", 20, palette.text); preview.addView(sample);
        sampleButton = button("Button", () -> { }); preview.addView(sampleButton);
        sampleHighlight = button("Highlight", () -> { }); preview.addView(sampleHighlight);
        form.addView(preview);
        LinearLayout actions = column(); actions.setPadding(dp(24), dp(12), dp(24), dp(16));
        actions.addView(button("Save theme", this::save)); root.addView(actions);
        install(root); updatePreview();
    }

    private void updatePreview() {
        if (preview == null) return;
        try {
            int[] colours = colours();
            Palette custom = new Palette(3, colours);
            preview.setBackground(background(custom.background, 14, true)); sample.setTextColor(custom.text);
            sampleButton.setBackground(background(custom.button, 12, false)); sampleButton.setTextColor(custom.buttonText);
            sampleHighlight.setBackground(background(custom.highlight, 12, false)); sampleHighlight.setTextColor(custom.highlightText);
            for (int i = 0; i < 4; i++) {
                swatches[i].setBackground(background(colours[i], 12, true));
                swatches[i].setTextColor(ColourMath.readableText(colours[i]));
            }
        } catch (IllegalArgumentException ignored) { /* Keep the last valid preview while typing. */ }
    }

    private int[] colours() {
        int[] values = new int[4];
        for (int i = 0; i < 4; i++) values[i] = ColourMath.parse(fields[i].getText().toString().trim());
        return values;
    }

    private void save() {
        if (isFinishing()) return; // A second queued tap.
        int[] values = new int[4];
        for (int i = 0; i < 4; i++) {
            try { values[i] = ColourMath.parse(fields[i].getText().toString().trim()); }
            catch (IllegalArgumentException e) { fields[i].setError(e.getMessage()); fields[i].requestFocus(); return; }
        }
        new TaskStore(this).customColours(values); finish();
    }

    private void pick(int index) {
        int colour;
        try { colour = ColourMath.parse(fields[index].getText().toString().trim()); }
        catch (IllegalArgumentException e) { colour = saved[index]; }
        int[] rgb = {(colour >> 16) & 255, (colour >> 8) & 255, colour & 255};
        LinearLayout controls = column(); controls.setPadding(dp(24), dp(12), dp(24), dp(12));
        TextView value = text(hex(colour), 18, palette.text); value.setGravity(Gravity.CENTER);
        value.setBackgroundColor(colour); value.setTextColor(ColourMath.readableText(colour));
        controls.addView(value);
        String[] channels = {"Red", "Green", "Blue"};
        for (int i = 0; i < 3; i++) {
            final int channel = i;
            controls.addView(label(channels[i]));
            SeekBar slider = new SeekBar(this); slider.setMax(255); slider.setProgress(rgb[i]);
            slider.setContentDescription(channels[i]); slider.setMinimumHeight(dp(48)); controls.addView(slider);
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                    rgb[channel] = progress;
                    int selected = rgb(rgb);
                    value.setText(hex(selected)); value.setBackgroundColor(selected);
                    value.setTextColor(ColourMath.readableText(selected));
                }
                @Override public void onStartTrackingTouch(SeekBar bar) { }
                @Override public void onStopTrackingTouch(SeekBar bar) { }
            });
        }
        new AlertDialog.Builder(this).setTitle(LABELS[index]).setView(controls)
                .setNegativeButton("Cancel", null).setPositiveButton("Use colour", (dialog, which) ->
                        fields[index].setText(hex(rgb(rgb)))).show();
    }

    private static int rgb(int[] channels) { return 0xff000000 | channels[0] << 16 | channels[1] << 8 | channels[2]; }

    private String hex(int colour) { return String.format(Locale.ROOT, "#%06X", colour & 0xffffff); }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        for (int i = 0; i < 4; i++) state.putString("colour" + i, fields[i].getText().toString());
    }
}
