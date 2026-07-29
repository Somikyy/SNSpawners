package ru.snplugins.snspawners.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор цветов в строках конфигурации.
 *
 * <p>Поддерживается одновременно:
 * <ul>
 *   <li>{@code &a}, {@code &l}, {@code &r} — классические коды;</li>
 *   <li>{@code &#RRGGBB}, {@code <#RRGGBB>}, {@code #RRGGBB} — HEX;</li>
 *   <li>{@code </#RRGGBB>} — закрывающий тег, игнорируется (цвет продолжается);</li>
 *   <li>{@code <gradient:#A1B2C3:#D4E5F6[:#...]>текст</gradient>} — градиент
 *       с произвольным числом опорных точек.</li>
 * </ul>
 *
 * <h2>Вложенность</h2>
 * Плейсхолдеры подставляются до разбора, а значение плейсхолдера само может
 * нести цвет — например {@code %type%} равен
 * {@code <gradient:#D9D9D9:#8E8E8E>Скелет</gradient>}. Если такой плейсхолдер
 * стоит внутри другого градиента, получается вложенная разметка.
 *
 * <p>Поэтому градиенты раскрываются <b>изнутри наружу</b>, а уже окрашенные
 * символы внешний градиент не перекрашивает: собственный цвет плейсхолдера
 * важнее оформления шаблона. Явно заданный цвет действует до следующего явного
 * цвета — так же, как в обычной legacy-разметке; {@code &r} возвращает
 * управление рампе градиента. Любые теги, оставшиеся непарными, вырезаются —
 * игрок не должен увидеть в чате разметку ни при какой ошибке в конфиге.
 *
 * <p>Строка сводится к legacy-представлению с {@code §x§R§R§G§G§B§B} и один раз
 * десериализуется Adventure. Форматирование ({@code &l}, {@code &o}) внутри
 * градиента переносится на каждый символ — в legacy цвет сбрасывает стиль.
 */
public final class Text {

    private Text() {
    }

    private static final char S = '§';

    /**
     * Градиент, внутри которого нет другого градиента, — то есть самый
     * глубокий из вложенных. Раскрывая такие по кругу, доходим до внешних.
     */
    private static final Pattern GRADIENT_INNERMOST = Pattern.compile(
            "<gradient:(#[0-9a-fA-F]{6}(?::#[0-9a-fA-F]{6})+)>((?:(?!<gradient:)[\\s\\S])*?)</gradient>");

    /** Непарные теги градиента — вырезаются, чтобы разметка не утекла игроку. */
    private static final Pattern ORPHAN_GRADIENT =
            Pattern.compile("</?gradient(?::#[0-9a-fA-F]{6})*>");

    private static final Pattern CLOSING_HEX = Pattern.compile("</#[0-9a-fA-F]{6}>");

    private static final Pattern HEX = Pattern.compile(
            "<#([0-9a-fA-F]{6})>|[&§]#([0-9a-fA-F]{6})|#([0-9a-fA-F]{6})");

    private static final Pattern AMPERSAND = Pattern.compile("&([0-9a-fk-orA-FK-OR])");

    /** Ограничитель на случай патологической вложенности в конфиге. */
    private static final int MAX_GRADIENT_DEPTH = 8;

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.builder()
            .character(S)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    public static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        return SERIALIZER.deserialize(toLegacy(raw));
    }

    public static Component parse(String raw, @Nullable Replacer replacer) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        return parse(replacer == null ? raw : replacer.apply(raw));
    }

    public static List<Component> parseAll(List<String> raw, @Nullable Replacer replacer) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Component> out = new ArrayList<>(raw.size());
        for (String line : raw) {
            out.add(parse(line, replacer));
        }
        return out;
    }

    /** Сводит все поддерживаемые формы записи цвета к legacy-строке с {@code §}. */
    public static String toLegacy(String raw) {
        String s = CLOSING_HEX.matcher(raw).replaceAll("");
        s = expandGradients(s);
        // Вырезать остатки нужно до разбора HEX: иначе цвета из непарного
        // <gradient:#A:#B> превратятся в коды, а сам тег останется мусором.
        s = ORPHAN_GRADIENT.matcher(s).replaceAll("");
        s = expandHex(s);
        return AMPERSAND.matcher(s).replaceAll(S + "$1");
    }

    /** Убирает всё оформление — для логов, консоли и сравнения строк. */
    public static String strip(String raw) {
        String legacy = toLegacy(raw);
        StringBuilder out = new StringBuilder(legacy.length());
        for (int i = 0; i < legacy.length(); i++) {
            char c = legacy.charAt(i);
            if (c == S && i + 1 < legacy.length()) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    // ── градиенты ────────────────────────────────────────────────────────────

    private static String expandGradients(String input) {
        if (input.indexOf("<gradient:") < 0) {
            return input;
        }

        String current = input;
        for (int depth = 0; depth < MAX_GRADIENT_DEPTH; depth++) {
            Matcher m = GRADIENT_INNERMOST.matcher(current);
            if (!m.find()) {
                break;
            }
            StringBuilder out = new StringBuilder(current.length() + 64);
            int last = 0;
            do {
                out.append(current, last, m.start());
                out.append(gradient(m.group(1), m.group(2)));
                last = m.end();
            } while (m.find());
            out.append(current, last, current.length());

            current = out.toString();
            if (current.indexOf("<gradient:") < 0) {
                break;
            }
        }
        return current;
    }

    private static String gradient(String stops, String content) {
        int[] colors = parseStops(stops);
        int visible = countVisible(content);
        if (visible == 0) {
            return "";
        }

        StringBuilder out = new StringBuilder(content.length() * 14);
        StringBuilder decorations = new StringBuilder(8);

        /* Цвет, заданный внутри содержимого явно. Пока он активен, градиент
           не перекрашивает символы — иначе вложенный %type% потерял бы себя. */
        String override = null;
        int index = 0;

        for (int i = 0; i < content.length(); ) {
            int[] token = colorTokenAt(content, i);
            if (token != null) {
                override = token[1] < 0 ? null : legacyHex(token[1]);
                i += token[0];
                continue;
            }

            char c = content.charAt(i);
            if ((c == '&' || c == S) && i + 1 < content.length()) {
                char code = Character.toLowerCase(content.charAt(i + 1));
                if ("klmno".indexOf(code) >= 0) {
                    decorations.append(S).append(code);
                    i += 2;
                    continue;
                }
                if (code == 'r') {
                    decorations.setLength(0);
                    override = null;
                    i += 2;
                    continue;
                }
                if ("0123456789abcdef".indexOf(code) >= 0) {
                    override = String.valueOf(S) + code;
                    i += 2;
                    continue;
                }
            }

            double t = visible == 1 ? 0.0d : (double) index / (visible - 1);
            out.append(override != null ? override : legacyHex(interpolate(colors, t)));
            out.append(decorations).append(c);
            index++;
            i++;
        }
        return out.toString();
    }

    private static int[] parseStops(String stops) {
        String[] parts = stops.split(":");
        int[] colors = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            colors[i] = Integer.parseInt(parts[i].substring(1), 16);
        }
        return colors;
    }

    private static int countVisible(String content) {
        int visible = 0;
        for (int i = 0; i < content.length(); ) {
            int[] token = colorTokenAt(content, i);
            if (token != null) {
                i += token[0];
                continue;
            }
            char c = content.charAt(i);
            if ((c == '&' || c == S) && i + 1 < content.length()
                    && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(content.charAt(i + 1)) >= 0) {
                i += 2;
                continue;
            }
            visible++;
            i++;
        }
        return visible;
    }

    /**
     * Распознаёт запись цвета в позиции {@code i}.
     *
     * @return {@code {длина, rgb}}, где {@code rgb < 0} — сброс цвета,
     *         либо {@code null}, если в этой позиции цвета нет
     */
    private static int @Nullable [] colorTokenAt(String content, int i) {
        char c = content.charAt(i);

        // §x§R§R§G§G§B§B — результат уже раскрытого вложенного градиента.
        if (c == S && i + 13 < content.length()
                && Character.toLowerCase(content.charAt(i + 1)) == 'x') {
            int rgb = 0;
            for (int part = 0; part < 6; part++) {
                int at = i + 2 + part * 2;
                if (content.charAt(at) != S) {
                    return null;
                }
                int digit = Character.digit(content.charAt(at + 1), 16);
                if (digit < 0) {
                    return null;
                }
                rgb = (rgb << 4) | digit;
            }
            return new int[] { 14, rgb };
        }

        if (c == '<') {
            if (content.startsWith("</#", i) && i + 9 < content.length() && content.charAt(i + 9) == '>') {
                int rgb = hexAt(content, i + 3);
                return rgb < 0 ? null : new int[] { 10, -1 };
            }
            if (content.startsWith("<#", i) && i + 8 < content.length() && content.charAt(i + 8) == '>') {
                int rgb = hexAt(content, i + 2);
                return rgb < 0 ? null : new int[] { 9, rgb };
            }
            return null;
        }

        if ((c == '&' || c == S) && i + 7 < content.length() && content.charAt(i + 1) == '#') {
            int rgb = hexAt(content, i + 2);
            return rgb < 0 ? null : new int[] { 8, rgb };
        }

        if (c == '#') {
            int rgb = hexAt(content, i + 1);
            return rgb < 0 ? null : new int[] { 7, rgb };
        }

        return null;
    }

    /** Читает ровно шесть шестнадцатеричных цифр, иначе {@code -1}. */
    private static int hexAt(String content, int start) {
        if (start + 6 > content.length()) {
            return -1;
        }
        int rgb = 0;
        for (int i = 0; i < 6; i++) {
            int digit = Character.digit(content.charAt(start + i), 16);
            if (digit < 0) {
                return -1;
            }
            rgb = (rgb << 4) | digit;
        }
        return rgb;
    }

    private static int interpolate(int[] colors, double t) {
        int segments = colors.length - 1;
        double scaled = t * segments;
        int index = (int) Math.floor(scaled);
        if (index >= segments) {
            index = segments - 1;
        }
        double local = scaled - index;

        int a = colors[index];
        int b = colors[index + 1];
        int r = (int) Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * local);
        int g = (int) Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * local);
        int bl = (int) Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * local);
        return (r << 16) | (g << 8) | bl;
    }

    // ── одиночный HEX ────────────────────────────────────────────────────────

    private static String expandHex(String input) {
        if (input.indexOf('#') < 0) {
            return input;
        }
        Matcher m = HEX.matcher(input);
        if (!m.find()) {
            return input;
        }
        StringBuilder out = new StringBuilder(input.length() + 32);
        int last = 0;
        do {
            out.append(input, last, m.start());
            String hex = m.group(1) != null ? m.group(1)
                    : m.group(2) != null ? m.group(2)
                    : m.group(3);
            appendLegacyHex(out, Integer.parseInt(hex, 16));
            last = m.end();
        } while (m.find());
        out.append(input, last, input.length());
        return out.toString();
    }

    private static String legacyHex(int rgb) {
        StringBuilder out = new StringBuilder(14);
        appendLegacyHex(out, rgb);
        return out.toString();
    }

    private static void appendLegacyHex(StringBuilder out, int rgb) {
        out.append(S).append('x');
        for (int shift = 20; shift >= 0; shift -= 4) {
            out.append(S).append(Character.forDigit((rgb >> shift) & 0xF, 16));
        }
    }
}
