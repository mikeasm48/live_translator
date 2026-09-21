package com.mikeasm.livetranslator;

/**
 * Простейший детектор речи по громкости (RMS).
 * <p>
 * Нужен по двум причинам: тарификация SpeechKit считает отправленный аудиопоток,
 * а протокол v3 позволяет вместо тишины слать {@code silence_chunk}. Чтобы не
 * срезать начало фразы, несколько последних «тихих» фрагментов хранятся в буфере
 * предзаписи и отправляются, как только речь началась.
 */
public final class SilenceGate {

    /** Сколько подряд тихих фрагментов нужно, чтобы признать паузу (мс / CHUNK_MS). */
    private static final int HANGOVER_CHUNKS = 8;
    /** Глубина буфера предзаписи до начала речи. */
    private static final int PREROLL_CHUNKS = 3;

    /**
     * Сколько звук должен длиться, чтобы считаться речью, а не помехой.
     * <p>
     * Кашель, стук двери, щелчок клавиши — всё это громче порога, но коротко:
     * две-три десятых секунды. Речь так не начинается. Раньше гейт открывался
     * на первом же громком фрагменте, и кашель уезжал в модель, а модель,
     * получив звук без слов, принималась сочинять.
     * <p>
     * Потерянного начала фразы бояться не нужно: всё это время звук копится в
     * буфере предзаписи и уходит целиком, как только речь признана.
     * <p>
     * Значение берётся из настроек: в шумном помещении его можно поднять.
     */

    /** Ниже этого уровня речи не бывает ни на одном источнике. */
    private static final double FLOOR_MINIMUM = 25;
    /** Во сколько раз речь должна превышать собственный шум источника. */
    private static final double SPEECH_OVER_NOISE = 3.0;

    private final Config config;
    /** Оценка собственного шума источника: быстро вниз, очень медленно вверх. */
    private volatile double noiseFloor = FLOOR_MINIMUM;
    private volatile double effectiveThreshold = FLOOR_MINIMUM * SPEECH_OVER_NOISE;
    /**
     * Буфер хранит и тишину до речи, и саму проверяемую вспышку: когда речь
     * наконец признана, отдать надо всё с самого начала.
     */
    private final byte[][] preroll;
    private final int onsetChunks;
    private int prerollSize;
    private int quietStreak;
    /** Сколько громких фрагментов подряд набралось до признания речи. */
    private int loudStreak;
    private boolean speaking;

    public SilenceGate(Config config) {
        this.config = config;
        this.onsetChunks = Math.max(1, config.speechOnsetMs() / AudioCapture.CHUNK_MS);
        this.preroll = new byte[PREROLL_CHUNKS + onsetChunks][];
    }

    /** Результат обработки фрагмента. */
    public sealed interface Decision permits Speech, Silence {}

    /** Отправить эти фрагменты как аудио (первым может идти буфер предзаписи). */
    public record Speech(byte[][] chunks) implements Decision {}

    /** Отправить silence_chunk указанной длительности. */
    public record Silence(int durationMs) implements Decision {}

    public Decision offer(byte[] chunk) {
        double level = rms(chunk);
        boolean loud = level >= threshold(level);
        if (loud) {
            quietStreak = 0;
            if (speaking) return new Speech(new byte[][]{chunk});

            // Речь ещё не признана: копим вспышку и ждём, продлится ли она.
            loudStreak++;
            pushPreroll(chunk);
            if (loudStreak < onsetChunks) return new Silence(AudioCapture.CHUNK_MS);

            speaking = true;
            loudStreak = 0;
            byte[][] out = new byte[prerollSize][];
            System.arraycopy(preroll, 0, out, 0, prerollSize);
            prerollSize = 0;
            return new Speech(out);
        }

        // Тихий фрагмент до начала речи не обнуляет вспышку разом, а убавляет
        // её: в начале слова бывает провал, и ронять из-за него всю фразу
        // обиднее, чем пропустить лишний щелчок.
        if (!speaking && loudStreak > 0) loudStreak--;

        if (speaking) {
            quietStreak++;
            if (quietStreak < HANGOVER_CHUNKS) {
                // Короткая пауза внутри фразы — продолжаем слать звук,
                // иначе классификатор конца фразы получит рваный поток.
                return new Speech(new byte[][]{chunk});
            }
            speaking = false;
            quietStreak = 0;
        }
        pushPreroll(chunk);
        return new Silence(AudioCapture.CHUNK_MS);
    }

    private void pushPreroll(byte[] chunk) {
        if (prerollSize == preroll.length) {
            System.arraycopy(preroll, 1, preroll, 0, preroll.length - 1);
            prerollSize--;
        }
        preroll[prerollSize++] = chunk;
    }

    /**
     * Порог, ниже которого фрагмент считается тишиной.
     * <p>
     * Фиксированное значение не годится сразу для двух источников. Микрофон
     * всегда шумит сам, и порог должен быть выше этого шума. Цифровой кабель
     * не шумит вовсе, зато громкость в нём зависит от того, насколько громко
     * играет источник, — тихое видео давало уровень втрое ниже порога,
     * подобранного по микрофону, и речь целиком уходила как тишина.
     * <p>
     * Поэтому порог считается от собственного шума источника. Оценка шума
     * опускается быстро и поднимается очень медленно: иначе затянувшаяся речь
     * сама себя примет за шум и порог уползёт вверх.
     */
    private double threshold(double level) {
        if (!config.vadAuto()) return config.vadThreshold();

        if (level < noiseFloor) {
            noiseFloor = level;
        } else if (!speaking) {
            noiseFloor += (level - noiseFloor) * 0.0005;
        }
        effectiveThreshold = Math.max(FLOOR_MINIMUM, noiseFloor * SPEECH_OVER_NOISE + 10);
        return effectiveThreshold;
    }

    /** Порог, действующий сейчас, — его показывает полоска уровня. */
    public double currentThreshold() {
        return config.vadAuto() ? effectiveThreshold : config.vadThreshold();
    }

    /** Истина, если сейчас идёт речь: используется, чтобы не рвать поток на фразе. */
    public boolean isSpeaking() {
        return speaking;
    }

    static double rms(byte[] pcm16le) {
        long sum = 0;
        int samples = pcm16le.length / 2;
        for (int i = 0; i < samples; i++) {
            int lo = pcm16le[2 * i] & 0xFF;
            int hi = pcm16le[2 * i + 1];
            int sample = (hi << 8) | lo;
            sum += (long) sample * sample;
        }
        return samples == 0 ? 0 : Math.sqrt((double) sum / samples);
    }
}
