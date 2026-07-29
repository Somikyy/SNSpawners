package ru.snplugins.snspawners.service;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import ru.snplugins.snspawners.SNSpawners;
import ru.snplugins.snspawners.config.Config;
import ru.snplugins.snspawners.config.LootEntry;
import ru.snplugins.snspawners.config.UpgradeLevel;
import ru.snplugins.snspawners.data.BlockPos;
import ru.snplugins.snspawners.data.SpawnerData;
import ru.snplugins.snspawners.util.Inventories;
import ru.snplugins.snspawners.util.Numbers;
import ru.snplugins.snspawners.util.Replacer;

import java.util.List;
import java.util.UUID;

/** Операции над спавнером: забрать, продать, улучшить, выгрузить. */
public final class Actions {

    private final SNSpawners plugin;

    public Actions(SNSpawners plugin) {
        this.plugin = plugin;
    }

    /** Догоняет производство перед любым действием игрока. */
    public void catchUp(SpawnerData data) {
        UpgradeLevel upgrade = plugin.types().level(data.level());
        data.produce(plugin.config(), upgrade, System.currentTimeMillis(), data.autoSell());
        if (data.autoSell() && plugin.economy().isAvailable()) {
            double income = autoSell(data);
            if (income > 0.0d) {
                data.addPendingIncome(income);
            }
        }
    }

    // ── забрать ──────────────────────────────────────────────────────────────

    public void collect(Player player, SpawnerData data) {
        catchUp(data);

        long experience = data.takeExperience();
        if (experience > 0L) {
            player.giveExp((int) Math.min(Integer.MAX_VALUE, experience));
            plugin.messages().send(player, "xp-collected",
                    Replacer.of("%xp%", Numbers.compact(experience)));
        }

        if (data.stored() <= 0L) {
            if (experience <= 0L) {
                plugin.messages().send(player, "collect-empty");
            }
            return;
        }

        long given = 0L;
        long left = 0L;

        List<LootEntry> loot = data.type().loot;
        for (int i = 0; i < loot.size(); i++) {
            long held = data.stored(i);
            if (held <= 0L) {
                continue;
            }
            long remaining = Inventories.push(player.getInventory(), loot.get(i), held);
            long moved = held - remaining;
            if (moved > 0L) {
                data.take(i, moved);
                given += moved;
            }
            left += remaining;
        }

        plugin.holograms().update(data, true);

        if (given <= 0L) {
            plugin.messages().send(player, "inventory-full");
        } else if (left > 0L) {
            plugin.messages().send(player, "collect-partial", Replacer
                    .of("%items%", Numbers.compact(given))
                    .with("%left%", Numbers.compact(left)));
        } else {
            plugin.messages().send(player, "collect-success",
                    Replacer.of("%items%", Numbers.compact(given)));
        }
    }

    /** Забирает одну позицию склада. {@code amount <= 0} — забрать всё. */
    public void collectOne(Player player, SpawnerData data, String lootId, long amount) {
        LootEntry entry = data.type().lootById(lootId);
        if (entry == null) {
            return;
        }
        long held = data.stored(lootId);
        if (held <= 0L) {
            plugin.messages().send(player, "collect-empty");
            return;
        }
        long wanted = amount <= 0L ? held : Math.min(amount, held);

        long remaining = Inventories.push(player.getInventory(), entry, wanted);
        long moved = wanted - remaining;
        if (moved <= 0L) {
            plugin.messages().send(player, "inventory-full");
            return;
        }
        data.take(lootId, moved);
        plugin.holograms().update(data, true);
        plugin.messages().send(player, "collect-success",
                Replacer.of("%items%", Numbers.compact(moved)));
    }

    // ── продать ──────────────────────────────────────────────────────────────

    public void sell(Player player, SpawnerData data) {
        if (!plugin.economy().isAvailable()) {
            plugin.messages().send(player, "sell-no-economy");
            return;
        }
        catchUp(data);

        long items = data.stored();
        if (items <= 0L) {
            plugin.messages().send(player, "sell-empty");
            return;
        }

        double money = data.value() * plugin.config().taxMultiplier;
        data.clearStorage();
        plugin.economy().deposit(player, money);
        plugin.holograms().update(data, true);

        plugin.messages().send(player, "sell-success", Replacer
                .of("%money%", plugin.economy().format(money))
                .with("%items%", Numbers.compact(items)));
    }

