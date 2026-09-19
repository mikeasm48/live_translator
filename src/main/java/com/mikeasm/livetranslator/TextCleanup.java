package com.mikeasm.livetranslator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Схлопывает зациклившиеся повторы.
 * <p>
 * Распознавание речи на длинных сегментах иногда срывается в цикл и повторяет
 * слово или оборот подряд. Само по себе это терпимо, но переводчик, получив
 * такой вход, срывается в тот же цикл и усиливает его — наблюдали превращение
 * пяти повторов в двадцать три. Поэтому повторы убираются до перевода, а вывод
 * переводчика чистится ещё раз на случай, если он зациклился сам.
 * <p>
 * Порог намеренно высокий: два повтора подряд в живой речи встречаются
 * («да, да», «yoʻq yoʻq»), три и больше — почти всегда сбой модели.
 */
public final class TextCleanup {

    /** Начиная со скольких одинаковых групп подряд считаем это зацикливанием. */
    private static final int REPEAT_LIMIT = 3;

    /** Максимальная длина повторяющегося оборота в словах. */
    private static final int MAX_GROUP_WORDS = 4;

    private TextCleanup() {}

    public static String collapseRepeats(String text) {
        if (text == null || text.isBlank()) return text;

        List<String> words = new ArrayList<>(List.of(text.trim().split("\\s+")));
        // От длинных оборотов к коротким: иначе повтор пары слов сначала
        // распадётся на два отдельных повтора и схлопнется неверно.
        for (int size = MAX_GROUP_WORDS; size >= 1; size--) {
            words = collapseGroups(words, size);
        }
        return String.join(" ", words);
    }

    private static List<String> collapseGroups(List<String> words, int size) {
        if (words.size() < size * REPEAT_LIMIT) return words;

        List<String> result = new ArrayList<>(words.size());
        int i = 0;
        while (i < words.size()) {
            int repeats = countRepeats(words, i, size);
            if (repeats >= REPEAT_LIMIT) {
                result.addAll(words.subList(i, i + size));
                i += size * repeats;
            } else {
                result.add(words.get(i));
                i++;
            }
        }
        return result;
    }

    /** Сколько раз подряд повторяется группа из {@code size} слов, начиная с {@code start}. */
    private static int countRepeats(List<String> words, int start, int size) {
        if (start + size > words.size()) return 0;
        List<String> group = words.subList(start, start + size);
        int repeats = 1;
        int next = start + size;
        while (next + size <= words.size() && sameGroup(group, words.subList(next, next + size))) {
            repeats++;
            next += size;
        }
        return repeats;
    }

    private static boolean sameGroup(List<String> a, List<String> b) {
        for (int i = 0; i < a.size(); i++) {
            if (!normalize(a.get(i)).equals(normalize(b.get(i)))) return false;
        }
        return true;
    }

    /** Сравниваем без учёта регистра и знаков препинания: «скриншот,» и «Скриншот» — одно. */
    private static String normalize(String word) {
        StringBuilder clean = new StringBuilder(word.length());
        for (char c : word.toCharArray()) {
            if (Character.isLetterOrDigit(c)) clean.append(c);
        }
        return clean.toString().toLowerCase(Locale.ROOT);
    }
}
