package com.mikeasm.livetranslator;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import yandex.cloud.api.ai.translate.v2.TranslationServiceGrpc;
import yandex.cloud.api.ai.translate.v2.TranslationServiceOuterClass.TranslateRequest;
import yandex.cloud.api.ai.translate.v2.TranslationServiceOuterClass.TranslateResponse;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Проверка связки без микрофона: собираются protobuf-сообщения, открывается
 * TLS-соединение и отправляются настройки сессии. Годный ключ не требуется —
 * ответ UNAUTHENTICATED тоже означает, что транспорт и схема в порядке.
 */
public final class SelfTest {

    private SelfTest() {}

    public static void run(Config config, Glossary glossary) throws InterruptedException {
        if (config.usesGemini()) {
            checkGemini(config);
            return;
        }
        checkRecognition(config);
        checkTranslation(config, glossary);
    }

    /**
     * Проверка связки с Gemini без микрофона.
     * <p>
     * Нужна не для отладки, а для встречи. Корпоративный VPN умеет подсунуть
     * нерабочий сервер имён, сеть в поездке бывает своенравной, ключ может быть
     * не тот — и выясняется это обычно в ту минуту, когда все уже собрались.
     * Секунда тишины стоит долей копейки и отвечает на вопрос заранее.
     */
    private static void checkGemini(Config config) {
        // Источник запоминается при чтении значения, поэтому сначала читаем.
        boolean haveKey = !config.geminiKey().isBlank();
        System.out.println("0. Ключ: " + (haveKey ? Settings.origin("LT_GEMINI_KEY") : "не задан")
                + "; модель " + config.geminiModel());
        System.out.println("1. Разрешение имени generativelanguage.googleapis.com…");
        try {
            java.net.InetAddress.getByName("generativelanguage.googleapis.com");
        } catch (java.net.UnknownHostException e) {
            System.out.println("   Имя не разрешается. Это не про ключ и не про приложение:");
            System.out.println("   не отвечает сервер имён. Частая причина — включённый VPN.");
            System.out.println("   Проверить: nslookup generativelanguage.googleapis.com");
            return;
        }

        System.out.println("2. Запрос к модели (секунда тишины)…");
        GeminiClient client = new GeminiClient(config);
        if (!client.skipReason().isBlank()) {
            System.out.println("   " + client.skipReason() + ".");
            System.out.println("   Ключ вводится в настройках: Cmd + , → «Доступ».");
            return;
        }
        try {
            long startedAt = System.currentTimeMillis();
            client.translate(GeminiSession.wav(new byte[config.sampleRate * 2], config.sampleRate),
                    false);
            System.out.println("3. Модель ответила за "
                    + (System.currentTimeMillis() - startedAt) + " мс.");
            System.out.println("   Израсходовано токенов: " + client.tokensUsed() + ".");
            System.out.println("4. Готово, связка работает.");
        } catch (java.io.IOException e) {
            reportGemini(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static void reportGemini(String message) {
        System.out.println("   Запрос не прошёл: " + message);
        if (message.contains("401") || message.contains("403")
                || message.toLowerCase().contains("api key")) {
            System.out.println("   Похоже, ключ не принят. Новый берётся на"
                    + " https://aistudio.google.com/apikey");
            System.out.println("   и вводится в настройках: Cmd + , → «Доступ».");
            return;
        }
        if (message.contains("429")) {
            System.out.println("   Исчерпан лимит запросов. На бесплатном тарифе он"
                    + " невелик — проверьте тариф в AI Studio.");
            return;
        }
        if (message.contains("404")) {
            System.out.println("   Модель " + "не найдена: имена у Gemini меняются."
                    + " Другое имя задаётся настройкой LT_GEMINI_MODEL.");
        }
    }

    /**
     * Перевод — отдельный сервис со своими правами, и идентификатор каталога
     * едет в теле запроса. Успешное распознавание про него ничего не говорит.
     */
    private static void checkTranslation(Config config, Glossary glossary) {
        if (!config.translate) {
            System.out.println("4. Перевод отключён (--no-translate), не проверяю.");
            return;
        }
        System.out.println("4. Перевод через " + YandexGrpc.TRANSLATE_ENDPOINT + ":443"
                + (glossary == null ? "" : " со словарём (" + Glossary.plural(glossary.size()) + ")")
                + "…");
        ManagedChannel channel = YandexGrpc.channel(YandexGrpc.TRANSLATE_ENDPOINT, config);
        try {
            TranslateRequest.Builder request = TranslateRequest.newBuilder()
                    .setSourceLanguageCode(config.primaryLang().split("-")[0])
                    .setTargetLanguageCode(config.targetLang)
                    .setFormat(TranslateRequest.Format.PLAIN_TEXT)
                    .addTexts("Bugun deploy qildik, testlar o'tdi");
            if (!config.folderId.isBlank()) request.setFolderId(config.folderId);
            if (glossary != null) request.setGlossaryConfig(glossary.config());

            TranslateResponse response = TranslationServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(15, TimeUnit.SECONDS)
                    .translate(request.build());
            System.out.println("   проверочная фраза: \"Bugun deploy qildik, testlar o'tdi\"");
            System.out.println("   перевод:           \"" + response.getTranslations(0).getText() + "\"");
            System.out.println("5. Готово, обе части связки работают.");
        } catch (StatusRuntimeException e) {
            reportTranslate(config, e);
        } finally {
            channel.shutdownNow();
        }
    }

    private static void reportTranslate(Config config, StatusRuntimeException e) {
        Status.Code code = e.getStatus().getCode();
        if (code == Status.Code.PERMISSION_DENIED || code == Status.Code.NOT_FOUND) {
            System.out.println("   Каталог " + config.folderId + " недоступен ("
                    + code + ").");
            System.out.println("   Роль ai.translate.user должна быть выдана сервисному "
                    + "аккаунту именно в этом каталоге,");
            System.out.println("   либо укажите в YC_FOLDER_ID тот каталог, где права уже есть.");
            return;
        }
        if (code == Status.Code.INVALID_ARGUMENT) {
            String description = e.getStatus().getDescription();
            System.out.println("   Запрос отклонён (" + code + "): " + description);
            // Сервер в таких случаях сам называет нужный каталог — не надо
            // отправлять человека искать несуществующую проблему в словаре.
            if (description != null && description.toLowerCase().contains("folder")) {
                System.out.println("   Возьмите идентификатор каталога из этого сообщения "
                        + "и пропишите его в YC_FOLDER_ID.");
            } else {
                System.out.println("   Частая причина — словарь: больше " + Glossary.MAX_PAIRS
                        + " пар или неподдерживаемая пара языков.");
            }
            return;
        }
        System.out.println("   Ошибка перевода: " + code + " / " + e.getStatus().getDescription());
    }

    private static void checkRecognition(Config config) throws InterruptedException {
        System.out.println("1. Сборка сообщений SpeechKit и Translate…");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        System.out.println("2. Соединение с " + YandexGrpc.STT_ENDPOINT + ":443…");
        ManagedChannel channel = YandexGrpc.channel(YandexGrpc.STT_ENDPOINT, config);
        try {
            SpeechKitStream stream = new SpeechKitStream(channel, config,
                    new SpeechKitStream.Listener() {
                        @Override
                        public void onPartial(String text) {}

                        @Override
                        public void onFinal(long index, String text, String language, long endMs) {}

                        @Override
                        public void onRefinement(long index, String text, String language) {}

                        @Override
                        public void onClosed(Throwable error) {
                            failure.set(error);
                            done.countDown();
                        }

                        @Override
                        public void onStatus(String message) {
                            System.out.println("   сервер: " + message);
                        }
                    });

            // Полсекунды тишины: сервер обязан либо принять сессию, либо отклонить ключ.
            stream.sendSilence(500);
            stream.finish();

            if (!done.await(20, TimeUnit.SECONDS)) {
                System.out.println("3. Распознавание: сессия открыта, сервер её принял.");
                return;
            }
            Throwable error = failure.get();
            if (error == null) {
                System.out.println("3. Распознавание: сессия открыта и закрыта штатно.");
            } else {
                report(error);
            }
        } finally {
            channel.shutdownNow();
        }
    }

    private static void report(Throwable error) {
        Status.Code code = error instanceof StatusRuntimeException sre
                ? sre.getStatus().getCode()
                : null;
        if (code == Status.Code.UNAUTHENTICATED || code == Status.Code.PERMISSION_DENIED) {
            System.out.println("3. Распознавание: транспорт и схема в порядке, но ключ не принят ("
                    + code + "). Проверьте YC_API_KEY и роли сервисного аккаунта.");
            return;
        }
        System.out.println("3. Распознавание, ошибка: " + (code == null ? error.toString() : code + " / "
                + ((StatusRuntimeException) error).getStatus().getDescription()));
    }
}
