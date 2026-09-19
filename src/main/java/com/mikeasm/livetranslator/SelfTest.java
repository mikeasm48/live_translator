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
        checkRecognition(config);
        checkTranslation(config, glossary);
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
                        public void onFinal(long index, String text, String language) {}

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
