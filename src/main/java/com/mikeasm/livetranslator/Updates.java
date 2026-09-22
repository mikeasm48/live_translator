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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

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
    static final String FORMULA = "https://raw.githubusercontent.com/"
            + "mikeasm48/homebrew-tap/main/Formula/live-translator.rb";

    /**
     * Ровно тот адрес, за которым {@code brew update} ходит к нашему тапу.
     * <p>
     * Проверка версии и установка живут на разных хостах: версия читается с
     * raw.githubusercontent.com, а список формул и сам jar — с github.com.
     * Там, где github.com закрыт корпоративной сетью, приложение видит новую
     * версию и не может её поставить, и без этой проверки объяснить такое
     * человеку нечем.
     */
    static final String TAP_GIT =
            "https://github.com/mikeasm48/homebrew-tap/info/refs?service=git-upload-pack";

    /** Адрес самого файла — его качает Homebrew, и он тоже на github.com. */
    static String jarUrl(String version) {
        return "https://github.com/mikeasm48/live_translator/releases/download/v" + version
                + "/live-translator-" + version + ".jar";
    }

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

    /** Настройка, в которой лежит пропущенная версия. */
    private static final String SKIPPED = "LT_SKIP_VERSION";

    /**
     * Сроки шагов. Прежде на всё обновление был один срок в десять минут, и
     * это было равносильно его отсутствию: никто не смотрит на неподвижную
     * полоску десять минут, человек считает программу зависшей и выключает её.
     */
    private static final int LIST_SECONDS = 120;
    private static final int INSTALL_SECONDS = 480;

    /** Процесс, который идёт прямо сейчас: через него работает «Отменить». */
    private static volatile Process current;
    private static volatile boolean cancelled;

    public record Available(String version) {}

    private Updates() {}

    /**
     * Есть ли версия новее текущей.
     *
     * @return пусто, если обновления нет, проверка отключена или сеть молчит
     */
    public static Optional<Available> check(String current) {
        if (!Boolean.parseBoolean(setting("LT_CHECK_UPDATES", "true"))) return Optional.empty();
        return checkNow(current);
    }

    /**
     * То же самое, но не спрашивая разрешения у настроек.
     * <p>
     * Кнопка «проверить обновление» должна работать и у того, кто выключил
     * проверку при запуске: выключают её, чтобы не спрашивали, а не чтобы
     * нельзя было спросить самому.
     */
    public static Optional<Available> checkNow(String current) {
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

    /**
     * Версия, про которую при запуске больше не спрашиваем.
     * <p>
     * Обновление всегда не вовремя: встреча начинается через минуту, а
     * программа предлагает подождать. Отказ «не сейчас» спросит снова завтра, а
     * «пропустить» закрывает вопрос до следующего выпуска. Поставить
     * пропущенную версию всё равно можно — из настроек, когда время будет.
     */
    public static void skip(String version) {
        Settings.save(SKIPPED, version);
    }

    public static boolean skipped(String version) {
        return version.equals(Settings.get(SKIPPED).trim());
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
     * @param step сообщает, какой шаг идёт сейчас: без этого окно ожидания
     *             неотличимо от зависшего
     * @return пустая строка при успехе, иначе — что пошло не так
     */
    public static String install(String version, Consumer<String> step) {
        String brew = brew();
        if (brew == null) return "Homebrew не найден";
        cancelled = false;

        Diagnostics.recordUpdate("Обновление до " + version + " с " + Main.VERSION);

        // Проверка версии читается с одного хоста, а ставится всё с другого.
        // Если github.com закрыт, дальше идти незачем: brew будет молча
        // ждать сеть, а человек — смотреть на полоску.
        step.accept("Проверяю связь с GitHub…");
        String unreachable = unreachable();
        if (unreachable != null) return unreachable;

        step.accept("Обновляю список версий…");
        Shell.Result list = brewRun(brew, LIST_SECONDS, "update", "--quiet");
        if (cancelled) return "обновление прервано";
        // Неудача здесь ещё не приговор: список мог обновиться сам при
        // прошлой команде, и нужная версия уже известна brew. Поэтому идём
        // дальше, но запоминаем — если установка не удастся, причина
        // объяснится этим шагом.
        String listProblem = list.ok() ? null : problem(list, "обновить список версий");

        step.accept("Ставлю версию " + version + "…");
        Shell.Result upgrade = brewRun(brew, INSTALL_SECONDS, "upgrade", "live-translator");
        if (cancelled) return "обновление прервано";

        // Homebrew умеет завершиться успешно, ничего не поставив: так бывает,
        // когда список версий не обновился и он считает текущую последней.
        // Поэтому верим не коду возврата, а тому, что лежит на диске.
        Shell.Result installed = brewRun(brew, 60, "list", "--versions", "live-translator");
        boolean arrived = installed.output().contains(version);
        Diagnostics.recordUpdate("После установки brew видит: " + installed.output());
        if (arrived) return "";

        if (!upgrade.ok()) {
            String failure = problem(upgrade, "поставить новую версию");
            return listProblem == null ? failure : failure + "; до этого не удалось "
                    + listProblem;
        }
        return listProblem == null
                ? "Homebrew отчитался об успехе, но версия не сменилась"
                : "не удалось " + listProblem + ", и новая версия до Homebrew не дошла";
    }

    /** @return null, если GitHub отвечает, иначе — что сказать человеку */
    private static String unreachable() {
        try {
            HttpResponse<Void> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
                    .send(HttpRequest.newBuilder(URI.create(TAP_GIT))
                                    .timeout(Duration.ofSeconds(8))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.discarding());
            Diagnostics.recordUpdate("Связь с github.com: код " + response.statusCode());
            if (response.statusCode() / 100 == 5) {
                return "GitHub отвечает ошибкой " + response.statusCode()
                        + ". Похоже, это не у вас — попробуйте позже.";
            }
            return null;
        } catch (IOException | RuntimeException e) {
            Diagnostics.recordUpdate("Связь с github.com: " + e);
            return "github.com не отвечает из этой сети, а обновление скачивается оттуда.\n"
                    + "Обычно так ведёт себя рабочий VPN или сеть офиса.\n"
                    + "Попробуйте выключить VPN или обновиться из дома.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "проверка связи прервана";
        }
    }

    /** Прерывает установку: команда убивается, а вместе с ней и ожидание. */
    public static void cancel() {
        cancelled = true;
        Process running = current;
        if (running != null) running.destroyForcibly();
    }

    private static Shell.Result brewRun(String brew, int seconds, String... arguments) {
        String[] command = new String[arguments.length + 1];
        command[0] = brew;
        System.arraycopy(arguments, 0, command, 1, arguments.length);

        long started = System.currentTimeMillis();
        Shell.Result result = Shell.run(seconds, environment(brew), p -> current = p, command);
        current = null;

        Diagnostics.recordUpdate("$ brew " + String.join(" ", arguments)
                + "\n(код " + result.code() + (result.timedOut() ? ", не уложилась в срок" : "")
                + ", " + (System.currentTimeMillis() - started) / 1000 + " с)\n"
                + result.output());
        return result;
    }

    /**
     * Окружение, в котором Homebrew ни о чём не спросит.
     * <p>
     * Приложение запускается из «Программ», а не из терминала: спрашивать
     * некого, ответить некому, и любой вопрос превращается в вечное ожидание.
     * Здесь же задаётся PATH — тот, что достаётся приложению от системы,
     * бывает пустым, а brew зовёт git и curl по имени.
     */
    private static Map<String, String> environment(String brew) {
        Map<String, String> env = new LinkedHashMap<>();
        Path bin = Path.of(brew).getParent();
        env.put("PATH", bin + ":/usr/bin:/bin:/usr/sbin:/sbin");
        env.put("HOMEBREW_NO_AUTO_UPDATE", "1");
        env.put("HOMEBREW_NO_ANALYTICS", "1");
        env.put("HOMEBREW_NO_ENV_HINTS", "1");
        env.put("HOMEBREW_NO_INSTALL_CLEANUP", "1");
        env.put("HOMEBREW_NO_COLOR", "1");
        env.put("HOMEBREW_NO_EMOJI", "1");
        env.put("HOMEBREW_CURL_RETRIES", "1");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_ASKPASS", "/usr/bin/true");
        env.put("SSH_ASKPASS", "/usr/bin/true");
        return env;
    }

    private static String problem(Shell.Result result, String what) {
        if (result.timedOut()) return what + " — команда не уложилась в срок";
        String output = tail(result.output());
        return output.isBlank() ? what + " (код " + result.code() + ")" : what + ": " + output;
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

    static String brew() {
        for (String path : BREW_PATHS) {
            if (Files.isExecutable(Path.of(path))) return path;
        }
        return null;
    }

    private static String tail(String output) {
        String[] lines = output.strip().split("\\R");
        int from = Math.max(0, lines.length - 3);
        return String.join(" / ", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }

    private static String setting(String name, String fallback) {
        String value = Settings.get(name);
        return value.isBlank() ? fallback : value;
    }
}
