package com.mikeasm.livetranslator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Сжатие звука перед отправкой в облако.
 * <p>
 * На счёт это не влияет: секунда звука стоит 32 токена в любом формате. Влияет
 * на скорость. Десять секунд сырого звука — это 417 килобайт запроса против
 * шестидесяти сжатого, и на домашнем канале разница выходит около четырёх
 * десятых секунды из наших двух с небольшим.
 * <p>
 * Само по себе это немного. Важнее другое: на гостиничном wi-fi в мегабит те же
 * 417 килобайт уезжают три секунды, то есть дольше, чем копится следующий
 * кусок. Переводчик начнёт отставать и накапливать отставание, пока не потеряет
 * встречу совсем. Сжатый кусок такого не заметит.
 * <p>
 * Битрейт выбран замером: на 32 кбит/с расшифровка неотличима от сырой, на 16
 * начинает врать — «вернёмся к консоли» превращается в «к консулу».
 */
public final class AudioCodec {

    /** Что отправляем в облако и чем это объявлено. */
    public record Payload(byte[] data, String mimeType) {}

    /** Дольше этого сжатие идти не должно: кусок всего в десять секунд. */
    private static final int TIMEOUT_SECONDS = 10;

    /** Сколько раз подряд не удалось сжать, прежде чем перестать пытаться. */
    private static final int GIVE_UP_AFTER = 3;

    private static volatile int failures;

    private AudioCodec() {}

    /**
     * Готовит кусок к отправке. При любой неудаче возвращает исходный WAV:
     * перевод важнее экономии, и остаться без звука из-за кодировщика нельзя.
     */
    public static Payload forUpload(byte[] wav, Config config) {
        if (!config.compressAudio() || failures >= GIVE_UP_AFTER) {
            return new Payload(wav, "audio/wav");
        }
        Path source = null;
        Path packed = null;
        try {
            source = Files.createTempFile("lt-audio-", ".wav");
            packed = Files.createTempFile("lt-audio-", ".m4a");
            Files.write(source, wav);
            // afconvert встроен в macOS, и приложение всё равно только для неё:
            // связка ключей, BlackHole, бандл .app. Тащить ради этого
            // библиотеку-кодировщик в jar незачем.
            Process process = new ProcessBuilder("/usr/bin/afconvert",
                    "-f", "m4af", "-d", "aac", "-b", String.valueOf(config.audioBitrate()),
                    source.toString(), packed.toString())
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return giveUp(wav, "сжатие не уложилось в " + TIMEOUT_SECONDS + " с");
            }
            if (process.exitValue() != 0) {
                return giveUp(wav, "afconvert вернул " + process.exitValue());
            }
            byte[] compressed = Files.readAllBytes(packed);
            if (compressed.length == 0 || compressed.length >= wav.length) {
                return giveUp(wav, "сжатие не дало выигрыша");
            }
            failures = 0;
            return new Payload(compressed, "audio/aac");
        } catch (IOException e) {
            return giveUp(wav, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Payload(wav, "audio/wav");
        } finally {
            delete(source);
            delete(packed);
        }
    }

    private static Payload giveUp(byte[] wav, String reason) {
        failures++;
        System.err.println("Звук отправляется без сжатия: " + reason);
        if (failures >= GIVE_UP_AFTER) {
            System.err.println("Сжатие отключено до перезапуска — не получилось "
                    + GIVE_UP_AFTER + " раза подряд.");
        }
        return new Payload(wav, "audio/wav");
    }

    private static void delete(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Временный файл в /tmp система уберёт и сама.
        }
    }
}
