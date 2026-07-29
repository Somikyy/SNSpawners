package ru.snplugins.snspawners.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Доступ к {@code gui.yml} и общим текстам-подстановкам. */
public final class GuiConfig {

    private final FileConfiguration yml;

    public final String enabledText;
    public final String disabledText;
    public final String noneText;
    private final String barFilled;
    private final String barEmpty;
    private final int barLength;

    public GuiConfig(FileConfiguration yml) {
        this.yml = yml;
        this.enabledText = yml.getString("placeholders.enabled", "вкл");
        this.disabledText = yml.getString("placeholders.disabled", "выкл");
        this.noneText = yml.getString("placeholders.none", "нет");
        this.barFilled = yml.getString("placeholders.bar-filled", "|");
        this.barEmpty = yml.getString("placeholders.bar-empty", "|");
        this.barLength = Math.max(1, Math.min(64, yml.getInt("placeholders.bar-length", 20)));
    }

    public @Nullable ConfigurationSection section(String path) {
        return yml.getConfigurationSection(path);
    }

    public String string(String path, String fallback) {
        return yml.getString(path, fallback);
    }

    public int integer(String path, int fallback) {
        return yml.getInt(path, fallback);
    }

    public List<Integer> integers(String path) {
        return yml.getIntegerList(path);
    }

    public List<String> strings(String path) {
        return yml.getStringList(path);
    }

    /** Полоска заполнения склада: {@code %storage_bar%}. */
    public String bar(long value, long max) {
        int filled = max <= 0L ? 0 : (int) Math.min(barLength, value * barLength / max);
        StringBuilder out = new StringBuilder(barLength * (barFilled.length() + 1));
        for (int i = 0; i < barLength; i++) {
            out.append(i < filled ? barFilled : barEmpty);
        }
        return out.toString();
    }
}
