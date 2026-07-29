package ru.snplugins.snspawners.hook;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import ru.snplugins.snspawners.util.Numbers;

/**
 * Мягкая привязка к Vault.
 *
 * <p>Плагин обязан полностью работать без экономики: если провайдера нет,
 * кнопки продажи и улучшений просто исчезают из меню, а всё остальное
 * продолжает работать.
 *
 * <p>Провайдер ищется <b>лениво</b>, а не один раз при включении. Vault сам по
 * себе денег не хранит — их регистрирует сервисом плагин экономики
 * (EssentialsX, CMI, XConomy), и делает это в своём {@code onEnable}. Если
 * SNSpawners включился раньше, разовый поиск вернул бы пусто навсегда, и
 * экономика «отвалилась» бы на ровном месте. Поэтому результат кэшируется
 * только когда он найден.
 */
public final class Eco {

    private final JavaPlugin plugin;
    private final String format;
    private final boolean compact;

    private @Nullable Economy economy;

    private Eco(JavaPlugin plugin, String format, boolean compact) {
        this.plugin = plugin;
        this.format = format;
        this.compact = compact;
    }

    public static Eco hook(JavaPlugin plugin, String format, boolean compact) {
        return new Eco(plugin, format, compact);
    }

    private @Nullable Economy provider() {
        if (economy != null) {
            return economy;
        }
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            return null;
        }
        try {
            RegisteredServiceProvider<Economy> registration =
                    plugin.getServer().getServicesManager().getRegistration(Economy.class);
            economy = registration == null ? null : registration.getProvider();
        } catch (NoClassDefFoundError | RuntimeException ignored) {
            // Vault есть, но классов экономики нет — работаем без денег.
            economy = null;
        }
        return economy;
    }

    public boolean isAvailable() {
        return provider() != null;
    }

    /** Человекочитаемое состояние — для лога при запуске и для {@code /snsp info}. */
    public String status() {
        Economy found = provider();
        if (found != null) {
            return found.getName();
        }
        return plugin.getServer().getPluginManager().getPlugin("Vault") == null
                ? "нет (Vault не установлен)"
                : "нет (Vault есть, но плагин экономики не найден)";
    }

    public double balance(OfflinePlayer player) {
        Economy found = provider();
        return found == null ? 0.0d : found.getBalance(player);
    }

    public boolean has(OfflinePlayer player, double amount) {
        Economy found = provider();
        return found != null && found.has(player, amount);
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        Economy found = provider();
        if (found == null || amount <= 0.0d) {
            return false;
        }
        return found.withdrawPlayer(player, amount).transactionSuccess();
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        Economy found = provider();
        if (found == null || amount <= 0.0d) {
            return false;
        }
        return found.depositPlayer(player, amount).transactionSuccess();
    }

    public String format(double amount) {
        return Numbers.format(amount, format, compact);
    }
}
