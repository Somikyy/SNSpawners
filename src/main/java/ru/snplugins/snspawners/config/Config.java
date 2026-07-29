package ru.snplugins.snspawners.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.Nullable;
import ru.snplugins.snspawners.util.SoundSpec;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Неизменяемый снимок {@code config.yml}.
 *
 * <p>Всё читается один раз при загрузке и складывается в {@code final}-поля:
 * в горячих путях (обход спавнеров, расчёт лута) обращений к
 * {@link FileConfiguration} быть не должно — это разбор строк и поиск по картам
 * на каждый вызов. Перезагрузка создаёт новый экземпляр целиком.
 */
public final class Config {

    /** Что делать, когда виртуальный склад заполнен. */
    public enum FullBehaviour {
        /** Производство встаёт. */
        STOP,
        /** Излишки сгорают. */
        VOID,
        /** Новый лут вытесняет самый дешёвый предмет. */
        REPLACE
    }

    public final boolean updateCheck;
    public final boolean debug;

    public final int sweepInterval;
    public final int maxPerTick;
    public final int saveInterval;
    public final int maxSavesPerTick;
    public final int exactRollThreshold;
    public final long maxOfflineMillis;

    public final int maxStack;
    public final int mergeRadius;
    public final boolean mergeSameOwnerOnly;
    public final double breakChance;
    public final boolean requireSilkTouch;
    public final boolean shiftBreaksWholeStack;
    public final boolean adoptNaturalSpawners;

    public final boolean ownerOnly;
    public final boolean explosionProof;
    public final boolean pistonProof;
    public final Set<String> blacklistedWorlds;

    public final FullBehaviour fullBehaviour;
    public final boolean autoFlushEnabled;
    /** Пауза между выгрузками в контейнер. */
    public final long flushIntervalMillis;
    /** Пауза после того, как контейнер оказался забит. */
    public final long flushRetryMillis;
    public final int maxLinkDistance;
    public final int maxLinkDistanceSquared;
    public final Set<Material> allowedContainers;
    public final boolean autoSellEnabled;
    public final double taxMultiplier;
    public final long notifyCooldownMillis;

    public final boolean hologramsEnabled;
    public final double hologramOffsetY;
    public final float hologramViewRange;
    public final int hologramUpdateInterval;
    public final List<String> hologramLines;

    public final String moneyFormat;
    public final boolean compactNumbers;

    private final Map<String, SoundSpec> sounds;

