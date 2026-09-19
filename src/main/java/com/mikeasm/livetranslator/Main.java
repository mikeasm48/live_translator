package com.mikeasm.livetranslator;

import io.grpc.ManagedChannel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

/**
 * Живой перевод речи: микрофон → SpeechKit STT v3 (узбекский) → Translate → русский текст.
 */
public final class Main {

    /** Версия приложения — попадает в Info.plist значка. */
    private static final String VERSION = "0.2.4";

    public static void main(String[] args) throws Exception {
        Config parsed = Config.parse(args);
        AppBundle.ensureInstalled(VERSION);

        if (has(args, "--help") || has(args, "-h")) {
            printUsage();
            return;
        }
        if (has(args, "--list-devices")) {
            System.out.println("Устройства записи (" + parsed.sampleRate + " Гц, 16 бит, моно):");
            AudioCapture.listDevices(parsed.sampleRate).forEach(d -> System.out.println("  " + d));
            System.out.println("\nЗапуск с конкретным устройством: --device=BlackHole");
            return;
        }
        if (has(args, "--level")) {
            LevelMeter.run(parsed);
            return;
        }
        if (has(args, "--demo")) {
            demo(parsed);
            return;
        }
        if (parsed.apiKey.isBlank() && parsed.iamToken.isBlank()
                || parsed.translate && parsed.folderId.isBlank()) {
            // Первый запуск на новой машине: спрашиваем доступы и запоминаем.
            if (!FirstRun.ensureConfigured(parsed.showUi)) {
                System.err.println("Без ключа и каталога работать не с чем. "
                        + "Подробности в README.md");
                System.exit(1);
            }
            parsed = Config.parse(args);
        }
        String probe = probePhrase(args);
        if (probe != null) {
            tryPhrase(parsed, probe);
            return;
        }
        if (has(args, "--selftest")) {
            System.out.println("0. Ключ: " + parsed.credentialOrigin()
                    + "; каталог " + parsed.folderId + ": " + Settings.origin("YC_FOLDER_ID"));
            SelfTest.run(parsed, loadGlossary(parsed));
            return;
        }
        // Захват создаётся раньше распознавания, поэтому сессия отдаётся ему
        // через ссылку: до её появления звук просто отбрасывается.
        AtomicReference<RecognizerSession> sessionRef = new AtomicReference<>();
        SilenceGate gate = parsed.vadEnabled ? new SilenceGate(parsed.vadThreshold) : null;
        CaptureController capture = openCapture(parsed, sessionRef, gate);

        // После возможной донастройки значение больше не меняется —
        // финальная копия нужна лямбдам ниже.
        final Config config = parsed;

        List<TranscriptView> views = new ArrayList<>();
        views.add(new ConsoleView());
        if (config.showUi) views.add(OverlayWindow.create(config, controlFor(capture, config.vadThreshold, sessionRef)));
        SessionLog log = new SessionLog(AppPaths.logsDir());
        views.add(log);
        TranscriptView view = fanOut(views);

        ManagedChannel sttChannel = YandexGrpc.channel(YandexGrpc.STT_ENDPOINT, config);
        ManagedChannel translateChannel = config.translate
                ? YandexGrpc.channel(YandexGrpc.TRANSLATE_ENDPOINT, config)
                : null;
        Glossary glossary = loadGlossary(config);
        Translator translator = translateChannel == null
                ? null
                : new Translator(translateChannel, config, glossary, view::status);

        RecognizerSession session = new RecognizerSession(sttChannel, config,
                new SpeechKitStream.Listener() {
                    @Override
                    public void onPartial(String text) {
                        view.partial(text);
                    }

                    @Override
                    public void onFinal(long index, String text, String language) {
                        // Чистим до показа и до перевода: зациклившееся
                        // распознавание переводчик усиливает многократно.
                        String clean = TextCleanup.collapseRepeats(text);
                        String lang = language.isBlank() ? config.primaryLang() : language;
                        view.phrase(index, clean, lang);

                        if (translator == null) return;
                        if (isTargetLanguage(lang, config.targetLang)) {
                            // Говорят на языке перевода — переводить нечего.
                            // Лишний вызов стоил бы денег и портил бы текст.
                            view.translation(index, clean);
                            return;
                        }
                        translator.translate(clean, lang,
                                ru -> view.translation(index, TextCleanup.collapseRepeats(ru)),
                                error -> view.status("перевод не удался: " + error));
                    }

                    @Override
                    public void onRefinement(long index, String text, String language) {
                        // Уточнённый текст приходит после финала: обновляем строку,
                        // а перевод не перезапрашиваем — он уже в пути или показан.
                        view.phrase(index, TextCleanup.collapseRepeats(text),
                                language.isBlank() ? config.primaryLang() : language);
                    }

                    @Override
                    public void onClosed(Throwable error) {
                        // переподключением занимается RecognizerSession
                    }

                    @Override
                    public void onStatus(String message) {
                        view.status(message);
                    }
                });

        sessionRef.set(session);
        if (config.recordFromStart) {
            System.out.println("Запись звука: " + capture.startRecording());
        }

        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Страховка: что бы ни зависло при закрытии, выход состоится.
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(8000);
                } catch (InterruptedException e) {
                    return;
                }
                System.err.println("Завершение затянулось, выходим принудительно.");
                Runtime.getRuntime().halt(0);
            }, "shutdown-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            // Каждый шаг изолирован: сбой одного не должен отменять остальные.
            // Лог закрывается до звука — он ценнее, а звуковая линия капризнее.
            quietly("закрытие потока распознавания", session::stop);
            quietly("остановка перевода", () -> {
                if (translator != null) translator.close();
            });
            quietly("закрытие соединений", () -> {
                sttChannel.shutdown();
                if (translateChannel != null) translateChannel.shutdown();
                sttChannel.awaitTermination(2, TimeUnit.SECONDS);
            });
            quietly("сохранение лога", log::close);
            quietly("остановка записи", capture::close);
            shutdown.countDown();
        }));

        if (glossary != null) {
            System.out.println("Словарь терминов: " + Glossary.plural(glossary.size())
                    + " из " + glossary.path() + " (правки подхватываются на ходу)");
        }
        System.out.println("Слушаю " + config.langsLabel()
                + " (" + capture.device() + ")"
                + ". Расшифровка пишется в " + log.path() + ". Выход — Ctrl+C.");
        System.out.println();
        shutdown.await();
    }

    /**
     * Переводит одну фразу дважды — без словаря и с ним — чтобы было видно,
     * что именно даёт словарь. Так подбираются словоформы: узбекский Translate
     * сопоставляет буквально, инфинитив из словаря к «qildik» не подойдёт.
     */
    private static void tryPhrase(Config config, String phrase) {
        Glossary glossary = loadGlossary(config);
        ManagedChannel channel = YandexGrpc.channel(YandexGrpc.TRANSLATE_ENDPOINT, config);
        try (Translator translator = new Translator(channel, config, glossary, s -> {})) {
            System.out.println("фраза:        " + phrase);
            System.out.println("без словаря:  " + translator.translateOnce(phrase, false));
            if (glossary == null) {
                System.out.println("(словарь не подключён)");
                return;
            }
            String withGlossary = translator.translateOnce(phrase, true);
            System.out.println("со словарём:  " + withGlossary);
        } catch (RuntimeException e) {
            System.err.println("Ошибка перевода: " + e.getMessage());
        } finally {
            channel.shutdownNow();
        }
    }

    /** Устройство теперь задаётся в .env, поэтому опечатка в имени — рядовой случай. */
    private static CaptureController openCapture(Config config,
                                                 AtomicReference<RecognizerSession> sessionRef,
                                                 SilenceGate gate) {
        try {
            return new CaptureController(config, chunk -> feed(sessionRef.get(), gate, chunk));
        } catch (javax.sound.sampled.LineUnavailableException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return null;
        }
    }

    /** Отдаёт фрагмент распознаванию через фильтр тишины. */
    private static void feed(RecognizerSession session, SilenceGate gate, byte[] chunk) {
        if (session == null) return;
        if (gate == null) {
            session.sendAudio(chunk, false);
            return;
        }
        switch (gate.offer(chunk)) {
            case SilenceGate.Speech speech -> {
                for (byte[] part : speech.chunks()) {
                    session.sendAudio(part, !gate.isSpeaking());
                }
            }
            case SilenceGate.Silence silence -> session.sendSilence(silence.durationMs());
        }
    }

    /** Переходник между окном и управлением захватом. */
    private static OverlayWindow.Control controlFor(CaptureController capture, double threshold,
                                                    AtomicReference<RecognizerSession> sessionRef) {
        return new OverlayWindow.Control() {
            @Override
            public List<String> availableDevices() {
                return capture.availableDevices();
            }

            @Override
            public String currentDevice() {
                return capture.device();
            }

            @Override
            public void switchDevice(String device) throws Exception {
                capture.switchDevice(device);
            }

            @Override
            public boolean isRecording() {
                return capture.isRecording();
            }

            @Override
            public Path startRecording() throws Exception {
                return capture.startRecording();
            }

            @Override
            public long stopRecording() {
                return capture.stopRecording();
            }

            @Override
            public long recordedSeconds() {
                return capture.recordedSeconds();
            }

            @Override
            public int inputLevel() {
                return capture.level();
            }

            @Override
            public double silenceThreshold() {
                return threshold;
            }

            @Override
            public void languagesChanged() {
                RecognizerSession session = sessionRef.get();
                if (session != null) session.restart();
            }

            @Override
            public boolean isPaused() {
                RecognizerSession session = sessionRef.get();
                return session != null && session.isPaused();
            }

            @Override
            public void setPaused(boolean paused) {
                RecognizerSession session = sessionRef.get();
                if (session == null) return;
                if (paused) session.pause();
                else session.resume();
            }
        };
    }

    /** Совпадает ли определённый язык с языком перевода: uz-UZ против ru. */
    private static boolean isTargetLanguage(String detected, String target) {
        return detected.split("-")[0].equalsIgnoreCase(target.split("-")[0]);
    }

    /** Шаг завершения работы, который не имеет права уронить остальные. */
    private static void quietly(String what, ThrowingRunnable action) {
        try {
            action.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            System.err.println("При завершении (" + what + "): " + t);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Извлекает фразу из --try=..., поддерживая пробелы без кавычек. */
    private static String probePhrase(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--try=")) return arg.substring("--try=".length());
        }
        return null;
    }

    /** Читает словарь, а при ошибке в нём останавливается: битый словарь ломает весь перевод. */
    private static Glossary loadGlossary(Config config) {
        if (!config.translate) return null;
        try {
            return Glossary.load(Path.of(config.glossaryPath), config.glossaryExplicit)
                    .orElse(null);
        } catch (IOException e) {
            System.err.println("Словарь терминов: " + e.getMessage());
            System.exit(1);
            return null;
        }
    }

    /** Показывает окно на выдуманных фразах: проверка шрифта и раскладки без ключей. */
    private static void demo(Config config) throws Exception {
        TranscriptView view = config.showUi
                ? fanOut(List.of(new ConsoleView(), OverlayWindow.create(config, null)))
                : new ConsoleView();
        String[][] phrases = {
                {"Salom, bugungi sprint haqida gaplashamiz",
                 "Привет, поговорим о сегодняшнем спринте"},
                {"Men autentifikatsiya servisini deploy qildim",
                 "Я задеплоил сервис аутентификации"},
                {"Testlar o'tmayapti, logni ko'rib chiqish kerak",
                 "Тесты не проходят, нужно посмотреть логи"},
        };
        for (int i = 0; i < phrases.length; i++) {
            view.partial(phrases[i][0].substring(0, phrases[i][0].length() / 2));
            Thread.sleep(600);
            view.phrase(i, phrases[i][0], "uz-UZ");
            Thread.sleep(500);
            view.translation(i, phrases[i][1]);
            Thread.sleep(700);
        }
        if (config.showUi) {
            System.out.println("Демо-окно открыто. Закройте его, чтобы выйти.");
            Thread.currentThread().join();
        }
    }

    /** Раздаёт события во все представления, не позволяя одному сломать остальные. */
    private static TranscriptView fanOut(List<TranscriptView> views) {
        return new TranscriptView() {
            @Override
            public void partial(String text) {
                views.forEach(v -> safely(() -> v.partial(text)));
            }

            @Override
            public void phrase(long id, String source, String language) {
                views.forEach(v -> safely(() -> v.phrase(id, source, language)));
            }

            @Override
            public void translation(long id, String translated) {
                views.forEach(v -> safely(() -> v.translation(id, translated)));
            }

            @Override
            public void status(String message) {
                views.forEach(v -> safely(() -> v.status(message)));
            }

            private void safely(Runnable action) {
                try {
                    action.run();
                } catch (RuntimeException e) {
                    System.err.println("вывод: " + e);
                }
            }
        };
    }

    private static boolean has(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equals(flag)) return true;
        }
        return false;
    }

    private static void printUsage() {
        System.out.println("""
                Live Translator — живой перевод речи через Yandex AI Studio.

                Переменные окружения:
                  YC_API_KEY     Api-Key сервисного аккаунта (или YC_IAM_TOKEN)
                  YC_FOLDER_ID   идентификатор каталога, обязателен для перевода

                Аргументы:
                  --list-devices        показать доступные устройства записи
                  --selftest            проверить соединение и ключ без микрофона
                  --demo                показать окно на тестовых фразах, без облака
                  --device=BlackHole    устройство (подстрока имени)
                  --level               показать уровень звука с устройства, без облака
                  --record              писать звук в WAV с самого старта
                  --lang=uz-UZ,ru-RU    языки распознавания через запятую (или auto)
                  --target=ru           язык перевода
                  --rate=16000          частота дискретизации
                  --no-ui               без окна, только терминал
                  --no-translate        только расшифровка, без перевода
                  --glossary=путь       словарь терминов (по умолчанию ./glossary.txt)
                  --try="фраза"         перевести фразу без словаря и со словарём
                  --pause=600           пауза (мс), после которой фраза считается законченной
                  --eou-default         реже резать фразы (для речи с чёткими паузами)
                  --max-phrase=25       предел длины фразы, с: дольше — закрываем принудительно
                  --no-literature       без литературной нормализации (она включена по умолчанию)
                  --no-vad              слать тишину как звук (отключить фильтр пауз)
                  --vad-threshold=180   порог тишины, RMS 0..32767
                  --font-size=20        размер шрифта в окне
                """);
    }
}
