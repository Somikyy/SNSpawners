package ru.snplugins.snspawners.util;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import ru.snplugins.snspawners.config.LootEntry;

import java.util.Map;

/** Выдача больших количеств предметов без создания лишних объектов. */
public final class Inventories {

    private Inventories() {
    }

    /** Сколько стопок собираем за один вызов {@code addItem}. */
    private static final int BATCH_STACKS = 36;

    /**
     * Кладёт до {@code amount} штук в инвентарь.
     *
     * <p>Предметы отдаются пачками по {@value #BATCH_STACKS} стопок: один вызов
     * {@code addItem} на 200 000 предметов невозможен, а вызов на каждую
     * стопку — это тысячи проходов по инвентарю. Как только пачка вернулась
     * не полностью — место кончилось, дальше можно не пытаться.
     *
     * @return сколько штук не поместилось
     */
    public static long push(Inventory inventory, LootEntry entry, long amount) {
        long remaining = amount;
        int stackSize = entry.maxStackSize();

        while (remaining > 0L) {
            int stacks = (int) Math.min(BATCH_STACKS, (remaining + stackSize - 1) / stackSize);
            ItemStack[] batch = new ItemStack[stacks];

            long batched = 0L;
            for (int i = 0; i < stacks; i++) {
                int size = (int) Math.min(stackSize, remaining - batched);
                batch[i] = entry.createStack(size);
                batched += size;
            }

            Map<Integer, ItemStack> leftover = inventory.addItem(batch);

            long returned = 0L;
            for (ItemStack left : leftover.values()) {
                returned += left.getAmount();
            }

            remaining -= batched - returned;
            if (returned > 0L) {
                break;
            }
        }
        return remaining;
    }

    /** Кладёт предмет игроку, остаток роняет под ноги. */
    public static void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack left : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
    }
}