    public Config(FileConfiguration yml) {
        this.updateCheck = yml.getBoolean("general.update-check", true);
        this.debug = yml.getBoolean("general.debug", false);

        this.sweepInterval = Math.max(1, yml.getInt("performance.sweep-interval", 1));
        this.maxPerTick = Math.max(1, yml.getInt("performance.max-per-tick", 200));
        this.saveInterval = Math.max(100, yml.getInt("performance.save-interval", 600));
        this.maxSavesPerTick = Math.max(1, yml.getInt("performance.max-saves-per-tick", 100));
        this.exactRollThreshold = Math.max(0, yml.getInt("performance.exact-roll-threshold", 64));
        this.maxOfflineMillis = TimeUnit.HOURS.toMillis(
                Math.max(0, yml.getInt("performance.max-offline-hours", 24)));

        this.maxStack = Math.max(1, yml.getInt("stacking.max-stack", 2048));
        this.mergeRadius = Math.max(0, Math.min(16, yml.getInt("stacking.merge-radius", 4)));
        this.mergeSameOwnerOnly = yml.getBoolean("stacking.merge-same-owner-only", false);
        this.breakChance = clamp01(yml.getDouble("stacking.break-chance", 1.0d));
        this.requireSilkTouch = yml.getBoolean("stacking.require-silk-touch", false);
        this.shiftBreaksWholeStack = yml.getBoolean("stacking.shift-breaks-whole-stack", true);
        this.adoptNaturalSpawners = yml.getBoolean("stacking.adopt-natural-spawners", true);

        this.ownerOnly = yml.getBoolean("protection.owner-only", true);
        this.explosionProof = yml.getBoolean("protection.explosion-proof", true);
        this.pistonProof = yml.getBoolean("protection.piston-proof", true);
        this.blacklistedWorlds = new HashSet<>(yml.getStringList("protection.blacklisted-worlds"));

        this.fullBehaviour = parseEnum(yml.getString("storage.full-behaviour"), FullBehaviour.STOP);
        this.autoFlushEnabled = yml.getBoolean("storage.auto-flush.enabled", true);
        this.flushIntervalMillis = Math.max(1, yml.getInt("storage.auto-flush.interval", 20)) * 50L;
        this.flushRetryMillis = Math.max(1, yml.getInt("storage.auto-flush.retry-when-full", 100)) * 50L;
        this.maxLinkDistance = Math.max(1, yml.getInt("storage.auto-flush.max-link-distance", 8));
        this.maxLinkDistanceSquared = maxLinkDistance * maxLinkDistance;
        this.allowedContainers = readContainers(yml.getStringList("storage.auto-flush.allowed-containers"));
        this.autoSellEnabled = yml.getBoolean("storage.auto-sell.enabled", true);
        this.taxMultiplier = 1.0d - clamp01(yml.getDouble("storage.auto-sell.tax-percent", 0.0d) / 100.0d);
        this.notifyCooldownMillis = Math.max(0, yml.getInt("storage.auto-sell.notify-cooldown", 30)) * 1000L;

        this.hologramsEnabled = yml.getBoolean("holograms.enabled", true);
        this.hologramOffsetY = yml.getDouble("holograms.offset-y", 1.1d);
        // Display-сущности меряют дальность в единицах по 64 блока, а в конфиге
        // она задана в блоках — иначе администратору пришлось бы считать доли.
        this.hologramViewRange =
                (float) Math.max(1.0d, yml.getDouble("holograms.view-range", 24.0d)) / 64.0f;
        this.hologramUpdateInterval = Math.max(20, yml.getInt("holograms.update-interval", 40));
        this.hologramLines = List.copyOf(yml.getStringList("holograms.lines"));

        this.moneyFormat = yml.getString("economy.money-format", "#,##0.##");
        this.compactNumbers = yml.getBoolean("economy.compact-numbers", true);

        this.sounds = readSounds(yml.getConfigurationSection("sounds"));
    }

    public @Nullable SoundSpec sound(String key) {
        return sounds.get(key);
    }

    /** Человекочитаемый список разрешённых контейнеров — для сообщения об ошибке. */
    public String allowedContainerNames() {
        List<String> names = new ArrayList<>(allowedContainers.size());
        for (Material material : allowedContainers) {
            names.add(material.name());
        }
        names.sort(String::compareTo);
        return String.join(", ", names);
    }

    private static Map<String, SoundSpec> readSounds(@Nullable ConfigurationSection section) {
        if (section == null) {
            return Map.of();
        }
        Map<String, SoundSpec> parsed = new HashMap<>();
        for (String key : section.getKeys(false)) {
            SoundSpec spec = SoundSpec.parse(section.getString(key));
            if (spec != null) {
                parsed.put(key, spec);
            }
        }
        return Map.copyOf(parsed);
    }

    private static Set<Material> readContainers(List<String> raw) {
        EnumSet<Material> materials = EnumSet.noneOf(Material.class);
        for (String name : raw) {
            Material material = Material.matchMaterial(name);
            if (material == null) {
                continue;
            }
            if (material == Material.SHULKER_BOX) {
                // В конфиге «SHULKER_BOX» означает «шалкер любого цвета».
                for (Material candidate : Material.values()) {
                    if (candidate.name().endsWith("SHULKER_BOX")) {
                        materials.add(candidate);
                    }
                }
            } else {
                materials.add(material);
            }
        }
        return materials.isEmpty() ? EnumSet.of(Material.CHEST, Material.BARREL) : materials;
    }

    private static <E extends Enum<E>> E parseEnum(@Nullable String raw, E fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            @SuppressWarnings("unchecked")
            E value = Enum.valueOf((Class<E>) fallback.getClass(), raw.trim().toUpperCase(Locale.ROOT));
            return value;
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static double clamp01(double value) {
        return value < 0.0d ? 0.0d : Math.min(value, 1.0d);
    }
}
