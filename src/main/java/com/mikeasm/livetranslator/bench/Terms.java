package com.mikeasm.livetranslator.bench;

import com.mikeasm.livetranslator.Config;
import com.mikeasm.livetranslator.Glossary;
import com.mikeasm.livetranslator.Settings;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Слова, которые стоит ожидать в речи.
 * <p>
 * Распознаванию перевод не нужен — ему надо заранее знать, какие слова могут
 * прозвучать, чтобы не принять «IntelliJ IDEA» за похожий набор звуков. Разные
 * движки принимают этот список по-разному: Azure отдельным полем запроса,
 * языковые модели — строкой в задании. Но список один и тот же, поэтому и
 * собирается он в одном месте.
 * <p>
 * Источника два. Словарь приложения составлялся для перевода и покрывает не
 * всё: в нём есть Kafka и Docker, но нет IntelliJ IDEA, хотя на встречах его
 * поминают постоянно. Поэтому список можно дополнить через
 * {@code LT_BENCH_PHRASES}, не трогая словарь и не меняя поведение живого
 * перевода.
 */
public final class Terms {

    private Terms() {}

    public static List<String> expected(Config config) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        try {
            Glossary.load(Path.of(config.glossaryPath), false)
                    .ifPresent(glossary -> terms.addAll(glossary.sourceTerms()));
        } catch (Exception e) {
            System.err.println("   словарь для подсказки не прочитан: " + e.getMessage());
        }
        for (String extra : Settings.get("LT_BENCH_PHRASES").split(",")) {
            String term = extra.trim();
            if (!term.isEmpty()) terms.add(term);
        }
        return List.copyOf(terms);
    }
}
