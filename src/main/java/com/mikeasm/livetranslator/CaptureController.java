package com.mikeasm.livetranslator;

import javax.sound.sampled.LineUnavailableException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Владеет захватом звука: позволяет переключить устройство и включить запись,
 * не перезапуская приложение.
 * <p>
 * Запись ведётся до фильтра тишины — в файл идёт всё, что слышало устройство.
 * Так запись годится для последующего разбора: отложенное распознавание умеет
 * то, чего не умеет потоковое, например разметку говорящих.
 */
public final class CaptureController implements AutoCloseable {

    private final Config config;
    private final Consumer<byte[]> sink;
    /** Куда сообщать о замеченных неполадках. Задаётся после создания окна. */
    private volatile Consumer<String> notices = message -> {};

    private volatile AudioCapture capture;
    private volatile String device;
    private volatile WavRecorder recorder;
    /** Сглаженный уровень сигнала: нужен, чтобы показывать его в окне. */
    private volatile double level;

    /** Сообщение о том, что пришлось взять не то устройство, которое просили. */
    private volatile String fallbackNotice = "";

    public CaptureController(Config config, Consumer<byte[]> sink)
            throws LineUnavailableException {
        this.config = config;
        this.sink = sink;

        // Устройство из настроек может отсутствовать: BlackHole ещё не
        // установлен, наушники отключены, виртуальный кабель переименован.
        // Это не повод не запускаться — переходим на устройство по умолчанию
        // и говорим об этом, а выбрать нужное можно в панели.
        try {
            this.device = config.device;
            this.capture = open(device);
        } catch (LineUnavailableException e) {
            if (config.device.isBlank()) throw e;
            fallbackNotice = "устройство «" + config.device + "» не найдено, "
                    + "слушаю устройство по умолчанию";
            System.err.println(fallbackNotice);
            this.device = "";
            this.capture = open("");
        }
    }

    /** Непустая строка, если при запуске пришлось взять другое устройство. */
    public String fallbackNotice() {
        return fallbackNotice;
    }

    private AudioCapture open(String name) throws LineUnavailableException {
        AudioCapture opened = new AudioCapture(config.sampleRate, name);
        opened.start(chunk -> {
            double rms = SilenceGate.rms(chunk);
            // Экспоненциальное сглаживание: мгновенный RMS слишком дёргается,
            // чтобы за ним следить глазами.
            level = 0.7 * level + 0.3 * rms;
            WavRecorder active = recorder;
            if (active != null) {
                try {
                    active.write(chunk);
                } catch (IOException e) {
                    System.err.println("Запись прервана: " + e.getMessage());
                    stopRecording();
                }
            }
            sink.accept(chunk);
        });
        return opened;
    }

    /** Куда сообщать о неполадках со звуком. */
    public void onNotice(Consumer<String> notices) {
        this.notices = notices;
    }

    /** Текущий уровень входного сигнала, шкала RMS 0..32767. */
    public int level() {
        return (int) level;
    }

    public String device() {
        return device.isBlank() ? "устройство по умолчанию" : device;
    }

    public List<String> availableDevices() {
        return AudioCapture.deviceNames(config.sampleRate);
    }

    /**
     * Переключает источник звука. Прежняя линия закрывается только после того,
     * как открыта новая: если новое устройство занято, останемся на работающем.
     */
    public synchronized void switchDevice(String name) throws LineUnavailableException {
        AudioCapture next = open(name);
        AudioCapture previous = capture;
        capture = next;
        device = name;
        if (previous != null) previous.close();
    }

    public boolean isRecording() {
        return recorder != null;
    }

    public synchronized Path startRecording() throws IOException {
        if (recorder != null) return recorder.path();
        recorder = new WavRecorder(AppPaths.logsDir(), config.sampleRate);
        return recorder.path();
    }

    /** @return длительность записи в секундах или -1, если записи не было */
    public synchronized long stopRecording() {
        WavRecorder active = recorder;
        recorder = null;
        if (active == null) return -1;
        long seconds = active.seconds();
        try {
            active.close();
        } catch (IOException e) {
            System.err.println("Не удалось закрыть запись: " + e.getMessage());
        }
        return seconds;
    }

    public Path recordingPath() {
        WavRecorder active = recorder;
        return active == null ? null : active.path();
    }

    public long recordedSeconds() {
        WavRecorder active = recorder;
        return active == null ? 0 : active.seconds();
    }

    @Override
    public synchronized void close() {
        stopRecording();
        AudioCapture current = capture;
        capture = null;
        if (current != null) current.close();
    }
}
