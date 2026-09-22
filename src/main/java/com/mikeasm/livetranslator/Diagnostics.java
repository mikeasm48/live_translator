package com.mikeasm.livetranslator;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Сбор сведений о сбое и отправка их разработчику.
 * <p>
 * У приложения есть пользователь, который не открывает терминал — и правильно
 * делает, это не его работа. Значит, всё, что обычно просят прислать словами
 * («выполните такую команду и покажите вывод»), приложение должно собрать само
 * и положить в письмо. Иначе любая поломка на чужой машине остаётся
 * неисследованной: человек видит замершее окно, а разработчик — ничего.
 * <p>
 * Письмо только открывается, никогда не отправляется само: что уходит с его
 * почты, решает человек. В журналах лежат тексты переводов, и это его встречи.
 */
public final class Diagnostics {

    private static final String MAIL_TO = "mikeasm48@gmail.com";

    /** Журнал консоли не должен съесть диск: выше этого файл начинается заново. */
    private static final long CONSOLE_LIMIT = 8L * 1024 * 1024;
    private static final long UPDATE_LIMIT = 1024 * 1024;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm");

    private static final Object LOCK = new Object();
    private static OutputStream consoleSink;
    private static long consoleWritten;
    private static Path consoleFile;
    /** Разбор команды терминалу: 0 — обычный текст, 1 — после ESC, 2 — внутри команды. */
    private static int escape;

    private Diagnostics() {}

