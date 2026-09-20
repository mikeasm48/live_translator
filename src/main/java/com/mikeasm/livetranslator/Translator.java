package com.mikeasm.livetranslator;

import io.grpc.ManagedChannel;
import yandex.cloud.api.ai.translate.v2.TranslationServiceGrpc;
import yandex.cloud.api.ai.translate.v2.TranslationServiceOuterClass.TranslateRequest;
import yandex.cloud.api.ai.translate.v2.TranslationServiceOuterClass.TranslateResponse;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Перевод распознанных фраз через Yandex Translate.
 * Работает в отдельном пуле, чтобы сеть не тормозила поток захвата звука.
 */
public final class Translator implements AutoCloseable {

    private final TranslationServiceGrpc.TranslationServiceBlockingStub stub;
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "translate");
        t.setDaemon(true);
        return t;
    });
    private final Config config;
    private final Glossary glossary;
    private final Consumer<String> statusSink;
    /** Если задан, перевод идёт через языковую модель, а этот — запасной. */
    private final LlmTranslator llm;

    public Translator(ManagedChannel channel, Config config,
                      Glossary glossary, Consumer<String> statusSink) {
        this(channel, config, glossary, statusSink, null);
    }

    public Translator(ManagedChannel channel, Config config, Glossary glossary,
                      Consumer<String> statusSink, LlmTranslator llm) {
        this.stub = TranslationServiceGrpc.newBlockingStub(channel);
        this.config = config;
        this.glossary = glossary;
        this.statusSink = statusSink;
        this.llm = llm;
    }

    /**
     * Переводит здесь и сейчас, с выбором: применять словарь или нет.
     * Нужно для подбора пар — разницу видно только сравнением.
     */
    public String translateOnce(String text, boolean withGlossary) {
        return translateOnce(text, withGlossary, config.primaryLang());
    }

    public String translateOnce(String text, boolean withGlossary, String sourceLang) {
        TranslateRequest.Builder request = TranslateRequest.newBuilder()
                .setSourceLanguageCode(shortCode(sourceLang))
                .setTargetLanguageCode(config.targetLang)
                .setFormat(TranslateRequest.Format.PLAIN_TEXT)
                .addTexts(text);
        if (!config.folderId.isBlank()) request.setFolderId(config.folderId);
        if (withGlossary && glossary != null) request.setGlossaryConfig(glossary.config());

        TranslateResponse response = stub
                .withDeadlineAfter(15, TimeUnit.SECONDS)
                .translate(request.build());
        return response.getTranslationsCount() == 0
                ? "" : response.getTranslations(0).getText();
    }

    /** Приводит uz-UZ к uz: Translate ожидает короткий код. */
    private static String shortCode(String language) {
        return language == null || language.isBlank()
                ? "" : language.split("-")[0].toLowerCase(java.util.Locale.ROOT);
    }

    /** Асинхронно переводит текст; callback вызывается в потоке пула. */
    public void translate(String text, String sourceLang,
                          Consumer<String> callback, Consumer<String> onError) {
        pool.submit(() -> {
            // Языковая модель — основной путь, но сбой в ней не должен оставлять
            // встречу без перевода: тогда работает обычный переводчик.
            if (llm != null) {
                try {
                    String answer = llm.translate(text, sourceLang);
                    if (answer != null && !answer.isBlank()) {
                        callback.accept(answer);
                        return;
                    }
                    statusSink.accept("модель не ответила, перевожу обычным способом");
                } catch (RuntimeException e) {
                    statusSink.accept("модель недоступна (" + shortMessage(e)
                            + "), перевожу обычным способом");
                }
            }
            try {
                TranslateRequest.Builder request = TranslateRequest.newBuilder()
                        .setSourceLanguageCode(shortCode(sourceLang))
                        .setTargetLanguageCode(config.targetLang)
                        .setFormat(TranslateRequest.Format.PLAIN_TEXT)
                        .addTexts(text);
                if (!config.folderId.isBlank()) request.setFolderId(config.folderId);
                if (glossary != null) {
                    Optional<String> reloaded = glossary.reloadIfChanged();
                    reloaded.ifPresent(statusSink);
                    request.setGlossaryConfig(glossary.config());
                }

                TranslateResponse response = stub
                        .withDeadlineAfter(10, TimeUnit.SECONDS)
                        .translate(request.build());
                if (response.getTranslationsCount() > 0) {
                    callback.accept(response.getTranslations(0).getText());
                }
            } catch (RuntimeException e) {
                onError.accept(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
    }

    private static String shortMessage(RuntimeException e) {
        String message = e.getMessage();
        if (message == null) return e.getClass().getSimpleName();
        return message.length() > 80 ? message.substring(0, 80) + "…" : message;
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
