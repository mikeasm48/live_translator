package com.mikeasm.livetranslator;

/**
 * Похож ли кусок звука на речь по форме, а не по громкости.
 * <p>
 * Детектор громкости отвечает на вопрос «есть ли звук». Этого мало: кашель,
 * стук по столу и хмыканье громче порога и длятся достаточно, чтобы пройти
 * проверку длительности. А модель, получив человеческий звук без слов,
 * принимается сочинять — и сочиняет правдоподобно.
 * <p>
 * Речь отличается от таких звуков не громкостью, а ритмом. Человек говорит
 * слогами, и громкость колеблется примерно два-семь раз в секунду. Кашель —
 * один всплеск с затуханием. Стук — мгновенный щелчок. Хмыканье — ровный гул
 * без колебаний. Поэтому считаем не уровень, а то, сколько раз он поднимался
 * и опускался.
 * <p>
 * Это не распознавание речи и не обещание точности: задача скромная — отсеять
 * звуки, у которых слогов нет вовсе.
 */
public final class SpeechShape {

    /** Окно, по которому меряется громкость. Слог короче не бывает. */
    private static final int FRAME_MS = 20;

    /** Всплеск засчитывается, если поднялся выше этой доли от среднего. */
    private static final double PEAK_OVER_MEAN = 1.3;

    /** Ниже этого всплеск считается спадом. */
    private static final double VALLEY_UNDER_MEAN = 0.7;

    /** Сколько слогов в секунду бывает у речи. */
    private static final double MIN_PEAKS_PER_SECOND = 1.2;
    private static final double MAX_PEAKS_PER_SECOND = 9.0;

    /** Короче этого о ритме говорить нечего. */
    private static final int MIN_LENGTH_MS = 700;

    /**
     * Ниже этого речи не бывает совсем.
     * <p>
     * Порог намеренно не там, где начинается «похоже на речь»: у настоящей речи
     * замерено от 0,9 всплеска в секунду, у кашля и гула до 1,0 — края
     * перекрываются. Отказываться отправлять можно только там, где перекрытия
     * нет, иначе потеряется живая реплика. А вот словарь при сомнении лучше
     * придержать: цена ошибки разная.
     */
    private static final double DEFINITELY_NOT_SPEECH = 0.6;

    private SpeechShape() {}

    /** Сколько раз громкость поднималась и опадала — для настройки и отладки. */
    public record Shape(int peaks, double perSecond, boolean speechLike) {}

    public static Shape of(byte[] pcm, int sampleRate) {
        int frameBytes = Math.max(2, sampleRate * 2 * FRAME_MS / 1000);
        int frames = pcm.length / frameBytes;
        double lengthMs = pcm.length / (sampleRate * 2.0) * 1000;
        if (frames < 4 || lengthMs < MIN_LENGTH_MS) return new Shape(0, 0, false);

        double[] envelope = new double[frames];
        double sum = 0;
        for (int i = 0; i < frames; i++) {
            envelope[i] = rms(pcm, i * frameBytes, frameBytes);
            sum += envelope[i];
        }
        double mean = sum / frames;
        if (mean <= 0) return new Shape(0, 0, false);

        // Считаем чередования: подъём выше среднего засчитывается только после
        // того, как громкость успела опуститься ниже него. Иначе один долгий
        // всплеск насчитал бы десяток слогов.
        int peaks = 0;
        boolean above = false;
        for (double level : envelope) {
            if (!above && level > mean * PEAK_OVER_MEAN) {
                above = true;
                peaks++;
            } else if (above && level < mean * VALLEY_UNDER_MEAN) {
                above = false;
            }
        }

        double perSecond = peaks / (lengthMs / 1000.0);
        boolean speechLike = perSecond >= MIN_PEAKS_PER_SECOND && perSecond <= MAX_PEAKS_PER_SECOND;
        return new Shape(peaks, perSecond, speechLike);
    }

    /** Точно ли это не речь: только для случаев без перекрытия. */
    public static boolean definitelyNotSpeech(Shape shape) {
        return shape.perSecond() < DEFINITELY_NOT_SPEECH;
    }

    private static double rms(byte[] pcm, int from, int length) {
        long sum = 0;
        int samples = 0;
        for (int i = from; i + 1 < from + length && i + 1 < pcm.length; i += 2) {
            int sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            sum += (long) sample * sample;
            samples++;
        }
        return samples == 0 ? 0 : Math.sqrt((double) sum / samples);
    }
}
