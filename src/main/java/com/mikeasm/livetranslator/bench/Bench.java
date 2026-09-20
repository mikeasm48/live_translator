package com.mikeasm.livetranslator.bench;

import com.mikeasm.livetranslator.AppPaths;
import com.mikeasm.livetranslator.Config;
import com.mikeasm.livetranslator.Glossary;
import com.mikeasm.livetranslator.LlmTranslator;
import com.mikeasm.livetranslator.TextCleanup;
import com.mikeasm.livetranslator.YandexGrpc;
import io.grpc.ManagedChannel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Стенд сравнения движков распознавания.
 * <p>
 * Одну и ту же запись прогоняет через все настроенные движки и кладёт
 * расшифровки рядом. Нужен он затем, что спорить о качестве распознавания
 * узбекского можно бесконечно: обещания вендоров меряны на чтении вслух, а у
 * нас созвон с перебиваниями, техническими терминами и переходами на русский.
 * Какой движок лучше именно на этом — показывает только прогон именно этого.
 * <p>
 * Движки запускаются по очереди, а не разом: так виден ход работы, а ошибка
 * одного сервиса не тонет в выводе остальных. Времени это стоит, поэтому есть
 * {@code --minutes} — обкатывать настройки на первых десяти минутах.
 */
public final class Bench {

    /** Шаг окна, по которому расшифровки ставятся рядом. */
    private static final int DEFAULT_WINDOW_SECONDS = 30;

    private Bench() {}

