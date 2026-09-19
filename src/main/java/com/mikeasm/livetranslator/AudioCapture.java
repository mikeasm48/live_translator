package com.mikeasm.livetranslator;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Захват звука с микрофона или виртуального устройства (BlackHole и т.п.)
 * и нарезка на фрагменты фиксированной длительности.
 */
public final class AudioCapture implements AutoCloseable {

    /** Длительность одного фрагмента, мс. */
    public static final int CHUNK_MS = 100;

    private final AudioFormat format;
    private final TargetDataLine line;
    private final int chunkBytes;
    private volatile boolean running;
    private Thread thread;

    public AudioCapture(int sampleRate, String deviceHint) throws LineUnavailableException {
        this.format = new AudioFormat(sampleRate, 16, 1, true, false);
        this.chunkBytes = sampleRate * 2 * CHUNK_MS / 1000;
        this.line = openLine(format, deviceHint);
    }

    private static TargetDataLine openLine(AudioFormat format, String hint)
            throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        if (hint != null && !hint.isBlank()) {
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (!mi.getName().toLowerCase().contains(hint.toLowerCase())) continue;
                Mixer mixer = AudioSystem.getMixer(mi);
                if (!mixer.isLineSupported(info)) continue;
                return (TargetDataLine) mixer.getLine(info);
            }
            throw new LineUnavailableException(
                    "Устройство не найдено или не поддерживает 16 кГц/16 бит/моно: " + hint
                            + "\nСписок доступных: --list-devices");
        }
        return (TargetDataLine) AudioSystem.getLine(info);
    }

    /** Устройства с описанием — для вывода в терминал. */
    public static List<String> listDevices(int sampleRate) {
        List<String> result = new ArrayList<>();
        for (Mixer.Info mi : suitableMixers(sampleRate)) {
            result.add(mi.getName() + "  —  " + mi.getDescription());
        }
        return result;
    }

    /** Только имена — для выпадающего списка в окне. */
    public static List<String> deviceNames(int sampleRate) {
        List<String> result = new ArrayList<>();
        for (Mixer.Info mi : suitableMixers(sampleRate)) {
            result.add(mi.getName());
        }
        return result;
    }

    private static List<Mixer.Info> suitableMixers(int sampleRate) {
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        List<Mixer.Info> result = new ArrayList<>();
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(mi).isLineSupported(info)) result.add(mi);
        }
        return result;
    }

    /**
     * Запускает чтение в отдельном потоке. Потребитель получает фрагменты
     * ровно по {@link #CHUNK_MS} миллисекунд.
     */
    public void start(Consumer<byte[]> consumer) throws LineUnavailableException {
        line.open(format, chunkBytes * 16);
        line.start();
        running = true;
        thread = new Thread(() -> {
            byte[] buffer = new byte[chunkBytes];
            while (running) {
                int read = 0;
                while (read < chunkBytes && running) {
                    int n = line.read(buffer, read, chunkBytes - read);
                    if (n <= 0) break;
                    read += n;
                }
                if (read == chunkBytes) consumer.accept(buffer.clone());
            }
        }, "audio-capture");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Останавливает захват, не блокируя вызывающего.
     * <p>
     * {@code line.close()} требует монитор, который поток чтения удерживает,
     * пока сидит внутри {@code read()}, а {@code read()} на молчащем устройстве
     * не возвращается сразу. Вызов из обработчика завершения намертво вешал
     * выход из программы, поэтому закрытие вынесено в отдельный поток с
     * ограниченным ожиданием: линию всё равно освободит операционная система.
     */
    @Override
    public void close() {
        running = false;
        Thread closer = new Thread(() -> {
            try {
                line.stop();
                line.close();
            } catch (RuntimeException ignored) {
                // линия уже закрыта или устройство исчезло
            }
        }, "audio-close");
        closer.setDaemon(true);
        closer.start();
        try {
            closer.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
