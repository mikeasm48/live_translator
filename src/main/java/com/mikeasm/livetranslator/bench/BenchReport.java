package com.mikeasm.livetranslator.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Отчёт стенда.
 * <p>
 * Главное в нём — не таблица с цифрами, а расшифровки, поставленные рядом по
 * времени. Цифра говорит, какой движок ошибается реже; соседние строки
 * показывают, как именно он ошибается, а это решает больше: движок с худшим
 * WER, который не выдумывает несуществующих слов, на встрече полезнее.
 */
public final class BenchReport {

    /** Длина окна, по которому расшифровки ставятся рядом. */
    private final int windowMs;

    private final Path directory;
    private final AudioFile audio;
    private final String reference;

    public BenchReport(Path directory, AudioFile audio, String reference, int windowMs) {
        this.directory = directory;
        this.audio = audio;
        this.reference = reference;
        this.windowMs = windowMs;
    }

    /**
     * Результат одного движка: что услышал, что из этого вышло по-русски, и
     * цена ошибки. {@code direct} — движок выдал перевод сам, минуя расшифровку.
     */
    public record Outcome(Transcript asr, Transcript russian, Wer.Score score, String skipped,
                          boolean direct) {}

    public Path write(List<Outcome> outcomes) throws IOException {
        Files.createDirectories(directory);
        for (Outcome outcome : outcomes) {
            if (!outcome.skipped().isBlank()) continue;
            writeLines(directory.resolve(outcome.asr().engineId() + ".txt"), outcome.asr());
            if (outcome.russian() != null) {
                writeLines(directory.resolve(outcome.asr().engineId() + ".ru.txt"), outcome.russian());
            }
        }

        StringBuilder md = new StringBuilder();
        header(md);
        summary(md, outcomes);
        sideBySide(md, outcomes);
        full(md, outcomes);

        Path report = directory.resolve("report.md");
        Files.writeString(report, md.toString(), StandardCharsets.UTF_8);
        return report;
    }

