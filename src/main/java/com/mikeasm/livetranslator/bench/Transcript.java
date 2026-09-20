package com.mikeasm.livetranslator.bench;

import java.util.ArrayList;
import java.util.List;

/**
 * Что услышал один движок на одной записи.
 * <p>
 * Времена сегментов нужны, чтобы расшифровки разных движков можно было
 * поставить рядом: без них сравнивать пришлось бы две простыни текста разной
 * длины, гадая, где у них общее место.
 */
public final class Transcript {

    /** Одна реплика. {@code startMs} — от начала записи. */
    public record Segment(int startMs, String text, String language) {}

    private final String engineId;
    private final String engineTitle;
    private final List<Segment> segments = new ArrayList<>();
    private long elapsedMs;
    private String failure = "";

    public Transcript(String engineId, String engineTitle) {
        this.engineId = engineId;
        this.engineTitle = engineTitle;
    }

    public String engineId() {
        return engineId;
    }

    public String engineTitle() {
        return engineTitle;
    }

    public List<Segment> segments() {
        return segments;
    }

    public synchronized void add(Segment segment) {
        if (!segment.text().isBlank()) segments.add(segment);
    }

    /** Добавляет сегменты куска, сдвигая их времена к началу записи. */
    public synchronized void addAll(List<Segment> found, int offsetMs) {
        for (Segment segment : found) {
            add(new Segment(segment.startMs() + offsetMs, segment.text().trim(), segment.language()));
        }
    }

    public long elapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    /** Непустая строка, если движок не отработал. */
    public String failure() {
        return failure;
    }

    public void fail(String reason) {
        this.failure = reason;
    }

    public boolean failed() {
        return !failure.isBlank();
    }

    /** Вся расшифровка одной строкой. */
    public String text() {
        StringBuilder all = new StringBuilder();
        for (Segment segment : segments) {
            if (!all.isEmpty()) all.append(' ');
            all.append(segment.text());
        }
        return all.toString();
    }

    public int words() {
        String text = text().trim();
        return text.isEmpty() ? 0 : text.split("\\s+").length;
    }

    /** Языковые метки движка и сколько раз каждая встретилась. */
    public java.util.Map<String, Integer> languages() {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (Segment segment : segments) {
            if (segment.language() == null || segment.language().isBlank()) continue;
            counts.merge(segment.language(), 1, Integer::sum);
        }
        return counts;
    }
}
