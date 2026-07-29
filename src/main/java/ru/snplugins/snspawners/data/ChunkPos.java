package ru.snplugins.snspawners.data;

import org.bukkit.Chunk;

import java.util.UUID;

/** Ключ чанка — по нему спавнеры пачкой поднимаются в память и выгружаются из неё. */
public record ChunkPos(UUID world, int x, int z) {

    public static ChunkPos of(Chunk chunk) {
        return new ChunkPos(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
    }
}
