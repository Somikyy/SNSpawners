package ru.snplugins.snspawners.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Форматирование чисел для GUI и сообщений. */
public final class Numbers {

    private Numbers() {
    }

    private static final char[] SUFFIXES = { 'K', 'M', 'B', 'T', 'Q' };

    private static final DecimalFormatSymbols SYMBOLS;

    static {
        SYMBOLS = new DecimalFormatSymbols(Locale.ROOT);
        SYMBOLS.setGroupingSeparator(' ');
        SYMBOLS.setDecimalSeparator('.');
    }

    /** Компактная запись: 1234 → «1.23K», 5_600_000 → «5.6M». */
    public static String compact(double value) {
        double abs = Math.abs(value);
        if (abs < 1000.0d) {
            return trim(value);
        }
        int tier = 0;
        while (abs >= 1000.0d && tier < SUFFIXES.length) {
            abs /= 1000.0d;
            value /= 1000.0d;
            tier++;
        }
        return trim(value) + SUFFIXES[tier - 1];
    }

    /** Разряды через неразрывный пробел: 1234567 → «1 234 567». */
    public static String grouped(long value) {
        return newFormat("#,##0").format(value);
    }

    public static String format(double value, String pattern, boolean compact) {
        if (compact) {
            return compact(value);
        }
        return newFormat(pattern).format(value);
    }

    /**
     * {@link DecimalFormat} не потокобезопасен, а вызовы редкие и дешёвые,
     * поэтому создаём экземпляр на месте вместо {@code ThreadLocal}.
     */
    public static DecimalFormat newFormat(String pattern) {
        DecimalFormat format = new DecimalFormat(pattern, SYMBOLS);
        format.setGroupingUsed(pattern.indexOf(',') >= 0);
        return format;
    }

    private static String trim(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        String text = String.format(Locale.ROOT, "%.2f", value);
        if (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".0")) {
            text = text.substring(0, text.length() - 2);
        }
        return text;
    }

    public static int parseInt(String input, int fallback) {
        try {
            return Integer.parseInt(input.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
