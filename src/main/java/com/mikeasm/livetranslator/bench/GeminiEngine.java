package com.mikeasm.livetranslator.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mikeasm.livetranslator.Config;
import com.mikeasm.livetranslator.Settings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gemini: модель, которая слышит звук сама, без отдельного распознавания.
 * <p>
 * Ради этого движок и добавлен. Вся остальная цепочка устроена как
 * «звук → текст → перевод», и средняя стрелка необратимо теряет акустику:
 * дальше по цепочке «IntelliJ IDEA» можно только угадать по смыслу окружения,
 * потому что произношения уже нет. Мультимодальная модель этого шага не делает.
 * <p>
 * Поэтому движка два. {@code gemini} расшифровывает дословно — его строка
 * сравнима с остальными движками. {@code gemini-ru} переводит звук сразу на
 * русский, минуя текстовое горлышко; сравнивать его с другими по WER
 * бессмысленно, зато можно прочесть и понять, стоит ли вся эта затея свеч.
 */
public final class GeminiEngine implements AsrEngine {

    private static final String URL = "https://generativelanguage.googleapis.com/v1beta/interactions";

    /**
     * Предел запроса — 20 МБ вместе с текстом, а base64 раздувает звук в 4/3.
     * Пять минут 16 кГц моно дают около 13 МБ в кодировке — с запасом.
     */
    private static final int MAX_CHUNK_MS = 300_000;

    /** Отметка времени в начале строки: [12:34]. */
    private static final Pattern STAMP = Pattern.compile("^\\[(\\d{1,2}):(\\d{2})]\\s*(.*)$");

    private static final String TRANSCRIBE = """
            Расшифруй звучащую речь дословно. Не переводи и не пересказывай:
            пиши на том языке, на котором говорят.

            Правила:
            1. Ничего не добавляй от себя и не достраивай оборванные фразы.
            2. Если фрагмент не разобрать, поставь [неразборчиво].
            3. Каждую реплику начинай с отметки времени от начала этого фрагмента
               в формате [ММ:СС].
            4. В ответе — только расшифровка, без пояснений.
            """;

    private static final String TRANSLATE = """
            Переведи звучащую речь на %s. Это рабочая встреча команды
            разработчиков, язык говорящих может меняться посреди разговора.

            Правила:
            1. Переводи то, что сказано, не пересказывая и ничего не добавляя.
            2. Не достраивай оборванные фразы: оборви перевод так же.
            3. Если фрагмент не разобрать, поставь [неразборчиво].
            4. Названия технологий пиши общепринятым написанием.
            5. Каждую реплику начинай с отметки времени от начала этого фрагмента
               в формате [ММ:СС].
            6. В ответе — только перевод, без пояснений.
            """;

    private final Config config;
    private final boolean toRussian;
    /** Перечислять ли модели ожидаемые термины прямо в задании. */
    private final boolean withTerms;
    /** Сколько токенов израсходовано за прогон — по ним считается счёт. */
    private final java.util.concurrent.atomic.AtomicLong tokens =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong thoughtTokens =
            new java.util.concurrent.atomic.AtomicLong();
    private final String key = Settings.get("LT_GEMINI_KEY");
    private final String model = ElevenLabsEngine.setting("LT_GEMINI_MODEL", "gemini-3.5-flash");
    private final String revision = ElevenLabsEngine.setting("LT_GEMINI_REVISION", "2026-05-20");
    /**
     * Сколько модели позволено размышлять перед ответом.
     * <p>
     * Расшифровка — работа механическая: услышал и записал. Размышления здесь
     * съедают и время, и деньги, а рассуждать особо не о чем. Пустое значение
     * оставляет умолчание модели.
     */
    private final String thinking = Settings.get("LT_GEMINI_THINKING");

    public GeminiEngine(Config config, boolean toRussian) {
        this(config, toRussian, false);
    }

    public GeminiEngine(Config config, boolean toRussian, boolean withTerms) {
        this.config = config;
        this.toRussian = toRussian;
        this.withTerms = withTerms;
    }

    @Override
    public String id() {
        String base = toRussian ? "gemini-ru" : "gemini";
        return withTerms ? base + "-terms" : base;
    }

    @Override
    public String title() {
        String base = toRussian
                ? "Gemini " + model + ", звук сразу в перевод"
                : "Gemini " + model + ", дословная расшифровка";
        return withTerms
                ? base + ", с подсказкой терминов (" + Terms.expected(config).size() + ")"
                : base;
    }

