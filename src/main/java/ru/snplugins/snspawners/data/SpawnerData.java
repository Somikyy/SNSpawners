package ru.snplugins.snspawners.data;

import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;
import ru.snplugins.snspawners.config.Config;
import ru.snplugins.snspawners.config.LootEntry;
import ru.snplugins.snspawners.config.SpawnerType;
import ru.snplugins.snspawners.config.UpgradeLevel;
import ru.snplugins.snspawners.util.Rng;

import java.util.List;
import java.util.UUID;

/**
 * Состояние одного стака спавнеров.
 *
 * <p>Производство ленивое: спавнер не «тикает», а помнит момент последнего
 * расчёта. Сколько бы времени ни прошло — час, сутки, месяц простоя сервера —
 * догон считается одной формулой и стоит одинаково.
 *
 * <p>Экземпляр живёт только пока загружен чанк. Данные сохраняются в PDC блока.
 */
public final class SpawnerData {

    private final BlockPos position;

    private SpawnerType type;
    private int stack;
    private int level;
    private @Nullable UUID owner;

    /** Момент, до которого производство уже посчитано (System.currentTimeMillis). */
    private long lastProduce;
    private long experience;

    /**
     * Виртуальный склад: количество по индексу записи в таблице лута типа.
     *
     * <p>Раньше здесь была {@code LinkedHashMap<String, Long>} — на спавнер это
     * сама карта, массив корзин, узел и упакованный {@link Long} на каждую
     * запись, около трёхсот байт против сорока у массива. При десятках тысяч
     * спавнеров разница измеряется десятками мегабайт, а каждый проход по
     * складу переставал быть хэшированием строк и распаковкой объектов.
     *
     * <p>Массив создаётся лениво: пустой склад не стоит ничего.
     */
    private long @Nullable [] storage;
    private long storedTotal;

    private @Nullable BlockPos link;
    private boolean autoSell;

    private boolean dirty;

    /** Индекс в кольцевом списке обхода, {@code -1} — не в обходе. */
    int sweepIndex = -1;

    /** Хэш последнего отрисованного текста голограммы — чтобы не слать лишние пакеты. */
    private long hologramHash;
    private long hologramUpdatedAt;
    private @Nullable UUID hologramEntity;

    /** Момент, раньше которого выгружать в сундук бессмысленно. */
    private long nextFlushAt;

    /** Момент последнего уведомления об автопродаже — антиспам чата. */
    private long lastSellNotify;

    /** Накопленная выручка автопродажи, ещё не показанная игроку. */
    private double pendingIncome;

    /** Подтверждение слома при непустом складе (одноразовое, только в памяти). */
    private long breakConfirmUntil;

    public SpawnerData(BlockPos position, SpawnerType type, int stack, int level,
                       @Nullable UUID owner, long lastProduce) {
        this.position = position;
        this.type = type;
        this.stack = stack;
        this.level = level;
        this.owner = owner;
        this.lastProduce = lastProduce;
    }

    // ── доступ ───────────────────────────────────────────────────────────────

    public BlockPos position() {
        return position;
    }

    public SpawnerType type() {
        return type;
    }

    /** Смена типа переносит склад по идентификаторам: индексы у типов свои. */
    public void type(SpawnerType type) {
        if (this.type == type) {
            return;
        }
        SpawnerType previous = this.type;
        long[] old = storage;
        this.type = type;
        storage = null;
        storedTotal = 0L;

        if (old != null) {
            for (int i = 0; i < old.length && i < previous.loot.size(); i++) {
                if (old[i] > 0L) {
                    add(type.lootIndex(previous.loot.get(i).id), old[i]);
                }
            }
        }
        markDirty();
    }

    public int stack() {
        return stack;
    }

    public void stack(int stack) {
        this.stack = Math.max(0, stack);
        markDirty();
    }

    public int level() {
        return level;
    }

    public void level(int level) {
        this.level = Math.max(1, level);
        markDirty();
    }

    public @Nullable UUID owner() {
        return owner;
    }

    public void owner(@Nullable UUID owner) {
        this.owner = owner;
        markDirty();
    }

    public boolean isOwner(UUID candidate) {
        return owner == null || owner.equals(candidate);
    }

    public long experience() {
        return experience;
    }

    public @Nullable BlockPos link() {
        return link;
    }

