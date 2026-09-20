package com.mikeasm.livetranslator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Накапливает распознанные фразы и отдаёт их переводчику связным куском.
 * <p>
 * Классификатор конца фразы режет речь по паузам, а паузы в живом разговоре
 * ложатся не по границам предложений. В логах встреч треть реплик оказалась
 * короче пяти слов — обрывками вроде «или же просто». Переводчик, получив такой
 * огрызок без продолжения, честно достраивает его до предложения и выдумывает
 * смысл, которого не было.
 * <p>
 * Поэтому куски копятся, пока не наберётся осмысленный объём или пока человек
 * не замолчит. Плата — задержка до {@link #quietMs} после последней фразы;
 * взамен переводчик видит законченную мысль.
 */
public final class PhraseBuffer implements AutoCloseable {

    /** Готовый к переводу кусок. */
    public interface Sink {
        void segment(long id, String text, String language);
    }

    private final Config config;
    private final Sink sink;

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "phrase-buffer");
        thread.setDaemon(true);
        return thread;
    });

    private final List<String> parts = new ArrayList<>();
    private String language = "";
    private int words;
    private long nextId;
    private ScheduledFuture<?> pending;

    public PhraseBuffer(Config config, Sink sink) {
        this.config = config;
        this.sink = sink;
    }

    /** Добавляет распознанную фразу; кусок уйдёт на перевод сам, когда созреет. */
    public synchronized void add(String text, String language) {
        if (text.isBlank()) return;
        parts.add(text.trim());
        words += text.trim().split("\\s+").length;
        // Язык берём по последней фразе: если в куске мешаются языки,
        // важнее тот, на котором говорят сейчас.
        if (!language.isBlank()) this.language = language;

        if (pending != null) pending.cancel(false);

        // Пороги читаются при каждой фразе: их крутят в настройках на ходу.
        if (words >= config.mergeWords()) {
            flush();
            return;
        }
        pending = timer.schedule(this::flushQuietly, config.mergeQuietMs(),
                TimeUnit.MILLISECONDS);
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException e) {
            System.err.println("Не удалось отдать фразу на перевод: " + e);
        }
    }

    /** Отдаёт накопленное немедленно — например, при остановке приложения. */
    public synchronized void flush() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
        if (parts.isEmpty()) return;

        String text = String.join(" ", parts);
        String lang = language;
        parts.clear();
        words = 0;
        sink.segment(nextId++, text, lang);
    }

    @Override
    public void close() {
        flush();
        timer.shutdownNow();
    }
}
