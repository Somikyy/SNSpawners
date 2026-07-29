package ru.snplugins.snspawners.data;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Координата блока без ссылки на {@link World}.
 *
 * <p>Хранить {@link Location} в долгоживущих коллекциях нельзя: он изменяемый
 * и держит жёсткую ссылку на мир, мешая выгрузке. Здесь только UUID мира
 * и три целых — запись дешёвая, хэш-код стабильный.
 */
public record BlockPos(UUID world, int x, int y, int z) {

    /**
     * Последний найденный мир.
     *
     * <p>{@code Bukkit.getWorld(UUID)} — линейный проход по списку миров со
     * сравнением UUID, а обход дёргает его на каждый спавнер каждый тик.
     * Спавнеры в списке лежат в порядке загрузки чанков, поэтому подряд идущие
     * почти всегда из одного мира, и попадание в кэш близко к стопроцентному.
     *
     * <p>Поле одно и проверяется по UUID самого мира — рассогласованной пары
     * не бывает даже при обращении из другого потока. Ссылка на выгруженный мир
     * сбрасывается слушателем {@code WorldUnloadEvent}.
     */
    private static World cachedWorld;

    public static BlockPos of(Block block) {
        return new BlockPos(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    public static BlockPos of(Location location) {
        World world = location.getWorld();
        return new BlockPos(world == null ? new UUID(0, 0) : world.getUID(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public ChunkPos chunk() {
        return new ChunkPos(world, x >> 4, z >> 4);
    }

    public @Nullable World bukkitWorld() {
        World cached = cachedWorld;
        if (cached != null && cached.getUID().equals(world)) {
            return cached;
        }
        World resolved = Bukkit.getWorld(world);
        cachedWorld = resolved;
        return resolved;
    }

    /** Сбрасывает кэш мира: держать ссылку на выгруженный мир нельзя. */
    public static void forgetWorlds() {
        cachedWorld = null;
    }

    public @Nullable Location toLocation() {
        World bukkit = bukkitWorld();
        return bukkit == null ? null : new Location(bukkit, x, y, z);
    }

    public @Nullable Location center() {
        World bukkit = bukkitWorld();
        return bukkit == null ? null : new Location(bukkit, x + 0.5d, y + 0.5d, z + 0.5d);
    }

    public @Nullable Block block() {
        World bukkit = bukkitWorld();
        return bukkit == null ? null : bukkit.getBlockAt(x, y, z);
    }

    public boolean isChunkLoaded() {
        World bukkit = bukkitWorld();
        return bukkit != null && bukkit.isChunkLoaded(x >> 4, z >> 4);
    }

    public long distanceSquared(BlockPos other) {
        long dx = (long) x - other.x;
        long dy = (long) y - other.y;
        long dz = (long) z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Компактная запись для PDC — мир не сохраняется, он и так известен из блока. */
    public String encode() {
        return x + "," + y + "," + z;
    }

    public static @Nullable BlockPos decode(UUID world, @Nullable String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] parts = encoded.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new BlockPos(world,
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Override
    public String toString() {
        return x + ", " + y + ", " + z;
    }
}
