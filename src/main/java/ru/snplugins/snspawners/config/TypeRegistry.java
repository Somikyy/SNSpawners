package ru.snplugins.snspawners.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Реестр типов спавнеров и уровней улучшений из {@code spawners.yml}. */
public final class TypeRegistry {

    private final Map<EntityType, SpawnerType> byEntity = new EnumMap<>(EntityType.class);
    private final Map<String, SpawnerType> byKey = new LinkedHashMap<>();
    private final List<UpgradeLevel> upgrades = new ArrayList<>();

    /** Уровни по номеру: индекс — это {@code level - byLevelFrom}. */
    private final UpgradeLevel[] byLevel;
    private final int byLevelFrom;

    public TypeRegistry(FileConfiguration yml, Logger logger) {
        ConfigurationSection upgradeSection = yml.getConfigurationSection("upgrades");
        if (upgradeSection != null) {
            List<Integer> levels = new ArrayList<>();
            for (String raw : upgradeSection.getKeys(false)) {
                try {
                    levels.add(Integer.parseInt(raw));
                } catch (NumberFormatException ignored) {
                    logger.warning("upgrades." + raw + " — номер уровня должен быть числом.");
                }
            }
            levels.sort(Integer::compareTo);
            for (int level : levels) {
                ConfigurationSection section = upgradeSection.getConfigurationSection(String.valueOf(level));
                if (section != null) {
                    upgrades.add(UpgradeLevel.of(level, section));
                }
            }
        }
        if (upgrades.isEmpty()) {
            logger.warning("В spawners.yml не найдено ни одного уровня улучшений — использую значение по умолчанию.");
            upgrades.add(new UpgradeLevel(1, 0.0d, 1.0d, 20_000L, 0.0d, org.bukkit.Material.IRON_NUGGET));
        }

        this.byLevelFrom = upgrades.get(0).level();
        this.byLevel = new UpgradeLevel[upgrades.get(upgrades.size() - 1).level() - byLevelFrom + 1];
        for (UpgradeLevel upgrade : upgrades) {
            byLevel[upgrade.level() - byLevelFrom] = upgrade;
        }

        ConfigurationSection types = yml.getConfigurationSection("types");
        if (types == null) {
            logger.severe("В spawners.yml нет секции 'types' — плагин не сможет работать.");
            return;
        }

        for (String key : types.getKeys(false)) {
            ConfigurationSection section = types.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            SpawnerType type = SpawnerType.read(key, section);
            if (type == null) {
                logger.warning("Тип '" + key + "' пропущен: неизвестный моб, пустой лут или enabled: false.");
                continue;
            }
            byEntity.put(type.entityType, type);
            byKey.put(type.key, type);
        }
    }

    public @Nullable SpawnerType byEntity(@Nullable EntityType entityType) {
        return entityType == null ? null : byEntity.get(entityType);
    }

    public @Nullable SpawnerType byKey(@Nullable String key) {
        return key == null ? null : byKey.get(key.toUpperCase(Locale.ROOT));
    }

    public List<String> keys() {
        return List.copyOf(byKey.keySet());
    }

    public int size() {
        return byKey.size();
    }

    public int maxLevel() {
        return upgrades.get(upgrades.size() - 1).level();
    }

    /**
     * Уровень по номеру; выход за границы зажимается в допустимый диапазон.
     *
     * <p>Обход спрашивает уровень у каждого спавнера каждый тик, поэтому вместо
     * перебора списка стоит таблица, где номер уровня — это индекс.
     */
    public UpgradeLevel level(int level) {
        int index = level - byLevelFrom;
        if (index >= 0 && index < byLevel.length) {
            UpgradeLevel exact = byLevel[index];
            if (exact != null) {
                return exact;
            }
        }
        return level < upgrades.get(0).level() ? upgrades.get(0) : upgrades.get(upgrades.size() - 1);
    }

    public List<UpgradeLevel> levels() {
        return List.copyOf(upgrades);
    }
}
