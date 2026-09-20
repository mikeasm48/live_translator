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
 * OpenAI Whisper. Узбекский у Whisper был в обучающих данных, но в малом
 * объёме — тем интереснее увидеть цифру вместо ожиданий.
 */
public final class OpenAiEngine implements AsrEngine {

    private static final String URL = "https://api.openai.com/v1/audio/transcriptions";

    /** 25 МБ на запрос — это примерно 13 минут 16 кГц моно. */
    private static final int MAX_CHUNK_MS = 600_000;

    private final Config config;
    private final String key = Settings.get("LT_OPENAI_KEY");
    private final String model = ElevenLabsEngine.setting("LT_OPENAI_MODEL", "whisper-1");

    public OpenAiEngine(Config config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "openai";
    }

    @Override
    public String title() {
        return "OpenAI " + model;
    }

    @Override
    public String skipReason() {
        return key.isBlank() ? "не задан LT_OPENAI_KEY" : "";
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
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("model", model);
        fields.put("language", config.primaryLang().split("-")[0].toLowerCase());
        fields.put("response_format", "verbose_json");

        String body = Http.postMultipart(URL, Map.of("authorization", "Bearer " + key), fields,
                "file", "audio.wav", clip.wav(), "audio/wav");
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        String language = ElevenLabsEngine.text(root, "language");

        JsonArray segments = root.has("segments") && root.get("segments").isJsonArray()
                ? root.getAsJsonArray("segments") : new JsonArray();
        if (segments.isEmpty()) {
            // Новые модели verbose_json не отдают — остаётся сплошной текст.
            String all = ElevenLabsEngine.text(root, "text");
            return all.isBlank() ? List.of() : List.of(new Transcript.Segment(0, all, language));
        }

        List<Transcript.Segment> found = new ArrayList<>();
        for (JsonElement element : segments) {
            JsonObject segment = element.getAsJsonObject();
            found.add(new Transcript.Segment(
                    (int) (ElevenLabsEngine.seconds(segment, "start") * 1000),
                    ElevenLabsEngine.text(segment, "text").trim(), language));
        }
        return found;
    }
}