    public static void run(Config config, String[] args) throws Exception {
        Path wav = Path.of(expand(value(args, "bench", "")));
        if (!Files.isRegularFile(wav)) {
            System.err.println("Не нахожу запись: " + wav);
            System.err.println("Запись встречи включается кнопкой в панели или ключом --record.");
            return;
        }

        AudioFile audio = AudioFile.read(wav);
        int minutes = Integer.parseInt(value(args, "minutes", "0"));
        if (minutes > 0) audio = audio.firstMinutes(minutes);

        String reference = "";
        String referencePath = value(args, "reference", "");
        if (!referencePath.isBlank()) {
            reference = Files.readString(Path.of(expand(referencePath)), StandardCharsets.UTF_8);
        }

        int chunkSeconds = Integer.parseInt(value(args, "chunk-seconds", "0"));
        List<AsrEngine> engines = engines(config, value(args, "engines", ""));
        if (engines.isEmpty()) {
            System.err.println("Не осталось ни одного движка — проверьте --engines.");
            return;
        }

        System.out.println("Запись: " + wav.getFileName() + ", "
                + BenchReport.clock(audio.durationMs())
                + (minutes > 0 ? " (первые " + minutes + " мин)" : ""));
        System.out.println("Движков: " + engines.size()
                + ", перевод " + (translating(config) ? "включён" : "выключен"));
        System.out.println("Каждый движок слушает запись целиком — это стоит денег у каждого вендора.");
        System.out.println();

        ManagedChannel llmChannel = translating(config)
                ? YandexGrpc.channel(YandexGrpc.LLM_ENDPOINT, config)
                : null;
        Glossary glossary = glossary(config);

        List<BenchReport.Outcome> outcomes = new ArrayList<>();
        try {
            for (AsrEngine engine : engines) {
                outcomes.add(measure(engine, audio, config, llmChannel, glossary,
                        reference, chunkSeconds));
            }
        } finally {
            if (llmChannel != null) llmChannel.shutdownNow();
            for (AsrEngine engine : engines) engine.close();
        }

        int window = Integer.parseInt(value(args, "window", String.valueOf(DEFAULT_WINDOW_SECONDS)));
        Path directory = AppPaths.logsDir().resolve("bench-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")));
        Path report = new BenchReport(directory, audio, reference, window * 1000).write(outcomes);

        System.out.println();
        System.out.println("Отчёт: " + report);
        System.out.println("Рядом — расшифровки по движкам отдельными файлами.");
    }

    /**
     * Насколько мелко резать запись. Обычно решает сам движок, но вендор может
     * отказаться принимать крупный запрос по причинам, которых нет в его
     * документации, — тогда предел задаётся снаружи, без пересборки.
     */
    private static int chunkLimitMs(AsrEngine engine, int chunkSeconds) {
        return chunkSeconds > 0
                ? Math.min(engine.maxChunkMs(), chunkSeconds * 1000)
                : engine.maxChunkMs();
    }

    private static BenchReport.Outcome measure(AsrEngine engine, AudioFile audio, Config config,
                                               ManagedChannel llmChannel, Glossary glossary,
                                               String reference, int chunkSeconds) {
        Transcript asr = new Transcript(engine.id(), engine.title());
        if (!engine.skipReason().isBlank()) {
            System.out.println("— " + engine.id() + ": пропускаю, " + engine.skipReason());
            return new BenchReport.Outcome(asr, null, null, engine.skipReason(),
                    engine.alreadyTranslated());
        }

        List<AudioFile.Clip> clips = audio.split(chunkLimitMs(engine, chunkSeconds));
        System.out.println("→ " + engine.id() + ": " + clips.size()
                + (clips.size() == 1 ? " кусок" : " кусков")
                + (engine.priceNote().isBlank() ? "" : ", " + engine.priceNote()));

        long startedAt = System.currentTimeMillis();
        try {
            for (int i = 0; i < clips.size(); i++) {
                AudioFile.Clip clip = clips.get(i);
                System.out.print("   " + (i + 1) + "/" + clips.size() + "… ");
                System.out.flush();
                asr.addAll(engine.transcribe(clip), clip.offsetMs());
                System.out.println("услышано слов: " + asr.words());
            }
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            asr.fail(reason);
            System.out.println("   сорвалось: " + reason);
        }
        asr.setElapsedMs(System.currentTimeMillis() - startedAt);

        Transcript russian = asr.failed() || llmChannel == null || engine.alreadyTranslated()
                ? null
                : translate(asr, config, llmChannel, glossary);
        // Эталон выправлен на языке говорящего, а этот движок сразу выдал
        // перевод: считать по нему долю ошибок — мерить одно линейкой другого.
        Wer.Score score = reference.isBlank() || asr.failed() || engine.alreadyTranslated()
                ? null
                : Wer.of(reference, asr.text());
        if (score != null) {
            System.out.println("   WER " + score.werLabel() + ", CER " + score.cerLabel());
        }
        return new BenchReport.Outcome(asr, russian, score, "", engine.alreadyTranslated());
    }

    /**
     * Переводит расшифровку той же моделью и тем же словарём, что и живой
     * перевод. Сравнивать расшифровки на узбекском может не каждый, а русский
     * текст — ровно то, ради чего всё затевалось.
     */
    private static Transcript translate(Transcript asr, Config config,
                                        ManagedChannel llmChannel, Glossary glossary) {
        // У каждого движка свой переводчик: контекст предыдущих реплик не должен
        // течь из чужой расшифровки.
        LlmTranslator llm = new LlmTranslator(llmChannel, config, glossary);
        Transcript russian = new Transcript(asr.engineId() + "-ru", asr.engineTitle() + " → русский");

        List<Transcript.Segment> groups = group(asr.segments(), config.mergeWords());
        System.out.println("   перевожу " + groups.size() + " реплик…");
        for (Transcript.Segment group : groups) {
            try {
                // Та же чистка повторов, что и в живом переводе: без неё
                // зациклившееся распознавание модель усиливает.
                String clean = TextCleanup.collapseRepeats(group.text());
                String result = llm.translate(clean, group.language());
                if (result != null) {
                    russian.add(new Transcript.Segment(group.startMs(),
                            TextCleanup.collapseRepeats(result), config.targetLang));
                }
            } catch (RuntimeException e) {
                russian.add(new Transcript.Segment(group.startMs(),
                        "[перевод не получен: " + e.getMessage() + "]", config.targetLang));
            }
        }
        return russian;
    }

    /** Собирает короткие реплики в куски той же длины, что и в живом переводе. */
    private static List<Transcript.Segment> group(List<Transcript.Segment> segments, int maxWords) {
        List<Transcript.Segment> groups = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int start = 0;
        int words = 0;
        String language = "";

        for (Transcript.Segment segment : segments) {
            if (text.isEmpty()) {
                start = segment.startMs();
                language = segment.language() == null ? "" : segment.language();
            } else {
                text.append(' ');
            }
            text.append(segment.text());
            words += segment.text().trim().split("\\s+").length;
            if (words >= maxWords) {
                groups.add(new Transcript.Segment(start, text.toString(), language));
                text.setLength(0);
                words = 0;
            }
        }
        if (!text.isEmpty()) groups.add(new Transcript.Segment(start, text.toString(), language));
        return groups;
    }

    private static boolean translating(Config config) {
        return config.translate && config.useLlm() && !config.folderId.isBlank();
    }

    private static Glossary glossary(Config config) {
        try {
            Optional<Glossary> loaded = Glossary.load(Path.of(config.glossaryPath), false);
            return loaded.orElse(null);
        } catch (Exception e) {
            System.err.println("Словарь не прочитан: " + e.getMessage());
            return null;
        }
    }

    /** Движки, которые запускаются только по явной просьбе. */
    private static final java.util.Set<String> SPARE = java.util.Set.of("azure-short");

    private static List<AsrEngine> engines(Config config, String only) {
        List<AsrEngine> all = List.of(
                new YandexEngine(config, false),
                new YandexEngine(config, true),
                new GoogleEngine(config),
                new AzureEngine(config, true),
                new AzureEngine(config, true, true),
                new AzureEngine(config, false),
                new GeminiEngine(config, false),
                new GeminiEngine(config, true),
                new ElevenLabsEngine(config),
                new OpenAiEngine(config),
                new AssemblyAiEngine(config));
        // Запасные движки денег без спроса не тратят: они нужны, только когда
        // основной путь у вендора почему-то не сработал.
        if (only.isBlank()) {
            return all.stream().filter(engine -> {
                boolean spare = SPARE.contains(engine.id());
                if (spare) engine.close();
                return !spare;
            }).toList();
        }

        List<String> wanted = java.util.Arrays.stream(only.split(","))
                .map(String::trim).filter(name -> !name.isEmpty()).toList();
        List<AsrEngine> chosen = new ArrayList<>();
        for (AsrEngine engine : all) {
            if (wanted.contains(engine.id())) chosen.add(engine);
            else engine.close();
        }
        for (String name : wanted) {
            if (chosen.stream().noneMatch(engine -> engine.id().equals(name))) {
                System.err.println("Неизвестный движок: " + name);
            }
        }
        return chosen;
    }

    private static String value(String[] args, String name, String fallback) {
        for (String arg : args) {
            if (arg.startsWith("--" + name + "=")) return arg.substring(name.length() + 3);
        }
        return fallback;
    }

    private static String expand(String path) {
        return path.startsWith("~/")
                ? System.getProperty("user.home") + path.substring(1)
                : path;
    }

    /** Подсказка по стенду — отдельная, чтобы не раздувать общую справку. */
    public static void printUsage() {
        System.out.println("""
                Стенд сравнения движков распознавания.

                  --bench=запись.wav      что слушать (WAV 16 бит)
                  --reference=эталон.txt  выправленная вручную расшифровка: без неё нет WER
                  --minutes=10            взять только первые минуты — дешевле и быстрее
                  --engines=yandex-live,google   какие движки прогнать
                  --window=30             шаг окна, с, по которому расшифровки ставятся рядом
                  --chunk-seconds=60      резать мельче, если вендор не принимает крупный запрос
                  --no-translate          не переводить, только расшифровки

                Движки и их ключи (кладутся туда же, где YC_API_KEY):
                  yandex-live     как в живом переводе       YC_API_KEY
                  yandex-offline  отложенный режим той же    YC_API_KEY
                                  модели, FULL_DATA
                  google          Cloud STT v2 / Chirp       LT_GOOGLE_PROJECT и
                                                             LT_GOOGLE_TOKEN_CMD=gcloud auth print-access-token
                  azure           быстрая расшифровка        LT_AZURE_KEY + LT_AZURE_REGION
                  azure-terms     то же с подсказкой         LT_AZURE_KEY + LT_AZURE_PHRASES
                                  ожидаемых терминов
                  azure-short     запасной путь Azure для    LT_AZURE_KEY + LT_AZURE_REGION
                                  коротких фрагментов; сам
                                  не запускается
                  gemini          дословная расшифровка      LT_GEMINI_KEY
                  gemini-ru       звук сразу в перевод,      LT_GEMINI_KEY
                                  без текста посередине
                  elevenlabs      Scribe                     LT_ELEVENLABS_KEY
                  openai          Whisper                    LT_OPENAI_KEY
                  assemblyai      отложенное распознавание   LT_ASSEMBLYAI_KEY

                Движки без ключа пропускаются, остальные работают.
                """);
    }
}
