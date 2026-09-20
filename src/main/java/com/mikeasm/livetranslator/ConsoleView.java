package com.mikeasm.livetranslator;

/** Вывод в терминал: узбекский текст и под ним перевод. */
public final class ConsoleView implements TranscriptView {

    /** Возврат каретки и очистка строки — чтобы промежуточная гипотеза не плодила строки. */
    private static final String CLEAR_LINE = "\r" + (char) 27 + "[2K";

    /** Уточнённая фраза приходит с тем же id — второй раз её печатать не нужно. */
    private long lastPrintedId = Long.MIN_VALUE;

    /**
     * Длина показываемой гипотезы. Очистка строки стирает ровно одну строку
     * терминала: гипотеза, не влезающая в ширину окна, переносится и остаётся
     * на экране, размножаясь с каждым обновлением. Интересен только хвост.
     */
    private static final int MAX_PARTIAL_CHARS = 70;

    @Override
    public synchronized void partial(String text) {
        String tail = text.length() > MAX_PARTIAL_CHARS
                ? "…" + text.substring(text.length() - MAX_PARTIAL_CHARS)
                : text;
        System.out.print(CLEAR_LINE + "  ... " + tail);
        System.out.flush();
    }

    @Override
    public synchronized void phrase(long id, String source, String language) {
        if (id == lastPrintedId) return;
        lastPrintedId = id;
        // Метка языка вместо жёсткого "uz": на встрече говорят на разных.
        System.out.println(CLEAR_LINE + tag(language) + ": " + source);
    }

    private static String tag(String language) {
        return language.isBlank() ? "??" : language.split("-")[0];
    }

    @Override
    public synchronized void translation(long id, String translated, TranslatedBy by) {
        System.out.println(CLEAR_LINE + "ru: " + translated);
        System.out.println();
    }

    @Override
    public synchronized void status(String message) {
        System.out.println(CLEAR_LINE + "[" + message + "]");
    }
}
