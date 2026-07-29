package ru.snplugins.snspawners.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Сборка предмета GUI из секции конфига. */
public final class ItemBuilder {

    private ItemBuilder() {
    }

    /**
     * @param fallback материал, если в секции он не указан (иконка типа спавнера)
     * @return {@code null}, если предмет отключён через {@code enabled: false}
     */
    public static @Nullable ItemStack fromSection(@Nullable ConfigurationSection section,
                                                  @Nullable Replacer replacer,
                                                  @Nullable Material fallback) {
        if (section == null || !section.getBoolean("enabled", true)) {
            return null;
        }

        Material material = Material.matchMaterial(section.getString("material", ""));
        if (material == null || material.isAir()) {
            material = fallback;
        }
        if (material == null || material.isAir()) {
            return null;
        }

        ItemStack item = new ItemStack(material, Math.max(1, section.getInt("amount", 1)));
        apply(item, section, replacer);
        return item;
    }

    /** Накладывает имя, описание и флаги поверх готового предмета. */
    public static void apply(ItemStack item, ConfigurationSection section, @Nullable Replacer replacer) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        String name = section.getString("name");
        if (name != null) {
            meta.displayName(clean(Text.parse(name, replacer)));
        }

        List<String> lore = section.getStringList("lore");
        if (!lore.isEmpty()) {
            meta.lore(cleanAll(lore, replacer));
        }

        int modelData = section.getInt("model-data", 0);
        if (modelData > 0) {
            meta.setCustomModelData(modelData);
        }

        if (section.getBoolean("glow", false)) {
            meta.setEnchantmentGlintOverride(true);
        }

        if (section.getBoolean("hide-flags", true)) {
            meta.addItemFlags(ItemFlag.values());
        }

        item.setItemMeta(meta);
    }

    /** Minecraft вешает курсив на любой кастомный текст предмета — снимаем. */
    public static Component clean(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    public static List<Component> cleanAll(List<String> raw, @Nullable Replacer replacer) {
        List<Component> out = new ArrayList<>(raw.size());
        for (String line : raw) {
            out.add(clean(Text.parse(line, replacer)));
        }
        return out;
    }
}
