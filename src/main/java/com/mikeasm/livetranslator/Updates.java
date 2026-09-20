package com.mikeasm.livetranslator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Проверка и установка обновлений.
 * <p>
 * Приложением пользуются не из терминала, а двойным щелчком из «Программ».
 * Просьба «выполните brew upgrade» для такого человека равносильна отсутствию
 * обновления: он до него не доберётся. Поэтому приложение спрашивает само и
 * ставит само, а терминал остаётся способом для тех, кому он привычен.
 */
public final class Updates {

    /**
     * Версия берётся из формулы Homebrew, а не из GitHub API, и на то две
     * причины.
     * <p>
     * У API лимит в шестьдесят запросов в час на адрес. В офисе за общим
     * выходом его исчерпают чужие запросы, и обновление молча перестанет
     * предлагаться — хуже, чем не иметь проверки вовсе, потому что на неё
     * рассчитывают.
     * <p>
     * И формула честнее отвечает на настоящий вопрос. Нас интересует не «вышел
     * ли релиз», а «есть ли версия, которую сейчас поставит brew». Если релиз
     * выпущен, а формула ещё не обновлена, обещанное обновление не состоится.
     */
    private static final String FORMULA = "https://raw.githubusercontent.com/"
            + "mikeasm48/homebrew-tap/main/Formula/live-translator.rb";

    private static final java.util.regex.Pattern VERSION_LINE =
            java.util.regex.Pattern.compile("(?m)^\\s*version\\s+\"([^\"]+)\"");

    /**
     * Проверка не должна задерживать запуск. Если сеть капризничает — а в
     * поездке и за корпоративным VPN это обычное дело, — молча работаем дальше.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(6);

    /** Где искать Homebrew: на Apple Silicon и на Intel пути разные. */
    private static final String[] BREW_PATHS = {
            "/opt/homebrew/bin/brew", "/usr/local/bin/brew",
    };

    public record Available(String version) {}

    private Updates() {}

    /**
     * Есть ли версия новее текущей.
     *
     * @return пусто, если обновления нет, проверка отключена или сеть молчит
     */
    public static Optional<Available> check(String current) {
        if (!Boolean.parseBoolean(setting("LT_CHECK_UPDATES", "true"))) return Optional.empty();
        // В разработке обновляться неоткуда, а без Homebrew — нечем.
        if (AppPaths.development() || brew() == null) return Optional.empty();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(FORMULA))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) return Optional.empty();

            java.util.regex.Matcher line = VERSION_LINE.matcher(response.body());
            if (!line.find()) return Optional.empty();
            String latest = line.group(1);
            return newer(latest, current) ? Optional.of(new Available(latest)) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            // Нет сети, закрыт GitHub, изменился формат ответа — ни одно из
            // этого не повод не дать человеку работать.
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** Сравнение вида 0.4.10 против 0.4.9: по числам, а не по алфавиту. */
    static boolean newer(String candidate, String current) {
        int[] left = parse(candidate);
        int[] right = parse(current);
        if (left.length == 0) return false;
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int a = i < left.length ? left[i] : 0;
            int b = i < right.length ? right[i] : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    private static int[] parse(String version) {
        String[] parts = version.trim().split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i].replaceAll("\\D.*$", ""));
            } catch (NumberFormatException e) {
                return new int[0];
            }
        }
        return numbers;
    }

    /**
     * Ставит новую версию через Homebrew.
     *
     * @return пустая строка при успехе, иначе — что пошло не так
     */
    public static String install() {
        String brew = brew();
        if (brew == null) return "Homebrew не найден";
        String update = run(brew, "update", "--quiet");
        if (update != null) return update;
        String upgrade = run(brew, "upgrade", "live-translator");
        return upgrade == null ? "" : upgrade;
    }

    /** @return null при успехе, иначе хвост вывода */
    private static String run(String brew, String... arguments) {
        try {
            List<String> command = new ArrayList<>();
            command.add(brew);
            command.addAll(Arrays.asList(arguments));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

            StringBuilder output = new StringBuilder();
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    output.append(new String(process.getInputStream().readAllBytes(),
                            StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // процесс закрылся раньше — читать больше нечего
                }
            });
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return "обновление не уложилось в десять минут";
            }
            reader.join(2000);
            return process.exitValue() == 0 ? null : tail(output.toString());
        } catch (IOException e) {
            return e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "обновление прервано";
        }
    }

    /**
     * Перезапускает приложение.
     * <p>
     * Запуск отложен: новая копия должна стартовать после того, как эта отпустит
     * окно и звуковую линию.
     */
    public static void relaunch() {
        try {
            new ProcessBuilder("/bin/sh", "-c", "sleep 2; open -a 'Live Translator'").start();
        } catch (IOException e) {
            System.err.println("Не удалось перезапустить: " + e.getMessage());
        }
    }

    private static String brew() {
        for (String path : BREW_PATHS) {
            if (Files.isExecutable(Path.of(path))) return path;
        }
        return null;
    }

    private static String tail(String output) {
        String[] lines = output.strip().split("\\R");
        int from = Math.max(0, lines.length - 3);
        return String.join(" / ", Arrays.copyOfRange(lines, from, lines.length));
    }

    private static String setting(String name, String fallback) {
        String value = Settings.get(name);
        return value.isBlank() ? fallback : value;
    }
}
