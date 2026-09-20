package com.mikeasm.livetranslator.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mikeasm.livetranslator.Config;
import com.mikeasm.livetranslator.Settings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AssemblyAI: отложенное распознавание, узбекский в списке языков заявлен.
 * <p>
 * Работает в три приёма — загрузка, задание, опрос готовности, — зато не имеет
 * предела на длину и может размечать говорящих. Для живого перевода не годится,
 * для разбора записи после встречи годится вполне.
 */
public final class AssemblyAiEngine implements AsrEngine {

    private static final String BASE = "https://api.assemblyai.com/v2";
    private static final long POLL_MS = 5_000;
    private static final long GIVE_UP_MS = 60 * 60 * 1000;

    private final Config config;
    private final String key = Settings.get("LT_ASSEMBLYAI_KEY");
    private final String model = Settings.get("LT_ASSEMBLYAI_MODEL");

    public AssemblyAiEngine(Config config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "assemblyai";
    }

    @Override
    public String title() {
        return "AssemblyAI" + (model.isBlank() ? "" : " (" + model + ")");
    }

    @Override
    public String skipReason() {
        return key.isBlank() ? "не задан LT_ASSEMBLYAI_KEY" : "";
    }

    @Override
    public int maxChunkMs() {
        // Предела на длину нет: пусть модель видит встречу целиком.
        return Integer.MAX_VALUE;
    }

    @Override
    public String priceNote() {
        return "тарифицируется по часам звука";
    }

    @Override
    public List<Transcript.Segment> transcribe(AudioFile.Clip clip) throws Exception {
        Map<String, String> auth = Map.of("authorization", key);

        String uploaded = JsonParser.parseString(
                        Http.postBytes(BASE + "/upload", auth, clip.wav(), "application/octet-stream"))
                .getAsJsonObject().get("upload_url").getAsString();

        JsonObject task = new JsonObject();
        task.addProperty("audio_url", uploaded);
        task.addProperty("language_code", config.primaryLang().split("-")[0].toLowerCase());
        if (!model.isBlank()) task.addProperty("speech_model", model);
        String id = JsonParser.parseString(Http.postJson(BASE + "/transcript", auth, task.toString()))
                .getAsJsonObject().get("id").getAsString();

        JsonObject done = awaitResult(auth, id);
        String language = ElevenLabsEngine.text(done, "language_code");

        JsonArray words = done.has("words") && done.get("words").isJsonArray()
                ? done.getAsJsonArray("words") : new JsonArray();
        if (words.isEmpty()) {
            String all = ElevenLabsEngine.text(done, "text");
            return all.isBlank() ? List.of() : List.of(new Transcript.Segment(0, all, language));
        }

        List<Http.Word> parsed = new ArrayList<>();
        for (JsonElement element : words) {
            JsonObject word = element.getAsJsonObject();
            // Времена уже в миллисекундах.
            parsed.add(new Http.Word(ElevenLabsEngine.text(word, "text"),
                    ElevenLabsEngine.seconds(word, "start"),
                    ElevenLabsEngine.seconds(word, "end")));
        }
        return Http.groupWords(parsed, language);
    }

    private JsonObject awaitResult(Map<String, String> auth, String id)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + GIVE_UP_MS;
        while (System.currentTimeMillis() < deadline) {
            JsonObject state = JsonParser.parseString(Http.get(BASE + "/transcript/" + id, auth))
                    .getAsJsonObject();
            String status = ElevenLabsEngine.text(state, "status");
            if ("completed".equals(status)) return state;
            if ("error".equals(status)) {
                throw new IOException("AssemblyAI: " + ElevenLabsEngine.text(state, "error"));
            }
            Thread.sleep(POLL_MS);
        }
        throw new IOException("AssemblyAI не закончил за час");
    }
}
