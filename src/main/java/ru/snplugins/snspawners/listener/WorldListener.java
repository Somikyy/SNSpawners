package ru.snplugins.snspawners.listener;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import ru.snplugins.snspawners.SNSpawners;
import ru.snplugins.snspawners.data.BlockPos;

import java.util.List;

/** Жизненный цикл чанков и защита блоков спавнеров. */
public final class WorldListener implements Listener {

    private final SNSpawners plugin;

    public WorldListener(SNSpawners plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.isNewChunk()) {
            plugin.manager().loadChunk(event.getChunk());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        plugin.manager().unloadChunk(event.getChunk());
    }

    /** Кэш мира в {@link BlockPos} не должен пережить сам мир. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        BlockPos.forgetWorlds();
    }

    /**
     * Последний рубеж против ванильного спавна.
     *
     * <p>Основная защита стоит в самом блоке (нулевой радиус активации,
     * нулевой лимит мобов), но состояние блока может переписать другой плагин
     * или команда {@code /data}. Отмена события гарантирует, что мобы не
     * появятся ни при каких обстоятельствах.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawnerSpawn(SpawnerSpawnEvent event) {
        if (event.getSpawner() == null) {
            return;
        }
        if (plugin.manager().at(event.getSpawner().getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        protectFromExplosion(event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        protectFromExplosion(event.blockList());
    }

    private void protectFromExplosion(List<Block> blocks) {
        if (!plugin.config().explosionProof) {
            return;
        }
        blocks.removeIf(block -> block.getType() == Material.SPAWNER
                && plugin.manager().at(block) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (plugin.config().pistonProof && containsSpawner(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (plugin.config().pistonProof && containsSpawner(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    private boolean containsSpawner(List<Block> blocks) {
        for (Block block : blocks) {
            if (block.getType() == Material.SPAWNER && plugin.manager().at(block) != null) {
                return true;
            }
        }
        return false;
    }
}
