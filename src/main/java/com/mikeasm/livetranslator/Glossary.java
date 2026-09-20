package com.mikeasm.livetranslator;

import yandex.cloud.api.ai.translate.v2.TranslationServiceOuterClass.GlossaryData;
import yandex.cloud.api.ai.translate.v2.TranslationServiceOuterClass.GlossaryPair;
import yandex.cloud.api.ai.translate.v2.TranslationServiceOuterClass.TranslateGlossaryConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Словарь терминов, который передаётся в каждый запрос перевода.
 * <p>
 * Нужен потому, что речь разработчиков — это узбекский с вкраплениями
 * английских и русских терминов: «deploy qildim», «merge qilamiz». Без словаря
 * переводчик обходится с ними как с обычными словами и смысл фразы плывёт.
 * <p>
 * Формат файла — по одной паре на строку:
 * <pre>
 * # комментарий
 * deploy = деплой
 * !Spring Boot = Spring Boot     # ! — точное совпадение словоформы
 * </pre>
 * Флаг {@code exact} относится к нейроглоссариям, а те поддержаны не для всех
 * языков — для uz он может быть проигнорирован. Обычные пары работают всегда.
 * Ограничения Yandex Translate: не более {@value #MAX_PAIRS} пар и
 * {@value #MAX_CHARS} символов суммарно. Превышение отвергает весь запрос,
 * поэтому файл проверяется на старте, а не в момент первой фразы.
 */
public final class Glossary {

    public static final int MAX_PAIRS = 50;
    public static final int MAX_CHARS = 20_000;

    /** Имя файла, который подхватывается автоматически, если он есть. */
    public static final String DEFAULT_FILE = "glossary.txt";

    private record Pair(String source, String target, boolean exact) {}

    private final Path path;
    private volatile List<Pair> pairs;
    private volatile long loadedMtime;

    private Glossary(Path path, List<Pair> pairs, long mtime) {
        this.path = path;
        this.pairs = pairs;
        this.loadedMtime = mtime;
    }

    /**
     * Читает словарь. {@code required} различает «файл указан явно» (отсутствие —
     * ошибка) и «файл по умолчанию» (отсутствие — нормально, работаем без словаря).
     */
    public static Optional<Glossary> load(Path path, boolean required) throws IOException {
        if (!Files.isRegularFile(path)) {
            if (required) {
                throw new IOException("Файл словаря не найден: " + path.toAbsolutePath());
            }
            return Optional.empty();
        }
        List<Pair> pairs = parse(path);
        if (pairs.isEmpty()) return Optional.empty();
        return Optional.of(new Glossary(path, pairs, Files.getLastModifiedTime(path).toMillis()));
    }

    private static List<Pair> parse(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<Pair> pairs = new ArrayList<>();
        int chars = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = stripComment(lines.get(i)).trim();
            if (line.isEmpty()) continue;

            boolean exact = line.startsWith("!");
            if (exact) line = line.substring(1).trim();

            int split = line.indexOf('=');
            if (split < 0) {
                throw new IOException(path + ":" + (i + 1)
                        + " — нет разделителя '=': " + lines.get(i).trim());
            }
            String source = line.substring(0, split).trim();
            String target = line.substring(split + 1).trim();
            if (source.isEmpty() || target.isEmpty()) {
                throw new IOException(path + ":" + (i + 1)
                        + " — пустая часть пары: " + lines.get(i).trim());
            }

            pairs.add(new Pair(source, target, exact));
            chars += source.length() + target.length();
        }

        if (pairs.size() > MAX_PAIRS) {
            throw new IOException(path + " — " + plural(pairs.size())
                    + ", а Translate принимает не больше " + MAX_PAIRS
                    + ". Оставьте самые частые термины.");
        }
        if (chars > MAX_CHARS) {
            throw new IOException(path + " — " + chars + " символов, лимит " + MAX_CHARS + ".");
        }
        return pairs;
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    /**
     * Перечитывает файл, если он изменился: термины можно дописывать прямо во
     * время встречи, не перезапуская приложение.
     *
     * @return сообщение для показа пользователю, если что-то изменилось
     */
    public Optional<String> reloadIfChanged() {
        try {
            long mtime = Files.getLastModifiedTime(path).toMillis();
            if (mtime == loadedMtime) return Optional.empty();
            List<Pair> reloaded = parse(path);
            pairs = reloaded;
            loadedMtime = mtime;
            return Optional.of("словарь перечитан: " + plural(reloaded.size()));
        } catch (IOException e) {
            // Правим файл на ходу — промежуточное состояние может быть битым.
            // Продолжаем со старой версией словаря.
            loadedMtime = System.currentTimeMillis();
            return Optional.of("словарь не перечитан: " + e.getMessage());
        }
    }

    public TranslateGlossaryConfig config() {
        GlossaryData.Builder data = GlossaryData.newBuilder();
        for (Pair pair : pairs) {
            data.addGlossaryPairs(GlossaryPair.newBuilder()
                    .setSourceText(pair.source())
                    .setTranslatedText(pair.target())
                    .setExact(pair.exact()));
        }
        return TranslateGlossaryConfig.newBuilder().setGlossaryData(data).build();
    }

    /**
     * Исходные термины без переводов — для подсказки распознаванию.
     * <p>
     * Распознаванию перевод не нужен: ему надо заранее знать, какие слова могут
     * прозвучать, чтобы не принять «IntelliJ IDEA» за похожий набор звуков.
     */
    public List<String> sourceTerms() {
        List<String> terms = new java.util.ArrayList<>();
        for (Pair pair : pairs) terms.add(pair.source());
        return terms;
    }

    /** Термины списком — для подсказки языковой модели. */
    public String asPromptList() {
        StringBuilder text = new StringBuilder();
        for (Pair pair : pairs) {
            text.append("- ").append(pair.source()).append(" → ").append(pair.target()).append('\n');
        }
        return text.toString();
    }

    /** Склонение слова «пара» по числу: 1 пара, 2 пары, 5 пар. */
    public static String plural(int count) {
        int tail = count % 100;
        if (tail >= 11 && tail <= 14) return count + " пар";
        return count + switch (count % 10) {
            case 1 -> " пара";
            case 2, 3, 4 -> " пары";
            default -> " пар";
        };
    }

    public int size() {
        return pairs.size();
    }

    public Path path() {
        return path;
    }
}
