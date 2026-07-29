package ru.snplugins.snspawners.util;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Звук из конфига в формате {@code ЗВУК:громкость:высота}.
 *
 * <p>Поиск идёт через {@link Registry}, а не через {@code Sound.valueOf}:
 * начиная с 1.21.3 звуки переехали в реестр, и обращение к реестру —
 * единственный способ, работающий на всей ветке 1.21.x.
 */
public record SoundSpec(Sound sound, float volume, float pitch) {

    /** Возвращает {@code null}, если строка пустая или звук не найден. */
    public static @Nullable SoundSpec parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(":");
        Sound sound = lookup(parts[0].trim());
        if (sound == null) {
            return null;
        }
        float volume = parts.length > 1 ? parseFloat(parts[1], 1.0f) : 1.0f;
        float pitch = parts.length > 2 ? parseFloat(parts[2], 1.0f) : 1.0f;
        return new SoundSpec(sound, volume, pitch);
    }

    private static @Nullable Sound lookup(String name) {
        String raw = name.toLowerCase(Locale.ROOT);
        // BLOCK_BARREL_OPEN → block.barrel.open
        NamespacedKey key = NamespacedKey.fromString(raw.indexOf('.') >= 0 || raw.indexOf(':') >= 0
                ? raw
                : raw.replace('_', '.'));
        if (key == null) {
            return null;
        }
        return Registry.SOUNDS.get(key);
    }

    private static float parseFloat(String raw, float fallback) {
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public void play(Player player) {
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    public void playAt(Location location) {
        if (location.getWorld() != null) {
            location.getWorld().playSound(location, sound, volume, pitch);
        }
    }
}
