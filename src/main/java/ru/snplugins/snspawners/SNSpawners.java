package ru.snplugins.snspawners;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;
import ru.snplugins.snspawners.command.MainCommand;
import ru.snplugins.snspawners.config.Config;
import ru.snplugins.snspawners.config.GuiConfig;
import ru.snplugins.snspawners.config.Messages;
import ru.snplugins.snspawners.config.TypeRegistry;
import ru.snplugins.snspawners.config.UpgradeLevel;
import ru.snplugins.snspawners.data.BlockPos;
import ru.snplugins.snspawners.data.Keys;
import ru.snplugins.snspawners.data.SpawnerData;
import ru.snplugins.snspawners.data.SpawnerManager;
import ru.snplugins.snspawners.gui.Menu;
import ru.snplugins.snspawners.gui.SpawnerMenu;
import ru.snplugins.snspawners.hologram.Holograms;
import ru.snplugins.snspawners.hook.Eco;
import ru.snplugins.snspawners.item.SpawnerItem;
import ru.snplugins.snspawners.listener.MenuListener;
import ru.snplugins.snspawners.listener.SpawnerListener;
import ru.snplugins.snspawners.listener.WorldListener;
import ru.snplugins.snspawners.service.Actions;
import ru.snplugins.snspawners.task.SweepTask;
import ru.snplugins.snspawners.util.Numbers;
import ru.snplugins.snspawners.util.Replacer;
import ru.snplugins.snspawners.util.SoundSpec;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SNSpawners — виртуальные стакающиеся спавнеры.
 *
 * <p>Идея плагина: спавнер на BoxPVP нужен ради лута, а не ради мобов.
 * Значит мобов можно не создавать вовсе — достаточно посчитать, что бы они
 * уронили. Исчезают сущности, их ИИ, поиск пути, предметы на земле и воронки
 * под фермой; остаётся один блок с числом и виртуальный склад.
 */
public final class SNSpawners extends JavaPlugin {

    private Config config;
    private Messages messages;
    private GuiConfig gui;
    private TypeRegistry types;

    private Keys keys;
    private SpawnerManager manager;
    private Holograms holograms;
    private Eco economy;
    private Actions actions;
    private SpawnerItem items;

    private SweepTask sweepTask;
    private BukkitTask sweepHandle;
    private BukkitTask saveHandle;

    /** Игроки в режиме выбора контейнера: игрок → позиция спавнера. */
    private final Map<UUID, BlockPos> pendingLinks = new HashMap<>();

    /** Кэш ников владельцев — {@code getOfflinePlayer} на каждый кадр GUI недопустим. */
    private final Map<UUID, String> ownerNames = new HashMap<>();

    // ── жизненный цикл ───────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        this.keys = new Keys(this);
        this.manager = new SpawnerManager(this);
        this.holograms = new Holograms(this);
        this.actions = new Actions(this);
        this.items = new SpawnerItem(this);
        this.sweepTask = new SweepTask(this);

        loadConfiguration();

        if (types.size() == 0) {
            getLogger().severe("Не загружено ни одного типа спавнера. Проверь spawners.yml.");
        }

