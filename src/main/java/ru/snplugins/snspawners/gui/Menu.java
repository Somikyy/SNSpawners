package ru.snplugins.snspawners.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.snplugins.snspawners.SNSpawners;
import ru.snplugins.snspawners.util.ItemBuilder;
import ru.snplugins.snspawners.util.Replacer;
import ru.snplugins.snspawners.util.SoundSpec;
import ru.snplugins.snspawners.util.Text;

import java.util.HashMap;
import java.util.Map;

/**
 * Базовое меню.
 *
 * <p>Идентификация идёт через {@link InventoryHolder}, а не через сравнение
 * заголовков: заголовок игрок видит, а холдер — это ссылка на объект, его
 * невозможно подделать и не нужно разбирать строку на каждый клик.
 */
public abstract class Menu implements InventoryHolder {

    protected final SNSpawners plugin;
    protected final Player viewer;
    protected Inventory inventory;

    /** Слот → идентификатор действия. Заполняется при отрисовке. */
    protected final Map<Integer, String> actions = new HashMap<>();

    protected Menu(SNSpawners plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
    }

    protected abstract String titlePath();

    protected abstract int rows();

    protected abstract Replacer titleReplacer();

    /** Раскладывает предметы по уже созданному инвентарю. */
    protected abstract void draw();

    public void open() {
        int size = Math.max(9, Math.min(54, rows() * 9));
        Component title = Text.parse(plugin.gui().string(titlePath(), " "), titleReplacer());
        this.inventory = Bukkit.createInventory(this, size, title);
        redraw();
        viewer.openInventory(inventory);
        playSound("menu-open");
    }

    public void redraw() {
        if (inventory == null) {
            return;
        }
        inventory.clear();
        actions.clear();
        draw();
    }

    public abstract void click(InventoryClickEvent event);

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public Player viewer() {
        return viewer;
    }

    // ── помощники отрисовки ──────────────────────────────────────────────────

    protected void fill(String basePath) {
        ConfigurationSection filler = plugin.gui().section(basePath + ".filler");
        if (filler == null || !filler.getBoolean("enabled", true)) {
            return;
        }
        ItemStack item = ItemBuilder.fromSection(filler, null, null);
        if (item == null) {
            return;
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, item);
        }
    }

    /**
     * Ставит предмет из конфига и вешает на его слоты действие.
     *
     * @param action идентификатор, который вернётся в {@link #click}
     */
    protected void place(String path, String action, @Nullable Replacer replacer,
                         @Nullable org.bukkit.Material fallback) {
        ConfigurationSection section = plugin.gui().section(path);
        if (section == null) {
            return;
        }
        ItemStack item = ItemBuilder.fromSection(section, replacer, fallback);
        if (item == null) {
            return;
        }
        for (int slot : slotsOf(section)) {
            if (slot >= 0 && slot < inventory.getSize()) {
                inventory.setItem(slot, item);
                actions.put(slot, action);
            }
        }
    }

    /** Тот же {@link #place}, но слот задаётся кодом, а не конфигом. */
    protected void placeAt(int slot, String path, String action, @Nullable Replacer replacer,
                           @Nullable org.bukkit.Material fallback) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        ConfigurationSection section = plugin.gui().section(path);
        if (section == null) {
            return;
        }
        ItemStack item = ItemBuilder.fromSection(section, replacer, fallback);
        if (item == null) {
            return;
        }
        inventory.setItem(slot, item);
        if (action != null) {
            actions.put(slot, action);
        }
    }

    protected static int[] slotsOf(ConfigurationSection section) {
        if (section.isList("slot")) {
            return section.getIntegerList("slot").stream().mapToInt(Integer::intValue).toArray();
        }
        return new int[] { section.getInt("slot", -1) };
    }

    protected void playSound(String key) {
        SoundSpec sound = plugin.config().sound(key);
        if (sound != null) {
            sound.play(viewer);
        }
    }
}
