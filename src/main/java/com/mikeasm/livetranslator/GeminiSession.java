package com.mikeasm.livetranslator;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Живой перевод через Gemini: копит звук и решает, когда его отправить.
 * <p>
 * Решает по паузе, а не по таймеру, и это главное. Резать ровно на десятой
 * секунде — значит регулярно разрывать фразу посередине: модель получит огрызок
 * без конца, а конец — без начала, и оба переведёт неверно. Поэтому после
 * базового окна кусок ждёт ближайшей паузы в речи и закрывается на ней.
 * <p>
 * У этого есть и второе следствие, важнее первого. Если кусок закрывается тогда
 * же, когда человек замолчал, то ждать остаётся только обработку — около
 * четырёх секунд, — а не всю длину куска сверху. Слепая нарезка по десять
 * секунд дала бы около четырнадцати.
 * <p>
 * Жёсткий предел всё равно нужен: докладчик может говорить минутами без
 * единой паузы, и без предела перевод не появится вовсе.
 */
public final class GeminiSession implements AutoCloseable {

    /** Столько тишины считаем паузой, после которой можно закрыть кусок. */
    private static final int PAUSE_MS = 500;

    /** Пока набрано меньше этой доли предела, паузы не ищем. */
    private static final double BASE_WINDOW_SHARE = 0.5;

    /** Что делать с готовым переводом. */
    public interface Listener {
        /**
         * @param original что прозвучало; пусто, если модель не вернула
         * @param text     перевод
         */
        void line(long id, String original, String text);

        void status(String message);
    }

    private final Config config;
    private final GeminiClient client;
    private final SilenceGate gate;
    private final Listener listener;

    /**
     * Отправка идёт по очереди и в одном потоке: куски связаны в разговор
     * ссылкой на предыдущий ответ, и обгон перемешал бы историю.
     */
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "gemini-send");
        thread.setDaemon(true);
        return thread;
    });

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final AtomicLong nextId = new AtomicLong();

    /** Сколько миллисекунд подряд стоит тишина. */
    private int silenceRunMs;
    /** Была ли в накопленном хоть какая-то речь: тишину отправлять незачем. */
    private boolean heardSpeech;
    private volatile boolean paused;
    private volatile boolean closed;

    public GeminiSession(Config config, GeminiClient client, SilenceGate gate, Listener listener) {
        this.config = config;
        this.client = client;
        this.gate = gate;
        this.listener = listener;
    }

    /** Принимает очередной фрагмент звука от захвата. */
    public synchronized void offer(byte[] chunk) {
        if (closed || paused) return;

        if (gate == null) {
            buffer.write(chunk, 0, chunk.length);
            heardSpeech = true;
            silenceRunMs = 0;
        } else {
            switch (gate.offer(chunk)) {
                case SilenceGate.Speech speech -> {
                    // Берём именно то, что отдал детектор: вместе с фрагментом
                    // приходит предзапись — доли секунды до первого звука, без
                    // которых у фразы срезается начало.
                    for (byte[] part : speech.chunks()) buffer.write(part, 0, part.length);
                    heardSpeech = true;
                    silenceRunMs = 0;
                }
                case SilenceGate.Silence silence -> {
                    // Тишину до первых слов не копим: платить за неё незачем.
                    if (!heardSpeech) return;
                    buffer.write(chunk, 0, chunk.length);
                    silenceRunMs += silence.durationMs();
                }
            }
        }

        int collected = durationMs(buffer.size());
        int hardLimitMs = Math.max(2000, config.chunkSeconds() * 1000);
        int baseWindowMs = (int) (hardLimitMs * BASE_WINDOW_SHARE);

        boolean pauseAfterEnough = collected >= baseWindowMs && silenceRunMs >= PAUSE_MS;
        if (pauseAfterEnough || collected >= hardLimitMs) send(flush());
    }

    /** Отправляет накопленное, не дожидаясь паузы: встреча кончилась. */
    public synchronized void finish() {
        if (heardSpeech && buffer.size() > 0) send(flush());
    }

    private byte[] flush() {
        byte[] audio = buffer.toByteArray();
        buffer.reset();
        silenceRunMs = 0;
        heardSpeech = false;
        return audio;
    }

    /** Короче этого посылать нечего: на вдохе слов не бывает. */
    private static final int MIN_SEND_MS = 1200;

    private void send(byte[] pcm) {
        if (durationMs(pcm.length) < MIN_SEND_MS) return;
        sender.submit(() -> {
            try {
                List<GeminiClient.Line> lines =
                        client.translate(wav(pcm, config.sampleRate), true);
                for (GeminiClient.Line line : lines) {
                    if (line.text().isBlank()) continue;
                    listener.line(nextId.getAndIncrement(),
                            TextCleanup.collapseRepeats(line.original()),
                            TextCleanup.collapseRepeats(line.text()));
                }
            } catch (Exception e) {
                String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                listener.status("Gemini: " + reason);
                // Оборванная цепочка — не повод молчать дальше: начинаем разговор
                // заново, иначе ссылка на потерянный ответ будет валить и следующие.
                client.resetConversation();
            }
        });
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
    }

    public boolean isPaused() {
        return paused;
    }

    private int durationMs(int bytes) {
        return (int) (bytes / (config.sampleRate * 2.0) * 1000);
    }

    /** Модель принимает файл, а не поток отсчётов — приделываем заголовок. */
    static byte[] wav(byte[] pcm, int sampleRate) {
        byte[] wav = new byte[44 + pcm.length];
        tag(wav, 0, "RIFF");
        intLE(wav, 4, 36 + pcm.length);
        tag(wav, 8, "WAVE");
        tag(wav, 12, "fmt ");
        intLE(wav, 16, 16);
        shortLE(wav, 20, 1);
        shortLE(wav, 22, 1);
        intLE(wav, 24, sampleRate);
        intLE(wav, 28, sampleRate * 2);
        shortLE(wav, 32, 2);
        shortLE(wav, 34, 16);
        tag(wav, 36, "data");
        intLE(wav, 40, pcm.length);
        System.arraycopy(pcm, 0, wav, 44, pcm.length);
        return wav;
    }

    private static void tag(byte[] bytes, int at, String tag) {
        System.arraycopy(tag.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, bytes, at, 4);
    }

    private static void intLE(byte[] b, int at, int value) {
        b[at] = (byte) value;
        b[at + 1] = (byte) (value >> 8);
        b[at + 2] = (byte) (value >> 16);
        b[at + 3] = (byte) (value >> 24);
    }

    private static void shortLE(byte[] b, int at, int value) {
        b[at] = (byte) value;
        b[at + 1] = (byte) (value >> 8);
    }

    @Override
    public void close() {
        closed = true;
        finish();
        sender.shutdown();
        try {
            // Хвост встречи уже отправлен — даём ему договорить.
            if (!sender.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                sender.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sender.shutdownNow();
        }
        if (client.tokensUsed() > 0) {
            System.out.println("Gemini: токенов " + client.tokensUsed()
                    + ", из них на размышления " + client.thoughtTokens());
        }
    }
}
