package ru.snplugins.snspawners.data;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.jetbrains.annotations.Nullable;
import ru.snplugins.snspawners.SNSpawners;
import ru.snplugins.snspawners.config.SpawnerType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Реестр спавнеров, живущих в загруженных чанках.
 *
 * <p>Обход построен на плоском {@link ArrayList} с кольцевым курсором, а не на
 * итераторе карты: список последовательно лежит в памяти, а удаление стоит
 * O(1) — элемент меняется местами с последним. Это позволяет держать жёсткий
 * бюджет «N спавнеров за тик» независимо от их общего количества.
 */
public final class SpawnerManager {

    private final SNSpawners plugin;

    private final Map<BlockPos, SpawnerData> byPosition = new HashMap<>();
    private final Map<ChunkPos, List<SpawnerData>> byChunk = new HashMap<>();

    /** Плоский список всех загруженных спавнеров — основа обхода. */
    private final List<SpawnerData> loaded = new ArrayList<>();

    private int sweepCursor;
    private int saveCursor;

    public SpawnerManager(SNSpawners plugin) {
        this.plugin = plugin;
    }

    // ── доступ ───────────────────────────────────────────────────────────────

    public @Nullable SpawnerData at(BlockPos position) {
        return byPosition.get(position);
    }

    public @Nullable SpawnerData at(Block block) {
        return byPosition.get(BlockPos.of(block));
    }

    public int loadedCount() {
        return loaded.size();
    }

    public long totalStack() {
        long total = 0L;
        for (SpawnerData data : loaded) {
            total += data.stack();
        }
        return total;
    }

    public int sweepingCount() {
        int count = 0;
        for (SpawnerData data : loaded) {
            if (data.needsSweep()) {
                count++;
            }
        }
        return count;
    }

    public List<SpawnerData> loaded() {
        return loaded;
    }

    /** Спавнеры одного чанка; пустой список, если их там нет. */
    public List<SpawnerData> inChunk(ChunkPos chunk) {
        List<SpawnerData> inChunk = byChunk.get(chunk);
        return inChunk == null ? List.of() : inChunk;
    }

    // ── регистрация ──────────────────────────────────────────────────────────

    public void register(SpawnerData data) {
        BlockPos position = data.position();
        SpawnerData previous = byPosition.put(position, data);
        List<SpawnerData> inChunk = byChunk.computeIfAbsent(position.chunk(), key -> new ArrayList<>(4));
        if (previous != null) {
            detach(previous);
            inChunk.remove(previous);
        }
        inChunk.add(data);
        data.sweepIndex = loaded.size();
        loaded.add(data);
    }

    public void unregister(BlockPos position) {
        SpawnerData data = byPosition.remove(position);
        if (data != null) {
            detach(data);
            List<SpawnerData> inChunk = byChunk.get(position.chunk());
            if (inChunk != null) {
                inChunk.remove(data);
                if (inChunk.isEmpty()) {
                    byChunk.remove(position.chunk());
                }
            }
            plugin.holograms().remove(data);
        }
    }

    /** Убирает из списка обхода за O(1), не сдвигая хвост. */
    private void detach(SpawnerData data) {
        int index = data.sweepIndex;
        if (index < 0 || index >= loaded.size() || loaded.get(index) != data) {
            loaded.remove(data);
            data.sweepIndex = -1;
            return;
        }
        int last = loaded.size() - 1;
        SpawnerData moved = loaded.get(last);
        loaded.set(index, moved);
        moved.sweepIndex = index;
        loaded.remove(last);
        data.sweepIndex = -1;
    }

    // ── создание и адаптация ─────────────────────────────────────────────────

    /**
     * Готовит блок под управление плагином.
     *
     * <p>Ванильная логика спавна глушится тремя независимыми способами:
     * нулевой радиус активации, нулевой лимит мобов рядом и запредельная
     * задержка. Отмена {@code SpawnerSpawnEvent} — четвёртый рубеж на случай
     * плагинов, переписывающих состояние блока.
     */
    public void suppressVanilla(CreatureSpawner spawner, SpawnerType type) {
        spawner.setSpawnedType(type.entityType);
        spawner.setRequiredPlayerRange(0);
        spawner.setMaxNearbyEntities(0);
        spawner.setSpawnCount(0);
        spawner.setDelay(Integer.MAX_VALUE);
    }

    /**
     * Создаёт запись для блока. Блок уже должен быть {@link Material#SPAWNER}.
     */
    public @Nullable SpawnerData create(Block block, SpawnerType type, int stack,
                                        int level, @Nullable UUID owner) {
        if (!(block.getState(false) instanceof CreatureSpawner spawner)) {
            return null;
        }

        SpawnerData data = new SpawnerData(BlockPos.of(block), type, stack, level,
                owner, System.currentTimeMillis());

        suppressVanilla(spawner, type);
        data.save(spawner.getPersistentDataContainer(), plugin.keys());
        spawner.update(true, false);

        register(data);
        plugin.holograms().create(data);
        return data;
    }

