package ru.snplugins.snspawners.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Настройки одного типа спавнера: скорость, опыт, таблица лута. */
public final class SpawnerType {

    public final String key;
    public final EntityType entityType;
    public final String display;
    public final Material icon;
    /** Базовый период одного цикла в миллисекундах, до множителя уровня. */
    public final long periodMillis;
    public final int perCycle;
    public final int xpMin;
    public final int xpMax;
    public final double price;
    public final List<LootEntry> loot;

    /** Сумма {@code chance × среднее количество} — ожидаемый выход с одного моба. */
    public final double expectedItemsPerMob;

    /** Индекс записей по идентификатору — вместо перебора списка на каждый доступ. */
    private final Map<String, LootEntry> byId;

    /**
     * Индексы записей от самой дешёвой к самой дорогой.
     *
     * <p>Нужен режиму {@code REPLACE}, где переполненный склад вытесняет дешёвый
     * лут. Порядок не меняется во время работы, поэтому считается один раз при
     * загрузке конфига, а не сортировкой копии списка на каждое вытеснение.
     */
    public final int[] cheapestFirst;

    private SpawnerType(String key, EntityType entityType, String display, Material icon,
                        long periodMillis, int perCycle, int xpMin, int xpMax,
                        double price, List<LootEntry> loot) {
        this.key = key;
        this.entityType = entityType;
        this.display = display;
        this.icon = icon;
        this.periodMillis = periodMillis;
        this.perCycle = perCycle;
        this.xpMin = xpMin;
        this.xpMax = xpMax;
        this.price = price;
        this.loot = List.copyOf(loot);

        double expected = 0.0d;
        Map<String, LootEntry> index = new HashMap<>(this.loot.size() * 2);
        for (LootEntry entry : this.loot) {
            expected += entry.chance * entry.averageAmount();
            index.put(entry.id, entry);
        }
        this.expectedItemsPerMob = expected;
        this.byId = Map.copyOf(index);

        List<LootEntry> sorted = new ArrayList<>(this.loot);
        sorted.sort((a, b) -> Double.compare(a.price, b.price));
        this.cheapestFirst = new int[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            this.cheapestFirst[i] = sorted.get(i).index;
        }
    }

    public static @Nullable SpawnerType read(String key, ConfigurationSection section) {
        if (!section.getBoolean("enabled", true)) {
            return null;
        }

        EntityType entityType;
        try {
            entityType = EntityType.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }

        Material icon = Material.matchMaterial(section.getString("icon", "SPAWNER"));
        if (icon == null || icon.isAir()) {
            icon = Material.SPAWNER;
        }

        List<Integer> xp = section.getIntegerList("xp");
        int xpMin = xp.isEmpty() ? 0 : xp.get(0);
        int xpMax = xp.size() > 1 ? xp.get(1) : xpMin;

        List<LootEntry> loot = new ArrayList<>();
        ConfigurationSection lootSection = section.getConfigurationSection("loot");
        if (lootSection != null) {
            for (String lootId : lootSection.getKeys(false)) {
                ConfigurationSection entrySection = lootSection.getConfigurationSection(lootId);
                if (entrySection == null) {
                    continue;
                }
                LootEntry entry = LootEntry.read(lootId, loot.size(), entrySection, key);
                if (entry != null) {
                    loot.add(entry);
                }
            }
        }
        if (loot.isEmpty()) {
            return null;
        }

        return new SpawnerType(
                key.toUpperCase(Locale.ROOT),
                entityType,
                section.getString("display", key),
                icon,
                Math.max(1000L, Math.round(section.getDouble("period", 20.0d) * 1000.0d)),
                Math.max(1, section.getInt("per-cycle", 4)),
                Math.max(0, xpMin),
                Math.max(0, xpMax),
                Math.max(0.0d, section.getDouble("price", 0.0d)),
                loot);
    }

    public @Nullable LootEntry lootById(String id) {
        return byId.get(id);
    }

    /** Индекс записи в таблице лута или {@code -1}, если такой записи нет. */
    public int lootIndex(String id) {
        LootEntry entry = byId.get(id);
        return entry == null ? -1 : entry.index;
    }

    public int lootCount() {
        return loot.size();
    }

    /** Оценка «предметов в час» для стака заданного размера на заданной скорости. */
    public double itemsPerHour(int stack, double speedMultiplier, double fortune) {
        double cyclesPerHour = 3_600_000.0d / (periodMillis / speedMultiplier);
        return cyclesPerHour * stack * perCycle * expectedItemsPerMob * (1.0d + fortune);
    }
}
