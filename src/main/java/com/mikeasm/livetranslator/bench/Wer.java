package com.mikeasm.livetranslator.bench;

import java.text.Normalizer;

/**
 * Доля ошибок распознавания относительно эталона.
 * <p>
 * Без эталона стенд показывает только мнения: две расшифровки выглядят
 * по-разному, но какая ближе к сказанному — вопрос вкуса. Эталон делает ответ
 * проверяемым, поэтому ради него стоит один раз вручную выправить десять минут
 * записи.
 * <p>
 * Для узбекского отдельно считается доля ошибок по буквам. Язык
 * агглютинативный: «kelyapman» и «kelyapsan» — одно слово с разной концовкой, и
 * пословный счёт назовёт такую пару полным промахом, хотя услышано почти всё.
 */
public final class Wer {

    /** Дальше этой длины посимвольный счёт занимает больше времени, чем стоит. */
    private static final int CER_LIMIT = 20_000;

    public record Score(int referenceWords, int errors, double wer, double cer) {
        public String werLabel() {
            return referenceWords == 0 ? "—" : String.format("%.1f%%", wer * 100);
        }

        public String cerLabel() {
            return cer < 0 ? "—" : String.format("%.1f%%", cer * 100);
        }
    }

    private Wer() {}

    public static Score of(String reference, String hypothesis) {
        String left = normalize(reference);
        String right = normalize(hypothesis);
        String[] referenceWords = words(left);
        String[] hypothesisWords = words(right);

        int errors = distance(referenceWords, hypothesisWords);
        double wer = referenceWords.length == 0 ? 0 : errors / (double) referenceWords.length;

        double cer = -1;
        if (!left.isEmpty() && left.length() <= CER_LIMIT && right.length() <= CER_LIMIT) {
            String[] referenceChars = chars(left);
            cer = distance(referenceChars, chars(right)) / (double) referenceChars.length;
        }
        return new Score(referenceWords.length, errors, wer, cer);
    }

    private static String[] words(String text) {
        return text.isEmpty() ? new String[0] : text.split(" ");
    }

    private static String[] chars(String text) {
        String packed = text.replace(" ", "");
        String[] result = new String[packed.length()];
        for (int i = 0; i < packed.length(); i++) result[i] = String.valueOf(packed.charAt(i));
        return result;
    }

    /**
     * Приводит к виду, в котором сравнение осмысленно: регистр, знаки
     * препинания и разнобой апострофов к делу не относятся.
     * <p>
     * Апострофы важны отдельно: в узбекской латинице «oʻ» пишут то модификатором
     * U+02BB, то машинописным апострофом, то типографской кавычкой. Разные
     * движки выбирают разное, и без приведения к одному виду каждое второе
     * слово считалось бы ошибкой.
     */
    static String normalize(String text) {
        if (text == null) return "";
        String result = Normalizer.normalize(text, Normalizer.Form.NFC)
                .toLowerCase(java.util.Locale.ROOT)
                .replace('ʻ', '\'')
                .replace('ʼ', '\'')
                .replace('‘', '\'')
                .replace('’', '\'')
                .replace('`', '\'')
                .replace('ʼ', '\'')
                .replace('ё', 'е');
        StringBuilder clean = new StringBuilder(result.length());
        for (char symbol : result.toCharArray()) {
            if (Character.isLetterOrDigit(symbol) || symbol == '\'') clean.append(symbol);
            else clean.append(' ');
        }
        return clean.toString().replaceAll("\\s+", " ").trim();
    }

    /**
     * Расстояние Левенштейна на двух строках матрицы: полная матрица для
     * часовой расшифровки не поместилась бы в память.
     */
    private static int distance(String[] reference, String[] hypothesis) {
        if (reference.length == 0) return hypothesis.length;
        if (hypothesis.length == 0) return reference.length;

        int[] previous = new int[hypothesis.length + 1];
        int[] current = new int[hypothesis.length + 1];
        for (int j = 0; j <= hypothesis.length; j++) previous[j] = j;

        for (int i = 1; i <= reference.length; i++) {
            current[0] = i;
            for (int j = 1; j <= hypothesis.length; j++) {
                int substitute = previous[j - 1]
                        + (reference[i - 1].equals(hypothesis[j - 1]) ? 0 : 1);
                current[j] = Math.min(substitute, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[hypothesis.length];
    }
}