    /**
     * Находит запись для блока, при необходимости подхватывая ванильный
     * спавнер из подземелья (если это разрешено конфигом).
     */
    public @Nullable SpawnerData resolve(Block block) {
        if (block.getType() != Material.SPAWNER) {
            return null;
        }
        SpawnerData known = at(block);
        if (known != null) {
            return known;
        }
        if (!plugin.config().adoptNaturalSpawners) {
            return null;
        }
        if (!(block.getState(false) instanceof CreatureSpawner spawner)) {
            return null;
        }
        SpawnerType type = plugin.types().byEntity(spawner.getSpawnedType());
        if (type == null) {
            return null;
        }
        return create(block, type, 1, 1, null);
    }

    // ── чанки ────────────────────────────────────────────────────────────────

    public void loadChunk(Chunk chunk) {
        ChunkPos key = ChunkPos.of(chunk);
        if (byChunk.containsKey(key)) {
            return;
        }

        // useSnapshot = false: работаем с живыми состояниями, без копирования.
        Collection<BlockState> states = chunk.getTileEntities(
                block -> block.getType() == Material.SPAWNER, false);
        if (states.isEmpty()) {
            return;
        }

        Keys keys = plugin.keys();
        for (BlockState state : states) {
            if (!(state instanceof CreatureSpawner spawner)) {
                continue;
            }
            BlockPos position = new BlockPos(chunk.getWorld().getUID(),
                    state.getX(), state.getY(), state.getZ());
            SpawnerData data = SpawnerData.load(position, spawner.getPersistentDataContainer(),
                    keys, name -> plugin.types().byKey(name));
            if (data == null) {
                continue;
            }
            register(data);
            // Голограмму создаёт обход: спавнить сущности прямо в обработчике
            // загрузки чанка нельзя — это рекурсивная загрузка соседей.
        }
    }

    public void unloadChunk(Chunk chunk) {
        List<SpawnerData> inChunk = byChunk.remove(ChunkPos.of(chunk));
        if (inChunk == null) {
            return;
        }
        for (SpawnerData data : inChunk) {
            if (data.isDirty()) {
                persist(data);
            }
            plugin.holograms().remove(data);
            byPosition.remove(data.position());
            detach(data);
        }
    }

    public void loadAllLoadedChunks() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                loadChunk(chunk);
            }
        }
    }

    // ── сохранение ───────────────────────────────────────────────────────────

    /** Записывает состояние в блок. Дорого — только для помеченных как изменённые. */
    public boolean persist(SpawnerData data) {
        Block block = data.position().block();
        if (block == null || block.getType() != Material.SPAWNER) {
            return false;
        }
        if (!(block.getState(false) instanceof CreatureSpawner spawner)) {
            return false;
        }
        data.save(spawner.getPersistentDataContainer(), plugin.keys());
        spawner.update(true, false);
        return true;
    }

    /**
     * Сохраняет порцию изменённых спавнеров, продолжая с прошлого места.
     *
     * @return сколько записей реально ушло в блоки
     */
    public int persistBatch(int budget) {
        if (loaded.isEmpty()) {
            return 0;
        }
        int saved = 0;
        int scanned = 0;
        int size = loaded.size();

        while (scanned < size && saved < budget) {
            if (saveCursor >= loaded.size()) {
                saveCursor = 0;
            }
            SpawnerData data = loaded.get(saveCursor++);
            scanned++;
            if (data.isDirty() && persist(data)) {
                saved++;
            }
        }
        return saved;
    }

    public int persistAll() {
        int saved = 0;
        for (SpawnerData data : loaded) {
            if (data.isDirty() && persist(data)) {
                saved++;
            }
        }
        return saved;
    }

    // ── обход ────────────────────────────────────────────────────────────────

    /**
     * Отдаёт следующую порцию спавнеров кольцевого обхода.
     *
     * <p>Курсор общий между вызовами, поэтому за несколько тиков список
     * проходится ровно один раз, а стоимость одного тика ограничена сверху.
     */
    public void sweepBatch(int budget, Consumer<SpawnerData> action) {
        int size = loaded.size();
        if (size == 0) {
            return;
        }
        int count = Math.min(budget, size);
        for (int i = 0; i < count; i++) {
            // action может снять спавнер с учёта (блок исчез, чанк выгрузился),
            // поэтому размер перепроверяется на каждой итерации.
            if (loaded.isEmpty()) {
                return;
            }
            if (sweepCursor >= loaded.size()) {
                sweepCursor = 0;
            }
            action.accept(loaded.get(sweepCursor++));
        }
    }

    public void clear() {
        byPosition.clear();
        byChunk.clear();
        for (SpawnerData data : loaded) {
            data.sweepIndex = -1;
        }
        loaded.clear();
        sweepCursor = 0;
        saveCursor = 0;
    }
}
