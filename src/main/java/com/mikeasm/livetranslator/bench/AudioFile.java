package com.mikeasm.livetranslator.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Запись встречи, приготовленная для сравнения движков: 16-битный моно-PCM
 * целиком в памяти.
 * <p>
 * Час записи в 16 кГц — это 115 МБ, поэтому целиком её не примет ни один
 * сервис: у одних предел на размер запроса, у других на длину аудио. Значит,
 * запись надо резать. Резать по таймеру нельзя — граница попадёт на середину
 * слова, и это слово потеряют все движки, но каждый по-своему; сравнение
 * начнёт мерить кривизну нарезки, а не качество распознавания. Поэтому рез
 * ищется в самом тихом месте рядом с нужной отметкой.
 */
public final class AudioFile {

    /** Кусок записи, пригодный для отправки движку. */
    public record Clip(byte[] pcm, int sampleRate, int offsetMs, int durationMs) {
        /** Тот же кусок как самостоятельный WAV — для сервисов, принимающих файл. */
        public byte[] wav() {
            return AudioFile.wrap(pcm, sampleRate);
        }
    }

    /** Длина окна, в котором меряется громкость при поиске места для реза. */
    private static final int FRAME_MS = 20;

    /** Какую часть куска в конце просматривать в поисках тишины. */
    private static final double SEARCH_TAIL = 0.2;

    private final byte[] pcm;
    private final int sampleRate;
    private final Path path;
    private final String note;

    private AudioFile(Path path, byte[] pcm, int sampleRate, String note) {
        this.path = path;
        this.pcm = pcm;
        this.sampleRate = sampleRate;
        this.note = note;
    }

    public Path path() {
        return path;
    }

    public int sampleRate() {
        return sampleRate;
    }

    /** Пояснение о том, что пришлось сделать с файлом при чтении. */
    public String note() {
        return note;
    }

    public int durationMs() {
        return (int) (pcm.length / (double) bytesPerMs());
    }

    private double bytesPerMs() {
        return sampleRate * 2 / 1000.0;
    }

    /** Первые {@code minutes} минут записи — чтобы прогнать стенд быстро. */
    public AudioFile firstMinutes(int minutes) {
        long limit = (long) (minutes * 60_000L * bytesPerMs());
        if (minutes <= 0 || limit >= pcm.length) return this;
        byte[] head = new byte[(int) (limit - limit % 2)];
        System.arraycopy(pcm, 0, head, 0, head.length);
        return new AudioFile(path, head, sampleRate, note);
    }

    /**
     * Режет запись на куски не длиннее {@code maxChunkMs}, выбирая для реза
     * самое тихое место в конце допустимого интервала.
     */
    public List<Clip> split(int maxChunkMs) {
        List<Clip> clips = new ArrayList<>();
        long maxBytes = Math.min((long) maxChunkMs * (long) bytesPerMs(), Integer.MAX_VALUE - 1L);
        maxBytes -= maxBytes % 2;
        int start = 0;
        while (start < pcm.length) {
            int hardEnd = (int) Math.min((long) start + maxBytes, pcm.length);
            int end = hardEnd < pcm.length ? quietCut(start, hardEnd) : hardEnd;
            byte[] slice = new byte[end - start];
            System.arraycopy(pcm, start, slice, 0, slice.length);
            clips.add(new Clip(slice, sampleRate,
                    (int) (start / bytesPerMs()), (int) (slice.length / bytesPerMs())));
            start = end;
        }
        return clips;
    }

    /** Самое тихое окно в хвосте интервала; возвращает смещение в байтах. */
    private int quietCut(int start, int hardEnd) {
        int frame = (int) (FRAME_MS * bytesPerMs());
        int searchFrom = hardEnd - (int) ((hardEnd - start) * SEARCH_TAIL);
        searchFrom = Math.max(start + frame, searchFrom);
        if (searchFrom >= hardEnd - frame) return hardEnd;

        int best = hardEnd;
        double quietest = Double.MAX_VALUE;
        for (int at = searchFrom; at + frame <= hardEnd; at += frame) {
            double level = rms(at, frame);
            if (level < quietest) {
                quietest = level;
                best = at + frame / 2;
            }
        }
        return best - best % 2;
    }

    private double rms(int from, int length) {
        long sum = 0;
        int count = 0;
        for (int i = from; i + 1 < from + length; i += 2) {
            int sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            sum += (long) sample * sample;
            count++;
        }
        return count == 0 ? 0 : Math.sqrt((double) sum / count);
    }

