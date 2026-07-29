package ru.snplugins.snspawners.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import ru.snplugins.snspawners.SNSpawners;
import ru.snplugins.snspawners.data.SpawnerData;
import ru.snplugins.snspawners.util.Replacer;

/** Главное меню спавнера. */
public final class SpawnerMenu extends Menu {

    private static final String BASE = "spawner-menu";

    private final SpawnerData data;

    public SpawnerMenu(SNSpawners plugin, Player viewer, SpawnerData data) {
        super(plugin, viewer);
        this.data = data;
    }

    public SpawnerData data() {
        return data;
    }

    @Override
    protected String titlePath() {
        return BASE + ".title";
    }

    @Override
    protected int rows() {
        return plugin.gui().integer(BASE + ".rows", 5);
    }

    @Override
    protected Replacer titleReplacer() {
        return plugin.placeholders(data, viewer);
    }

    @Override
    protected void draw() {
        // Открытие меню — момент, когда накопленное становится «настоящим».
        plugin.actions().catchUp(data);

        Replacer replacer = plugin.placeholders(data, viewer);
        boolean economy = plugin.economy().isAvailable();

        fill(BASE);

        place(BASE + ".items.info", "info", replacer, data.type().icon);
        place(BASE + ".items.collect", "collect", replacer, null);
        place(BASE + ".items.storage", "storage", replacer, null);
        place(BASE + ".items.close", "close", replacer, null);

        if (economy) {
            place(BASE + ".items.sell", "sell", replacer, null);
            place(BASE + ".items.upgrade", "upgrade", replacer, null);
        }

        if (economy && plugin.config().autoSellEnabled && viewer.hasPermission("snspawners.autosell")) {
            place(BASE + ".items." + (data.autoSell() ? "autosell-on" : "autosell-off"),
                    "autosell", replacer, null);
        }

        if (plugin.config().autoFlushEnabled) {
            place(BASE + ".items.link", "link", replacer, null);
        }
    }

    @Override
    public void click(InventoryClickEvent event) {
        String action = actions.get(event.getRawSlot());
        if (action == null) {
            return;
        }
        playSound("menu-click");

        switch (action) {
            case "collect" -> {
                plugin.actions().collect(viewer, data);
                redraw();
            }
            case "sell" -> {
                plugin.actions().sell(viewer, data);
                redraw();
            }
            case "storage" -> new StorageMenu(plugin, viewer, data, 0).open();
            case "upgrade" -> new UpgradeMenu(plugin, viewer, data).open();
            case "autosell" -> {
                data.autoSell(!data.autoSell());
                plugin.messages().send(viewer, data.autoSell() ? "autosell-enabled" : "autosell-disabled");
                redraw();
            }
            case "link" -> {
                if (event.getClick() == ClickType.RIGHT) {
                    data.link(null);
                    plugin.messages().send(viewer, "link-removed");
                    redraw();
                } else {
                    plugin.beginLinking(viewer, data);
                    viewer.closeInventory();
                }
            }
            case "close" -> viewer.closeInventory();
            default -> {
                // "info" и незнакомые действия — просто съедаем клик.
            }
        }
    }
}
