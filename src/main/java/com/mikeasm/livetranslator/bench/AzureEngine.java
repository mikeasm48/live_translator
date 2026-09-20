package com.mikeasm.livetranslator.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mikeasm.livetranslator.Config;
import com.mikeasm.livetranslator.Settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Azure AI Speech. Узбекскую латиницу поддерживает, в том числе быстрой
 * расшифровкой.
 * <p>
 * {@code azure} — быстрая расшифровка (fast transcription): принимает запись
 * целиком, отдаёт реплики с временами и умеет размечать говорящих. Это
 * основной путь, и он же запускается по умолчанию.
 * <p>
 * {@code azure-short} — старый REST для коротких фрагментов. Берёт не больше
 * минуты за раз и отдаёт одну реплику на запрос, то есть на записи встречи
 * заведомо услышит меньше сказанного. Держим его как запасной на случай, если
 * быстрая расшифровка откажется работать с нужным языком или регионом; сам он
 * не запускается, а только по явному {@code --engines=azure-short}.
 */
public final class AzureEngine implements AsrEngine {

    /** Предел старого REST — 60 секунд; берём с запасом. */
    private static final int SHORT_CHUNK_MS = 50_000;

    /** Быстрая расшифровка принимает до пяти часов. */
    private static final int FAST_CHUNK_MS = Integer.MAX_VALUE;

    private static final String API_VERSION = "2024-11-15";

    private final Config config;
    private final boolean fast;
    private final String key = Settings.get("LT_AZURE_KEY");
    private final String region = Settings.get("LT_AZURE_REGION");
    private final String resource = Settings.get("LT_AZURE_RESOURCE");

    public AzureEngine(Config config, boolean fast) {
        this.config = config;
        this.fast = fast;
    }

    @Override
    public String id() {
        return fast ? "azure" : "azure-short";
    }

    @Override
    public String title() {
        return fast
                ? "Azure AI Speech, быстрая расшифровка"
                : "Azure AI Speech, короткие фрагменты по " + SHORT_CHUNK_MS / 1000 + " с";
    }

    @Override
    public String skipReason() {
        if (key.isBlank()) return "не задан LT_AZURE_KEY";
        if (host().isBlank()) return "не задан LT_AZURE_REGION (или LT_AZURE_RESOURCE)";
        return "";
    }

    @Override
    public int maxChunkMs() {
        return fast ? FAST_CHUNK_MS : SHORT_CHUNK_MS;
    }

    @Override
    public String priceNote() {
        return fast
                ? "тарифицируется по часам звука"
                : "тарифицируется по часам звука; отдаёт одну реплику на запрос,"
                        + " поэтому услышит меньше сказанного";
    }

    /**
     * Ресурс со своим поддоменом или обычный региональный адрес — у Azure
     * встречаются оба, и по ключу не догадаться, какой у вас.
     */
    private String host() {
        if (!resource.isBlank()) return resource + ".cognitiveservices.azure.com";
        if (!region.isBlank()) return region + ".api.cognitive.microsoft.com";
        return "";
    }

    @Override
    public List<Transcript.Segment> transcribe(AudioFile.Clip clip) throws Exception {
        return fast ? viaFastTranscription(clip) : viaShortAudio(clip);
    }

    // --- Быстрая расшифровка -------------------------------------------------

    private List<Transcript.Segment> viaFastTranscription(AudioFile.Clip clip) throws Exception {
        JsonObject definition = new JsonObject();
        JsonArray locales = new JsonArray();
        for (String locale : locales()) locales.add(locale);
        definition.add("locales", locales);
        definition.addProperty("profanityFilterMode", "None");

        String url = "https://" + host() + "/speechtotext/transcriptions:transcribe"
                + "?api-version=" + ElevenLabsEngine.setting("LT_AZURE_API_VERSION", API_VERSION);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("definition", definition.toString());

        String body = Http.postMultipart(url, Map.of("Ocp-Apim-Subscription-Key", key,
                        "accept", "application/json"), fields,
                "audio", "audio.wav", clip.wav(), "audio/wav");

        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        List<Transcript.Segment> found = new ArrayList<>();
        if (root.has("phrases")) {
            for (JsonElement element : root.getAsJsonArray("phrases")) {
                JsonObject phrase = element.getAsJsonObject();
                String text = ElevenLabsEngine.text(phrase, "text");
                if (text.isBlank()) continue;
                found.add(new Transcript.Segment(
                        (int) ElevenLabsEngine.seconds(phrase, "offsetMilliseconds"),
                        text, ElevenLabsEngine.text(phrase, "locale")));
            }
        }
        if (found.isEmpty() && root.has("combinedPhrases")) {
            for (JsonElement element : root.getAsJsonArray("combinedPhrases")) {
                JsonObject combined = element.getAsJsonObject();
                String text = ElevenLabsEngine.text(combined, "text");
                if (!text.isBlank()) {
                    found.add(new Transcript.Segment(0, text,
                            ElevenLabsEngine.text(combined, "locale")));
                }
            }
        }
        return found;
    }

    // --- Короткие фрагменты --------------------------------------------------

    private List<Transcript.Segment> viaShortAudio(AudioFile.Clip clip) throws Exception {
        String locale = locales().get(0);
        String url = "https://" + host()
                + "/stt/speech/recognition/conversation/cognitiveservices/v1"
                + "?language=" + locale + "&format=detailed&profanity=raw";

        String body = Http.postBytes(url, Map.of("Ocp-Apim-Subscription-Key", key,
                        "accept", "application/json"), clip.wav(),
                "audio/wav; codecs=audio/pcm; samplerate=" + clip.sampleRate());

        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        String status = ElevenLabsEngine.text(root, "RecognitionStatus");
        // Тишина и отсутствие совпадений — не ошибка: на записи встречи такие
        // куски будут, и прогон из-за них останавливать незачем.
        if (!"Success".equals(status)) return List.of();

        String text = ElevenLabsEngine.text(root, "DisplayText");
        if (text.isBlank() && root.has("NBest")) {
            JsonArray best = root.getAsJsonArray("NBest");
            if (!best.isEmpty()) {
                text = ElevenLabsEngine.text(best.get(0).getAsJsonObject(), "Display");
            }
        }
        return text.isBlank() ? List.of() : List.of(new Transcript.Segment(0, text, locale));
    }

    /** Список языков для Azure: коды там полные, как у нас. */
    private List<String> locales() {
        String override = Settings.get("LT_AZURE_LOCALES");
        if (!override.isBlank()) {
            return java.util.Arrays.stream(override.split(",")).map(String::trim)
                    .filter(code -> !code.isEmpty()).toList();
        }
        return List.of(config.primaryLang());
    }
}