    // --- Чтение WAV ----------------------------------------------------------

    /**
     * Читает WAV. Стерео сводится в моно, частота оставляется как есть:
     * пересчёт частоты — это уже обработка звука, а стенд должен показывать
     * движки, а не наш ресемплер.
     */
    public static AudioFile read(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < 44 || !tag(bytes, 0, "RIFF") || !tag(bytes, 8, "WAVE")) {
            throw new IOException("Это не WAV: " + path);
        }

        int format = 0;
        int channels = 1;
        int rate = 0;
        int bits = 0;
        int dataAt = -1;
        int dataLength = 0;

        int at = 12;
        while (at + 8 <= bytes.length) {
            String id = new String(bytes, at, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = intLE(bytes, at + 4);
            int body = at + 8;
            if (size < 0 || body + size > bytes.length) size = bytes.length - body;

            if (id.equals("fmt ") && size >= 16) {
                format = shortLE(bytes, body);
                channels = shortLE(bytes, body + 2);
                rate = intLE(bytes, body + 4);
                bits = shortLE(bytes, body + 14);
            } else if (id.equals("data")) {
                dataAt = body;
                // Оборванная запись (приложение убили) оставляет в заголовке ноль,
                // но сами отсчёты на месте — берём всё, что есть до конца файла.
                dataLength = size > 0 ? size : bytes.length - body;
            }
            at = body + size + (size % 2);
        }

        if (dataAt < 0 || dataLength <= 0) throw new IOException("В файле нет звука: " + path);
        if (format != 1 || bits != 16) {
            throw new IOException("Нужен несжатый WAV 16 бит (в файле: формат " + format
                    + ", " + bits + " бит): " + path);
        }
        if (rate <= 0) throw new IOException("В заголовке нет частоты дискретизации: " + path);

        byte[] mono = channels > 1 ? downmix(bytes, dataAt, dataLength, channels)
                : java.util.Arrays.copyOfRange(bytes, dataAt, dataAt + dataLength);
        String note = channels > 1 ? channels + " канала сведены в моно" : "";
        return new AudioFile(path, mono, rate, note);
    }

    private static byte[] downmix(byte[] bytes, int from, int length, int channels) {
        int frames = length / (2 * channels);
        byte[] mono = new byte[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            int sum = 0;
            for (int channel = 0; channel < channels; channel++) {
                int at = from + (frame * channels + channel) * 2;
                sum += (short) ((bytes[at] & 0xFF) | (bytes[at + 1] << 8));
            }
            short value = (short) (sum / channels);
            mono[frame * 2] = (byte) value;
            mono[frame * 2 + 1] = (byte) (value >> 8);
        }
        return mono;
    }

    /** Оборачивает PCM в WAV: сервисам, принимающим файл, нужен заголовок. */
    static byte[] wrap(byte[] pcm, int sampleRate) {
        byte[] wav = new byte[44 + pcm.length];
        putTag(wav, 0, "RIFF");
        putIntLE(wav, 4, 36 + pcm.length);
        putTag(wav, 8, "WAVE");
        putTag(wav, 12, "fmt ");
        putIntLE(wav, 16, 16);
        putShortLE(wav, 20, 1);
        putShortLE(wav, 22, 1);
        putIntLE(wav, 24, sampleRate);
        putIntLE(wav, 28, sampleRate * 2);
        putShortLE(wav, 32, 2);
        putShortLE(wav, 34, 16);
        putTag(wav, 36, "data");
        putIntLE(wav, 40, pcm.length);
        System.arraycopy(pcm, 0, wav, 44, pcm.length);
        return wav;
    }

    private static boolean tag(byte[] bytes, int at, String expected) {
        return new String(bytes, at, 4, java.nio.charset.StandardCharsets.US_ASCII).equals(expected);
    }

    private static void putTag(byte[] bytes, int at, String tag) {
        System.arraycopy(tag.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, bytes, at, 4);
    }

    private static int intLE(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8)
                | ((b[at + 2] & 0xFF) << 16) | ((b[at + 3] & 0xFF) << 24);
    }

    private static int shortLE(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8);
    }

    private static void putIntLE(byte[] b, int at, int value) {
        b[at] = (byte) value;
        b[at + 1] = (byte) (value >> 8);
        b[at + 2] = (byte) (value >> 16);
        b[at + 3] = (byte) (value >> 24);
    }

    private static void putShortLE(byte[] b, int at, int value) {
        b[at] = (byte) value;
        b[at + 1] = (byte) (value >> 8);
    }
}