    private void header(StringBuilder md) {
        md.append("# Сравнение движков распознавания\n\n");
        md.append("- запись: `").append(audio.path()).append("`\n");
        md.append("- длительность: ").append(clock(audio.durationMs()))
                .append(", частота ").append(audio.sampleRate()).append(" Гц\n");
        if (!audio.note().isBlank()) md.append("- при чтении: ").append(audio.note()).append('\n');
        md.append("- прогон: ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append('\n');
        md.append("- эталон: ").append(reference.isBlank()
                ? "не задан, поэтому WER не считался" : "задан, " + words(reference) + " слов")
                .append("\n\n");
    }

    private void summary(StringBuilder md, List<Outcome> outcomes) {
        md.append("## Итоги\n\n");
        md.append("| движок | слов | реплик | время | WER | CER | метки языка |\n");
        md.append("|---|---:|---:|---:|---:|---:|---|\n");
        for (Outcome outcome : outcomes) {
            Transcript asr = outcome.asr();
            md.append("| ").append(asr.engineTitle()).append(" | ");
            if (!outcome.skipped().isBlank()) {
                md.append("— | — | — | — | — | пропущен: ").append(outcome.skipped()).append(" |\n");
                continue;
            }
            if (asr.failed()) {
                md.append("— | — | — | — | — | ошибка: ").append(asr.failure()).append(" |\n");
                continue;
            }
            md.append(asr.words()).append(" | ")
                    .append(asr.segments().size()).append(" | ")
                    .append(seconds(asr.elapsedMs())).append(" | ")
                    .append(outcome.score() == null ? "—" : outcome.score().werLabel()).append(" | ")
                    .append(outcome.score() == null ? "—" : outcome.score().cerLabel()).append(" | ")
                    .append(outcome.direct() ? "перевод прямо из звука" : languages(asr))
                    .append(" |\n");
        }
        md.append("\nWER — доля ошибочных слов относительно эталона, меньше лучше. ");
        md.append("CER считает то же по буквам: для узбекского он честнее, ");
        md.append("потому что не записывает в полный промах слово с неверным окончанием.\n\n");
        if (outcomes.stream().anyMatch(Outcome::direct)) {
            md.append("У движков, переводящих прямо из звука, WER пуст намеренно: ");
            md.append("эталон выправлен на языке говорящего, и считать по нему долю ошибок ");
            md.append("в русском тексте — мерить одно линейкой другого. ");
            md.append("Их строку читают глазами, а не сравнивают числом.\n\n");
        }
    }

    private static String languages(Transcript asr) {
        Map<String, Integer> counts = asr.languages();
        if (counts.isEmpty()) return "не сообщает";
        List<String> parts = new ArrayList<>();
        counts.forEach((code, count) -> parts.add(code + " ×" + count));
        return String.join(", ", parts);
    }

    /** Расшифровки рядом по окнам времени — ради этого стенд и делался. */
    private void sideBySide(StringBuilder md, List<Outcome> outcomes) {
        List<Outcome> working = outcomes.stream()
                .filter(outcome -> outcome.skipped().isBlank() && !outcome.asr().failed())
                .toList();
        if (working.isEmpty()) {
            md.append("## Расшифровки рядом\n\nНи один движок не отработал.\n\n");
            return;
        }

        Map<String, Map<Integer, List<String>>> byEngine = new LinkedHashMap<>();
        TreeSet<Integer> windows = new TreeSet<>();
        for (Outcome outcome : working) {
            Map<Integer, List<String>> lines = new LinkedHashMap<>();
            collect(lines, outcome.asr(), "");
            if (outcome.russian() != null) collect(lines, outcome.russian(), "→ ");
            byEngine.put(outcome.asr().engineTitle(), lines);
            windows.addAll(lines.keySet());
        }

        md.append("## Расшифровки рядом\n\n");
        for (int window : windows) {
            md.append("### ").append(clock(window * windowMs))
                    .append(" — ").append(clock((window + 1) * windowMs)).append("\n\n");
            byEngine.forEach((title, lines) -> {
                List<String> text = lines.get(window);
                md.append("**").append(title).append("**  \n");
                if (text == null || text.isEmpty()) {
                    md.append("_тишина_\n\n");
                } else {
                    text.forEach(line -> md.append(line).append("  \n"));
                    md.append('\n');
                }
            });
        }
    }

    private void collect(Map<Integer, List<String>> lines, Transcript transcript, String prefix) {
        for (Transcript.Segment segment : transcript.segments()) {
            lines.computeIfAbsent(segment.startMs() / windowMs, key -> new ArrayList<>())
                    .add(prefix + segment.text());
        }
    }

    private void full(StringBuilder md, List<Outcome> outcomes) {
        md.append("## Полные расшифровки\n\n");
        if (!reference.isBlank()) {
            md.append("### Эталон\n\n").append(reference.trim()).append("\n\n");
        }
        for (Outcome outcome : outcomes) {
            if (!outcome.skipped().isBlank() || outcome.asr().failed()) continue;
            md.append("### ").append(outcome.asr().engineTitle()).append("\n\n");
            md.append(outcome.asr().text()).append("\n\n");
            if (outcome.russian() != null && !outcome.russian().segments().isEmpty()) {
                md.append("Перевод:\n\n").append(outcome.russian().text()).append("\n\n");
            }
        }
    }

    private void writeLines(Path path, Transcript transcript) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Transcript.Segment segment : transcript.segments()) {
            text.append('[').append(clock(segment.startMs())).append("] ")
                    .append(segment.text()).append('\n');
        }
        Files.writeString(path, text.toString(), StandardCharsets.UTF_8);
    }

    /** Время работы движка: на коротком куске «0 с» выглядит поломкой. */
    private static String seconds(long ms) {
        return ms < 10_000 ? String.format("%.1f с", ms / 1000.0) : (ms / 1000) + " с";
    }

    static String clock(int ms) {
        int seconds = Math.max(ms, 0) / 1000;
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    private static int words(String text) {
        String trimmed = text.trim();
        return trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
    }
}
