package ru.snplugins.snspawners.listener;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.jetbrains.annotations.Nullable;
import ru.snplugins.snspawners.SNSpawners;
import ru.snplugins.snspawners.config.SpawnerType;
import ru.snplugins.snspawners.data.BlockPos;
import ru.snplugins.snspawners.data.ChunkPos;
import ru.snplugins.snspawners.data.SpawnerData;
import ru.snplugins.snspawners.util.Inventories;
import ru.snplugins.snspawners.util.Numbers;
import ru.snplugins.snspawners.util.Replacer;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Установка, разрушение, открытие меню и привязка контейнера. */
public final class SpawnerListener implements Listener {

    private static final String STACK_PERMISSION = "snspawners.stack.";

    /** Сколько секунд действует подтверждение слома при непустом складе. */
    private static final long CONFIRM_WINDOW_MILLIS = 15_000L;

    private final SNSpawners plugin;

    public SpawnerListener(SNSpawners plugin) {
        this.plugin = plugin;
    }

    // ── установка ────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (block.getType() != Material.SPAWNER) {
            return;
        }

        Player player = event.getPlayer();

        if (plugin.config().blacklistedWorlds.contains(block.getWorld().getName())) {
            event.setCancelled(true);
            plugin.messages().send(player, "place-blacklisted-world");
            return;
        }

        if (!player.hasPermission("snspawners.place")) {
            event.setCancelled(true);
            plugin.messages().send(player, "no-permission",
                    Replacer.of("%permission%", "snspawners.place"));
            return;
        }

        ItemStack item = event.getItemInHand();
        SpawnerType type = plugin.items().typeOf(item);
        if (type == null) {
            type = typeFromVanillaItem(item);
        }
        if (type == null) {
            // Спавнер неизвестного плагину моба — оставляем ванильному поведению.
            return;
        }

        int amount = plugin.items().stackOf(item);
        int limit = stackLimit(player);

        SpawnerData target = findMergeTarget(block, type, player.getUniqueId());

        if (target != null) {
            event.setCancelled(true);

            int space = limit - target.stack();
            if (space <= 0) {
                plugin.messages().send(player, "place-limit-reached",
                        Replacer.of("%limit%", limit));
                return;
            }

            int added = Math.min(space, amount);
            target.stack(target.stack() + added);
            plugin.manager().persist(target);
            plugin.holograms().update(target, true);
            consume(player, event.getHand());

            if (added < amount) {
                // Влезло не всё — разницу возвращаем предметом, чтобы не сгорела.
                Inventories.giveOrDrop(player, plugin.items().create(type, amount - added, 1));
                plugin.messages().send(player, "place-limit-reached",
                        Replacer.of("%limit%", limit));
            }

            plugin.playSound(player, "stack-merge");
            plugin.messages().send(player, "place-merged", Replacer
                    .of("%type%", type.display)
                    .with("%added%", added)
                    .with("%stack%", target.stack()));
            return;
        }

        if (amount > limit) {
            event.setCancelled(true);
            plugin.messages().send(player, "place-limit-reached", Replacer.of("%limit%", limit));
            return;
        }

        SpawnerData data = plugin.manager().create(block, type, amount, 1, player.getUniqueId());
        if (data == null) {
            return;
        }
        plugin.messages().send(player, "place-success", Replacer
                .of("%type%", type.display)
                .with("%stack%", data.stack()));
    }

    /** Ванильный предмет спавнера несёт тип моба в состоянии блока. */
    private @Nullable SpawnerType typeFromVanillaItem(ItemStack item) {
        if (item.getType() != Material.SPAWNER || !item.hasItemMeta()) {
            return null;
        }
        if (item.getItemMeta() instanceof org.bukkit.inventory.meta.BlockStateMeta meta
                && meta.getBlockState() instanceof CreatureSpawner spawner) {
            return plugin.types().byEntity(spawner.getSpawnedType());
        }
        return null;
    }

    /**
     * Ищет ближайший стак того же типа в кубе радиуса {@code merge-radius}.
     *
     * <p>Перебираются спавнеры из чанков, накрывающих куб, а не сами клетки:
     * при радиусе 4 клеток 729, а чанков — максимум четыре, и в них обычно
     * лежит пара записей. Раньше на каждую клетку создавался ключ и делался
     * поиск по карте — три четверти тысячи объектов на одну установку блока.
     */
    private @Nullable SpawnerData findMergeTarget(Block block, SpawnerType type, UUID placer) {
        int radius = plugin.config().mergeRadius;
        if (radius <= 0) {
            return null;
        }

        UUID world = block.getWorld().getUID();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        SpawnerData best = null;
        long bestDistance = Long.MAX_VALUE;

        for (int chunkX = (x - radius) >> 4; chunkX <= (x + radius) >> 4; chunkX++) {
            for (int chunkZ = (z - radius) >> 4; chunkZ <= (z + radius) >> 4; chunkZ++) {
                for (SpawnerData candidate : plugin.manager().inChunk(new ChunkPos(world, chunkX, chunkZ))) {
                    if (candidate.type() != type) {
                        continue;
                    }
                    BlockPos position = candidate.position();
                    int dx = position.x() - x;
                    int dy = position.y() - y;
                    int dz = position.z() - z;
                    if ((dx | dy | dz) == 0
                            || Math.abs(dx) > radius || Math.abs(dy) > radius || Math.abs(dz) > radius) {
                        continue;
                    }
                    if (plugin.config().mergeSameOwnerOnly && !candidate.isOwner(placer)) {
                        continue;
                    }
                    long distance = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (distance < bestDistance
                            || (distance == bestDistance && precedes(position, best.position()))) {
                        bestDistance = distance;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Порядок при равном расстоянии: меньший X, затем Y, затем Z.
     *
     * <p>Куб раньше обходился именно в этом порядке, а из равноудалённых
     * кандидатов выигрывал первый встреченный. Обход по чанкам идёт в другом
     * порядке, поэтому правило приходится задавать явно — иначе при двух
     * одинаково близких стаках выбор стал бы зависеть от раскладки чанков.
     */
    private static boolean precedes(BlockPos candidate, BlockPos current) {
        if (candidate.x() != current.x()) {
            return candidate.x() < current.x();
        }
        if (candidate.y() != current.y()) {
            return candidate.y() < current.y();
        }
        return candidate.z() < current.z();
    }

    private int stackLimit(Player player) {
        int limit = plugin.config().maxStack;
        int fromPermission = 0;

        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue;
            }
            String permission = info.getPermission();
            if (!permission.startsWith(STACK_PERMISSION)) {
                continue;
            }
            fromPermission = Math.max(fromPermission,
                    Numbers.parseInt(permission.substring(STACK_PERMISSION.length()), 0));
        }

        return fromPermission > 0 ? Math.min(fromPermission, limit) : limit;
    }

    private void consume(Player player, @Nullable EquipmentSlot hand) {
        if (player.getGameMode() == GameMode.CREATIVE || hand == null) {
            return;
        }
        ItemStack item = player.getInventory().getItem(hand);
        if (item == null || item.getAmount() <= 0) {
            return;
        }
        item.setAmount(item.getAmount() - 1);
        // Клиент уже отрисовал установку блока и списал предмет сам;
        // после отмены события его нужно вернуть к серверной картине.
        player.updateInventory();
    }

    // ── разрушение ───────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.SPAWNER) {
            return;
        }

        SpawnerData data = plugin.manager().resolve(block);
        if (data == null) {
            return;
        }

        Player player = event.getPlayer();
        boolean bypass = player.hasPermission("snspawners.bypass");

        if (plugin.config().ownerOnly && !bypass && !data.isOwner(player.getUniqueId())) {
            event.setCancelled(true);
            plugin.messages().send(player, "break-not-owner",
                    Replacer.of("%owner%", plugin.ownerName(data.owner())));
            return;
        }

        if (plugin.config().requireSilkTouch && !bypass && player.getGameMode() != GameMode.CREATIVE
                && !player.getInventory().getItemInMainHand()
                        .containsEnchantment(Enchantment.SILK_TOUCH)) {
            event.setCancelled(true);
            plugin.messages().send(player, "break-need-silk");
            return;
        }

        boolean whole = player.isSneaking() && plugin.config().shiftBreaksWholeStack;
        int taken = whole ? data.stack() : 1;
        boolean removesBlock = taken >= data.stack();

        plugin.actions().catchUp(data);

        // Снос последнего спавнера уничтожает всё, что лежит на складе,
        // поэтому первый удар только предупреждает.
        if (removesBlock && !data.isStorageEmpty()) {
            long now = System.currentTimeMillis();
            if (data.breakConfirmUntil() < now) {
                data.breakConfirmUntil(now + CONFIRM_WINDOW_MILLIS);
                event.setCancelled(true);
                plugin.messages().send(player, "break-storage-not-empty",
                        Replacer.of("%storage%", Numbers.grouped(data.stored())));
                return;
            }
        }

        event.setDropItems(false);
        event.setExpToDrop(0);

        boolean lucky = bypass || player.getGameMode() == GameMode.CREATIVE
                || ThreadLocalRandom.current().nextDouble() < plugin.config().breakChance;

        if (lucky && player.getGameMode() != GameMode.CREATIVE) {
            Inventories.giveOrDrop(player, plugin.items().create(data.type(), taken, 1));
        }

        if (removesBlock) {
            plugin.manager().unregister(data.position());
            if (!lucky) {
                plugin.messages().send(player, "break-failed-chance", Replacer
                        .of("%chance%", Math.round(plugin.config().breakChance * 100.0d)));
            } else {
                plugin.messages().send(player, "break-success-whole", Replacer
                        .of("%type%", data.type().display)
                        .with("%amount%", taken));
            }
            return;
        }

        // Блок остаётся: уменьшаем стак и отменяем разрушение.
        event.setCancelled(true);
        data.stack(data.stack() - taken);
        plugin.manager().persist(data);
        plugin.holograms().update(data, true);

        plugin.messages().send(player, "break-success", Replacer
                .of("%type%", data.type().display)
                .with("%amount%", taken)
                .with("%left%", data.stack()));
    }

    // ── взаимодействие ───────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }

        Player player = event.getPlayer();
        SpawnerData linking = plugin.pendingLink(player);

        if (linking != null) {
            event.setCancelled(true);
            handleLinking(player, linking, block);
            return;
        }

        if (block.getType() != Material.SPAWNER) {
            return;
        }

        SpawnerData data = plugin.manager().resolve(block);
        if (data == null) {
            return;
        }

        event.setCancelled(true);

        if (!player.hasPermission("snspawners.use")) {
            plugin.messages().send(player, "no-permission",
                    Replacer.of("%permission%", "snspawners.use"));
            return;
        }

        if (plugin.config().ownerOnly && !player.hasPermission("snspawners.bypass")
                && !data.isOwner(player.getUniqueId())) {
            plugin.messages().send(player, "gui-not-owner");
            return;
        }

        plugin.openSpawnerMenu(player, data);
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        plugin.forgetPlayer(event.getPlayer().getUniqueId());
    }

    private void handleLinking(Player player, SpawnerData data, Block block) {
        if (player.isSneaking()) {
            plugin.cancelLinking(player);
            plugin.messages().send(player, "link-cancelled");
            return;
        }

        if (!plugin.config().allowedContainers.contains(block.getType())) {
            plugin.messages().send(player, "link-invalid-container",
                    Replacer.of("%allowed%", plugin.config().allowedContainerNames()));
            return;
        }

        BlockPos container = BlockPos.of(block);
        if (!container.world().equals(data.position().world())) {
            plugin.messages().send(player, "link-too-far", Replacer
                    .of("%distance%", "∞")
                    .with("%max%", plugin.config().maxLinkDistance));
            return;
        }

        long distanceSquared = container.distanceSquared(data.position());
        if (distanceSquared > plugin.config().maxLinkDistanceSquared) {
            plugin.messages().send(player, "link-too-far", Replacer
                    .of("%distance%", (long) Math.ceil(Math.sqrt(distanceSquared)))
                    .with("%max%", plugin.config().maxLinkDistance));
            return;
        }

        data.link(container);
        plugin.manager().persist(data);
        plugin.cancelLinking(player);

        plugin.messages().send(player, "link-success", Replacer
                .of("%x%", container.x())
                .with("%y%", container.y())
                .with("%z%", container.z()));
    }
}
