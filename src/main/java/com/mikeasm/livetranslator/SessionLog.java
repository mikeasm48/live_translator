package com.mikeasm.livetranslator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Пишет расшифровку в файл, чтобы после встречи можно было перечитать.
 * <p>
 * Фраза не записывается сразу: её ещё может уточнить final_refinement, а перевод
 * приходит отдельным событием. Запись происходит, когда запись фразы завершена —
 * пришёл перевод, появилась следующая фраза или сессия закрывается. Благодаря
 * этому лог корректен и в режиме {@code --no-translate}.
 */
public final class SessionLog implements TranscriptView, AutoCloseable {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String NL = System.lineSeparator();

    private record Entry(String time, String source, String translated, String language) {}

    private final Writer writer;
    private final Path path;
    private final Map<Long, Entry> pending = new LinkedHashMap<>();

    public SessionLog(Path directory) throws IOException {
        Files.createDirectories(directory);
        this.path = directory.resolve("session-" + LocalDateTime.now().format(STAMP) + ".md");
        this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public Path path() {
        return path;
    }

    @Override
    public void partial(String text) {
        // промежуточные гипотезы в лог не пишем
    }

    @Override
    public synchronized void phrase(long id, String source, String language) {
        Entry existing = pending.get(id);
        if (existing != null) {
            pending.put(id, new Entry(existing.time(), source, existing.translated(), language));
            return;
        }
        flushAllExcept(id);
        pending.put(id, new Entry(LocalDateTime.now().format(TIME), source, null, language));
    }

    @Override
    public synchronized void translation(long id, String translated) {
        Entry entry = pending.remove(id);
        if (entry == null) return;
        write(new Entry(entry.time(), entry.source(), translated, entry.language()));
    }

    @Override
    public synchronized void status(String message) {
        writeRaw("<!-- " + message + " -->" + NL);
    }

    /** Дописывает всё, что ещё не выгружено: следующая фраза означает, что прежние закрыты. */
    private void flushAllExcept(long keepId) {
        pending.entrySet().removeIf(e -> {
            if (e.getKey() == keepId) return false;
            write(e.getValue());
            return true;
        });
    }

    private void write(Entry entry) {
        String tag = entry.language() == null || entry.language().isBlank()
                ? "" : " `" + entry.language() + "`";
        StringBuilder text = new StringBuilder("**").append(entry.time()).append("**")
                .append(tag).append(" ");
        if (entry.translated() == null) {
            text.append(entry.source()).append(NL).append(NL);
        } else {
            text.append(entry.translated()).append(NL)
                    .append("> ").append(entry.source()).append(NL).append(NL);
        }
        writeRaw(text.toString());
    }

    private void writeRaw(String text) {
        try {
            writer.write(text);
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        pending.values().forEach(this::write);
        pending.clear();
        writer.close();
    }
}
