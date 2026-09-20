package com.mikeasm.livetranslator.bench;

import com.google.gson.JsonArray;
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
    private final String key = Settings.get("LT_GEMINI_KEY");
    private final String model = ElevenLabsEngine.setting("LT_GEMINI_MODEL", "gemini-3.5-flash");
    private final String revision = ElevenLabsEngine.setting("LT_GEMINI_REVISION", "2026-05-20");

    public GeminiEngine(Config config, boolean toRussian) {
        this.config = config;
        this.toRussian = toRussian;
    }

    @Override
    public String id() {
        return toRussian ? "gemini-ru" : "gemini";
    }

    @Override
    public String title() {
        return toRussian
                ? "Gemini " + model + ", звук сразу в перевод"
                : "Gemini " + model + ", дословная расшифровка";
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

    @Override
    public List<Transcript.Segment> transcribe(AudioFile.Clip clip) throws Exception {
        JsonObject prompt = new JsonObject();
        prompt.addProperty("type", "text");
        prompt.addProperty("text", toRussian
                ? TRANSLATE.formatted(languageName(config.targetLang))
                : TRANSCRIBE);

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

        String body = Http.postJson(URL, Map.of(
                "x-goog-api-key", key,
                "Api-Revision", revision), request.toString());
        return parse(answer(body));
    }

    /**
     * Текст ответа. Если поля нет, дальше гадать бессмысленно — показываем, что
     * на самом деле прислали: имена моделей и вид ответа у Gemini меняются, и
     * молчаливый пустой результат в отчёте хуже понятной ошибки.
     */
    private static String answer(String body) throws IOException {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (root.has("output_text")) return ElevenLabsEngine.text(root, "output_text");
        String keys = String.join(", ", root.keySet());
        String snippet = body.length() > 300 ? body.substring(0, 300) + "…" : body;
        throw new IOException("в ответе нет output_text (поля: " + keys + "): " + snippet);
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
