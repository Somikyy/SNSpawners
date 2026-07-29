package ru.snplugins.snspawners.data;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * Ключи {@code PersistentDataContainer}.
 *
 * <p>Состояние спавнера живёт прямо в блоке, а не во внешней базе. Плюсы:
 * данные не разъезжаются с миром при откате бэкапа, не нужны миграции,
 * не нужен I/O при выключении. Минус — доступ только к загруженным чанкам,
 * что и так требуется по логике плагина.
 */
public final class Keys {

    public final NamespacedKey type;
    public final NamespacedKey stack;
    public final NamespacedKey owner;
    public final NamespacedKey level;
    public final NamespacedKey lastProduce;
    public final NamespacedKey storage;
    public final NamespacedKey experience;
    public final NamespacedKey link;
    public final NamespacedKey autoSell;

    /** Метка на предмете-спавнере в инвентаре. */
    public final NamespacedKey itemType;
    public final NamespacedKey itemStack;

    /** Метка на голограмме, чтобы отличать свои TextDisplay от чужих. */
    public final NamespacedKey hologram;

    public Keys(Plugin plugin) {
        this.type = new NamespacedKey(plugin, "type");
        this.stack = new NamespacedKey(plugin, "stack");
        this.owner = new NamespacedKey(plugin, "owner");
        this.level = new NamespacedKey(plugin, "level");
        this.lastProduce = new NamespacedKey(plugin, "last");
        this.storage = new NamespacedKey(plugin, "storage");
        this.experience = new NamespacedKey(plugin, "xp");
        this.link = new NamespacedKey(plugin, "link");
        this.autoSell = new NamespacedKey(plugin, "autosell");
        this.itemType = new NamespacedKey(plugin, "item_type");
        this.itemStack = new NamespacedKey(plugin, "item_stack");
        this.hologram = new NamespacedKey(plugin, "hologram");
    }
}
