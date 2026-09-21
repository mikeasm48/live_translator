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
         * @param spokenAt когда эту реплику произнесли
         */
        void line(long id, String original, String text, java.time.LocalTime spokenAt);

        void status(String message);

        /** Чем сессия занята прямо сейчас. */
        void state(String state);

        /** Что уехало в облако и что вернулось — для расшифровки. */
        void note(String note);
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

    /**
     * Незаконченная реплика с прошлого куска: её продолжение придёт со
     * следующим. Человеку нужна целая фраза, а не три обрывка с отметками
     * времени, поэтому продолжение дописывается к той же строке.
     */
    private volatile long openId = -1;
    private volatile String openText = "";
    private volatile String openOriginal = "";
    /** Сколько раз подряд дописывали к этой же строке. */
    private volatile int extensions;

    /**
     * Дальше этого реплику не наращиваем.
     * <p>
     * Знак конца предложения модель ставит не всегда, и без предела одна строка
     * может расти всю встречу, слипаясь в нечитаемое полотно. Три куска — это
     * полминуты речи, законченная мысль в них помещается.
     */
    private static final int MAX_EXTENSIONS = 2;

    /**
     * Когда начал набираться нынешний кусок.
     * <p>
     * Модель размечает реплики внутри куска, но кусок приходит целиком. Без
     * этой отметки все его фразы получили бы одно время — время получения, —
     * хотя между первой и последней прошло десять секунд.
     */
    private volatile long bufferStartedAt;
    /** То же для куска, который уже ушёл в отправку. */
    private volatile long chunkStartedAt;

    /** Сколько миллисекунд подряд стоит тишина. */
    private int silenceRunMs;
    /** Была ли в накопленном хоть какая-то речь: тишину отправлять незачем. */
    private boolean heardSpeech;
    /** Сколько в накопленном именно речи, а не пауз. */
    private int speechMs;
    /**
     * Приложение начинает работу на паузе — это намеренно.
     * <p>
     * Окно открывают заранее, до начала встречи, и в тишине модель принимается
     * выдумывать: на комнатном шуме она сочиняет правдоподобный разговор из
     * терминов, которые мы же ей и подсказали. Пусть лучше человек нажмёт
     * «продолжить», когда встреча действительно началась.
     */
    private volatile boolean paused = true;
    private volatile boolean closed;

    /**
     * Что показывать в строке состояния.
     * <p>
     * Только положения, которые длятся: «записываю речь», «жду ответа модели»,
     * «тишина». Короткие переходы не показываются вовсе — мелькающая надпись
     * хуже отсутствующей, потому что её нельзя прочесть, но она отвлекает.
     * Поэтому «тишина» появляется не сразу, а после двух секунд молчания.
     */
    private static final int QUIET_STATE_MS = 2000;

    private volatile String shownState = "";
    /** Сколько кусков сейчас в работе у модели. */
    private final java.util.concurrent.atomic.AtomicInteger inFlight =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean speaking;

    public GeminiSession(Config config, GeminiClient client, SilenceGate gate, Listener listener) {
        this.config = config;
        this.client = client;
        this.gate = gate;
        this.listener = listener;
    }

    /** Принимает очередной фрагмент звука от захвата. */
    public synchronized void offer(byte[] chunk) {
        if (closed || paused) return;

        if (buffer.size() == 0) bufferStartedAt = System.currentTimeMillis();
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
                    // Считаем по тому, что действительно отправляем: в начале
                    // фразы детектор отдаёт разом весь накопленный буфер, и
                    // мерить его длиной входящего фрагмента было бы неверно.
                    int written = 0;
                    for (byte[] part : speech.chunks()) {
                        buffer.write(part, 0, part.length);
                        written += part.length;
                    }
                    speaking = true;
                    heardSpeech = true;
                    speechMs += durationMs(written);
                    silenceRunMs = 0;
                }
                case SilenceGate.Silence silence -> {
                    speaking = false;
                    // Тишину до первых слов не копим: платить за неё незачем.
                    if (!heardSpeech) {
                        silenceRunMs += silence.durationMs();
                        refreshState();
                        return;
                    }
                    buffer.write(chunk, 0, chunk.length);
                    silenceRunMs += silence.durationMs();
                }
            }
        }

        refreshState();
        int collected = durationMs(buffer.size());
        int hardLimitMs = Math.max(2000, config.chunkSeconds() * 1000);
        int baseWindowMs = (int) (hardLimitMs * BASE_WINDOW_SHARE);

        boolean pauseAfterEnough = collected >= baseWindowMs && silenceRunMs >= PAUSE_MS;
        if (pauseAfterEnough || collected >= hardLimitMs) {
            int speech = speechMs;
            send(flush(), speech);
        }
    }

    /** Отправляет накопленное, не дожидаясь паузы: встреча кончилась. */
    public synchronized void finish() {
        if (heardSpeech && buffer.size() > 0) {
            int speech = speechMs;
            send(flush(), speech);
        }
    }

    private byte[] flush() {
        chunkStartedAt = bufferStartedAt;
        byte[] audio = buffer.toByteArray();
        buffer.reset();
        silenceRunMs = 0;
        heardSpeech = false;
        speechMs = 0;
        return audio;
    }

    /** Короче этого посылать нечего: на вдохе слов не бывает. */
    private static final int MIN_SEND_MS = 1200;

    /**
     * Столько речи должно набраться, чтобы кусок вообще уехал.
     * <p>
     * Проверяется именно речь, а не длина куска. Тихая комната, в которой
     * детектор изредка принимает за голос стук клавиш или гул кондиционера,
     * копит секунды почти-тишины — а модель на записи без слов не молчит, она
     * сочиняет правдоподобный разговор.
     */
    private static final int MIN_SPEECH_MS = 1500;

    /**
     * И речь должна занимать заметную часть куска.
     * <p>
     * Одного порога в секундах мало: за десять секунд редкие щелчки наберут и
     * полторы. Настоящий разговор заполняет кусок, случайные звуки — нет.
     * <p>
     * Ценой этого изредка потеряется одинокое короткое «да» в куске, где больше
     * ничего не прозвучало. Это осознанный размен: пропустить короткий ответ
     * заметно менее вредно, чем показать выдуманную реплику, которую не
     * отличить от настоящей.
     */
    private static final double MIN_SPEECH_SHARE = 0.25;

    private void send(byte[] pcm, int speechMs) {
        int length = durationMs(pcm.length);
        if (length < MIN_SEND_MS || speechMs < MIN_SPEECH_MS) return;
        if (speechMs < length * MIN_SPEECH_SHARE) return;

        // Громкость говорит, что звук есть. Ритм говорит, речь ли это: человек
        // произносит слоги, и громкость колеблется несколько раз в секунду,
        // а кашель, стук и гул так себя не ведут.
        SpeechShape.Shape shape = SpeechShape.of(pcm, config.sampleRate);
        if (SpeechShape.definitelyNotSpeech(shape)) {
            listener.note(String.format(
                    "не отправлено %.1f с: ритм не речевой (%.1f всплеска в секунду)",
                    length / 1000.0, shape.perSecond()));
            return;
        }

        long startedAt = chunkStartedAt;
        inFlight.incrementAndGet();
        refreshState();
        sender.submit(() -> {
            try {
                long before = client.tokensUsed();
                List<GeminiClient.Line> lines = client.translate(
                        wav(pcm, config.sampleRate), true, shape.speechLike());
                // Пустой ответ — обычное дело на звуке без слов, и это как раз
                // то, что стоит видеть в расшифровке: отправляли, но сказать
                // модели было нечего.
                listener.note(String.format(
                        "отправлено %.1f с звука (%.1f всплеска в секунду, словарь %s),"
                                + " токенов %d, реплик %d",
                        durationMs(pcm.length) / 1000.0, shape.perSecond(),
                        shape.speechLike() ? "подан" : "придержан",
                        client.tokensUsed() - before, lines.size()));
                for (GeminiClient.Line line : lines) {
                    if (line.text().isBlank()) continue;
                    emit(TextCleanup.collapseRepeats(line.original()),
                            TextCleanup.collapseRepeats(line.text()),
                            spokenAt(startedAt, line.startMs()));
                }
            } catch (Exception e) {
                String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                listener.status("Gemini: " + reason);
                // Оборванная цепочка — не повод молчать дальше: начинаем разговор
                // заново, иначе ссылка на потерянный ответ будет валить и следующие.
                client.resetConversation();
            } finally {
                inFlight.decrementAndGet();
                refreshState();
            }
        });
    }

    /**
     * Отдаёт реплику наружу, дописывая её к начатой, если та не закончена.
     * <p>
     * Признак незаконченности — реплика не кончается знаком конца предложения.
     * Такая почти наверняка оборвана границей куска, а не говорящим.
     * Продолжение уходит под тем же номером: и окно, и расшифровка обновляют
     * строку на месте, вместо того чтобы плодить огрызки.
     */
    /** Время реплики: начало куска плюс её смещение внутри него. */
    private static java.time.LocalTime spokenAt(long chunkStartedAt, int offsetMs) {
        long at = (chunkStartedAt > 0 ? chunkStartedAt : System.currentTimeMillis())
                + Math.max(offsetMs, 0);
        return java.time.Instant.ofEpochMilli(at)
                .atZone(java.time.ZoneId.systemDefault()).toLocalTime();
    }

    private synchronized void emit(String original, String text,
                                   java.time.LocalTime spokenAt) {
        if (openId >= 0) {
            String joinedText = (openText + " " + text).strip();
            String joinedOriginal = (openOriginal + " " + original).strip();
            listener.line(openId, joinedOriginal, joinedText, spokenAt);
            extensions++;
            if (finished(joinedText) || extensions >= MAX_EXTENSIONS) {
                openId = -1;
            } else {
                openText = joinedText;
                openOriginal = joinedOriginal;
            }
            return;
        }
        long id = nextId.getAndIncrement();
        listener.line(id, original, text, spokenAt);
        if (!finished(text)) {
            openId = id;
            openText = text;
            openOriginal = original;
            extensions = 0;
        }
    }

    private static boolean finished(String text) {
        if (text.isBlank()) return true;
        char last = text.charAt(text.length() - 1);
        return ".!?…".indexOf(last) >= 0;
    }

    /**
     * Сводит положение дел к одной надписи.
     * <p>
     * Приоритет не случаен: ожидание ответа важнее всего — именно в эти секунды
     * человек не понимает, работает ли программа. Речь важнее тишины: пока
     * говорят, показывать «тишина» неверно, даже если детектор моргнул.
     */
    private void refreshState() {
        String state;
        if (paused) state = "на паузе";
        else if (inFlight.get() > 0) state = "жду ответа модели";
        else if (speaking) state = "записываю речь";
        else if (silenceRunMs >= QUIET_STATE_MS) state = "тишина";
        else return;   // короткий переход: прежняя надпись честнее новой

        if (!state.equals(shownState)) {
            shownState = state;
            listener.state(state);
        }
    }

    public void pause() {
        paused = true;
        refreshState();
    }

    public void resume() {
        paused = false;
        refreshState();
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
            String total = "всего за сессию: токенов " + client.tokensUsed()
                    + ", из них на размышления " + client.thoughtTokens();
            System.out.println("Gemini: " + total);
            listener.note(total);
        }
    }
}
