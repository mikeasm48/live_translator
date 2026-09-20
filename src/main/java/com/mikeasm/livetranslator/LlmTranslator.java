package com.mikeasm.livetranslator;

import com.google.protobuf.DoubleValue;
import com.google.protobuf.Int64Value;
import io.grpc.ManagedChannel;
import yandex.cloud.api.ai.foundation_models.v1.TextCommon;
import yandex.cloud.api.ai.foundation_models.v1.TextGenerationServiceGrpc;
import yandex.cloud.api.ai.foundation_models.v1.TextGenerationServiceOuterClass.CompletionRequest;
import yandex.cloud.api.ai.foundation_models.v1.TextGenerationServiceOuterClass.CompletionResponse;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * Перевод языковой моделью вместо обычного машинного перевода.
 * <p>
 * Обычный переводчик получает фразу в отрыве от всего: без предыдущих реплик,
 * без темы разговора и без права усомниться во входных данных. На расшифровке
 * живой речи это подводит дважды. Во-первых, распознавание ошибается на
 * терминах — «IntelliJ IDEA» превращается в «intel e gtn i koruyan sular», и
 * переводчик покорно переводит бессмыслицу. Во-вторых, оборванная фраза
 * достраивается выдуманным продолжением.
 * <p>
 * Языковая модель видит несколько предыдущих реплик, знает предметную область
 * и словарь терминов, поэтому узнаёт искажённое название по окружению и
 * оставляет обрывок обрывком.
 */
public final class LlmTranslator {

    /** Сколько предыдущих пар держать как контекст. */
    private static final int CONTEXT_DEPTH = 4;

    private static final String INSTRUCTION = """
            Ты переводишь живую речь на рабочей встрече команды разработчиков.
            Текст приходит из распознавания речи, поэтому содержит ошибки:
            искажённые названия технологий, разорванные фразы, лишние слова.

            Правила:
            1. Переводи на %s. Если реплика уже на этом языке, повтори её дословно.
            2. Узнавай искажённые названия технологий по смыслу окружения и пиши
               их верно: «intel e gtn» в разговоре про написание кода — это
               «IntelliJ IDEA». Это касается только названий.
            3. Ничего не придумывай. Если часть реплики не поддаётся расшифровке,
               поставь на её месте [неразборчиво] — додумывать смысл нельзя,
               выдуманная фраза хуже пропуска.
            4. Ничего не пропускай: каждая понятная часть должна попасть в перевод.
            5. Не достраивай оборванные фразы: переведи то, что есть, и оборви так же.
            6. Не отвечай на содержание реплики и не добавляй ничего от себя.
            7. В ответе — только перевод, без пояснений и кавычек.
            """;

    private final TextGenerationServiceGrpc.TextGenerationServiceBlockingStub stub;
    private final Config config;
    private final Glossary glossary;
    private final Deque<String> context = new ArrayDeque<>();

    public LlmTranslator(ManagedChannel channel, Config config, Glossary glossary) {
        this.stub = TextGenerationServiceGrpc.newBlockingStub(channel);
        this.config = config;
        this.glossary = glossary;
    }

    /** @return перевод или null, если модель не ответила */
    public String translate(String text, String sourceLang) {
        CompletionRequest request = CompletionRequest.newBuilder()
                .setModelUri("gpt://" + config.folderId + "/" + config.llmModel)
                .setCompletionOptions(TextCommon.CompletionOptions.newBuilder()
                        .setStream(false)
                        // Перевод должен быть предсказуемым, а не изобретательным.
                        .setTemperature(DoubleValue.of(0.1))
                        .setMaxTokens(Int64Value.of(1000)))
                .addMessages(message("system", systemPrompt()))
                .addMessages(message("user", userPrompt(text, sourceLang)))
                .build();

        Iterator<CompletionResponse> responses = stub
                .withDeadlineAfter(config.llmTimeoutSeconds, TimeUnit.SECONDS)
                .completion(request);
        if (!responses.hasNext()) return null;

        CompletionResponse response = responses.next();
        if (response.getAlternativesCount() == 0) return null;
        String answer = response.getAlternatives(0).getMessage().getText().trim();
        if (answer.isBlank()) return null;

        remember(text, answer);
        return strip(answer);
    }

    private String systemPrompt() {
        StringBuilder prompt = new StringBuilder(INSTRUCTION.formatted(languageName(config.targetLang)));
        if (glossary != null && glossary.size() > 0) {
            prompt.append("\nТермины, которые надо узнавать и писать именно так:\n");
            prompt.append(glossary.asPromptList());
        }
        return prompt.toString();
    }

    /** Контекст идёт отдельным блоком, чтобы модель не переводила его заново. */
    private String userPrompt(String text, String sourceLang) {
        StringBuilder prompt = new StringBuilder();
        if (!context.isEmpty()) {
            prompt.append("Предыдущие реплики встречи, для контекста:\n");
            context.forEach(line -> prompt.append(line).append('\n'));
            prompt.append('\n');
        }
        prompt.append("Переведи эту реплику");
        if (!sourceLang.isBlank()) prompt.append(" (язык: ").append(sourceLang).append(")");
        prompt.append(":\n").append(text);
        return prompt.toString();
    }

    private synchronized void remember(String source, String translation) {
        context.addLast(translation);
        while (context.size() > CONTEXT_DEPTH) context.removeFirst();
    }

    /** Модель иногда оборачивает ответ в кавычки — убираем. */
    private static String strip(String answer) {
        String text = answer.trim();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private static TextCommon.Message message(String role, String text) {
        return TextCommon.Message.newBuilder().setRole(role).setText(text).build();
    }

    private static String languageName(String code) {
        return switch (code.split("-")[0].toLowerCase()) {
            case "ru" -> "русский";
            case "en" -> "английский";
            case "uz" -> "узбекский";
            default -> code;
        };
    }
}
