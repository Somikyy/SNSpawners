package ru.snplugins.snspawners.config;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;
import ru.snplugins.snspawners.util.Replacer;
import ru.snplugins.snspawners.util.SoundSpec;
import ru.snplugins.snspawners.util.Text;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Одно сообщение из {@code messages.yml} со своим способом доставки.
 *
 * <p>Каждый ключ настраивается независимо: канал (чат / actionbar / титры),
 * префикс, звук, тайминги титров. Сообщения без плейсхолдеров разбираются
 * один раз и дальше отдаются из кэша — в чате они летят пачками.
 */
public final class Message {

    public enum Type {
        CHAT,
        ACTIONBAR,
        TITLE,
        NONE
    }

    private final Type type;
    private final boolean usePrefix;
    private final String prefix;
    private final List<String> lines;
    private final @Nullable String subtitle;
    private final Title.Times times;
    private final @Nullable SoundSpec sound;

    private final boolean cacheable;
    private @Nullable List<Component> cachedLines;
    private @Nullable Component cachedSubtitle;

    private Message(Type type, boolean usePrefix, String prefix, List<String> lines,
                    @Nullable String subtitle, Title.Times times, @Nullable SoundSpec sound) {
        this.type = type;
        this.usePrefix = usePrefix;
        this.prefix = prefix;
        this.lines = lines;
        this.subtitle = subtitle;
        this.times = times;
        this.sound = sound;
        this.cacheable = lines.stream().noneMatch(line -> line.indexOf('%') >= 0)
                && (subtitle == null || subtitle.indexOf('%') < 0);
    }

    /**
     * Читает как полную секцию, так и короткую форму «ключ: строка».
     *
     * @param holder секция-родитель, {@code key} — имя записи внутри неё
     */
    public static Message read(ConfigurationSection holder, String key, String prefix) {
        Object raw = holder.get(key);

        if (raw instanceof ConfigurationSection section) {
            return fromSection(section, prefix);
        }

        List<String> lines = raw instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of(String.valueOf(raw));

        return new Message(Type.CHAT, true, prefix, lines, null, defaultTimes(), null);
    }

    private static Message fromSection(ConfigurationSection section, String prefix) {
        Type type = parseType(section.getString("type"));

        List<String> lines;
        if (section.isList("text")) {
            lines = new ArrayList<>(section.getStringList("text"));
        } else {
            String single = section.getString("text", "");
            lines = single.isEmpty() ? List.of() : List.of(single);
        }

        Title.Times times = Title.Times.times(
                ticks(section.getInt("fade-in", 10)),
                ticks(section.getInt("stay", 40)),
                ticks(section.getInt("fade-out", 10)));

        return new Message(
                type,
                section.getBoolean("prefix", true),
                prefix,
                lines,
                section.getString("subtitle"),
                times,
                SoundSpec.parse(section.getString("sound")));
    }

    public boolean isSilent() {
        return type == Type.NONE || lines.isEmpty();
    }

    public void send(Audience audience, @Nullable Replacer replacer) {
        if (isSilent()) {
            playSound(audience);
            return;
        }

        List<Component> rendered = render(replacer);

        switch (type) {
            case CHAT -> {
                for (Component line : rendered) {
                    audience.sendMessage(line);
                }
            }
            case ACTIONBAR -> audience.sendActionBar(rendered.get(0));
            case TITLE -> {
                Component sub = renderSubtitle(replacer);
                audience.showTitle(Title.title(rendered.get(0), sub, times));
            }
            case NONE -> {
                // Недостижимо: отсечено isSilent().
            }
        }

        playSound(audience);
    }

    /** Готовые компоненты — нужны там, где текст встраивается в другой вывод. */
    public List<Component> render(@Nullable Replacer replacer) {
        if (cacheable) {
            if (cachedLines == null) {
                cachedLines = build(null);
            }
            return cachedLines;
        }
        return build(replacer);
    }

    private List<Component> build(@Nullable Replacer replacer) {
        List<Component> out = new ArrayList<>(lines.size());
        boolean first = true;
        for (String line : lines) {
            // Префикс ставится только перед первой строкой: в многострочных
            // сообщениях повтор на каждой строке выглядит как спам.
            String text = usePrefix && first && type == Type.CHAT ? prefix + line : line;
            out.add(Text.parse(text, replacer));
            first = false;
        }
        return List.copyOf(out);
    }

    private Component renderSubtitle(@Nullable Replacer replacer) {
        if (subtitle == null) {
            return Component.empty();
        }
        if (cacheable) {
            if (cachedSubtitle == null) {
                cachedSubtitle = Text.parse(subtitle);
            }
            return cachedSubtitle;
        }
        return Text.parse(subtitle, replacer);
    }

    private void playSound(Audience audience) {
        if (sound != null && audience instanceof org.bukkit.entity.Player player) {
            sound.play(player);
        }
    }

    private static Type parseType(@Nullable String raw) {
        if (raw == null) {
            return Type.CHAT;
        }
        try {
            return Type.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Type.CHAT;
        }
    }

    private static Title.Times defaultTimes() {
        return Title.Times.times(ticks(10), ticks(40), ticks(10));
    }

    private static Duration ticks(int ticks) {
        return Duration.ofMillis(Math.max(0, ticks) * 50L);
    }
}
