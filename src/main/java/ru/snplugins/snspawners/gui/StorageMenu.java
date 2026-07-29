package ru.snplugins.snspawners.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.snplugins.snspawners.SNSpawners;
import ru.snplugins.snspawners.config.LootEntry;
import ru.snplugins.snspawners.data.SpawnerData;
import ru.snplugins.snspawners.util.ItemBuilder;
import ru.snplugins.snspawners.util.Numbers;
import ru.snplugins.snspawners.util.Replacer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Постраничный просмотр виртуального склада. */
public final class StorageMenu extends Menu {

    private static final String BASE = "storage-menu";

    private final SpawnerData data;
    private final int pages;
    private int page;

    /** Слот → идентификатор записи лута. Отдельно от {@link #actions}. */
    private final Map<Integer, String> lootSlots = new HashMap<>();

    public StorageMenu(SNSpawners plugin, Player viewer, SpawnerData data, int page) {
        super(plugin, viewer);
        this.data = data;
        plugin.actions().catchUp(data);

        int perPage = Math.max(1, contentRows() * 9);
        this.pages = Math.max(1, (entries().size() + perPage - 1) / perPage);
        this.page = Math.max(0, Math.min(page, pages - 1));
    }

    private int contentRows() {
        return Math.max(1, plugin.gui().integer(BASE + ".content-rows", 5));
    }

    /** Непустые записи склада от большего количества к меньшему. */
    private List<LootEntry> entries() {
        List<LootEntry> loot = data.type().loot;
        List<LootEntry> out = new ArrayList<>(loot.size());
        for (int i = 0; i < loot.size(); i++) {
            if (data.stored(i) > 0L) {
                out.add(loot.get(i));
            }
        }
        out.sort((a, b) -> Long.compare(data.stored(b.index), data.stored(a.index)));
        return out;
    }

    @Override
    protected String titlePath() {
        return BASE + ".title";
    }

    @Override
    protected int rows() {
        return plugin.gui().integer(BASE + ".rows", 6);
    }

    @Override
    protected Replacer titleReplacer() {
        return plugin.placeholders(data, viewer)
                .with("%page%", page + 1)
                .with("%pages%", pages);
    }

    @Override
    protected void draw() {
        lootSlots.clear();
        fill(BASE);

        Replacer base = plugin.placeholders(data, viewer)
                .with("%page%", page + 1)
                .with("%pages%", pages);

        List<LootEntry> entries = entries();

        if (entries.isEmpty()) {
            place(BASE + ".items.empty", null, base, null);
        } else {
            int perPage = contentRows() * 9;
            int from = page * perPage;
            int to = Math.min(entries.size(), from + perPage);
            List<String> loreTemplate = itemLore();

            for (int index = from; index < to; index++) {
                LootEntry entry = entries.get(index);
                int slot = index - from;
                inventory.setItem(slot, icon(entry, data.stored(entry.index), loreTemplate));
                lootSlots.put(slot, entry.id);
            }
        }

        if (page > 0) {
            place(BASE + ".items.previous", "previous", base, null);
        }
        place(BASE + ".items.back", "back", base, null);
        if (page < pages - 1) {
            place(BASE + ".items.next", "next", base, null);
        }
    }

    /** Без экономики цены и подсказка о продаже только сбивают с толку. */
    private List<String> itemLore() {
        if (!plugin.economy().isAvailable()) {
            List<String> alternate = plugin.gui().strings(BASE + ".item-lore-no-economy");
            if (!alternate.isEmpty()) {
                return alternate;
            }
        }
        return plugin.gui().strings(BASE + ".item-lore");
    }

    private ItemStack icon(LootEntry entry, long amount, List<String> loreTemplate) {
        // Визуальный размер стопки ограничен вместимостью слота, реальное
        // количество живёт в описании — на складе могут лежать миллионы.
        ItemStack item = entry.createStack((int) Math.min(amount, entry.maxStackSize()));

        Replacer replacer = Replacer
                .of("%amount%", Numbers.grouped(amount))
                .with("%price%", plugin.economy().isAvailable()
                        ? plugin.economy().format(entry.price) : "—")
                .with("%total%", plugin.economy().isAvailable()
                        ? plugin.economy().format(entry.price * amount) : "—");

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.lore(ItemBuilder.cleanAll(loreTemplate, replacer));
            item.setItemMeta(meta);
        }
        return item;
    }

    @Override
    public void click(InventoryClickEvent event) {
        int slot = event.getRawSlot();

        String lootId = lootSlots.get(slot);
        if (lootId != null) {
            playSound("menu-click");
            ClickType click = event.getClick();
            if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
                plugin.actions().sellOne(viewer, data, lootId);
            } else if (click.isShiftClick()) {
                plugin.actions().collectOne(viewer, data, lootId, 0L);
            } else {
                LootEntry entry = data.type().lootById(lootId);
                plugin.actions().collectOne(viewer, data, lootId,
                        entry == null ? 64L : entry.maxStackSize());
            }
            redraw();
            return;
        }

        String action = actions.get(slot);
        if (action == null) {
            return;
        }
        playSound("menu-click");

        switch (action) {
            case "previous" -> new StorageMenu(plugin, viewer, data, page - 1).open();
            case "next" -> new StorageMenu(plugin, viewer, data, page + 1).open();
            case "back" -> new SpawnerMenu(plugin, viewer, data).open();
            default -> {
                // Пустой слот-заглушка.
            }
        }
    }
}
