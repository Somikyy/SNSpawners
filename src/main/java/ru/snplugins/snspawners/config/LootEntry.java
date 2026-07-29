package ru.snplugins.snspawners.config;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;
import ru.snplugins.snspawners.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Одна строка таблицы лута.
 *
 * <p>Эталонный {@link ItemStack} собирается один раз при загрузке конфига.
 * Выдача предмета — это {@code clone()} эталона, а не пересборка меты:
 * при выгрузке склада на 200 000 предметов разница принципиальная.
 */
public final class LootEntry {

    public final String id;

    /**
     * Порядковый номер записи в таблице лута своего типа.
     *
     * <p>Виртуальный склад хранится массивом {@code long[]} с этим индексом
     * вместо карты «строка → количество»: ни хэширования, ни упаковки в
     * {@link Long}, ни узлов карты на каждый спавнер.
     */
    public final int index;

    public final Material material;
    public final int minAmount;
    public final int maxAmount;
    public final double chance;
    public final double price;
    public final boolean affectedByFortune;

    private final ItemStack prototype;
    private final int stackSize;

    private LootEntry(String id, int index, Material material, int minAmount, int maxAmount,
                      double chance, double price, boolean affectedByFortune, ItemStack prototype) {
        this.id = id;
        this.index = index;
        this.material = material;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.chance = chance;
        this.price = price;
        this.affectedByFortune = affectedByFortune;
        this.prototype = prototype;
        this.stackSize = Math.max(1, material.getMaxStackSize());
    }

    public static @Nullable LootEntry read(String id, int index, ConfigurationSection section, String typeKey) {
        String materialName = section.getString("item", "");
        Material material = Material.matchMaterial(materialName);
        if (material == null || material.isAir()) {
            return null;
        }

        List<Integer> amount = section.getIntegerList("amount");
        int min = amount.isEmpty() ? section.getInt("amount", 1) : amount.get(0);
        int max = amount.size() > 1 ? amount.get(1) : min;
        if (max < min) {
            int swap = min;
            min = max;
            max = swap;
        }
        min = Math.max(0, min);

        double chance = section.getDouble("chance", 1.0d);
        if (chance <= 0.0d) {
            return null;
        }

        ItemStack prototype = buildPrototype(section, material);

        return new LootEntry(
                typeKey + '.' + id,
                index,
                material,
                min,
                Math.max(max, 1),
                Math.min(chance, 1.0d),
                Math.max(0.0d, section.getDouble("price", 0.0d)),
                section.getBoolean("affected-by-fortune", true),
                prototype);
    }

    private static ItemStack buildPrototype(ConfigurationSection section, Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        String name = section.getString("name");
        if (name != null && !name.isBlank()) {
            meta.displayName(Text.parse(name).decoration(TextDecoration.ITALIC, false));
        }

        List<String> lore = section.getStringList("lore");
        if (!lore.isEmpty()) {
            List<Component> rendered = new ArrayList<>(lore.size());
            for (String line : lore) {
                rendered.add(Text.parse(line).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(rendered);
        }

        int modelData = section.getInt("model-data", 0);
        if (modelData > 0) {
            meta.setCustomModelData(modelData);
        }

        for (String raw : section.getStringList("enchants")) {
            String[] parts = raw.split(":");
            NamespacedKey key = NamespacedKey.fromString(parts[0].trim().toLowerCase(Locale.ROOT));
            if (key == null) {
                continue;
            }
            // Начиная с 1.21 зачарования живут в реестре мира, а не в статике.
            Enchantment enchantment = RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.ENCHANTMENT).get(key);
            if (enchantment == null) {
                continue;
            }
            int level = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 1;
            meta.addEnchant(enchantment, level, true);
        }

        item.setItemMeta(meta);
        return item;
    }

    /** Копия эталона с заданным количеством (не больше размера стопки). */
    public ItemStack createStack(int amount) {
        ItemStack copy = prototype.clone();
        copy.setAmount(Math.max(1, Math.min(amount, stackSize)));
        return copy;
    }

    public int maxStackSize() {
        return stackSize;
    }

    /** Отображаемое имя для GUI — кастомное, если задано, иначе материал. */
    public Component displayName() {
        ItemMeta meta = prototype.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Component name = meta.displayName();
            if (name != null) {
                return name;
            }
        }
        return Component.translatable(material.translationKey())
                .decoration(TextDecoration.ITALIC, false);
    }

    /** Среднее количество за одно выпадение — для оценки выхода в час. */
    public double averageAmount() {
        return (minAmount + maxAmount) / 2.0d;
    }
}
