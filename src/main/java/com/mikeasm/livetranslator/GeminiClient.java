package com.mikeasm.livetranslator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Обращение к Gemini: модель слушает звук и отвечает текстом.
 * <p>
 * Отличие от прежней схемы принципиальное. Раньше было «звук → текст →
 * перевод», и средняя стрелка необратимо теряла акустику: дальше по цепочке
 * «IntelliJ IDEA» можно было только угадать по смыслу соседних слов, потому что
 * произношения уже ни у кого не оставалось. Модель, которая слышит сама, этого
 * шага не делает.
 * <p>
 * Один класс на два применения — живой перевод и стенд сравнения, — чтобы
 * замеренное на стенде совпадало с тем, что работает на встрече.
 */
public final class GeminiClient {

    private static final String URL = "https://generativelanguage.googleapis.com/v1beta/interactions";

    /** Отметка времени в начале строки: [12:34]. */
    private static final Pattern STAMP = Pattern.compile("^\\[(\\d{1,3}):(\\d{2})]\\s*(.*)$");

    private static final String RULES = """
            Правила:
            1. Передавай то, что сказано, не пересказывая и ничего не добавляя.
            2. Не достраивай оборванные фразы: оборви так же, как оборвалось.
            3. Если фрагмент не разобрать, поставь [неразборчиво]. Выдуманная
               фраза хуже пропуска.
            4. Названия технологий пиши общепринятым написанием.
            5. Не отвечай на содержание реплик и не добавляй ничего от себя.
            6. Одна строка — одна законченная реплика. Не дроби слова между
               строками и не обрывай строку посреди слова.
            """;

    private static final String TRANSCRIBE = """
            Расшифруй звучащую речь дословно. Не переводи и не пересказывай:
            пиши на том языке, на котором говорят.

            %s
            Каждую реплику начинай с отметки времени от начала этого фрагмента
            в формате [ММ:СС]. В ответе — только расшифровка.
            """;

    private static final String TRANSLATE = """
            Это рабочая встреча команды разработчиков, язык говорящих может
            меняться посреди разговора. Переведи звучащую речь на %s.

            %s
            Ответ выводи строками такого вида, по две на каждую реплику:
            [ММ:СС] перевод
            > то же самое на языке оригинала

            Отметка времени — от начала этого фрагмента. Больше в ответе ничего
            быть не должно.
            """;

    /** Одна реплика: перевод и, если просили, исходные слова. */
    public record Line(int startMs, String text, String original) {}

    private final Config config;
    /** Перечислять ли модели ожидаемые термины. */
    private final boolean withTerms;
    private final AtomicLong tokens = new AtomicLong();
    private final AtomicLong thoughts = new AtomicLong();

    /** Сколько предыдущих реплик держать как контекст. */
    private static final int CONTEXT_DEPTH = 3;

    /**
     * Предыдущие переводы — чтобы модель не теряла нить между кусками.
     * <p>
     * Без контекста местоимения живут своей жизнью: «он» в новом куске не с чем
     * связать. Прежний переводчик на YandexGPT держал для этого четыре
     * последние реплики, и терять это при переезде нельзя.
     * <p>
     * Контекст именно текстовый, хотя у сервиса есть и своя память разговора
     * через {@code previous_interaction_id}. Проверено на живом прогоне: с
     * серверной историей 3,6 минуты встречи обошлись в 198 722 токена вместо
     * 22 000, потому что весь прошлый звук перевыставляется в счёт с каждым
     * новым куском, и чем дольше встреча, тем быстрее растёт цена. Три строки
     * текста дают то же понимание за сотню токенов.
     */
    private final java.util.Deque<String> context = new java.util.ArrayDeque<>();

    public GeminiClient(Config config) {
        this(config, true);
    }

    public GeminiClient(Config config, boolean withTerms) {
        this.config = config;
        this.withTerms = withTerms;
    }

    /** Почему работать нечем, или пустая строка. */
    public String skipReason() {
        return config.geminiKey().isBlank() ? "не задан ключ Gemini (LT_GEMINI_KEY)" : "";
    }

    public long tokensUsed() {
        return tokens.get();
    }

    public long thoughtTokens() {
        return thoughts.get();
    }

    public int expectedTermCount() {
        return withTerms ? expectedTerms().size() : 0;
    }

    /** Забывает предыдущие реплики: после сбоя контекст может быть рваным. */
    public synchronized void resetConversation() {
        context.clear();
    }

    /**
     * Переводит кусок звука на язык перевода.
     *
     * @param withOriginal просить ли вместе с переводом исходные слова: они
     *                     нужны в расшифровке встречи и под переводом в окне
     */
    public List<Line> translate(byte[] wav, boolean withOriginal) throws IOException {
        return ask(wav, task(TRANSLATE.formatted(languageName(config.targetLang), RULES),
                withOriginal));
    }

