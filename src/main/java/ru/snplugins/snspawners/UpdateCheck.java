package ru.snplugins.snspawners;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Проверка обновлений через GitHub Releases.
 *
 * <p>Полностью асинхронная и полностью необязательная: любая ошибка сети,
 * отсутствие релизов или недоступность GitHub гасятся молча. Плагин не должен
 * ни падать, ни спамить в консоль из-за проверки версии.
 */
final class UpdateCheck {

    private static final String API =
            "https://api.github.com/repos/ASNETplugins/SNSpawners/releases/latest";

    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");

    private UpdateCheck() {
    }

    static void run(SNSpawners plugin) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String latest = fetch();
            if (latest == null) {
                return;
            }
            String current = plugin.getPluginMeta().getVersion();
            if (latest.equalsIgnoreCase(current)) {
                return;
            }
            plugin.getLogger().info("Доступна версия " + latest + " (установлена " + current
                    + "): https://github.com/ASNETplugins/SNSpawners/releases");
        });
    }

    private static String fetch() {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {

            HttpRequest request = HttpRequest.newBuilder(URI.create(API))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "SNSpawners")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            Matcher matcher = TAG.matcher(response.body());
            return matcher.find() ? matcher.group(1) : null;

        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
