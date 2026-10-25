package style.spacedragon.repeatremind;

public final class ColourMathTest {
    public static void main(String[] args) {
        int checks = 0;
        if (ColourMath.parse("#a9D9fA") != 0xffa9d9fa || ColourMath.parse("000000") != 0xff000000) {
            throw new AssertionError("Hex colour parsing");
        }
        checks++;
        if (!ColourMath.isLight(0xffffffff) || ColourMath.isLight(0xff000000)) throw new AssertionError("isLight endpoints");
        if (ColourMath.blend(0xff000000, 0xffffffff, .5) != 0xff808080) throw new AssertionError("Blend midpoint");
        checks += 2;
        for (String invalid : new String[]{"", "#ABC", "#12345678", "#xyzxyz", "12345", "-12345"}) {
            try { ColourMath.parse(invalid); throw new AssertionError("Invalid colour accepted: " + invalid); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        for (int r = 0; r <= 255; r += 17) for (int g = 0; g <= 255; g += 17) for (int b = 0; b <= 255; b += 17) {
            int background = 0xff000000 | r << 16 | g << 8 | b;
            int text = ColourMath.readableText(background);
            double l = ColourMath.luminance(background);
            double contrast = text == 0xff000000 ? (l + .05) / .05 : 1.05 / (l + .05);
            if (contrast < 4.5) throw new AssertionError("Unreadable button or highlight text");
            if (ColourMath.isLight(background) != (text == 0xff000000)) throw new AssertionError("isLight matches dark text");
            if (ColourMath.blend(background, text, 0) != background || ColourMath.blend(background, text, 1) != text) {
                throw new AssertionError("Blend endpoints");
            }
            checks += 2;
        }
        System.out.println("PASS: " + checks + " colour assertions");
    }
}