    /** Расшифровывает кусок дословно, без перевода. */
    public List<Line> transcribe(byte[] wav) throws IOException {
        return ask(wav, task(TRANSCRIBE.formatted(RULES), false));
    }

    /** Задание модели: правила плюс список слов, которые стоит ожидать. */
    private String task(String instruction, boolean withOriginal) {
        StringBuilder text = new StringBuilder(instruction);
        if (!withOriginal) {
            // Строка с оригиналом просится в самой инструкции перевода; когда
            // она не нужна, просьбу надо снять, иначе модель её всё равно даст.
            int at = text.indexOf("\n> то же самое на языке оригинала");
            if (at >= 0) text.delete(at, at + "\n> то же самое на языке оригинала".length());
        }
        List<String> terms = withTerms ? expectedTerms() : List.of();
        if (!terms.isEmpty()) {
            text.append("\nВ речи, скорее всего, прозвучат эти термины. ")
                    .append("Узнавай их на слух и пиши именно так:\n");
            for (String term : terms) text.append("- ").append(term).append('\n');
        }
        return text.toString();
    }

    /**
     * Слова, которые стоит ожидать: словарь приложения плюс добавленные в
     * {@code LT_BENCH_PHRASES}. Распознаванию перевод не нужен — ему надо
     * заранее знать, какие слова могут прозвучать.
     */
    private List<String> expectedTerms() {
        java.util.LinkedHashSet<String> terms = new java.util.LinkedHashSet<>();
        try {
            Glossary.load(java.nio.file.Path.of(config.glossaryPath), false)
                    .ifPresent(glossary -> terms.addAll(glossary.sourceTerms()));
        } catch (Exception ignored) {
            // Словарь — подсказка, а не условие работы.
        }
        for (String extra : Settings.get("LT_BENCH_PHRASES").split(",")) {
            String term = extra.trim();
            if (!term.isEmpty()) terms.add(term);
        }
        return List.copyOf(terms);
    }

    private List<Line> ask(byte[] wav, String task) throws IOException {
        JsonObject prompt = new JsonObject();
        prompt.addProperty("type", "text");
        prompt.addProperty("text", task + contextBlock());

        // Сжатие — деталь разговора с сервисом, поэтому живёт здесь: и живой
        // перевод, и стенд должны уезжать одинаково, иначе замеренное на
        // стенде перестанет совпадать с тем, что работает на встрече.
        AudioCodec.Payload payload = AudioCodec.forUpload(wav, config);
        JsonObject audio = new JsonObject();
        audio.addProperty("type", "audio");
        audio.addProperty("mime_type", payload.mimeType());
        audio.addProperty("data", Base64.getEncoder().encodeToString(payload.data()));

        JsonArray input = new JsonArray();
        input.add(prompt);
        input.add(audio);

        JsonObject request = new JsonObject();
        request.addProperty("model", config.geminiModel());
        request.add("input", input);
        // Хранить разговор на сервере незачем: своей памятью мы не пользуемся,
        // а запись рабочей встречи лежала бы у сервиса 55 дней.
        request.addProperty("store", false);
        if (!config.geminiThinking().isBlank()) {
            JsonObject generation = new JsonObject();
            generation.addProperty("thinking_level", config.geminiThinking());
            request.add("generation_config", generation);
        }

        String body = Http.postJson(URL, Map.of(
                "x-goog-api-key", config.geminiKey(),
                "Api-Revision", Settings.get("LT_GEMINI_REVISION").isBlank()
                        ? "2026-05-20" : Settings.get("LT_GEMINI_REVISION")),
                request.toString());
        count(body);
        List<Line> lines = parse(answer(body));
        remember(lines);
        return lines;
    }

    /** Запоминает переводы, чтобы следующий кусок переводился со связью. */
    private synchronized void remember(List<Line> lines) {
        for (Line line : lines) {
            if (line.text().isBlank()) continue;
            context.addLast(line.text());
            while (context.size() > CONTEXT_DEPTH) context.removeFirst();
        }
    }

    /** Предыдущие реплики отдельным блоком, чтобы их не перевели заново. */
    private synchronized String contextBlock() {
        if (context.isEmpty()) return "";
        StringBuilder text = new StringBuilder(
                "\nПеред этим фрагментом на встрече прозвучало следующее. "
                        + "Это только для связности — переводить заново не нужно:\n");
        for (String line : context) text.append("- ").append(line).append('\n');
        return text.toString();
    }

