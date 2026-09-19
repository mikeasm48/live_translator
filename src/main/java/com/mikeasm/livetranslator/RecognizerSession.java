package com.mikeasm.livetranslator;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Надстройка над {@link SpeechKitStream}, которая держит распознавание живым
 * сколь угодно долго.
 * <p>
 * Ограничение API: один поток живёт не более 5 минут и не более 10 МБ данных.
 * Поэтому поток проактивно перезапускается — по возможности в паузе между
 * фразами, чтобы не разорвать предложение, а при жёстком лимите — принудительно.
 * Индексы фраз сквозные между потоками, иначе после переподключения обновления
 * попадали бы в чужие строки.
 */
public final class RecognizerSession {

    private static final long SOFT_LIMIT_MS = 210_000;
    private static final long HARD_LIMIT_MS = 270_000;
    private static final long RECONNECT_DELAY_MS = 1_000;
    private static final long MAX_RECONNECT_DELAY_MS = 30_000;

    private final ManagedChannel channel;
    private final Config config;
    private final SpeechKitStream.Listener listener;
    private final AtomicLong indexOffset = new AtomicLong();

    private volatile SpeechKitStream stream;
    private volatile long streamStartedAt;
    private volatile long maxIndexInStream;
    private volatile boolean stopped;
    /** Пауза: поток закрыт и звук в облако не уходит, но сессию можно поднять. */
    private volatile boolean paused;
    private volatile long reconnectDelay = RECONNECT_DELAY_MS;
    /** Метка текущего потока: закрытие устаревшего не должно поднимать новый. */
    private volatile Object currentToken;
    /** Когда последний раз сервер признал фразу законченной. */
    private volatile long lastFinalAt = System.currentTimeMillis();

    public RecognizerSession(ManagedChannel channel, Config config,
                             SpeechKitStream.Listener listener) {
        this.channel = channel;
        this.config = config;
        this.listener = listener;
        open();
    }

    private void open() {
        maxIndexInStream = 0;
        streamStartedAt = System.currentTimeMillis();
        long offset = indexOffset.get();
        Object token = new Object();
        currentToken = token;
        stream = new SpeechKitStream(channel, config, new SpeechKitStream.Listener() {
            @Override
            public void onPartial(String text) {
                reconnectDelay = RECONNECT_DELAY_MS;
                listener.onPartial(text);
            }

            @Override
            public void onFinal(long index, String text, String language) {
                reconnectDelay = RECONNECT_DELAY_MS;
                lastFinalAt = System.currentTimeMillis();
                track(index);
                listener.onFinal(offset + index, text, language);
            }

            @Override
            public void onRefinement(long index, String text, String language) {
                track(index);
                listener.onRefinement(offset + index, text, language);
            }

            @Override
            public void onClosed(Throwable error) {
                if (stopped || currentToken != token) return;
                if (error == null) {
                    reconnectLater();
                    return;
                }
                if (isFatal(error)) {
                    stopped = true;
                    listener.onStatus("остановлено: " + shortMessage(error));
                    return;
                }
                listener.onStatus("поток прерван: " + shortMessage(error));
                reconnectLater();
            }

            @Override
            public void onStatus(String message) {
                listener.onStatus(message);
            }
        });
    }

    private void track(long index) {
        if (index > maxIndexInStream) maxIndexInStream = index;
    }

    private void reconnectLater() {
        long delay = reconnectDelay;
        reconnectDelay = Math.min(delay * 2, MAX_RECONNECT_DELAY_MS);
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!stopped) {
                indexOffset.addAndGet(maxIndexInStream + 1);
                open();
            }
        });
    }

    /** Ошибки, которые не лечатся повтором: неверный ключ, нет прав, кривые настройки. */
    private static boolean isFatal(Throwable error) {
        if (!(error instanceof StatusRuntimeException sre)) return false;
        return switch (sre.getStatus().getCode()) {
            case UNAUTHENTICATED, PERMISSION_DENIED, INVALID_ARGUMENT, NOT_FOUND -> true;
            default -> false;
        };
    }

    /**
     * Ставит распознавание на паузу: текущий поток закрывается, новый не
     * открывается. Звук в облако не уходит вовсе, поэтому пауза ещё и
     * останавливает расход денег.
     */
    public synchronized void pause() {
        if (paused || stopped) return;
        paused = true;
        SpeechKitStream current = stream;
        stream = null;
        currentToken = null;
        if (current != null) current.finish();
    }

    public synchronized void resume() {
        if (!paused || stopped) return;
        paused = false;
        indexOffset.addAndGet(maxIndexInStream + 1);
        open();
    }

    public boolean isPaused() {
        return paused;
    }

    /** Передаёт звук, попутно решая, не пора ли перезапустить поток. */
    public void sendAudio(byte[] pcm, boolean atPhraseBoundary) {
        if (paused) return;
        rotateIfNeeded(atPhraseBoundary);
        SpeechKitStream current = stream;
        if (current != null) current.sendAudio(pcm);
    }

    public void sendSilence(int durationMs) {
        if (paused) return;
        rotateIfNeeded(true);
        SpeechKitStream current = stream;
        if (current != null) current.sendSilence(durationMs);
    }

    private void rotateIfNeeded(boolean atPhraseBoundary) {
        if (paused) return;
        long now = System.currentTimeMillis();
        long age = now - streamStartedAt;
        boolean soft = atPhraseBoundary && age > SOFT_LIMIT_MS;
        // Классификатор конца фразы опирается на паузы. Под музыку заставки или
        // при безостановочной речи пауз нет, фраза не закрывается никогда, и
        // пользователь видит лишь растущую гипотезу. Закрываем поток сами:
        // сервер тогда обязан выдать накопленное как final.
        boolean stuck = now - lastFinalAt > config.maxPhraseSeconds * 1000L;
        if (!soft && !stuck && age <= HARD_LIMIT_MS) return;
        if (stuck) lastFinalAt = now;

        SpeechKitStream previous = stream;
        stream = null;
        currentToken = null;
        indexOffset.addAndGet(maxIndexInStream + 1);
        if (previous != null) previous.finish();
        open();
    }

    /** Пересоздаёт поток, чтобы подхватить изменённые настройки распознавания. */
    public synchronized void restart() {
        if (stopped) return;
        SpeechKitStream previous = stream;
        stream = null;
        currentToken = null;
        indexOffset.addAndGet(maxIndexInStream + 1);
        if (previous != null) previous.finish();
        open();
    }

    public void stop() {
        stopped = true;
        SpeechKitStream current = stream;
        if (current != null) current.finish();
    }

    private static String shortMessage(Throwable t) {
        String message = t.getMessage();
        return message == null ? t.getClass().getSimpleName() : message;
    }
}
