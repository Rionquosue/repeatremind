package style.spacedragon.repeatremind;

/** Opaque sRGB colour helpers, independent of Android. */
final class ColourMath {
    private ColourMath() { }

    static int parse(String hex) {
        String value = hex.startsWith("#") ? hex.substring(1) : hex;
        if (!value.matches("[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Use six hex digits, for example #A9D9FA");
        return 0xff000000 | Integer.parseInt(value, 16);
    }

    static double luminance(int colour) {
        return linear((colour >> 16) & 255) * .2126 + linear((colour >> 8) & 255) * .7152
                + linear(colour & 255) * .0722;
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4);
    }

    static int readableText(int background) {
        double l = luminance(background);
        return (l + .05) / .05 >= 1.05 / (l + .05) ? 0xff000000 : 0xffffffff;
    }

    static boolean isLight(int background) { return readableText(background) == 0xff000000; }

    static int blend(int a, int b, double fraction) {
        int r = (int) Math.round(((a >> 16) & 255) * (1 - fraction) + ((b >> 16) & 255) * fraction);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - fraction) + ((b >> 8) & 255) * fraction);
        int blue = (int) Math.round((a & 255) * (1 - fraction) + (b & 255) * fraction);
        return 0xff000000 | r << 16 | g << 8 | blue;
    }
}