    /** Продаёт одну позицию склада. */
    public void sellOne(Player player, SpawnerData data, String lootId) {
        if (!plugin.economy().isAvailable()) {
            plugin.messages().send(player, "sell-no-economy");
            return;
        }
        LootEntry entry = data.type().lootById(lootId);
        long held = data.stored(lootId);
        if (entry == null || held <= 0L) {
            plugin.messages().send(player, "sell-empty");
            return;
        }
        data.take(lootId, held);
        double money = entry.price * held * plugin.config().taxMultiplier;
        plugin.economy().deposit(player, money);
        plugin.holograms().update(data, true);

        plugin.messages().send(player, "sell-success", Replacer
                .of("%money%", plugin.economy().format(money))
                .with("%items%", Numbers.compact(held)));
    }

    // ── улучшить ─────────────────────────────────────────────────────────────

    public void upgrade(Player player, SpawnerData data) {
        int maxLevel = plugin.types().maxLevel();
        if (data.level() >= maxLevel) {
            plugin.messages().send(player, "upgrade-max-level");
            return;
        }
        if (!plugin.economy().isAvailable()) {
            plugin.messages().send(player, "sell-no-economy");
            return;
        }

        UpgradeLevel next = plugin.types().level(data.level() + 1);
        double balance = plugin.economy().balance(player);

        if (balance < next.cost()) {
            plugin.messages().send(player, "upgrade-no-money", Replacer
                    .of("%cost%", plugin.economy().format(next.cost()))
                    .with("%balance%", plugin.economy().format(balance))
                    .with("%needed%", plugin.economy().format(next.cost() - balance)));
            return;
        }

        if (!plugin.economy().withdraw(player, next.cost())) {
            plugin.messages().send(player, "upgrade-no-money", Replacer
                    .of("%cost%", plugin.economy().format(next.cost()))
                    .with("%balance%", plugin.economy().format(balance))
                    .with("%needed%", plugin.economy().format(next.cost() - balance)));
            return;
        }

        data.level(next.level());
        plugin.holograms().update(data, true);
        plugin.messages().send(player, "upgrade-success", Replacer
                .of("%level%", next.level())
                .with("%cost%", plugin.economy().format(next.cost())));
    }

    // ── выгрузка и автопродажа (вызывается обходом) ──────────────────────────

    /**
     * Перекладывает склад в привязанный контейнер.
     *
     * <p>Вызывается обходом, то есть потенциально каждый тик на каждый спавнер.
     * Сама выгрузка дорогая — снимок состояния блока, доступ к инвентарю,
     * сборка стопок, проход {@code addItem} по всем слотам, — а класть в сундук
     * по три предмета двадцать раз в секунду смысла нет: производство ленивое и
     * ничего не теряет, если забрать накопленное одним движением. Поэтому между
     * выгрузками выдерживается интервал, а забитый контейнер откладывается
     * надолго — иначе каждый тик тратился бы на проход по полному сундуку
     * ради нулевого результата.
     *
     * @return сколько предметов ушло
     */
    public long flush(SpawnerData data, long now) {
        BlockPos link = data.link();
        if (link == null || data.stored() <= 0L || now < data.nextFlushAt()) {
            return 0L;
        }

        Config config = plugin.config();
        if (!link.isChunkLoaded()) {
            data.nextFlushAt(now + config.flushRetryMillis);
            return 0L;
        }

        Block block = link.block();
        if (block == null || !config.allowedContainers.contains(block.getType())
                || !(block.getState(false) instanceof Container container)) {
            unlink(data);
            return 0L;
        }

        Inventory inventory = container.getInventory();
        List<LootEntry> loot = data.type().loot;
        long moved = 0L;
        boolean full = false;

        for (int i = 0; i < loot.size(); i++) {
            long held = data.stored(i);
            if (held <= 0L) {
                continue;
            }
            long remaining = Inventories.push(inventory, loot.get(i), held);
            long pushed = held - remaining;
            if (pushed > 0L) {
                data.take(i, pushed);
                moved += pushed;
            }
            if (remaining > 0L) {
                full = true;
                break;
            }
        }

        data.nextFlushAt(now + (full ? config.flushRetryMillis : config.flushIntervalMillis));
        return moved;
    }

    /** Конвертирует весь склад в деньги владельца. Возвращает выручку. */
    public double autoSell(SpawnerData data) {
        if (!plugin.economy().isAvailable() || data.stored() <= 0L) {
            return 0.0d;
        }
        UUID owner = data.owner();
        if (owner == null) {
            return 0.0d;
        }

        double money = data.value() * plugin.config().taxMultiplier;
        data.clearStorage();

        if (money <= 0.0d) {
            return 0.0d;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(owner);
        plugin.economy().deposit(target, money);
        return money;
    }

    private void unlink(SpawnerData data) {
        data.link(null);
        UUID owner = data.owner();
        if (owner == null) {
            return;
        }
        Player player = Bukkit.getPlayer(owner);
        if (player != null) {
            plugin.messages().send(player, "link-lost");
        }
    }
}
