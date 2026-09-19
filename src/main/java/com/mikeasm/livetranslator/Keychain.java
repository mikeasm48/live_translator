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
     * Кладёт секрет в связку ключей. Значение передаётся через стандартный ввод
     * (дважды — утилита просит подтверждение), а не аргументом командной строки:
     * аргументы видны в списке процессов, ввод — нет.
     */
    public static void store(char[] secret) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("security", "add-generic-password",
                "-U",                                   // обновить, если запись уже есть
                "-a", System.getProperty("user.name", "user"),
                "-s", SERVICE,
                "-w")
                .redirectErrorStream(true)
                .start();

        try (OutputStream in = process.getOutputStream()) {
            byte[] bytes = new String(secret).getBytes(StandardCharsets.UTF_8);
            in.write(bytes);
            in.write('\n');
            in.write(bytes);
            in.write('\n');
            in.flush();
            java.util.Arrays.fill(bytes, (byte) 0);
        }

        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Связка ключей не ответила");
        }
        if (process.exitValue() != 0) {
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            throw new IOException("Связка ключей отклонила запись: " + output);
        }
    }
}
