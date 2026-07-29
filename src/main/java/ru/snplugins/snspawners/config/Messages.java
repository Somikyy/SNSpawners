package ru.snplugins.snspawners.config;

import net.kyori.adventure.audience.Audience;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.Nullable;
import ru.snplugins.snspawners.util.Replacer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Реестр сообщений из {@code messages.yml}. */
public final class Messages {

    private final Map<String, Message> messages;
    private final String prefix;
    private final String itemName;
    private final List<String> itemLore;
    private final Logger logger;

    /** Ключи, о пропаже которых уже предупредили — чтобы не залить консоль. */
    private final Set<String> reportedMissing = new HashSet<>();

    public Messages(FileConfiguration yml, Logger logger) {
        this.logger = logger;
        this.prefix = yml.getString("prefix", "");

        Map<String, Message> parsed = new HashMap<>();
        ConfigurationSection section = yml.getConfigurationSection("messages");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    parsed.put(key, Message.read(section, key, prefix));
                } catch (RuntimeException error) {
                    logger.log(Level.WARNING, "Не удалось прочитать сообщение '" + key + "'", error);
                }
            }
        }
        this.messages = Map.copyOf(parsed);

        this.itemName = yml.getString("item.name", "%type% x%stack%");
        this.itemLore = List.copyOf(yml.getStringList("item.lore"));
    }

    public void send(Audience audience, String key) {
        send(audience, key, null);
    }

    public void send(Audience audience, String key, @Nullable Replacer replacer) {
        Message message = messages.get(key);
        if (message == null) {
            reportMissing(key);
            return;
        }
        message.send(audience, replacer);
    }

    public @Nullable Message get(String key) {
        return messages.get(key);
    }

    public String prefix() {
        return prefix;
    }

    public String itemName() {
        return itemName;
    }

    public List<String> itemLore() {
        return itemLore;
    }

    private void reportMissing(String key) {
        if (reportedMissing.add(key)) {
            logger.warning("В messages.yml нет ключа '" + key + "' — сообщение пропущено. "
                    + "Удали файл, чтобы пересоздать его с новыми ключами.");
        }
    }
}