    public void link(@Nullable BlockPos link) {
        this.link = link;
        // Новый контейнер разбирается сразу, а не досиживает откат прежнего.
        this.nextFlushAt = 0L;
        markDirty();
    }

    public boolean autoSell() {
        return autoSell;
    }

    public void autoSell(boolean autoSell) {
        this.autoSell = autoSell;
        markDirty();
    }

    public long stored() {
        return storedTotal;
    }

    public boolean isStorageEmpty() {
        return storedTotal <= 0L && experience <= 0L;
    }

    /** Сколько лежит записи с этим индексом в таблице лута типа. */
    public long stored(int lootIndex) {
        long[] slots = storage;
        return slots == null || lootIndex < 0 || lootIndex >= slots.length ? 0L : slots[lootIndex];
    }

    public long stored(String lootId) {
        return stored(type.lootIndex(lootId));
    }

    /** Массив под запись, создаётся при первом поступлении лута. */
    private long[] slots() {
        long[] slots = storage;
        if (slots == null) {
            slots = new long[type.lootCount()];
            storage = slots;
        }
        return slots;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }

    void clearDirty() {
        this.dirty = false;
    }

    /** Участвует ли спавнер в обходе: выгрузка в сундук или автопродажа. */
    public boolean needsSweep() {
        return link != null || autoSell;
    }

    // ── служебное состояние в памяти (в PDC не пишется) ──────────────────────

    public long hologramHash() {
        return hologramHash;
    }

    public void hologramHash(long hologramHash) {
        this.hologramHash = hologramHash;
    }

    public long hologramUpdatedAt() {
        return hologramUpdatedAt;
    }

    public void hologramUpdatedAt(long hologramUpdatedAt) {
        this.hologramUpdatedAt = hologramUpdatedAt;
    }

    public @Nullable UUID hologramEntity() {
        return hologramEntity;
    }

    public void hologramEntity(@Nullable UUID hologramEntity) {
        this.hologramEntity = hologramEntity;
    }

    public long nextFlushAt() {
        return nextFlushAt;
    }

    public void nextFlushAt(long nextFlushAt) {
        this.nextFlushAt = nextFlushAt;
    }

    public long lastSellNotify() {
        return lastSellNotify;
    }

    public void lastSellNotify(long lastSellNotify) {
        this.lastSellNotify = lastSellNotify;
    }

    public double pendingIncome() {
        return pendingIncome;
    }

    public void addPendingIncome(double amount) {
        this.pendingIncome += amount;
    }

    public double takePendingIncome() {
        double taken = pendingIncome;
        pendingIncome = 0.0d;
        return taken;
    }

    public long breakConfirmUntil() {
        return breakConfirmUntil;
    }

    public void breakConfirmUntil(long breakConfirmUntil) {
        this.breakConfirmUntil = breakConfirmUntil;
    }

    // ── производство ─────────────────────────────────────────────────────────

    /**
     * Догоняет производство до текущего момента.
     *
     * @param ignoreCapacity игнорировать лимит склада — нужно для автопродажи,
     *                       где лут всё равно уходит в деньги сразу
     * @return сколько предметов добавлено на склад
     */
    public long produce(Config config, UpgradeLevel upgrade, long now, boolean ignoreCapacity) {
        long period = Math.max(50L, (long) (type.periodMillis / upgrade.speed()));

        if (lastProduce <= 0L) {
            lastProduce = now;
            markDirty();
            return 0L;
        }

        long elapsed = now - lastProduce;
        if (elapsed < period) {
            return 0L;
        }

        long capacity = upgrade.storage();
        boolean stopWhenFull = !ignoreCapacity && config.fullBehaviour == Config.FullBehaviour.STOP;

        if (stopWhenFull && storedTotal >= capacity) {
            // Склад забит — время простоя не копится, иначе после разгрузки
            // на игрока вывалился бы разом весь пропущенный период.
            lastProduce = now;
            markDirty();
            return 0L;
        }

        long maxCycles = Math.max(1L, config.maxOfflineMillis / period);
        long cycles = Math.min(elapsed / period, maxCycles);
        if (cycles <= 0L) {
            return 0L;
        }

        lastProduce = Math.min(now, lastProduce + cycles * period);

        long mobs = cycles * stack * type.perCycle;
        if (mobs <= 0L) {
            markDirty();
            return 0L;
        }

        int threshold = config.exactRollThreshold;
        double fortune = 1.0d + upgrade.fortune();
        long added = 0L;

        for (LootEntry entry : type.loot) {
            long hits = Rng.binomial(mobs, entry.chance, threshold);
            if (hits <= 0L) {
                continue;
            }
            long amount = Rng.sumUniform(hits, entry.minAmount, entry.maxAmount, threshold);
            if (entry.affectedByFortune && fortune > 1.0d) {
                amount = Math.round(amount * fortune);
            }
            if (amount <= 0L) {
                continue;
            }
            added += deposit(entry.index, amount, capacity, ignoreCapacity, config.fullBehaviour);
        }

        if (type.xpMax > 0) {
            experience += Rng.sumUniform(mobs, type.xpMin, type.xpMax, threshold);
        }

        markDirty();
        return added;
    }