    /**
     * Дублирует вывод консоли в файл.
     * <p>
     * Запущенное из «Программ» приложение пишет в никуда: окна терминала нет, и
     * всё, что оно рассказывает о себе, пропадает. Ровно эти строки и нужны,
     * когда потом разбираешься, почему на чужой машине не сработало.
     */
    public static void captureConsole() {
        synchronized (LOCK) {
            if (consoleSink != null) return;
            try {
                Path dir = AppPaths.supportLogsDir();
                Files.createDirectories(dir);
                Path file = dir.resolve("console.log");
                if (Files.exists(file) && Files.size(file) > CONSOLE_LIMIT) {
                    Files.move(file, dir.resolve("console-1.log"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                consoleFile = file;
                consoleWritten = Files.exists(file) ? Files.size(file) : 0;
                consoleSink = Files.newOutputStream(file, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                return;
            }
        }
        System.setOut(tee(System.out));
        System.setErr(tee(System.err));
        // Журнал складывается из многих запусков, поэтому у каждого — заголовок.
        System.out.println();
        System.out.println("==== " + LocalDateTime.now().format(STAMP)
                + "  Live Translator " + Main.VERSION + " ====");
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            System.err.println("Неперехваченная ошибка в потоке " + thread.getName() + ":");
            error.printStackTrace();
        });
    }

    public static Path consoleFile() {
        return consoleFile;
    }

    public static Path updateLog() {
        return AppPaths.supportLogsDir().resolve("update.log");
    }

    /** Ход обновления пишется отдельно: его читают целиком и редко. */
    public static void recordUpdate(String text) {
        synchronized (LOCK) {
            try {
                Path file = updateLog();
                Files.createDirectories(file.getParent());
                if (Files.exists(file) && Files.size(file) > UPDATE_LIMIT) Files.delete(file);
                Files.writeString(file,
                        LocalDateTime.now().format(STAMP) + "  " + text + "\n\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // Диагностика не должна ломать то, что диагностирует.
            }
        }
    }

    /**
     * Складывает журналы и сведения о машине в один файл на рабочем столе.
     *
     * @param reason что случилось — попадёт первой строкой
     */
    public static Path collect(String reason, Consumer<String> step) throws IOException {
        step.accept("Проверяю сеть…");
        String summary = summary(reason);

        step.accept("Собираю журналы…");
        // Имена внутри архива и у него самого — латиницей. Кириллические
        // читает Finder, но консольный unzip на них спотыкается («illegal byte
        // sequence»), а разбирать архив будут как раз в консоли.
        Path zip = desktop().resolve("live-translator-diagnostics-"
                + LocalDateTime.now().format(FILE_STAMP) + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip),
                StandardCharsets.UTF_8)) {
            write(out, "summary.txt", summary.getBytes(StandardCharsets.UTF_8));
            copy(out, updateLog(), "update.log");
            Path dir = AppPaths.supportLogsDir();
            copy(out, dir.resolve("console.log"), "console.log");
            copy(out, dir.resolve("console-1.log"), "console-previous.log");
            write(out, "settings.txt", settings().getBytes(StandardCharsets.UTF_8));
        }
        return zip;
    }

    /**
     * Открывает письмо с уже вложенным файлом.
     *
     * @return что теперь делать человеку — это показывается ему в окне
     */
    public static String openLetter(Path zip) {
        String subject = "Live Translator " + Main.VERSION + ": диагностика";

        if (appleMailHandlesLetters()
                && attachViaMail(zip, subject, "Здравствуйте! Во вложении журналы Live Translator "
                        + Main.VERSION + ".\n")) {
            return "Письмо открыто в «Почте», файл уже вложен.\n"
                    + "Осталось нажать «Отправить».";
        }

        // Без «Почты» вложить файл нечем: ссылка mailto вложений не передаёт.
        // Тогда письмо и файл показываются рядом, и остаётся перетащить.
        Shell.run(10, "/usr/bin/open", "-R", zip.toString());
        Shell.run(10, "/usr/bin/open", mailto(subject,
                "Здравствуйте! Журналы Live Translator " + Main.VERSION + " — в файле "
                        + zip.getFileName() + " с рабочего стола.\n"));
        return "Файл лежит на рабочем столе: " + zip.getFileName() + "\n\n"
                + "Открылись письмо и папка с файлом — перетащите файл в письмо\n"
                + "и нажмите «Отправить». Адрес уже подставлен.";
    }

    /**
     * Отдаёт ли система письма «Почте».
     * <p>
     * Вложить файл прямо в письмо умеет только она: у неё есть словарь
     * AppleScript, в котором это выражается, а ссылка {@code mailto} вложений
     * не передаёт вовсе.
     * <p>
     * Судить о «Почте» по каталогу {@code ~/Library/Mail} нельзя: macOS
     * закрывает его без «Полного доступа к диску», и приложение видит пустоту
     * вместо настроенных ящиков — именно на этом письмо однажды ушло без
     * вложения. Спрашивать саму «Почту» тоже нельзя: вопрос её запустит.
     * Поэтому смотрим, кому система отдаёт ссылки mailto.
     */
    private static boolean appleMailHandlesLetters() {
        Shell.Result handlers = Shell.run(15, "/usr/bin/defaults", "read",
                "com.apple.LaunchServices/com.apple.launchservices.secure", "LSHandlers");
        // Переопределений нет вовсе — письма открывает «Почта».
        if (!handlers.ok() || !handlers.output().contains("mailto")) return true;

        java.util.regex.Matcher handler = MAILTO_HANDLER.matcher(handlers.output());
        if (!handler.find()) return false;
        return handler.group(1).equalsIgnoreCase("com.apple.mail");
    }

    private static final java.util.regex.Pattern MAILTO_HANDLER =
            java.util.regex.Pattern.compile(
                    "LSHandlerRoleAll\\s*=\\s*\"?([^\";]+)\"?;[^{}]*"
                            + "LSHandlerURLScheme\\s*=\\s*mailto;");

    private static boolean attachViaMail(Path zip, String subject, String body) {
        String script = """
                tell application "Mail"
                  set letter to make new outgoing message with properties \
                {subject:"%s", content:"%s", visible:true}
                  tell letter
                    make new to recipient at end of to recipients \
                with properties {address:"%s"}
                    tell content to make new attachment \
                with properties {file name:POSIX file "%s"} at after the last paragraph
                  end tell
                  activate
                end tell
                """.formatted(escape(subject), escape(body), MAIL_TO, escape(zip.toString()));
        // Срок большой: система может спросить разрешение на управление
        // «Почтой», и человеку надо дать время ответить.
        Shell.Result result = Shell.run(180, "/usr/bin/osascript", "-e", script);
        if (!result.ok()) recordUpdate("Письмо через «Почту» не открылось: " + result.output());
        return result.ok();
    }

    private static String mailto(String subject, String body) {
        return "mailto:" + MAIL_TO + "?subject=" + encode(subject) + "&body=" + encode(body);
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static Path desktop() throws IOException {
        Path desktop = Path.of(System.getProperty("user.home"), "Desktop");
        if (Files.isDirectory(desktop)) return desktop;
        Path home = Path.of(System.getProperty("user.home"));
        if (Files.isDirectory(home)) return home;
        return Files.createTempDirectory("live-translator");
    }

    /** Сведения о машине и сети — то, что иначе пришлось бы выспрашивать. */
    private static String summary(String reason) {
        List<String> lines = new ArrayList<>();
        lines.add("Live Translator " + Main.VERSION);
        lines.add("Что случилось: " + reason);
        lines.add("Когда: " + LocalDateTime.now().format(STAMP));
        lines.add("");
        lines.add("macOS: " + firstLine(Shell.run(20, "/usr/bin/sw_vers", "-productVersion")));
        lines.add("Сборка macOS: " + firstLine(Shell.run(20, "/usr/bin/sw_vers", "-buildVersion")));
        lines.add("Процессор: " + System.getProperty("os.arch"));
        lines.add("Java: " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")");
        lines.add("PATH приложения: " + System.getenv("PATH"));
        lines.add("");

        String brew = Updates.brew();
        lines.add("Homebrew: " + (brew == null ? "НЕ НАЙДЕН" : brew));
        if (brew != null) {
            lines.add("Установлено по мнению Homebrew: "
                    + firstLine(Shell.run(60, brew, "list", "--versions", "live-translator")));
        }
        lines.add("Значок: " + Path.of(System.getProperty("user.home"),
                "Applications", "Live Translator.app"));
        lines.add("");

        lines.add("Имена (DNS):");
        for (String host : new String[]{"raw.githubusercontent.com", "github.com",
                "objects.githubusercontent.com", "generativelanguage.googleapis.com"}) {
            lines.add("  " + name(host));
        }
        lines.add("");
        lines.add("Сеть:");
        lines.add("  " + probe("проверка версии", Updates.FORMULA, "GET"));
        lines.add("  " + probe("список формул brew", Updates.TAP_GIT, "GET"));
        // Файл версии качать незачем — важно лишь, что он отдаётся.
        lines.add("  " + probe("файл новой версии", Updates.jarUrl(Main.VERSION), "HEAD"));
        lines.add("  " + probe("Gemini", "https://generativelanguage.googleapis.com/", "HEAD"));
        return String.join("\n", lines) + "\n";
    }

    private static String name(String host) {
        long started = System.currentTimeMillis();
        try {
            return host + " → " + InetAddress.getByName(host).getHostAddress()
                    + " (" + (System.currentTimeMillis() - started) + " мс)";
        } catch (UnknownHostException e) {
            return host + " → НЕ РАЗРЕШАЕТСЯ (" + (System.currentTimeMillis() - started) + " мс)";
        }
    }

    private static String probe(String label, String url, String method) {
        long started = System.currentTimeMillis();
        try {
            HttpResponse<Void> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
                    .send(HttpRequest.newBuilder(URI.create(url))
                                    .timeout(Duration.ofSeconds(8))
                                    .method(method, HttpRequest.BodyPublishers.noBody())
                                    .build(),
                            HttpResponse.BodyHandlers.discarding());
            return label + ": код " + response.statusCode()
                    + " за " + (System.currentTimeMillis() - started) + " мс";
        } catch (IOException | RuntimeException e) {
            return label + ": НЕ ОТВЕЧАЕТ — " + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + " (" + (System.currentTimeMillis() - started) + " мс)";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return label + ": проверка прервана";
        }
    }

    /**
     * Настройки без секретов.
     * <p>
     * Ключ Gemini лежит в связке ключей, а не здесь, но в файле остаются ключи
     * от прежних движков. Их место — не в почте.
     */
    private static String settings() {
        Path file = AppPaths.settingsFile();
        if (!Files.isRegularFile(file)) return "Файла настроек нет: " + file + "\n";
        try {
            List<String> out = new ArrayList<>();
            out.add("# " + file);
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                out.add(hideSecret(line));
            }
            return String.join("\n", out) + "\n";
        } catch (IOException e) {
            return "Настройки прочитать не удалось: " + e.getMessage() + "\n";
        }
    }

    private static String hideSecret(String line) {
        int equals = line.indexOf('=');
        if (equals <= 0 || line.strip().startsWith("#")) return line;
        String name = line.substring(0, equals).strip();
        // Строки вида YC_API_KEY_CMD — это команда получения ключа, а не сам
        // ключ; она как раз и объясняет, откуда приложение его берёт.
        if (name.endsWith("_CMD")) return line;
        if (!name.matches("(?i).*(KEY|TOKEN|SECRET|PASSWORD).*")) return line;
        int length = line.length() - equals - 1;
        return name + "=(скрыто, " + length + " символов)";
    }

    private static String firstLine(Shell.Result result) {
        String output = result.output();
        if (output.isBlank()) return "нет ответа (код " + result.code() + ")";
        return output.lines().findFirst().orElse("").strip();
    }

    private static void copy(ZipOutputStream out, Path file, String name) throws IOException {
        if (!Files.isRegularFile(file)) return;
        write(out, name, Files.readAllBytes(file));
    }

    private static void write(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    private static PrintStream tee(PrintStream console) {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] bytes, int from, int length) throws IOException {
                console.write(bytes, from, length);
                synchronized (LOCK) {
                    if (consoleSink == null || consoleWritten > CONSOLE_LIMIT) return;
                    toFile(bytes, from, length);
                }
            }

            @Override
            public void flush() throws IOException {
                console.flush();
                synchronized (LOCK) {
                    if (consoleSink != null) consoleSink.flush();
                }
            }
        }, true, StandardCharsets.UTF_8);
    }

    /**
     * Пишет в файл всё, кроме команд терминалу.
     * <p>
     * Строка с текущей фразой перерисовывается поверх себя, и для этого в поток
     * уходит «стереть строку». На экране это невидимо, а в файле остаётся
     * мусором вроде {@code [2K} посреди текста.
     */
    private static void toFile(byte[] bytes, int from, int length) throws IOException {
        int plainFrom = from;
        for (int i = from; i < from + length; i++) {
            int b = bytes[i] & 0xFF;
            if (escape == 0) {
                if (b == 0x1B) {
                    save(bytes, plainFrom, i - plainFrom);
                    escape = 1;
                }
            } else if (escape == 1) {
                // За ESC либо «[» и дальше команда, либо одиночный знак.
                escape = b == '[' ? 2 : 0;
                if (escape == 0) plainFrom = i + 1;
            } else if (b >= 0x40 && b <= 0x7E) {
                escape = 0;
                plainFrom = i + 1;
            }
        }
        if (escape == 0) save(bytes, plainFrom, from + length - plainFrom);
    }

    private static void save(byte[] bytes, int from, int length) throws IOException {
        if (length <= 0) return;
        consoleSink.write(bytes, from, length);
        consoleWritten += length;
    }
}
