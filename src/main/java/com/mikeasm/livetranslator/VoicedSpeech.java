package com.mikeasm.livetranslator;

/**
 * Есть ли в куске звука голос.
 * <p>
 * Громкость и ритм отличают звук от тишины, но не речь от стука. Двойной клик
 * по тачпаду набрал 1,2 колебания громкости в секунду — столько же, сколько
 * тихая речь, — и модель сочинила по нему разговор про Docker. Подбирать
 * пороги под каждый вид помехи бесперспективно: за кликом будет клавиатура,
 * за клавиатурой стул.
 * <p>
 * Поэтому здесь ищется то, что у речи есть всегда, а у щелчков не бывает
 * никогда, — периодичность. Голосовые связки дают основной тон 70–350 Гц, и он
 * держится десятки миллисекунд. Щелчок, стук и шорох апериодичны: у них
 * периода нет вовсе.
 * <p>
 * Оценка делается алгоритмом YIN (de Cheveigné, Kawahara, 2002): для каждого
 * окна ищется сдвиг, при котором сигнал больше всего похож сам на себя.
 * Насколько похож — и есть мера периодичности.
 */
public final class VoicedSpeech {

    /** Окно: должно вмещать хотя бы два периода самого низкого голоса. */
    private static final int FRAME = 1024;
    private static final int HOP = 512;

    /** Границы человеческого основного тона. */
    private static final double MIN_HZ = 70;
    private static final double MAX_HZ = 350;

    /**
     * Насколько непохожим на себя позволено быть периодическому сигналу.
     * <p>
     * Классическое значение для YIN — 0,1–0,15. Берём мягче: на встрече
     * говорят не в студийный микрофон, и чистой периодичности ждать не стоит.
     */
    private static final double APERIODICITY_LIMIT = 0.3;

    /** Окна тише этого не рассматриваем: в паузах периодичности нет и не надо. */
    private static final double QUIET_FRAME_RMS = 60;

    private VoicedSpeech() {}

    /**
     * @param voicedFrames сколько окон оказались озвученными
     * @param loudFrames   сколько окон вообще были громче тишины
     * @param share        доля озвученных среди громких
     * @param medianHz     основной тон, срединное значение по озвученным окнам
     */
    public record Voice(int voicedFrames, int loudFrames, double share, double medianHz,
                        int voicedMs) {}

    public static Voice of(byte[] pcm, int sampleRate) {
        int samples = pcm.length / 2;
        if (samples < FRAME) return new Voice(0, 0, 0, 0, 0);

        int minLag = (int) Math.floor(sampleRate / MAX_HZ);
        int maxLag = (int) Math.ceil(sampleRate / MIN_HZ);
        if (maxLag >= FRAME) maxLag = FRAME - 1;

        double[] frame = new double[FRAME];
        java.util.List<Double> pitches = new java.util.ArrayList<>();
        int loud = 0;

        for (int start = 0; start + FRAME <= samples; start += HOP) {
            double sum = 0;
            for (int i = 0; i < FRAME; i++) {
                int at = (start + i) * 2;
                frame[i] = (short) ((pcm[at] & 0xFF) | (pcm[at + 1] << 8));
                sum += frame[i] * frame[i];
            }
            if (Math.sqrt(sum / FRAME) < QUIET_FRAME_RMS) continue;
            loud++;

            double hz = pitch(frame, minLag, maxLag, sampleRate);
            if (hz > 0) pitches.add(hz);
        }

        int voicedMs = (int) (pitches.size() * (HOP * 1000.0 / sampleRate));
        if (loud == 0) return new Voice(0, 0, 0, 0, voicedMs);
        double share = pitches.size() / (double) loud;
        java.util.Collections.sort(pitches);
        double median = pitches.isEmpty() ? 0 : pitches.get(pitches.size() / 2);
        return new Voice(pitches.size(), loud, share, median, voicedMs);
    }

    /** @return частота основного тона или 0, если окно апериодично */
    private static double pitch(double[] frame, int minLag, int maxLag, int sampleRate) {
        double[] difference = new double[maxLag + 1];
        for (int lag = 1; lag <= maxLag; lag++) {
            double sum = 0;
            for (int i = 0; i + lag < frame.length; i++) {
                double d = frame[i] - frame[i + lag];
                sum += d * d;
            }
            difference[lag] = sum;
        }

        // Нормировка накопленным средним: без неё минимум всегда на нуле.
        double[] normalized = new double[maxLag + 1];
        normalized[0] = 1;
        double running = 0;
        for (int lag = 1; lag <= maxLag; lag++) {
            running += difference[lag];
            normalized[lag] = running == 0 ? 1 : difference[lag] * lag / running;
        }

        for (int lag = minLag; lag <= maxLag; lag++) {
            if (normalized[lag] >= APERIODICITY_LIMIT) continue;
            // Спускаемся до дна этой впадины: первый пересёкший порог сдвиг
            // обычно чуть левее настоящего минимума.
            while (lag + 1 <= maxLag && normalized[lag + 1] < normalized[lag]) lag++;
            // Дно на самом краю диапазона — обычно не тон, а обрыв поиска:
            // апериодичный сигнал даёт «минимум» там, где сравнивать уже
            // почти нечего. На записи набора текста именно так и выходило:
            // ровно 70 Гц, нижняя граница.
            if (lag >= maxLag) return 0;
            return (double) sampleRate / lag;
        }
        return 0;
    }
}
