package com.mikeasm.livetranslator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Постоянные настройки: ключ и каталог не нужно задавать в каждой сессии терминала.
 * <p>
 * Значение ищется в таком порядке:
 * <ol>
 *   <li>переменная окружения — если она задана, она главнее всего;</li>
 *   <li>{@code ./.env} рядом с проектом;</li>
 *   <li>{@code ~/.config/live-translator/config} — общий для всех копий;</li>
 *   <li>команда из {@code <ИМЯ>_CMD} — её вывод становится значением.</li>
 * </ol>
 * Последний вариант нужен, чтобы секрет не лежал на диске открытым: в macOS
 * его можно держать в Keychain и получать через {@code security}.
 */
public final class Settings {

    private static final Path PROJECT_FILE = Path.of(".env");
    /** Куда пишутся изменения: рядом с исходниками или в настройки пользователя. */
    private static final Path WRITE_FILE = AppPaths.settingsFile();
    private static final Path USER_FILE =
            Path.of(System.getProperty("user.home"), ".config", "live-translator", "config");

    /** Значения из файлов: ключ — имя настройки, значение — пара «что» и «откуда». */
    private static final Map<String, String> values = new LinkedHashMap<>();
    private static final Map<String, String> origins = new LinkedHashMap<>();

    static {
        // Проектный файл читается первым и побеждает пользовательский.
        readFile(PROJECT_FILE);
        readFile(USER_FILE);
    }

    private Settings() {}

    /** Файл настроек проекта. */
    public static Path envPath() {
        return WRITE_FILE;
    }

    /**
     * Сохраняет значение в {@code ./.env}, сохраняя комментарии и порядок строк.
     * Существующая строка переписывается, новая дописывается в конец.
     */
    public static synchronized void save(String name, String value) {
        try {
            if (WRITE_FILE.getParent() != null) Files.createDirectories(WRITE_FILE.getParent());
            List<String> lines = Files.isRegularFile(WRITE_FILE)
                    ? new java.util.ArrayList<>(Files.readAllLines(WRITE_FILE, StandardCharsets.UTF_8))
                    : new java.util.ArrayList<>();

            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.startsWith("#")) continue;
                if (line.startsWith("export ")) line = line.substring("export ".length()).trim();
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                if (line.substring(0, eq).trim().equals(name)) {
                    lines.set(i, name + "=" + value);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) lines.add(name + "=" + value);

            Files.write(WRITE_FILE, lines, StandardCharsets.UTF_8);
            restrictPermissions(WRITE_FILE);
            values.put(name, value);
            origins.put(name, WRITE_FILE.toString());
        } catch (IOException e) {
            System.err.println("Не удалось сохранить настройку " + name + ": " + e.getMessage());
        }
    }

    /** Файл может хранить идентификаторы и пути — чужим он не нужен. */
    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException ignored) {
            // не POSIX-система — не страшно
        }
    }

    /** Значение задано где-либо? */
    public static boolean has(String name) {
        return !get(name).isBlank();
    }

    /** Значение настройки или пустая строка. */
    public static String get(String name) {
        String fromEnv = System.getenv(name);
        if (fromEnv != null && !fromEnv.isBlank()) {
            // Именно put, а не putIfAbsent: источник из файла записан при чтении
            // файлов, но переменная окружения главнее и должна это вытеснить.
            origins.put(name, "переменная окружения");
            return fromEnv.trim();
        }
        String fromFile = values.get(name);
        if (fromFile != null && !fromFile.isBlank()) return fromFile;

        String command = resolveCommand(name + "_CMD");
        if (command != null && !command.isBlank()) {
            String output = run(command);
            if (!output.isBlank()) {
                origins.put(name, "команда " + name + "_CMD");
                return output;
            }
        }
        return "";
    }

    /** Откуда взято значение — для диагностики, без раскрытия самого значения. */
    public static String origin(String name) {
        return origins.getOrDefault(name, "не задано");
    }

    private static String resolveCommand(String name) {
        String fromEnv = System.getenv(name);
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv.trim();
        return values.get(name);
    }

    /**
     * Столько ждём вывода команды. Запас большой намеренно: при первом обращении
     * к Keychain macOS показывает диалог доступа, и человеку нужно время нажать
     * «Разрешить».
     */
    private static final int COMMAND_TIMEOUT_SECONDS = 90;

    private static String run(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("/bin/sh", "-c", command).start();

            // Вывод читается отдельным потоком: readAllBytes() блокируется до
            // закрытия потока, поэтому в основном потоке таймаут не сработал бы.
            StringBuilder collected = new StringBuilder();
            Process running = process;
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    collected.append(new String(running.getInputStream().readAllBytes(),
                            StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // процесс убит по таймауту — вывод нам уже не нужен
                }
            });

            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                reader.join(1000);
                System.err.println("Команда не ответила за " + COMMAND_TIMEOUT_SECONDS
                        + " с и была прервана: " + command);
                return "";
            }
            reader.join(1000);

            if (process.exitValue() != 0) {
                System.err.println("Команда завершилась с кодом " + process.exitValue()
                        + ": " + command);
                return "";
            }
            return collected.toString().trim();
        } catch (IOException e) {
            System.err.println("Не удалось выполнить команду: " + e.getMessage());
            return "";
        } catch (InterruptedException e) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            return "";
        }
    }

    private static void readFile(Path path) {
        if (!Files.isRegularFile(path)) return;
        warnIfReadableByOthers(path);
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("export ")) line = line.substring("export ".length()).trim();

                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String name = line.substring(0, eq).trim();
                String value = unquote(line.substring(eq + 1).trim());
                if (name.isEmpty() || value.isEmpty()) continue;
                // Первый файл в порядке чтения выигрывает.
                if (values.putIfAbsent(name, value) == null) {
                    origins.put(name, path.toString());
                }
            }
        } catch (IOException e) {
            System.err.println("Не удалось прочитать " + path + ": " + e.getMessage());
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** Файл с ключом не должен читаться кем попало. */
    private static void warnIfReadableByOthers(Path path) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            boolean open = permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_READ);
            if (open) {
                System.err.println("Внимание: " + path + " доступен на чтение другим "
                        + "пользователям. Исправить: chmod 600 " + path);
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // не POSIX-система или файл исчез — предупреждение необязательное
        }
    }
}
