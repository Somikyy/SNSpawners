package ru.snplugins.snspawners.hologram;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import ru.snplugins.snspawners.SNSpawners;
import ru.snplugins.snspawners.config.Config;
import ru.snplugins.snspawners.config.UpgradeLevel;
import ru.snplugins.snspawners.data.BlockPos;
import ru.snplugins.snspawners.data.SpawnerData;
import ru.snplugins.snspawners.util.Numbers;
import ru.snplugins.snspawners.util.Replacer;
import ru.snplugins.snspawners.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Голограммы над спавнерами на базе {@link TextDisplay}.
 *
 * <p>Display-сущности не имеют ИИ, не тикают и не участвуют в физике — по
 * стоимости это ближе к пакету, чем к мобу. Дополнительно они помечаются
 * как непостоянные: в файл чанка не пишутся и при выгрузке исчезают.
 * Классические голограммы на стойках брони дают ровно обратный набор свойств.
 *
 * <p>Полагаться только на непостоянность нельзя. Ссылка на сущность живёт в
 * оперативной памяти, а состояние спавнера — в блоке, поэтому после рестарта
 * плагин про свои голограммы ничего не знает. Если сущность при этом уцелела
 * (краш, {@code kill -9}, выключение по таймауту, ручная перезагрузка плагина),
 * наивное создание повесит вторую голограмму поверх первой — и так на каждый
 * рестарт. Поэтому перед созданием голограмма подбирается по метке в PDC:
 * своя — переиспользуется, лишние дубли удаляются, бесхозные подчищаются на
 * старте в {@link #reconcile()}.
 *
 * <p>Текст пересобирается только когда содержимое реально изменилось —
 * сравнивается хэш, а не строки.
 */
public final class Holograms {

    private final SNSpawners plugin;

    public Holograms(SNSpawners plugin) {
        this.plugin = plugin;
    }

    /**
     * Создаёт голограмму, если её ещё нет, иначе обновляет по расписанию.
     *
     * <p>Вызывается обходом на каждый спавнер, поэтому дешёвая проверка
     * интервала стоит перед поиском сущности, а не после: {@code getEntity}
     * по UUID хоть и быстрый, но лишний вызов на каждом тике не нужен.
     */
    public void ensure(SpawnerData data, long now) {
        Config config = plugin.config();
        if (!config.hologramsEnabled || config.hologramLines.isEmpty()) {
            return;
        }
        if (data.hologramEntity() == null) {
            create(data);
            return;
        }
        if (now - data.hologramUpdatedAt() < config.hologramUpdateInterval * 50L) {
            return;
        }
        update(data, false);
    }

    /**
     * Привязывает к спавнеру голограмму: подбирает уцелевшую с прошлого запуска
     * либо создаёт новую.
     *
     * <p>Поиск идёт по метке в PDC, а не по координатам сущности: {@code offset-y}
     * мог измениться в конфиге между запусками, и старая голограмма висит не там,
     * где создавалась бы новая. Поэтому просматривается весь чанк, а совпадение
     * метки с позицией блока даёт точную привязку без ложных срабатываний.
     */
    public void create(SpawnerData data) {
        Config config = plugin.config();
        if (!config.hologramsEnabled) {
            return;
        }

        Location center = data.position().center();
        if (center == null || !data.position().isChunkLoaded()) {
            return;
        }
        center.add(0.0d, config.hologramOffsetY, 0.0d);

        List<TextDisplay> existing = findExisting(data, center.getWorld());

        // Строк нет — показывать нечего, но подобрать за собой всё равно нужно.
        if (config.hologramLines.isEmpty()) {
            for (TextDisplay stale : existing) {
                stale.remove();
            }
            return;
        }

        TextDisplay display;
        if (existing.isEmpty()) {
            display = center.getWorld().spawn(center, TextDisplay.class, spawned -> {
                apply(spawned, data, config);
                spawned.text(render(data));
            });
        } else {
            // Дубли могли накопиться за несколько рестартов подряд: оставляем
            // одну голограмму, остальные убираем.
            display = existing.get(0);
            for (int i = 1; i < existing.size(); i++) {
                existing.get(i).remove();
            }
            display.teleport(center);
            apply(display, data, config);
            display.text(render(data));
        }

        data.hologramEntity(display.getUniqueId());
        data.hologramHash(hash(data));
        data.hologramUpdatedAt(System.currentTimeMillis());
    }

    /** Настройки отображения. Применяются и к новой сущности, и к подобранной. */
    private void apply(TextDisplay display, SpawnerData data, Config config) {
        // Для подобранной сущности флаг переставляется заново: она пережила
        // рестарт именно потому, что когда-то была записана в файл чанка.
        display.setPersistent(false);
        display.setBillboard(Display.Billboard.CENTER);
        display.setViewRange(config.hologramViewRange);
        display.setShadowed(false);
        display.setSeeThrough(false);
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        display.setBackgroundColor(org.bukkit.Color.fromARGB(0));
        display.getPersistentDataContainer().set(
                plugin.keys().hologram, PersistentDataType.STRING, data.position().encode());
    }

    /**
     * Ищет уже существующие голограммы этого спавнера в его чанке.
     *
     * <p>Обход сущностей чанка стоит дороже точечного {@code getEntity} по UUID,
     * но выполняется один раз на спавнер — при первой привязке после загрузки
     * чанка, а не в обходе.
     */
    private List<TextDisplay> findExisting(SpawnerData data, World world) {
        BlockPos position = data.position();
        Chunk chunk = world.getChunkAt(position.x() >> 4, position.z() >> 4);
        String tag = position.encode();

        List<TextDisplay> found = new ArrayList<>(1);
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof TextDisplay display && display.isValid()
                    && tag.equals(display.getPersistentDataContainer()
                    .get(plugin.keys().hologram, PersistentDataType.STRING))) {
                found.add(display);
            }
        }
        return found;
    }

    /**
     * Обновляет текст, если он изменился.
     *
     * @param force пропустить проверку интервала (после действия игрока)
     */
    public void update(SpawnerData data, boolean force) {
        Config config = plugin.config();
        if (!config.hologramsEnabled) {
            return;
        }

        long now = System.currentTimeMillis();
        if (!force && now - data.hologramUpdatedAt() < config.hologramUpdateInterval * 50L) {
            return;
        }

        long hash = hash(data);
        if (hash == data.hologramHash() && !force) {
            data.hologramUpdatedAt(now);
            return;
        }

        TextDisplay display = resolve(data);
        if (display == null) {
            return;
        }
        display.text(render(data));
        data.hologramHash(hash);
        data.hologramUpdatedAt(now);
    }

    public void remove(SpawnerData data) {
        TextDisplay display = resolve(data);
        if (display != null) {
            display.remove();
        }
        data.hologramEntity(null);
    }

    public void removeAll() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(TextDisplay.class)) {
                if (entity.getPersistentDataContainer().has(plugin.keys().hologram, PersistentDataType.STRING)) {
                    entity.remove();
                }
            }
        }
    }

    /**
     * Подчищает голограммы, оставшиеся с прошлого запуска без хозяина.
     *
     * <p>Вызывается один раз на старте, уже после регистрации спавнеров из
     * загруженных чанков. Голограммы над живыми спавнерами не трогаются —
     * их подберёт {@link #create(SpawnerData)}; удаляются только те, чей
     * спавнер сломали, и все подряд, если голограммы выключены в конфиге.
     *
     * @return сколько сущностей удалено
     */
    public int reconcile() {
        Config config = plugin.config();
        boolean enabled = config.hologramsEnabled && !config.hologramLines.isEmpty();
        int removed = 0;

        for (World world : Bukkit.getWorlds()) {
            for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
                String tag = display.getPersistentDataContainer()
                        .get(plugin.keys().hologram, PersistentDataType.STRING);
                if (tag == null) {
                    continue;
                }
                BlockPos position = BlockPos.decode(world.getUID(), tag);
                if (enabled && position != null && plugin.manager().at(position) != null) {
                    // Сущность уцелела, значит когда-то попала в файл чанка.
                    display.setPersistent(false);
                    continue;
                }
                display.remove();
                removed++;
            }
        }
        return removed;
    }

    private TextDisplay resolve(SpawnerData data) {
        UUID id = data.hologramEntity();
        if (id == null) {
            return null;
        }
        Entity entity = Bukkit.getEntity(id);
        if (entity instanceof TextDisplay display && display.isValid()) {
            return display;
        }
        data.hologramEntity(null);
        return null;
    }

    private Component render(SpawnerData data) {
        Replacer replacer = plugin.placeholders(data);
        List<Component> lines = new ArrayList<>(plugin.config().hologramLines.size());
        for (String raw : plugin.config().hologramLines) {
            lines.add(Text.parse(raw, replacer));
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    /**
     * Дешёвая свёртка видимого состояния. Пересобирать компоненты и сравнивать
     * строки на каждом проходе дороже, чем сложить четыре числа.
     */
    private long hash(SpawnerData data) {
        UpgradeLevel upgrade = plugin.types().level(data.level());
        long value = data.stack();
        value = value * 31L + data.level();
        // Склад округляем: дёргать голограмму на каждую единицу лута незачем.
        value = value * 31L + data.stored() / Math.max(1L, upgrade.storage() / 100L);
        return value;
    }

    /** Оценка выхода в час — используется и голограммой, и меню. */
    public static String rate(SpawnerData data, UpgradeLevel upgrade) {
        return Numbers.compact(data.type().itemsPerHour(data.stack(), upgrade.speed(), upgrade.fortune()));
    }
}
