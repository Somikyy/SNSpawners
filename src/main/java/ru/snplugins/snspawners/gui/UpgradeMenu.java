package ru.snplugins.snspawners.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import ru.snplugins.snspawners.SNSpawners;
import ru.snplugins.snspawners.config.UpgradeLevel;
import ru.snplugins.snspawners.data.SpawnerData;
import ru.snplugins.snspawners.util.Numbers;
import ru.snplugins.snspawners.util.Replacer;

import java.util.List;

/** Меню улучшений: лестница уровней с ценами и бонусами. */
public final class UpgradeMenu extends Menu {

    private static final String BASE = "upgrade-menu";

    private final SpawnerData data;

    public UpgradeMenu(SNSpawners plugin, Player viewer, SpawnerData data) {
        super(plugin, viewer);
        this.data = data;
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
        Replacer base = plugin.placeholders(data, viewer);
        fill(BASE);

        List<Integer> slots = plugin.gui().integers(BASE + ".level-slots");
        List<UpgradeLevel> levels = plugin.types().levels();
        int current = data.level();

        for (int index = 0; index < levels.size() && index < slots.size(); index++) {
            UpgradeLevel level = levels.get(index);
            String state = level.level() < current ? "unlocked"
                    : level.level() == current ? "current"
                    : level.level() == current + 1 ? "available"
                    : "locked";

            Replacer replacer = Replacer
                    .of("%lvl%", level.level())
                    .with("%speed%", Numbers.compact(level.speed()))
                    .with("%fortune%", Math.round(level.fortune() * 100.0d))
                    .with("%storage%", Numbers.compact(level.storage()))
                    .with("%cost%", plugin.economy().isAvailable()
                            ? plugin.economy().format(level.cost()) : "—")
                    .with("%balance%", plugin.economy().isAvailable()
                            ? plugin.economy().format(plugin.economy().balance(viewer)) : "—");

            placeAt(slots.get(index), BASE + ".level." + state,
                    "available".equals(state) ? "buy" : null,
                    replacer, level.material());
        }

        place(BASE + ".items.back", "back", base, null);
    }

    @Override
    public void click(InventoryClickEvent event) {
        String action = actions.get(event.getRawSlot());
        if (action == null) {
            return;
        }
        playSound("menu-click");

        switch (action) {
            case "buy" -> {
                plugin.actions().upgrade(viewer, data);
                redraw();
            }
            case "back" -> new SpawnerMenu(plugin, viewer, data).open();
            default -> {
                // Уровень, который нельзя купить — клик игнорируется.
            }
        }
    }
}
