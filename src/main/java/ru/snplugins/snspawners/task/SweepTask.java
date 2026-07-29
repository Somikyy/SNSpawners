package ru.snplugins.snspawners.task;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.snplugins.snspawners.SNSpawners;
import ru.snplugins.snspawners.config.Config;
import ru.snplugins.snspawners.config.UpgradeLevel;
import ru.snplugins.snspawners.data.SpawnerData;
import ru.snplugins.snspawners.util.Numbers;
import ru.snplugins.snspawners.util.Replacer;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Кольцевой обход загруженных спавнеров.
 *
 * <p>Стоимость одного запуска ограничена сверху параметром
 * {@code performance.max-per-tick} и не зависит от того, сколько спавнеров
 * на сервере — тысяча или сто тысяч. Растёт лишь время полного круга.
 *
 * <p>Сам расчёт производства здесь не «навёрстывается по тикам»: он ленивый
 * и опирается на системные часы, поэтому пропущенный запуск ничего не теряет.
 */
public final class SweepTask implements Runnable, Consumer<SpawnerData> {

    private final SNSpawners plugin;

    private long runs;
    private long totalNanos;
    private long peakNanos;

    /**
     * Конфиг и время текущего прохода.
     *
     * <p>Задача сама выступает получателем спавнеров вместо лямбды: лямбда
     * захватывала бы эти два значения и создавала объект на каждый тик.
     * Ровно на время одного вызова {@code run()}, всегда в основном потоке.
     */
    private Config config;
    private long now;

    public SweepTask(SNSpawners plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        long started = System.nanoTime();
        this.config = plugin.config();
        this.now = System.currentTimeMillis();

        plugin.manager().sweepBatch(config.maxPerTick, this);

        long elapsed = System.nanoTime() - started;
        runs++;
        totalNanos += elapsed;
        if (elapsed > peakNanos) {
            peakNanos = elapsed;
        }
    }

    @Override
    public void accept(SpawnerData data) {
        process(data, config, now);
    }

    private void process(SpawnerData data, Config config, long now) {
        if (data.needsSweep()) {
            UpgradeLevel upgrade = plugin.types().level(data.level());
            data.produce(config, upgrade, now, data.autoSell());

            if (data.autoSell()) {
                double income = plugin.actions().autoSell(data);
                if (income > 0.0d) {
                    data.addPendingIncome(income);
                }
                notifyIncome(data, config, now);
            } else {
                plugin.actions().flush(data, now);
                notifyFull(data, upgrade, config, now);
            }
        }
        plugin.holograms().ensure(data, now);
    }

    private void notifyIncome(SpawnerData data, Config config, long now) {
        if (data.pendingIncome() <= 0.0d || now - data.lastSellNotify() < config.notifyCooldownMillis) {
            return;
        }
        Player owner = onlineOwner(data);
        if (owner == null) {
            return;
        }
        data.lastSellNotify(now);
        plugin.messages().send(owner, "autosell-income",
                Replacer.of("%money%", plugin.economy().format(data.takePendingIncome())));
    }

    private void notifyFull(SpawnerData data, UpgradeLevel upgrade, Config config, long now) {
        if (config.fullBehaviour != Config.FullBehaviour.STOP || data.stored() < upgrade.storage()) {
            return;
        }
        if (now - data.lastSellNotify() < config.notifyCooldownMillis) {
            return;
        }
        Player owner = onlineOwner(data);
        if (owner == null) {
            return;
        }
        data.lastSellNotify(now);
        plugin.messages().send(owner, "storage-full", Replacer
                .of("%storage%", Numbers.compact(data.stored()))
                .with("%max%", Numbers.compact(upgrade.storage())));
    }

    private Player onlineOwner(SpawnerData data) {
        UUID owner = data.owner();
        return owner == null ? null : Bukkit.getPlayer(owner);
    }

    // ── статистика для /snsp info ────────────────────────────────────────────

    public double averageMillis() {
        return runs == 0L ? 0.0d : totalNanos / (double) runs / 1_000_000.0d;
    }

    public double peakMillis() {
        return peakNanos / 1_000_000.0d;
    }

    public void resetStats() {
        runs = 0L;
        totalNanos = 0L;
        peakNanos = 0L;
    }
}
