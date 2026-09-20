package com.mikeasm.livetranslator.bench;

import com.mikeasm.livetranslator.Config;
import com.mikeasm.livetranslator.SpeechKitStream;
import com.mikeasm.livetranslator.YandexGrpc;
import io.grpc.ManagedChannel;
import yandex.cloud.api.ai.stt.v3.Stt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SpeechKit v3 — то, чем приложение пользуется сейчас, и точка отсчёта для всех
 * остальных движков.
 * <p>
 * Стенд гоняет его в двух видах. {@code yandex-live} повторяет настройки живого
 * перевода: тот же режим REAL_TIME, та же нарезка фраз по паузам. {@code
 * yandex-offline} включает FULL_DATA — режим для готовых файлов, где модель
 * видит запись целиком и не обязана отвечать немедленно. Разница между этими
 * двумя строками отчёта показывает, сколько качества стоит сама живость: если
 * она велика, чинить надо не движок, а то, что мы просим у него невозможного.
 */
public final class YandexEngine implements AsrEngine {

    /** Предел одной сессии — 5 минут; берём с запасом. */
    private static final int MAX_CHUNK_MS = 240_000;

    /** Столько отсчётов отдаём за раз: канал сам придержит, если сервер не успевает. */
    private static final int SEND_BYTES = 16 * 1024;

    private final Config config;
    private final boolean fullData;
    private final ManagedChannel channel;

    public YandexEngine(Config config, boolean fullData) {
        this.config = config;
        this.fullData = fullData;
        this.channel = ready() ? YandexGrpc.channel(YandexGrpc.STT_ENDPOINT, config) : null;
    }

    private boolean ready() {
        return !config.apiKey.isBlank() || !config.iamToken.isBlank();
    }

    @Override
    public String id() {
        return fullData ? "yandex-offline" : "yandex-live";
    }

    @Override
    public String title() {
        return fullData
                ? "Yandex SpeechKit v3, отложенный режим (FULL_DATA)"
                : "Yandex SpeechKit v3, как в живом переводе (REAL_TIME)";
    }

    @Override
    public String skipReason() {
        return ready() ? "" : "не задан YC_API_KEY";
    }

    @Override
    public int maxChunkMs() {
        return MAX_CHUNK_MS;
    }

    @Override
    public String priceNote() {
        return "около 0,16 ₽ за 15 с звука";
    }

    @Override
    public List<Transcript.Segment> transcribe(AudioFile.Clip clip) throws Exception {
        List<Transcript.Segment> found = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        // Начало фразы сервер не сообщает — только конец. Берём за начало конец
        // предыдущей: на слитной речи это ровно то же самое.
        long[] previousEnd = {0};

        SpeechKitStream stream = new SpeechKitStream(channel, options(clip),
                new SpeechKitStream.Listener() {
                    @Override
                    public void onPartial(String text) {}

                    @Override
                    public void onFinal(long index, String text, String language, long endMs) {
                        synchronized (found) {
                            found.add(new Transcript.Segment((int) previousEnd[0], text, language));
                            previousEnd[0] = endMs;
                        }
                    }

                    @Override
                    public void onRefinement(long index, String text, String language) {}

                    @Override
                    public void onClosed(Throwable error) {
                        failure.set(error);
                        done.countDown();
                    }

                    @Override
                    public void onStatus(String message) {
                        System.err.println("   " + id() + ": " + message);
                    }
                });

        byte[] pcm = clip.pcm();
        for (int at = 0; at < pcm.length; at += SEND_BYTES) {
            int length = Math.min(SEND_BYTES, pcm.length - at);
            byte[] piece = new byte[length];
            System.arraycopy(pcm, at, piece, 0, length);
            stream.sendAudio(piece);
        }
        stream.finish();

        // Отложенный режим отвечает не сразу: ждём с запасом к длине куска.
        long waitMs = Math.max(120_000L, clip.durationMs());
        if (!done.await(waitMs, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("сервер не ответил за " + waitMs / 1000 + " с");
        }
        Throwable error = failure.get();
        if (error != null) throw new IllegalStateException(error.getMessage(), error);
        synchronized (found) {
            return List.copyOf(found);
        }
    }

    /**
     * Настройки живого перевода с двумя поправками: частота берётся из файла,
     * а не из микрофонной, и в отложенном режиме меняется тип обработки.
     */
    private Stt.StreamingOptions options(AudioFile.Clip clip) {
        Stt.StreamingOptions live = SpeechKitStream.sessionOptions(config);
        Stt.RecognitionModelOptions.Builder model = live.getRecognitionModel().toBuilder();
        model.getAudioFormatBuilder().getRawAudioBuilder()
                .setSampleRateHertz(clip.sampleRate())
                .setAudioChannelCount(1);
        model.setAudioProcessingType(fullData
                ? Stt.RecognitionModelOptions.AudioProcessingType.FULL_DATA
                : Stt.RecognitionModelOptions.AudioProcessingType.REAL_TIME);
        return live.toBuilder().setRecognitionModel(model).build();
    }

    @Override
    public void close() {
        if (channel != null) channel.shutdownNow();
    }
}