    @Override
    public String skipReason() {
        return key.isBlank() ? "не задан LT_GEMINI_KEY" : "";
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

    /** Задание модели: правила плюс, если просили, список ожидаемых терминов. */
    private String task() {
        StringBuilder text = new StringBuilder(toRussian
                ? TRANSLATE.formatted(languageName(config.targetLang))
                : TRANSCRIBE);
        if (withTerms) {
            List<String> terms = Terms.expected(config);
            if (!terms.isEmpty()) {
                text.append("\nВ речи, скорее всего, прозвучат эти термины. ")
                        .append("Узнавай их на слух и пиши именно так:\n");
                for (String term : terms) text.append("- ").append(term).append('\n');
            }
        }
        return text.toString();
    }

    @Override
    public List<Transcript.Segment> transcribe(AudioFile.Clip clip) throws Exception {
        JsonObject prompt = new JsonObject();
        prompt.addProperty("type", "text");
        prompt.addProperty("text", task());

        JsonObject audio = new JsonObject();
        audio.addProperty("type", "audio");
        audio.addProperty("mime_type", "audio/wav");
        audio.addProperty("data", Base64.getEncoder().encodeToString(clip.wav()));

        JsonArray input = new JsonArray();
        input.add(prompt);
        input.add(audio);

        JsonObject request = new JsonObject();
        request.addProperty("model", model);
        request.add("input", input);
        if (!thinking.isBlank()) {
            JsonObject generation = new JsonObject();
            generation.addProperty("thinking_level", thinking);
            request.add("generation_config", generation);
        }

        String body = Http.postJson(URL, Map.of(
                "x-goog-api-key", key,
                "Api-Revision", revision), request.toString());
        count(body);
        return parse(answer(body));
    }

    /** Складывает израсходованные токены: без них не посчитать стоимость часа. */
    private void count(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!root.has("usage")) return;
            JsonObject usage = root.getAsJsonObject("usage");
            tokens.addAndGet((long) ElevenLabsEngine.seconds(usage, "total_tokens"));
            thoughtTokens.addAndGet((long) ElevenLabsEngine.seconds(usage, "total_thought_tokens"));
        } catch (RuntimeException ignored) {
            // Счётчик — удобство, а не работа движка: его сбой ничего не ломает.
        }
    }

    @Override
    public void close() {
        long total = tokens.get();
        if (total == 0) return;
        System.out.printf("   %s: токенов %d, из них на размышления %d%n",
                id(), total, thoughtTokens.get());
    }

    /**
     * Текст ответа.
     * <p>
     * Ответ приходит списком шагов: сначала размышления модели, потом сам
     * результат. Берём только шаги {@code model_output} — размышления в
     * расшифровку попадать не должны.
     * <p>
     * Если текста не нашлось, дальше гадать бессмысленно: показываем, что
     * сервис прислал на самом деле. Имена моделей и вид ответа у Gemini
     * меняются, и молчаливый пустой результат в отчёте хуже понятной ошибки.
     */
    private static String answer(String body) throws IOException {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        // На случай, если очередная ревизия API вернётся к плоскому полю.
        if (root.has("output_text")) return ElevenLabsEngine.text(root, "output_text");

        StringBuilder text = new StringBuilder();
        if (root.has("steps") && root.get("steps").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("steps")) {
                if (!element.isJsonObject()) continue;
                JsonObject step = element.getAsJsonObject();
                if (!"model_output".equals(ElevenLabsEngine.text(step, "type"))) continue;
                if (!step.has("content") || !step.get("content").isJsonArray()) continue;
                for (JsonElement part : step.getAsJsonArray("content")) {
                    if (!part.isJsonObject()) continue;
                    JsonObject piece = part.getAsJsonObject();
                    if (!"text".equals(ElevenLabsEngine.text(piece, "type"))) continue;
                    if (!text.isEmpty()) text.append('\n');
                    text.append(ElevenLabsEngine.text(piece, "text"));
                }
            }
        }
        if (!text.isEmpty()) return text.toString();

        String keys = String.join(", ", root.keySet());
        String snippet = body.length() > 300 ? body.substring(0, 300) + "…" : body;
        throw new IOException("в ответе нет текста (поля: " + keys + "): " + snippet);
    }

    /**
     * Разбирает строки вида «[01:23] текст». Строку без отметки приклеиваем к
     * предыдущей: модель иногда переносит длинную реплику.
     */
    private static List<Transcript.Segment> parse(String answer) {
        List<Transcript.Segment> found = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int startMs = 0;

        for (String raw : answer.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            Matcher stamp = STAMP.matcher(line);
            if (!stamp.matches()) {
                if (current.isEmpty()) startMs = 0;
                else current.append(' ');
                current.append(line);
                continue;
            }
            if (!current.isEmpty()) {
                found.add(new Transcript.Segment(startMs, current.toString().strip(), ""));
                current.setLength(0);
            }
            startMs = (Integer.parseInt(stamp.group(1)) * 60 + Integer.parseInt(stamp.group(2))) * 1000;
            current.append(stamp.group(3).strip());
        }
        if (!current.isEmpty()) {
            found.add(new Transcript.Segment(startMs, current.toString().strip(), ""));
        }
        return found;
    }

    private static String languageName(String code) {
        return switch (code.split("-")[0].toLowerCase()) {
            case "ru" -> "русский язык";
            case "en" -> "английский язык";
            case "uz" -> "узбекский язык";
            default -> code;
        };
    }
}
