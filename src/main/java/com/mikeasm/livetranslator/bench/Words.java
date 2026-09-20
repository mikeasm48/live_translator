package com.mikeasm.livetranslator.bench;

import java.util.ArrayList;
import java.util.List;

/**
 * Склейка слов в реплики.
 * <p>
 * Движки, отдающие только слова с временами, иначе дали бы одну простыню,
 * которую не с чем сопоставить в отчёте.
 */
final class Words {

    /** Пауза, после которой начинается новая реплика. */
    private static final double GAP_MS = 700;

    /** Предел длины реплики в словах. */
    private static final int MAX_WORDS = 14;

    private Words() {}

    /** Слово с временами в миллисекундах от начала куска. */
    record Word(String text, double startMs, double endMs) {}

    /**
     * Собирает слова в реплики: по паузе между словами или по накопленной длине.
     * Движки, отдающие только слова с временами, иначе дали бы одну простыню,
     * которую не с чем сопоставить.
     */
    static List<Transcript.Segment> group(List<Word> words, String language) {
        List<Transcript.Segment> segments = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int start = 0;
        int count = 0;
        double previousEnd = -1;

        for (Word word : words) {
            if (word.text() == null || word.text().isBlank()) continue;
            boolean pause = previousEnd >= 0 && word.startMs() - previousEnd > GAP_MS;
            if (!line.isEmpty() && (pause || count >= MAX_WORDS)) {
                segments.add(new Transcript.Segment(start, line.toString().trim(), language));
                line.setLength(0);
                count = 0;
            }
            if (line.isEmpty()) start = (int) word.startMs();
            if (!line.isEmpty() && !word.text().startsWith(" ")) line.append(' ');
            line.append(word.text().trim());
            previousEnd = word.endMs();
            count++;
        }
        if (!line.isEmpty()) {
            segments.add(new Transcript.Segment(start, line.toString().trim(), language));
        }
        return segments;
    }

}
