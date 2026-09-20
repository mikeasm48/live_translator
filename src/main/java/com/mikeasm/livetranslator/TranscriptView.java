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

    /**
     * То же, но известно, когда реплику произнесли.
     * <p>
     * Gemini отдаёт кусок звука целиком, размечая реплики внутри него. Если
     * ставить им время получения, все фразы куска получат одну метку, хотя
     * между первой и последней прошло десять секунд. Приёмник, которому время
     * безразлично, может это переопределение не замечать.
     */
    default void phrase(long id, String source, String language, java.time.LocalTime spokenAt) {
        phrase(id, source, language);
    }

    /** Перевод для ранее показанной фразы и то, каким путём он получен. */
    void translation(long id, String translated, TranslatedBy by);

    /** Служебное сообщение. */
    void status(String message);
}
