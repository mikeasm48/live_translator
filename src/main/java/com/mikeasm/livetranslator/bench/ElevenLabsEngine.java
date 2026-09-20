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

/** ElevenLabs Scribe: универсальная модель, узбекский заявлен в списке языков. */
public final class ElevenLabsEngine implements AsrEngine {

    private static final String URL = "https://api.elevenlabs.io/v1/speech-to-text";

    private final Config config;
    private final String key = Settings.get("LT_ELEVENLABS_KEY");
    private final String model = setting("LT_ELEVENLABS_MODEL", "scribe_v1");

    public ElevenLabsEngine(Config config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "elevenlabs";
    }

    @Override
    public String title() {
        return "ElevenLabs Scribe (" + model + ")";
    }

    @Override
    public String skipReason() {
        return key.isBlank() ? "не задан LT_ELEVENLABS_KEY" : "";
    }

    @Override
    public int maxChunkMs() {
        return 600_000;
    }

    @Override
    public String priceNote() {
        return "тарифицируется по минутам звука";
    }

    @Override
    public List<Transcript.Segment> transcribe(AudioFile.Clip clip) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("model_id", model);
        fields.put("language_code", language());
        String body = Http.postMultipart(URL, Map.of("xi-api-key", key), fields,
                "file", "audio.wav", clip.wav(), "audio/wav");

        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        String language = text(root, "language_code");
        JsonArray words = root.has("words") && root.get("words").isJsonArray()
                ? root.getAsJsonArray("words") : new JsonArray();
        if (words.isEmpty()) {
            String all = text(root, "text");
            return all.isBlank() ? List.of() : List.of(new Transcript.Segment(0, all, language));
        }

        List<Http.Word> parsed = new ArrayList<>();
        for (JsonElement element : words) {
            JsonObject word = element.getAsJsonObject();
            // Служебные элементы («spacing») текста не несут.
            if (word.has("type") && !"word".equals(text(word, "type"))) continue;
            parsed.add(new Http.Word(text(word, "text"),
                    seconds(word, "start") * 1000, seconds(word, "end") * 1000));
        }
        return Http.groupWords(parsed, language);
    }

    /** Scribe ждёт код языка без региона. */
    private String language() {
        return config.primaryLang().split("-")[0].toLowerCase();
    }

    static String setting(String name, String fallback) {
        String value = Settings.get(name);
        return value.isBlank() ? fallback : value;
    }

    static String text(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    static double seconds(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? 0 : value.getAsDouble();
    }
}