    private long deposit(int lootIndex, long amount, long capacity,
                         boolean ignoreCapacity, Config.FullBehaviour behaviour) {
        if (ignoreCapacity) {
            return add(lootIndex, amount);
        }

        long free = capacity - storedTotal;

        if (free >= amount) {
            return add(lootIndex, amount);
        }

        switch (behaviour) {
            case STOP, VOID -> {
                if (free <= 0L) {
                    return 0L;
                }
                return add(lootIndex, free);
            }
            case REPLACE -> {
                long added = add(lootIndex, amount);
                evictCheapest(capacity, lootIndex);
                return added;
            }
            default -> {
                return 0L;
            }
        }
    }

    /** @return сколько реально легло на склад */
    private long add(int lootIndex, long amount) {
        if (lootIndex < 0 || amount <= 0L) {
            return 0L;
        }
        long[] slots = slots();
        if (lootIndex >= slots.length) {
            return 0L;
        }
        slots[lootIndex] += amount;
        storedTotal += amount;
        return amount;
    }

    /** Вытесняет самые дешёвые позиции, пока склад не влезет в лимит. */
    private void evictCheapest(long capacity, int protectedIndex) {
        if (storedTotal <= capacity) {
            return;
        }
        for (int index : type.cheapestFirst) {
            if (storedTotal <= capacity) {
                return;
            }
            if (index == protectedIndex) {
                continue;
            }
            long held = stored(index);
            if (held <= 0L) {
                continue;
            }
            take(index, Math.min(held, storedTotal - capacity));
        }

        if (storedTotal > capacity) {
            take(protectedIndex, storedTotal - capacity);
        }
    }

    // ── изъятие ──────────────────────────────────────────────────────────────

    /** Снимает со склада до {@code amount} штук, возвращает фактическое количество. */
    public long take(int lootIndex, long amount) {
        if (amount <= 0L) {
            return 0L;
        }
        long[] slots = storage;
        if (slots == null || lootIndex < 0 || lootIndex >= slots.length) {
            return 0L;
        }
        long held = slots[lootIndex];
        if (held <= 0L) {
            return 0L;
        }
        long taken = Math.min(held, amount);
        slots[lootIndex] = held - taken;
        storedTotal -= taken;
        markDirty();
        return taken;
    }

    public long take(String lootId, long amount) {
        return take(type.lootIndex(lootId), amount);
    }

    public void clearStorage() {
        storage = null;
        storedTotal = 0L;
        markDirty();
    }

    public long takeExperience() {
        long taken = experience;
        experience = 0L;
        markDirty();
        return taken;
    }

    /** Суммарная стоимость склада по ценам из {@code spawners.yml}. */
    public double value() {
        long[] slots = storage;
        if (slots == null) {
            return 0.0d;
        }
        List<LootEntry> loot = type.loot;
        double total = 0.0d;
        for (int i = 0; i < slots.length && i < loot.size(); i++) {
            if (slots[i] > 0L) {
                total += loot.get(i).price * slots[i];
            }
        }
        return total;
    }

    // ── сохранение ───────────────────────────────────────────────────────────

