package com.mikeasm.livetranslator;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import yandex.cloud.api.ai.stt.v3.RecognizerGrpc;
import yandex.cloud.api.ai.stt.v3.Stt;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Одна сессия потокового распознавания SpeechKit v3 (Recognizer/RecognizeStreaming).
 * <p>
 * Сессия живёт не дольше нескольких минут — переподключением занимается
 * {@link RecognizerSession}.
 */
public final class SpeechKitStream {

    /** Слушатель событий распознавания. */
    public interface Listener {
        /** Промежуточная гипотеза, текст ещё будет меняться. */
        void onPartial(String text);

        /**
         * Фраза распознана. {@code language} — определённый язык или пусто,
         * {@code endMs} — конец фразы от начала потока.
         */
        void onFinal(long index, String text, String language, long endMs);

        /** Уточнённый (нормализованный) вариант ранее выданной фразы. */
        void onRefinement(long index, String text, String language);

        /** Сессия закрылась: по ошибке (error != null) или штатно. */
        void onClosed(Throwable error);

        /** Служебное сообщение сервера. */
        void onStatus(String message);
    }

    private final StreamObserver<Stt.StreamingRequest> requests;
    private final AtomicBoolean closed = new AtomicBoolean();

    public SpeechKitStream(ManagedChannel channel, Config config, Listener listener) {
        this(channel, sessionOptions(config), listener);
    }

    /**
     * Сессия с готовыми настройками. Нужна стенду сравнения движков: он гоняет
     * ту же связку в отложенном режиме и с другой частотой дискретизации.
     */
    public SpeechKitStream(ManagedChannel channel, Stt.StreamingOptions options,
                           Listener listener) {
        RecognizerGrpc.RecognizerStub stub = RecognizerGrpc.newStub(channel);
        this.requests = stub.recognizeStreaming(new StreamObserver<>() {
            @Override
            public void onNext(Stt.StreamingResponse response) {
                switch (response.getEventCase()) {
                    case PARTIAL -> {
                        String text = firstAlternative(response.getPartial());
                        if (!text.isBlank()) listener.onPartial(text);
                    }
                    case FINAL -> {
                        String text = firstAlternative(response.getFinal());
                        if (!text.isBlank()) {
                            listener.onFinal(response.getAudioCursors().getFinalIndex(), text,
                                    detectedLanguage(response.getFinal()),
                                    response.getAudioCursors().getFinalTimeMs());
                        }
                    }
                    case FINAL_REFINEMENT -> {
                        Stt.FinalRefinement refinement = response.getFinalRefinement();
                        String text = firstAlternative(refinement.getNormalizedText());
                        if (!text.isBlank()) {
                            listener.onRefinement(refinement.getFinalIndex(), text,
                                    detectedLanguage(refinement.getNormalizedText()));
                        }
                    }
                    case STATUS_CODE -> {
                        Stt.StatusCode status = response.getStatusCode();
                        // CLOSED приходит при каждой штатной ротации потока —
                        // показывать это пользователю незачем.
                        boolean routine = status.getCodeType() == Stt.CodeType.WORKING
                                || status.getCodeType() == Stt.CodeType.CLOSED;
                        if (!routine) {
                            listener.onStatus(status.getCodeType() + ": " + status.getMessage());
                        }
                    }
                    default -> { /* eou_update и аналитика нам не нужны */ }
                }
            }

            @Override
            public void onError(Throwable t) {
                if (closed.compareAndSet(false, true)) listener.onClosed(t);
            }

            @Override
            public void onCompleted() {
                if (closed.compareAndSet(false, true)) listener.onClosed(null);
            }
        });

        requests.onNext(Stt.StreamingRequest.newBuilder()
                .setSessionOptions(options)
                .build());
    }

    /** Настройки сессии, какими их использует живой перевод. */
    public static Stt.StreamingOptions sessionOptions(Config config) {
        return Stt.StreamingOptions.newBuilder()
                .setRecognitionModel(Stt.RecognitionModelOptions.newBuilder()
                        .setModel("general")
                        .setAudioFormat(Stt.AudioFormatOptions.newBuilder()
                                .setRawAudio(Stt.RawAudio.newBuilder()
                                        .setAudioEncoding(Stt.RawAudio.AudioEncoding.LINEAR16_PCM)
                                        .setSampleRateHertz(config.sampleRate)
                                        .setAudioChannelCount(1)))
                        .setTextNormalization(Stt.TextNormalizationOptions.newBuilder()
                                .setTextNormalization(Stt.TextNormalizationOptions
                                        .TextNormalization.TEXT_NORMALIZATION_ENABLED)
                                .setProfanityFilter(false)
                                // Пунктуация — это то, по чему переводчик понимает
                                // границы мыслей; на слитной речи может помочь.
                                .setLiteratureText(config.literature()))
                        .setLanguageRestriction(Stt.LanguageRestrictionOptions.newBuilder()
                                .setRestrictionType(Stt.LanguageRestrictionOptions
                                        .LanguageRestrictionType.WHITELIST)
                                .addAllLanguageCode(config.sourceLangs()))
                        .setAudioProcessingType(
                                Stt.RecognitionModelOptions.AudioProcessingType.REAL_TIME))
                // Лектор или увлёкшийся докладчик говорит почти без пауз, и при
                // настройках по умолчанию фраза тянется десятками секунд: перевод
                // приходит поздно и целой простынёй. Поэтому режем чаще.
                .setEouClassifier(Stt.EouClassifierOptions.newBuilder()
                        .setDefaultClassifier(Stt.DefaultEouClassifier.newBuilder()
                                .setType(config.eouHigh()
                                        ? Stt.DefaultEouClassifier.EouSensitivity.HIGH
                                        : Stt.DefaultEouClassifier.EouSensitivity.DEFAULT)
                                .setMaxPauseBetweenWordsHintMs(config.pauseMs())))
                .build();
    }

    /** Самая вероятная языковая метка фразы; пусто, если сервис её не дал. */
    private static String detectedLanguage(Stt.AlternativeUpdate update) {
        if (update.getAlternativesCount() == 0) return "";
        return update.getAlternatives(0).getLanguagesList().stream()
                .max(java.util.Comparator.comparingDouble(Stt.LanguageEstimation::getProbability))
                .map(Stt.LanguageEstimation::getLanguageCode)
                .orElse("");
    }

    private static String firstAlternative(Stt.AlternativeUpdate update) {
        return update.getAlternativesCount() == 0 ? "" : update.getAlternatives(0).getText();
    }

    public void sendAudio(byte[] pcm) {
        if (closed.get()) return;
        requests.onNext(Stt.StreamingRequest.newBuilder()
                .setChunk(Stt.AudioChunk.newBuilder().setData(ByteString.copyFrom(pcm)))
                .build());
    }

    public void sendSilence(int durationMs) {
        if (closed.get()) return;
        requests.onNext(Stt.StreamingRequest.newBuilder()
                .setSilenceChunk(Stt.SilenceChunk.newBuilder().setDurationMs(durationMs))
                .build());
    }

    /** Завершает отправку; сервер дошлёт финальные результаты и закроет поток. */
    public void finish() {
        if (closed.get()) return;
        try {
            requests.onCompleted();
        } catch (Throwable ignored) {
            // Поток мог быть уже разорван сервером. Ловим Throwable, а не
            // RuntimeException: при закрытии приложения сюда долетает и
            // NoClassDefFoundError, если jar подменили под работающей JVM.
            // Что бы ни случилось, закрытие должно продолжаться.
        }
    }
}
