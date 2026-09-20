package com.mikeasm.livetranslator;

/** Приёмник результатов: консоль, окно, файл. */
public interface TranscriptView {

    /** Промежуточная гипотеза (узбекский), ещё не финал. */
    void partial(String text);

    /**
     * Фраза распознана или уточнена: одинаковый id — одна и та же фраза.
     * {@code language} — язык, на котором её произнесли.
     */
    void phrase(long id, String source, String language);

    /** Перевод для ранее показанной фразы и то, каким путём он получен. */
    void translation(long id, String translated, TranslatedBy by);

    /** Служебное сообщение. */
    void status(String message);
}
