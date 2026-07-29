package ru.snplugins.snspawners.config;

import org.bukkit.Material;

/**
 * Уровень улучшения спавнера.
 *
 * @param level    номер, начиная с 1
 * @param cost     цена перехода на этот уровень
 * @param speed    множитель скорости производства
 * @param storage  вместимость виртуального склада в штуках
 * @param fortune  прибавка к количеству лута (0.25 = +25%)
 * @param material иконка в меню улучшений
 */
public record UpgradeLevel(int level, double cost, double speed, long storage,
                           double fortune, Material material) {

    public static UpgradeLevel of(int level, org.bukkit.configuration.ConfigurationSection section) {
        Material material = Material.matchMaterial(section.getString("material", "PAPER"));
        return new UpgradeLevel(
                level,
                Math.max(0.0d, section.getDouble("cost", 0.0d)),
                Math.max(0.01d, section.getDouble("speed", 1.0d)),
                Math.max(1L, section.getLong("storage", 20_000L)),
                Math.max(0.0d, section.getDouble("fortune", 0.0d)),
                material == null ? Material.PAPER : material);
    }
}
