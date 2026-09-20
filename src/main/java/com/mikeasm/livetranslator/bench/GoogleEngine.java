package com.mikeasm.livetranslator.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mikeasm.livetranslator.Config;
import com.mikeasm.livetranslator.Http;
import com.mikeasm.livetranslator.Settings;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Google Cloud Speech-to-Text v2, модель Chirp.
 * <p>
 * Chirp обучен сразу на сотне с лишним языков, и низкоресурсные языки на таких
 * моделях обычно выигрывают больше всего — ради этой проверки движок и
 * добавлен.
 * <p>
 * Ключом здесь служит короткоживущий токен доступа. Его не надо хранить в
 * файле: достаточно задать {@code LT_GOOGLE_TOKEN_CMD=gcloud auth
 * print-access-token}, и стенд будет получать свежий сам.
 */
public final class GoogleEngine implements AsrEngine {

    /** Синхронный recognize принимает не больше минуты звука. */
    private static final int MAX_CHUNK_MS = 55_000;

    /** Токен живёт час; обновляем заметно раньше. */
    private static final long TOKEN_LIFETIME_MS = 30 * 60 * 1000;

    private final Config config;
    private final String project = Settings.get("LT_GOOGLE_PROJECT");
    private final String location = ElevenLabsEngine.setting("LT_GOOGLE_LOCATION", "us-central1");
    private final String model = ElevenLabsEngine.setting("LT_GOOGLE_MODEL", "chirp_2");

    private String token = "";
    private long tokenTakenAt;

    public GoogleEngine(Config config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "google";
    }

    @Override
    public String title() {
        return "Google Cloud STT v2 (" + model + ", " + location + ")";
    }

    @Override
    public String skipReason() {
        if (project.isBlank()) return "не задан LT_GOOGLE_PROJECT";
        if (Settings.get("LT_GOOGLE_TOKEN").isBlank()) {
            return "нет токена: задайте LT_GOOGLE_TOKEN_CMD=gcloud auth print-access-token";
        }
        return "";
    }

    @Override
    public int maxChunkMs() {
        return MAX_CHUNK_MS;
    }

    @Override
    public String priceNote() {
        return "тарифицируется по минутам звука";
    }

    @Override
    public List<Transcript.Segment> transcribe(AudioFile.Clip clip) throws Exception {
        JsonObject recognition = new JsonObject();
        recognition.add("autoDecodingConfig", new JsonObject());
        recognition.addProperty("model", model);
        JsonArray languages = new JsonArray();
        for (String language : languageCodes()) languages.add(language);
        recognition.add("languageCodes", languages);

        JsonObject request = new JsonObject();
        request.add("config", recognition);
        request.addProperty("content", Base64.getEncoder().encodeToString(clip.wav()));

        String url = "https://" + location + "-speech.googleapis.com/v2/projects/" + project
                + "/locations/" + location + "/recognizers/_:recognize";
        String body = Http.postJson(url, Map.of("authorization", "Bearer " + token()),
                request.toString());

        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("results")) return List.of();

        List<Transcript.Segment> found = new ArrayList<>();
        int previousEnd = 0;
        for (JsonElement element : root.getAsJsonArray("results")) {
            JsonObject result = element.getAsJsonObject();
            if (!result.has("alternatives")) continue;
            JsonArray alternatives = result.getAsJsonArray("alternatives");
            if (alternatives.isEmpty()) continue;

            String text = ElevenLabsEngine.text(alternatives.get(0).getAsJsonObject(), "transcript");
            if (text.isBlank()) continue;
            found.add(new Transcript.Segment(previousEnd, text.trim(),
                    ElevenLabsEngine.text(result, "languageCode")));
            previousEnd = offsetMs(ElevenLabsEngine.text(result, "resultEndOffset"), previousEnd);
        }
        return found;
    }

    /**
     * Языки, которые Google согласен слушать одновременно, зависят от модели и
     * региона, поэтому список можно задать отдельно от общих настроек.
     */
    private List<String> languageCodes() {
        String override = Settings.get("LT_GOOGLE_LANGS");
        if (!override.isBlank()) {
            return java.util.Arrays.stream(override.split(",")).map(String::trim)
                    .filter(code -> !code.isEmpty()).toList();
        }
        return List.of(config.primaryLang());
    }

    /** Google отдаёт смещения строкой вида «12.340s». */
    private static int offsetMs(String offset, int fallback) {
        if (offset == null || offset.isBlank()) return fallback;
        try {
            return (int) (Double.parseDouble(offset.replace("s", "")) * 1000);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private synchronized String token() {
        if (token.isBlank() || System.currentTimeMillis() - tokenTakenAt > TOKEN_LIFETIME_MS) {
            token = Settings.get("LT_GOOGLE_TOKEN");
            tokenTakenAt = System.currentTimeMillis();
        }
        return token;
    }
}
