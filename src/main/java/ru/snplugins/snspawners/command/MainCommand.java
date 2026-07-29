package ru.snplugins.snspawners.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.snplugins.snspawners.SNSpawners;
import ru.snplugins.snspawners.config.SpawnerType;
import ru.snplugins.snspawners.util.Inventories;
import ru.snplugins.snspawners.util.Numbers;
import ru.snplugins.snspawners.util.Replacer;
import ru.snplugins.snspawners.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code /snspawners} — выдача, статистика, перезагрузка. */
public final class MainCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("give", "info", "types", "reload", "help");

    private final SNSpawners plugin;

    public MainCommand(SNSpawners plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            plugin.messages().send(sender, "help");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> give(sender, args);
            case "info" -> info(sender);
            case "types" -> types(sender);
            case "reload" -> reload(sender);
            default -> plugin.messages().send(sender, "unknown-subcommand");
        }
        return true;
    }

    // ── give ─────────────────────────────────────────────────────────────────

    private void give(CommandSender sender, String[] args) {
        if (!checkPermission(sender, "snspawners.command.give")) {
            return;
        }
        if (args.length < 3) {
            plugin.messages().send(sender, "help");
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.messages().send(sender, "player-not-found", Replacer.of("%player%", args[1]));
            return;
        }

        SpawnerType type = plugin.types().byKey(args[2]);
        if (type == null) {
            plugin.messages().send(sender, "give-unknown-type", Replacer
                    .of("%type%", args[2])
                    .with("%types%", String.join(", ", plugin.types().keys())));
            return;
        }

        int amount = args.length > 3 ? Numbers.parseInt(args[3], -1) : 1;
        if (amount <= 0) {
            plugin.messages().send(sender, "invalid-number", Replacer.of("%input%", args[3]));
            return;
        }

        int stack = args.length > 4 ? Numbers.parseInt(args[4], -1) : 1;
        if (stack <= 0) {
            plugin.messages().send(sender, "invalid-number", Replacer.of("%input%", args[4]));
            return;
        }
        stack = Math.min(stack, plugin.config().maxStack);

        int given = 0;
        while (given < amount) {
            int batch = Math.min(64, amount - given);
            Inventories.giveOrDrop(target, plugin.items().create(type, stack, batch));
            given += batch;
        }

        plugin.messages().send(sender, "give-sender", Replacer
                .of("%player%", target.getName())
                .with("%type%", type.display)
                .with("%amount%", amount)
                .with("%stack%", stack));

        plugin.messages().send(target, "give-target", Replacer
                .of("%type%", type.display)
                .with("%amount%", amount)
                .with("%stack%", stack));
    }

    // ── info ─────────────────────────────────────────────────────────────────

    private void info(CommandSender sender) {
        if (!checkPermission(sender, "snspawners.command.info")) {
            return;
        }
        plugin.messages().send(sender, "info", Replacer
                .of("%types%", plugin.types().size())
                .with("%loaded%", Numbers.grouped(plugin.manager().loadedCount()))
                .with("%stack_total%", Numbers.grouped(plugin.manager().totalStack()))
                .with("%tracked%", Numbers.grouped(plugin.manager().sweepingCount()))
                .with("%avg_ms%", String.format(Locale.ROOT, "%.3f", plugin.sweepTask().averageMillis()))
                .with("%max_ms%", String.format(Locale.ROOT, "%.3f", plugin.sweepTask().peakMillis()))
                .with("%economy%", plugin.economy().status()));
    }

    private void types(CommandSender sender) {
        plugin.messages().send(sender, "types-list",
                Replacer.of("%types%", String.join(", ", plugin.types().keys())));
    }

    private void reload(CommandSender sender) {
        if (!checkPermission(sender, "snspawners.command.reload")) {
            return;
        }
        long started = System.nanoTime();
        try {
            plugin.reload();
            plugin.messages().send(sender, "reload-success", Replacer
                    .of("%ms%", String.format(Locale.ROOT, "%.1f", (System.nanoTime() - started) / 1_000_000.0d)));
        } catch (RuntimeException error) {
            plugin.getLogger().severe("Перезагрузка не удалась: " + error);
            plugin.messages().send(sender, "reload-failed",
                    Replacer.of("%error%", String.valueOf(error.getMessage())));
        }
    }

    private boolean checkPermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        plugin.messages().send(sender, "no-permission", Replacer.of("%permission%", permission));
        return false;
    }

    // ── автодополнение ───────────────────────────────────────────────────────

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, String[] args) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("give")) {
            return switch (args.length) {
                case 2 -> filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
                case 3 -> filter(plugin.types().keys(), args[2]);
                case 4 -> filter(List.of("1", "8", "16", "64"), args[3]);
                case 5 -> filter(List.of("1", "16", "64", "256"), args[4]);
                default -> List.of();
            };
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = Text.strip(prefix).toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }
}