    public void save(PersistentDataContainer container, Keys keys) {
        container.set(keys.type, PersistentDataType.STRING, type.key);
        container.set(keys.stack, PersistentDataType.INTEGER, stack);
        container.set(keys.level, PersistentDataType.INTEGER, level);
        container.set(keys.lastProduce, PersistentDataType.LONG, lastProduce);
        container.set(keys.experience, PersistentDataType.LONG, experience);
        container.set(keys.storage, PersistentDataType.STRING, encodeStorage());
        container.set(keys.autoSell, PersistentDataType.BYTE, (byte) (autoSell ? 1 : 0));

        if (owner != null) {
            container.set(keys.owner, PersistentDataType.STRING, owner.toString());
        } else {
            container.remove(keys.owner);
        }

        if (link != null) {
            container.set(keys.link, PersistentDataType.STRING, link.encode());
        } else {
            container.remove(keys.link);
        }

        clearDirty();
    }

    public static @Nullable SpawnerData load(BlockPos position, PersistentDataContainer container,
                                             Keys keys, java.util.function.Function<String, SpawnerType> lookup) {
        String typeKey = container.get(keys.type, PersistentDataType.STRING);
        if (typeKey == null) {
            return null;
        }
        SpawnerType type = lookup.apply(typeKey);
        if (type == null) {
            return null;
        }

        Integer stack = container.get(keys.stack, PersistentDataType.INTEGER);
        Integer level = container.get(keys.level, PersistentDataType.INTEGER);
        Long last = container.get(keys.lastProduce, PersistentDataType.LONG);
        String ownerRaw = container.get(keys.owner, PersistentDataType.STRING);

        UUID owner = null;
        if (ownerRaw != null) {
            try {
                owner = UUID.fromString(ownerRaw);
            } catch (IllegalArgumentException ignored) {
                // Повреждённая запись — спавнер станет «ничей», это безопасно.
            }
        }

        SpawnerData data = new SpawnerData(
                position,
                type,
                stack == null ? 1 : Math.max(1, stack),
                level == null ? 1 : Math.max(1, level),
                owner,
                last == null ? System.currentTimeMillis() : last);

        Long experience = container.get(keys.experience, PersistentDataType.LONG);
        if (experience != null) {
            data.experience = Math.max(0L, experience);
        }

        data.decodeStorage(container.get(keys.storage, PersistentDataType.STRING));

        Byte sell = container.get(keys.autoSell, PersistentDataType.BYTE);
        data.autoSell = sell != null && sell != 0;

        data.link = BlockPos.decode(position.world(), container.get(keys.link, PersistentDataType.STRING));

        data.clearDirty();
        return data;
    }

    /**
     * Склад в одну строку: {@code ZOMBIE.iron=1523;ZOMBIE.flesh=90210}.
     *
     * <p>Ванильный дроп — это предметы без NBT, поэтому хранить нужно только
     * идентификатор записи и количество. Сериализовать настоящий инвентарь
     * (Base64 от {@code BukkitObjectOutputStream}) было бы в десятки раз
     * толще и заметно дороже при каждом сохранении.
     */
    private String encodeStorage() {
        long[] slots = storage;
        if (slots == null || storedTotal <= 0L) {
            return "";
        }
        List<LootEntry> loot = type.loot;
        StringBuilder out = new StringBuilder(slots.length * 24);
        for (int i = 0; i < slots.length && i < loot.size(); i++) {
            if (slots[i] <= 0L) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(';');
            }
            out.append(loot.get(i).id).append('=').append(slots[i]);
        }
        return out.toString();
    }

    private void decodeStorage(@Nullable String encoded) {
        storage = null;
        storedTotal = 0L;
        if (encoded == null || encoded.isEmpty()) {
            return;
        }
        // Разбор вручную, без split: загрузка чанка не должна плодить массивы
        // подстрок на каждый спавнер.
        int from = 0;
        while (from <= encoded.length()) {
            int end = encoded.indexOf(';', from);
            if (end < 0) {
                end = encoded.length();
            }
            int separator = encoded.lastIndexOf('=', end - 1);
            if (separator > from) {
                // Запись, пропавшая из конфига, молча выбрасывается: иначе GUI
                // показал бы предмет, который невозможно выдать.
                int index = type.lootIndex(encoded.substring(from, separator));
                if (index >= 0) {
                    try {
                        add(index, Long.parseLong(encoded, separator + 1, end, 10));
                    } catch (NumberFormatException ignored) {
                        // Повреждённая запись пропускается.
                    }
                }
            }
            from = end + 1;
        }
    }
}
