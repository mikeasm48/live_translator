package com.mikeasm.livetranslator.bench;

import com.mikeasm.livetranslator.Config;
import com.mikeasm.livetranslator.GeminiClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Gemini на стенде: модель слышит звук сама, без отдельного распознавания.
 * <p>
 * {@code gemini} расшифровывает дословно — его строка сравнима с остальными
 * движками. {@code gemini-ru} переводит звук сразу на русский, минуя текстовое
 * горлышко; это проверка не движка, а всей схемы.
 * <p>
 * Вся работа — в {@link GeminiClient}, том же самом, что работает на встрече.
 * Иначе замеренное на стенде однажды перестанет совпадать с тем, что происходит
 * в жизни: так уже случилось с уровнем размышлений, который стенд читал мимо
 * настроек приложения и потому мерил втрое более долгие ответы.
 */
public final class GeminiEngine implements AsrEngine {

    /**
     * Предел запроса — 20 МБ вместе с текстом, а base64 раздувает звук в 4/3.
     * Пять минут 16 кГц моно дают около 13 МБ в кодировке — с запасом.
     */
    private static final int MAX_CHUNK_MS = 300_000;

    private final Config config;
    private final boolean toRussian;
    private final boolean withTerms;
    private final GeminiClient client;

    public GeminiEngine(Config config, boolean toRussian) {
        this(config, toRussian, false);
    }

    public GeminiEngine(Config config, boolean toRussian, boolean withTerms) {
        this.config = config;
        this.toRussian = toRussian;
        this.withTerms = withTerms;
        this.client = new GeminiClient(config, withTerms);
    }

    @Override
    public String id() {
        String base = toRussian ? "gemini-ru" : "gemini";
        return withTerms ? base + "-terms" : base;
    }

    @Override
    public String title() {
        String base = toRussian
                ? "Gemini " + config.geminiModel() + ", звук сразу в перевод"
                : "Gemini " + config.geminiModel() + ", дословная расшифровка";
        return withTerms
                ? base + ", с подсказкой терминов (" + client.expectedTermCount() + ")"
                : base;
    }

    @Override
    public String skipReason() {
        return client.skipReason();
    }

    @Override
    public int maxChunkMs() {
        return MAX_CHUNK_MS;
    }

    @Override
    public boolean alreadyTranslated() {
        return toRussian;
    }

    @Override
    public String priceNote() {
        return "тарифицируется по токенам, 32 токена на секунду звука";
    }

    @Override
    public List<Transcript.Segment> transcribe(AudioFile.Clip clip) throws Exception {
        List<GeminiClient.Line> lines = toRussian
                ? client.translate(clip.wav(), false)
                : client.transcribe(clip.wav());
        List<Transcript.Segment> found = new ArrayList<>();
        for (GeminiClient.Line line : lines) {
            if (!line.text().isBlank()) {
                found.add(new Transcript.Segment(line.startMs(), line.text(), ""));
            }
        }
        return found;
    }

    @Override
    public void close() {
        if (client.tokensUsed() > 0) {
            System.out.println("   " + id() + ": токенов " + client.tokensUsed()
                    + ", из них на размышления " + client.thoughtTokens());
        }
    }
}
