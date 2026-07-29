package ru.snplugins.snspawners.item;

import org.bukkit.Material;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;
import ru.snplugins.snspawners.SNSpawners;
import ru.snplugins.snspawners.config.SpawnerType;
import ru.snplugins.snspawners.util.ItemBuilder;
import ru.snplugins.snspawners.util.Replacer;
import ru.snplugins.snspawners.util.Text;

/**
 * Предмет-спавнер в инвентаре.
 *
 * <p>Одна единица предмета несёт свой размер стака в PDC: снял стак из 64
 * спавнеров — получил один предмет со значением 64, а не 64 предмета.
 */
public final class SpawnerItem {

    private final SNSpawners plugin;

    public SpawnerItem(SNSpawners plugin) {
        this.plugin = plugin;
    }

    public ItemStack create(SpawnerType type, int stack, int amount) {
        ItemStack item = new ItemStack(Material.SPAWNER, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        Replacer replacer = Replacer.of("%type%", Text.strip(type.display).isBlank() ? type.key : type.display)
                .with("%stack%", stack);

        meta.displayName(ItemBuilder.clean(Text.parse(plugin.messages().itemName(), replacer)));
        meta.lore(ItemBuilder.cleanAll(plugin.messages().itemLore(), replacer));
        meta.addItemFlags(ItemFlag.values());

        // Чтобы предмет показывал правильного моба в руках и в раздатчике.
        if (meta instanceof BlockStateMeta blockState
                && blockState.getBlockState() instanceof CreatureSpawner spawner) {
            spawner.setSpawnedType(type.entityType);
            blockState.setBlockState(spawner);
        }

        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(plugin.keys().itemType, PersistentDataType.STRING, type.key);
        container.set(plugin.keys().itemStack, PersistentDataType.INTEGER, Math.max(1, stack));

        item.setItemMeta(meta);
        return item;
    }

    public @Nullable SpawnerType typeOf(@Nullable ItemStack item) {
        if (item == null || item.getType() != Material.SPAWNER || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        String key = meta.getPersistentDataContainer().get(plugin.keys().itemType, PersistentDataType.STRING);
        return key == null ? null : plugin.types().byKey(key);
    }

    /** Сколько спавнеров несёт одна единица предмета. По умолчанию — один. */
    public int stackOf(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 1;
        }
        Integer stack = item.getItemMeta().getPersistentDataContainer()
                .get(plugin.keys().itemStack, PersistentDataType.INTEGER);
        return stack == null ? 1 : Math.max(1, stack);
    }
}