        getServer().getPluginManager().registerEvents(new WorldListener(this), this);
        getServer().getPluginManager().registerEvents(new SpawnerListener(this), this);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);

        PluginCommand command = getCommand("snspawners");
        if (command != null) {
            MainCommand executor = new MainCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        manager.loadAllLoadedChunks();
        scheduleTasks();

        // Отчёт печатается на первом тике, а не здесь: плагины экономики
        // регистрируют свой сервис в собственном onEnable, и до конца загрузки
        // сервера состояние Vault ещё не окончательное. Заодно — уборка
        // голограмм, переживших прошлое выключение: обход стартует позже
        // (tick 40), так что бесхозные исчезнут раньше, чем появятся новые.
        getServer().getScheduler().runTask(this, () -> {
            int orphans = holograms.reconcile();
            if (orphans > 0) {
                getLogger().info("Удалено голограмм без спавнера: " + orphans + '.');
            }
            getLogger().info("SNSpawners запущен: типов — " + types.size()
                    + ", спавнеров в памяти — " + manager.loadedCount()
                    + ", экономика — " + economy.status() + '.');
        });

        if (config.updateCheck) {
            UpdateCheck.run(this);
        }
    }

    @Override
    public void onDisable() {
        cancelTasks();

        // Меню держат ссылки на объекты, которые сейчас исчезнут.
        closeAllMenus();

        if (manager != null) {
            int saved = manager.persistAll();
            if (saved > 0) {
                getLogger().info("Сохранено спавнеров: " + saved + '.');
            }
        }
        if (holograms != null) {
            holograms.removeAll();
        }
        if (manager != null) {
            manager.clear();
        }
        pendingLinks.clear();
        ownerNames.clear();
    }

    /** Полная перезагрузка: конфиги, типы, реестр спавнеров, задачи. */
    public void reload() {
        cancelTasks();
        closeAllMenus();
        pendingLinks.clear();
        ownerNames.clear();

        manager.persistAll();
        holograms.removeAll();
        manager.clear();

        loadConfiguration();

        manager.loadAllLoadedChunks();
        sweepTask.resetStats();
        scheduleTasks();
    }

    private void loadConfiguration() {
        saveDefaultConfig();
        reloadConfig();

        this.config = new Config(getConfig());
        this.messages = new Messages(loadYaml("messages.yml"), getLogger());
        this.gui = new GuiConfig(loadYaml("gui.yml"));
        this.types = new TypeRegistry(loadYaml("spawners.yml"), getLogger());
        this.economy = Eco.hook(this, config.moneyFormat, config.compactNumbers);
    }

    private FileConfiguration loadYaml(String name) {
        File file = new File(getDataFolder(), name);
        if (!file.exists()) {
            saveResource(name, false);
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    private void scheduleTasks() {
        this.sweepHandle = getServer().getScheduler()
                .runTaskTimer(this, sweepTask, 40L, config.sweepInterval);
        this.saveHandle = getServer().getScheduler()
                .runTaskTimer(this, () -> manager.persistBatch(config.maxSavesPerTick),
                        config.saveInterval, config.saveInterval);
    }

    private void cancelTasks() {
        if (sweepHandle != null) {
            sweepHandle.cancel();
            sweepHandle = null;
        }
        if (saveHandle != null) {
            saveHandle.cancel();
            saveHandle = null;
        }
    }

    private void closeAllMenus() {
        for (Player player : getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu) {
                player.closeInventory();
            }
        }
    }

    // ── компоненты ───────────────────────────────────────────────────────────

    public Config config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public GuiConfig gui() {
        return gui;
    }

    public TypeRegistry types() {
        return types;
    }

    public Keys keys() {
        return keys;
    }

    public SpawnerManager manager() {
        return manager;
    }

    public Holograms holograms() {
        return holograms;
    }

    public Eco economy() {
        return economy;
    }

    public Actions actions() {
        return actions;
    }

    public SpawnerItem items() {
        return items;
    }

    public SweepTask sweepTask() {
        return sweepTask;
    }

    // ── меню и привязка ──────────────────────────────────────────────────────

    public void openSpawnerMenu(Player player, SpawnerData data) {
        new SpawnerMenu(this, player, data).open();
    }

    public void beginLinking(Player player, SpawnerData data) {
        pendingLinks.put(player.getUniqueId(), data.position());
        messages.send(player, "link-start",
                Replacer.of("%distance%", config.maxLinkDistance));
    }

    public @Nullable SpawnerData pendingLink(Player player) {
        BlockPos position = pendingLinks.get(player.getUniqueId());
        return position == null ? null : manager.at(position);
    }

    public void cancelLinking(Player player) {
        pendingLinks.remove(player.getUniqueId());
    }

    public void forgetPlayer(UUID player) {
        pendingLinks.remove(player);
    }

    public void playSound(Player player, String key) {
        SoundSpec sound = config.sound(key);
        if (sound != null) {
            sound.play(player);
        }
    }

    // ── плейсхолдеры ─────────────────────────────────────────────────────────

    public Replacer placeholders(SpawnerData data) {
        return placeholders(data, null);
    }

    /** Единая таблица подстановок для меню, голограмм и сообщений. */
    public Replacer placeholders(SpawnerData data, @Nullable Player viewer) {
        UpgradeLevel current = types.level(data.level());
        int maxLevel = types.maxLevel();
        UpgradeLevel next = types.level(Math.min(data.level() + 1, maxLevel));

        long stored = data.stored();
        long capacity = current.storage();
        boolean hasEconomy = economy.isAvailable();
        String dash = "—";

        return Replacer
                .of("%type%", data.type().display)
                .with("%stack%", Numbers.grouped(data.stack()))
                .with("%owner%", ownerName(data.owner()))
                .with("%level%", data.level())
                .with("%max_level%", maxLevel)
                .with("%speed%", Numbers.compact(current.speed()))
                .with("%fortune%", Math.round(current.fortune() * 100.0d))
                .with("%next_speed%", Numbers.compact(next.speed()))
                .with("%next_fortune%", Math.round(next.fortune() * 100.0d))
                .with("%next_storage%", Numbers.compact(next.storage()))
                .with("%upgrade_cost%", data.level() >= maxLevel || !hasEconomy
                        ? dash : economy.format(next.cost()))
                .with("%storage%", Numbers.compact(stored))
                .with("%storage_max%", Numbers.compact(capacity))
                .with("%storage_percent%", capacity <= 0L ? 0 : Math.min(100L, stored * 100L / capacity))
                .with("%storage_bar%", gui.bar(stored, capacity))
                .with("%value%", hasEconomy ? economy.format(data.value()) : dash)
                .with("%xp%", Numbers.compact(data.experience()))
                .with("%rate%", Holograms.rate(data, current))
                .with("%autosell%", data.autoSell() ? gui.enabledText : gui.disabledText)
                .with("%link%", data.link() == null ? gui.noneText : data.link().toString())
                .with("%balance%", viewer != null && hasEconomy
                        ? economy.format(economy.balance(viewer)) : dash);
    }

    public String ownerName(@Nullable UUID owner) {
        if (owner == null) {
            return "—";
        }
        if (ownerNames.size() > 4096) {
            // Кэш не должен расти бесконечно на большом сервере.
            ownerNames.clear();
        }
        return ownerNames.computeIfAbsent(owner, id -> {
            Player online = Bukkit.getPlayer(id);
            if (online != null) {
                return online.getName();
            }
            String name = Bukkit.getOfflinePlayer(id).getName();
            return name == null ? id.toString().substring(0, 8) : name;
        });
    }
}
