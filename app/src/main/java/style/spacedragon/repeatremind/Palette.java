package style.spacedragon.repeatremind;

import android.graphics.Color;

final class Palette {
    final int background, surface, text, secondary, button, buttonText, highlight, highlightText, border;
    final int theme;

    Palette(int mode) {
        theme = switch (mode) {
            case 1 -> R.style.AppTheme_Dark;
            case 2 -> R.style.AppTheme_Mono;
            default -> R.style.AppTheme_Light;
        };
        String[] colors = switch (mode) {
            case 1 -> new String[] {"#15171B", "#22262C", "#F5F6F8", "#BCC4CF",
                    "#173C64", "#FFFFFF", "#AC531E", "#FFFFFF", "#57616F"};
            case 2 -> new String[] {"#FFFFFF", "#F0F0F0", "#111111", "#505050",
                    "#333333", "#FFFFFF", "#525252", "#FFFFFF", "#707070"};
            default -> new String[] {"#FFFFFF", "#F3F7FA", "#172C3D", "#4C6170",
                    "#A9D9FA", "#13344E", "#BCE3D6", "#183E32", "#748A99"};
        };
        background = Color.parseColor(colors[0]); surface = Color.parseColor(colors[1]);
        text = Color.parseColor(colors[2]); secondary = Color.parseColor(colors[3]);
        button = Color.parseColor(colors[4]); buttonText = Color.parseColor(colors[5]);
        highlight = Color.parseColor(colors[6]); highlightText = Color.parseColor(colors[7]);
        border = Color.parseColor(colors[8]);
    }
}