    /** Складывает израсходованные токены: по ним считается счёт. */
    private void count(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!root.has("usage")) return;
            JsonObject usage = root.getAsJsonObject("usage");
            tokens.addAndGet(number(usage, "total_tokens"));
            thoughts.addAndGet(number(usage, "total_thought_tokens"));
        } catch (RuntimeException ignored) {
            // Счётчик — удобство, а не работа клиента.
        }
    }

    static long number(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? 0 : value.getAsLong();
    }

    /**
     * Текст ответа.
     * <p>
     * Ответ приходит списком шагов: сначала размышления модели, потом
     * результат. Берём только шаги {@code model_output} — размышления в
     * расшифровку попадать не должны.
     */
    private String answer(String body) throws IOException {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        // На случай, если очередная ревизия API вернётся к плоскому полю.
        if (root.has("output_text")) return string(root, "output_text");

        StringBuilder text = new StringBuilder();
        if (root.has("steps") && root.get("steps").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("steps")) {
                if (!element.isJsonObject()) continue;
                JsonObject step = element.getAsJsonObject();
                if (!"model_output".equals(string(step, "type"))) continue;
                if (!step.has("content") || !step.get("content").isJsonArray()) continue;
                for (JsonElement part : step.getAsJsonArray("content")) {
                    if (!part.isJsonObject()) continue;
                    JsonObject piece = part.getAsJsonObject();
                    if (!"text".equals(string(piece, "type"))) continue;
                    if (!text.isEmpty()) text.append('\n');
                    text.append(string(piece, "text"));
                }
            }
        }
        if (!text.isEmpty()) return text.toString();

        // Модель может не сказать ничего: так бывает на коротком хвосте встречи,
        // где остался вдох да щелчок. Это не сбой, это тишина.
        if (root.has("usage")
                && number(root.getAsJsonObject("usage"), "total_output_tokens") == 0) {
            return "";
        }

        String snippet = body.length() > 300 ? body.substring(0, 300) + "…" : body;
        throw new IOException("в ответе нет текста (поля: "
                + String.join(", ", root.keySet()) + "): " + snippet);
    }

    static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    /**
     * Разбирает ответ: строка с отметкой времени начинает реплику, строка с
     * «&gt;» даёт её оригинал, всё прочее приклеивается к предыдущей — модель
     * иногда переносит длинную реплику.
     */
    static List<Line> parse(String answer) {
        List<Line> found = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        StringBuilder original = new StringBuilder();
        int startMs = 0;
        boolean open = false;

        for (String raw : answer.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;

            Matcher stamp = STAMP.matcher(line);
            if (stamp.matches()) {
                if (open) found.add(line(startMs, text, original));
                text.setLength(0);
                original.setLength(0);
                startMs = (Integer.parseInt(stamp.group(1)) * 60
                        + Integer.parseInt(stamp.group(2))) * 1000;
                text.append(stamp.group(3).strip());
                open = true;
            } else if (line.startsWith(">")) {
                if (!original.isEmpty()) original.append(' ');
                original.append(line.substring(1).strip());
            } else {
                if (!text.isEmpty()) text.append(' ');
                text.append(line);
                open = true;
            }
        }
        if (open) found.add(line(startMs, text, original));
        return join(found);
    }

    /**
     * Склеивает оборванные строки.
     * <p>
     * Модель иногда дробит реплику на куски по одному слову: «System», «out»,
     * «print» тремя строками. Признак обрыва — предыдущая строка не кончилась
     * знаком конца предложения; тогда следующая её продолжает. Внутри одного
     * ответа это надёжно, потому что законченную мысль модель знаками
     * завершает.
     */
    private static List<Line> join(List<Line> lines) {
        List<Line> joined = new ArrayList<>();
        for (Line line : lines) {
            if (!joined.isEmpty() && unfinished(joined.get(joined.size() - 1).text())) {
                Line previous = joined.remove(joined.size() - 1);
                joined.add(new Line(previous.startMs(),
                        (previous.text() + " " + line.text()).strip(),
                        (previous.original() + " " + line.original()).strip()));
            } else {
                joined.add(line);
            }
        }
        return joined;
    }

    private static boolean unfinished(String text) {
        if (text.isBlank()) return false;
        char last = text.charAt(text.length() - 1);
        return ".!?…:".indexOf(last) < 0;
    }

    private static Line line(int startMs, StringBuilder text, StringBuilder original) {
        return new Line(startMs, clean(text.toString()), clean(original.toString()));
    }

    /**
     * Убирает отметки времени, оставшиеся внутри самой реплики: свою мы уже
     * разобрали, а эти модель вставляет посреди текста, и читать они мешают.
     * <p>
     * Заодно пострадает произнесённое время вроде «в 10:30». На рабочей встрече
     * разработчиков это редкость, а мусор от модели — нет.
     */
    private static String clean(String text) {
        return text.strip()
                .replaceAll("(?<![\\d:])\\d{1,3}:\\d{2}(?![\\d:])", " ")
                .replaceAll("\\s{2,}", " ")
                .strip();
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
