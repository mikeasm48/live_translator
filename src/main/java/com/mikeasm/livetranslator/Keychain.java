package com.mikeasm.livetranslator;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Хранение секрета в связке ключей macOS.
 * <p>
 * Ключ доступа не должен лежать в файле настроек открытым текстом. В {@code .env}
 * сохраняется только команда чтения, а сам секрет живёт в Keychain.
 */
public final class Keychain {

    /** Под этим именем запись видна в «Связке ключей». */
    public static final String SERVICE = "live-translator";

    /** Команда, которую приложение потом выполняет, чтобы получить ключ. */
    public static final String READ_COMMAND =
            "security find-generic-password -s " + SERVICE + " -w";

    private Keychain() {}

    public static boolean available() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    /**
     * Кладёт секрет в связку ключей.
     * <p>
     * Используется пакетный режим {@code security -i}: команда целиком подаётся
     * в стандартный ввод уже запущенного процесса. Два очевидных способа не
     * годятся. Передать ключ аргументом нельзя — аргументы видны всей системе
     * в списке процессов. Отдать его в ответ на запрос {@code -w} тоже нельзя:
     * при наличии управляющего терминала утилита спрашивает пароль напрямую у
     * него и поданный ввод игнорирует, из-за чего запуск замирал на приглашении
     * «password data for new item».
     */
    public static void store(char[] secret) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("security", "-i")
                .redirectErrorStream(true)
                .start();

        // Вывод вычитывается параллельно: иначе переполнение буфера подвесит
        // утилиту до того, как она успеет завершиться.
        StringBuilder output = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                output.append(new String(process.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // процесс закрылся раньше — читать больше нечего
            }
        });

        try (OutputStream in = process.getOutputStream()) {
            String command = "add-generic-password -U"
                    + " -a " + quote(System.getProperty("user.name", "user"))
                    + " -s " + quote(SERVICE)
                    + " -w " + quote(new String(secret))
                    + "\n";
            byte[] bytes = command.getBytes(StandardCharsets.UTF_8);
            in.write(bytes);
            in.flush();
            java.util.Arrays.fill(bytes, (byte) 0);
        }

        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Связка ключей не ответила");
        }
        reader.join(2000);
        if (process.exitValue() != 0) {
            throw new IOException("Связка ключей отклонила запись: " + output.toString().trim());
        }
    }

    /** Ключ может содержать что угодно, поэтому экранируем по правилам утилиты. */
    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
