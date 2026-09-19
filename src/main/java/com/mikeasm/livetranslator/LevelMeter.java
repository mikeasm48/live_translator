package com.mikeasm.livetranslator;

import javax.sound.sampled.LineUnavailableException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Измеритель уровня сигнала: показывает, доходит ли звук до выбранного
 * устройства, не обращаясь в облако.
 * <p>
 * Нужен потому, что «ничего не распознаётся» и «до устройства не доходит звук» —
 * это разные поломки с одинаковыми симптомами. Для виртуального кабеля вроде
 * BlackHole вторая куда вероятнее: устройство открывается успешно и честно
 * отдаёт тишину, если системный вывод на него не направлен.
 */
public final class LevelMeter {

    private static final int SECONDS = 15;

    private LevelMeter() {}

    public static void run(Config config) throws LineUnavailableException, InterruptedException {
        String device = config.device.isBlank() ? "устройство по умолчанию" : config.device;
        System.out.println("Слушаю " + device + " " + SECONDS + " секунд.");
        System.out.println("Включите звук и следите за шкалой. Порог тишины: "
                + (int) config.vadThreshold);
        System.out.println();

        AtomicLong peak = new AtomicLong();
        AtomicInteger loudChunks = new AtomicInteger();
        AtomicInteger totalChunks = new AtomicInteger();

        try (AudioCapture capture = new AudioCapture(config.sampleRate, config.device)) {
            capture.start(chunk -> {
                int level = (int) SilenceGate.rms(chunk);
                peak.accumulateAndGet(level, Math::max);
                totalChunks.incrementAndGet();
                if (level >= config.vadThreshold) loudChunks.incrementAndGet();
                // Обновляем не каждый фрагмент, иначе строка мельтешит.
                if (totalChunks.get() % 3 == 0) print(level, config.vadThreshold);
            });
            Thread.sleep(SECONDS * 1000L);
        }

        int total = Math.max(totalChunks.get(), 1);
        int loudPercent = 100 * loudChunks.get() / total;
        System.out.println();
        System.out.println();
        System.out.println("Пик уровня: " + peak.get()
                + ", речь распознана в " + loudPercent + "% фрагментов.");
        System.out.println(verdict(peak.get(), loudPercent, config));
    }

    private static void print(int level, double threshold) {
        int bars = Math.min(50, level / 200);
        StringBuilder line = new StringBuilder("\r  [");
        for (int i = 0; i < 50; i++) line.append(i < bars ? '#' : ' ');
        line.append("] ").append(level).append(level >= threshold ? "  речь " : "  тишина");
        System.out.print(line);
        System.out.flush();
    }

    private static String verdict(long peak, int loudPercent, Config config) {
        if (peak < 30) {
            return """
                   Звука нет вообще. Устройство открылось, но отдаёт тишину.
                   Для BlackHole: в «Звук → Выход» должен быть выбран Multi-Output Device,
                   включающий BlackHole, — иначе в кабель ничего не попадает.""";
        }
        if (peak < config.vadThreshold) {
            return "Звук есть, но тихий: пик " + peak + " ниже порога "
                    + (int) config.vadThreshold + ". Прибавьте громкость источника "
                    + "или снизьте порог: --vad-threshold=" + Math.max(30, peak / 2);
        }
        if (loudPercent < 5) {
            return "Звук проходит, но речь звучала редко. Если говорили всё время — "
                    + "снизьте порог: --vad-threshold=" + Math.max(30, peak / 4);
        }
        return "Звук доходит нормально, можно запускать распознавание.";
    }
}
