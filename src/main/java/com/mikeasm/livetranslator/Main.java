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
    /** Версия приложения — попадает в Info.plist значка и в окно настроек. */
    static final String VERSION = "0.4.3";

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
        if (has(args, "--bench-help")) {
            com.mikeasm.livetranslator.bench.Bench.printUsage();
            return;
        }
        // Ключи Yandex спрашиваем, только если ими и будем пользоваться: на
        // Gemini у приложения свой ключ, и требовать чужой — значит не пускать
        // человека к работе из-за сервиса, который ему не нужен.
        boolean needsYandex = !parsed.usesGemini() || has(args, "--selftest")
                || probePhrase(args) != null;
        if (needsYandex && (parsed.apiKey.isBlank() && parsed.iamToken.isBlank()
                || parsed.translate && parsed.folderId.isBlank())) {
            // Первый запуск на новой машине: спрашиваем доступы и запоминаем.
            if (!FirstRun.ensureConfigured(parsed.showUi)) {
                System.err.println("Без ключа и каталога работать не с чем. "
                        + "Подробности в README.md");
                System.exit(1);
            }
            parsed = Config.parse(args);
        }
        offerUpdate(parsed.showUi);

        // Ключ спрашивается до того, как откроется окно перевода: иначе
        // диалог соревнуется с ним за передний план, а человек видит панель и
        // думает, что программа просто не работает.
        if (parsed.usesGemini() && !has(args, "--selftest") && probePhrase(args) == null) {
            if (!FirstRun.ensureGeminiKey(parsed.showUi)) {
                System.err.println("Без ключа Gemini переводить нечем.");
                System.err.println("Ключ берётся на https://aistudio.google.com/apikey");
                System.err.println("Либо работать по-прежнему через Yandex: --engine=yandex");
                System.exit(1);
            }
            parsed = Config.parse(args);
        }
        if (hasValue(args, "--bench=")) {
            com.mikeasm.livetranslator.bench.Bench.run(parsed, args);
            return;
        }
        String probe = probePhrase(args);
        if (probe != null) {
            tryPhrase(parsed, probe);
            return;
        }
        if (has(args, "--selftest")) {
            if (!parsed.usesGemini()) {
                System.out.println("0. Ключ: " + parsed.credentialOrigin()
                        + "; каталог " + parsed.folderId + ": "
                        + Settings.origin("YC_FOLDER_ID"));
            }
            SelfTest.run(parsed, loadGlossary(parsed));
            return;
        }
        // Захват создаётся раньше распознавания, поэтому сессия отдаётся ему
        // через ссылку: до её появления звук просто отбрасывается.
        AtomicReference<Listening> sessionRef = new AtomicReference<>();
        SilenceGate gate = parsed.vadEnabled ? new SilenceGate(parsed) : null;
        CaptureController capture = openCapture(parsed, sessionRef, gate);

        // После возможной донастройки значение больше не меняется —
        // финальная копия нужна лямбдам ниже.
        final Config config = parsed;

        List<TranscriptView> views = new ArrayList<>();
        views.add(new ConsoleView());
        if (config.showUi) views.add(OverlayWindow.create(config, controlFor(capture, gate, sessionRef)));
        SessionLog log = new SessionLog(AppPaths.logsDir());
        views.add(log);
        TranscriptView view = fanOut(views);

        if (config.usesGemini()) {
            runOnGemini(config, capture, gate, sessionRef, view, log);
            return;
        }

        AtomicReference<Translator> translatorRef = new AtomicReference<>();
        PhraseBuffer phrases = new PhraseBuffer(config,
                (id, text, lang) -> showAndTranslate(view, translatorRef.get(), config,
                        id, text, lang));

        ManagedChannel sttChannel = YandexGrpc.channel(YandexGrpc.STT_ENDPOINT, config);
        ManagedChannel translateChannel = config.translate
                ? YandexGrpc.channel(YandexGrpc.TRANSLATE_ENDPOINT, config)
                : null;
        Glossary glossary = loadGlossary(config);
        ManagedChannel llmChannel = config.translate && config.useLlm()
                ? YandexGrpc.channel(YandexGrpc.LLM_ENDPOINT, config)
                : null;
        LlmTranslator llm = llmChannel == null
                ? null
                : new LlmTranslator(llmChannel, config, glossary);
        Translator translator = translateChannel == null
                ? null
                : new Translator(translateChannel, config, glossary, view::status, llm);
        translatorRef.set(translator);

        RecognizerSession session = new RecognizerSession(sttChannel, config,
                new SpeechKitStream.Listener() {
                    @Override
                    public void onPartial(String text) {
                        view.partial(text);
                    }

                    @Override
                    public void onFinal(long index, String text, String language, long endMs) {
                        // Чистим до показа и до перевода: зациклившееся
                        // распознавание переводчик усиливает многократно.
                        String clean = TextCleanup.collapseRepeats(text);
                        String lang = language.isBlank() ? config.primaryLang() : language;
                        phrases.add(clean, lang);
                    }

                    @Override
                    public void onRefinement(long index, String text, String language) {
                        // При склейке фраз уточнение привязать не к чему: кусок
                        // уже мог уйти на перевод вместе с соседями.
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

        session.pause();
        sessionRef.set(new Listening() {
            @Override
            public void feed(byte[] chunk) {
                Main.feed(session, gate, chunk);
            }

            @Override
            public void pause() {
                session.pause();
            }

            @Override
            public void resume() {
                session.resume();
            }

            @Override
            public boolean isPaused() {
                return session.isPaused();
            }

            @Override
            public void languagesChanged() {
                session.restart();
            }

            @Override
            public void close() {
                session.stop();
            }
        });
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
            // Недоговорённый кусок тоже нужно перевести, иначе конец встречи
            // потеряется вместе с буфером.
            quietly("последняя фраза", phrases::close);
            quietly("остановка перевода", () -> {
                if (translator != null) translator.close();
            });
            quietly("закрытие соединений", () -> {
                sttChannel.shutdown();
                if (translateChannel != null) translateChannel.shutdown();
                if (llmChannel != null) llmChannel.shutdown();
                sttChannel.awaitTermination(2, TimeUnit.SECONDS);
            });
            quietly("сохранение лога", log::close);
            quietly("остановка записи", capture::close);
            shutdown.countDown();
        }));

        capture.onNotice(view::status);
        if (!capture.fallbackNotice().isBlank()) {
            view.status(capture.fallbackNotice());
        }
        if (glossary != null) {
            System.out.println("Словарь терминов: " + Glossary.plural(glossary.size())
                    + " из " + glossary.path() + " (правки подхватываются на ходу)");
        }
        view.status("перевод на паузе — нажмите «продолжить», когда встреча начнётся");
        System.out.println("Перевод на паузе: нажмите «продолжить» в панели, "
                + "когда встреча начнётся.");
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

    /**
     * Живой перевод через Gemini: модель слышит звук сама и сразу отвечает
     * по-русски, без отдельного распознавания и отдельного перевода.
     */
    private static void runOnGemini(Config config, CaptureController capture, SilenceGate gate,
                                    AtomicReference<Listening> sessionRef, TranscriptView view,
                                    SessionLog log) throws Exception {
        GeminiClient client = new GeminiClient(config);
        if (!client.skipReason().isBlank()) {
            // Приложение запускают двойным щелчком из «Программ», и человек,
            // которому оно предназначено, терминала не увидит вовсе — для него
            // сообщение в консоли равносильно тому, что программа не работает.
            // Поэтому ключ спрашивается окном, а консольная подсказка остаётся
            // только для запуска из терминала.
            if (!FirstRun.ensureGeminiKey(config.showUi)) {
                System.err.println("Gemini выбран движком, но " + client.skipReason() + ".");
                System.err.println("Ключ берётся на https://aistudio.google.com/apikey");
                System.err.println("и вводится в настройках приложения: Cmd + , → «Доступ».");
                System.err.println("Либо работать по-прежнему через Yandex: --engine=yandex");
                System.exit(1);
            }
            // Настройки перечитываются: ключ только что появился.
            client = new GeminiClient(Config.parse(new String[0]));
        }

        GeminiClient ready = client;
        GeminiSession gemini = new GeminiSession(config, ready, gate,
                new GeminiSession.Listener() {
                    @Override
                    public void line(long id, String original, String text,
                                     java.time.LocalTime spokenAt) {
                        // Языковой метки у нас здесь нет и взяться ей неоткуда:
                        // модель слушает звук, а не подписывает его языком.
                        if (original.isBlank()) {
                            view.phrase(id, text, "", spokenAt);
                            view.translation(id, text, TranslatedBy.SOURCE);
                        } else {
                            view.phrase(id, original, "", spokenAt);
                            view.translation(id, text, TranslatedBy.MODEL);
                        }
                    }

                    @Override
                    public void status(String message) {
                        view.status(message);
                    }

                    @Override
                    public void state(String state) {
                        view.state(state);
                    }
                });

        sessionRef.set(new Listening() {
            @Override
            public void feed(byte[] chunk) {
                gemini.offer(chunk);
            }

            @Override
            public void pause() {
                gemini.pause();
            }

            @Override
            public void resume() {
                gemini.resume();
            }

            @Override
            public boolean isPaused() {
                return gemini.isPaused();
            }

            @Override
            public void close() {
                gemini.close();
            }
        });

        if (config.recordFromStart) {
            System.out.println("Запись звука: " + capture.startRecording());
        }

        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(45000);
                } catch (InterruptedException e) {
                    return;
                }
                System.err.println("Завершение затянулось, выходим принудительно.");
                Runtime.getRuntime().halt(0);
            }, "shutdown-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            // Недоговорённый кусок отправляется и дожидается ответа: конец
            // встречи обычно и есть самое важное.
            quietly("последний кусок", gemini::close);
            quietly("сохранение лога", log::close);
            quietly("остановка записи", capture::close);
            shutdown.countDown();
        }));

        capture.onNotice(view::status);
        if (!capture.fallbackNotice().isBlank()) view.status(capture.fallbackNotice());

        view.status("перевод на паузе — нажмите «продолжить», когда встреча начнётся");
        System.out.println("Перевод на паузе: нажмите «продолжить» в панели, "
                + "когда встреча начнётся.");
        System.out.println("Слушаю через Gemini " + config.geminiModel()
                + ", кусками по " + config.chunkSeconds() + " с"
                + " (" + capture.device() + ")."
                + " Расшифровка пишется в " + log.path() + ". Выход — Ctrl+C.");
        System.out.println();
        shutdown.await();
    }

    /**
     * Предлагает обновиться, если вышла версия новее.
     * <p>
     * Спрашивается до того, как откроется окно перевода: иначе диалог окажется
     * под ним, как это уже было с вводом ключа. И до начала встречи, а не
     * посреди неё.
     */
    private static void offerUpdate(boolean graphical) {
        // Проверка не должна задерживать запуск: на сломанном сервере имён
        // обращение к сети может подвиснуть дольше собственного таймаута.
        java.util.concurrent.atomic.AtomicReference<Updates.Available> found =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread probe = new Thread(() -> Updates.check(VERSION).ifPresent(found::set), "updates");
        probe.setDaemon(true);
        probe.start();
        try {
            probe.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        Updates.Available update = found.get();
        if (update == null) return;

        if (!graphical || java.awt.GraphicsEnvironment.isHeadless()) {
            System.out.println("Доступна версия " + update.version()
                    + ". Обновить: brew upgrade live-translator");
            return;
        }

        proposeInstall(null, update);
    }

    /**
     * Проверяет обновление по просьбе из настроек.
     * <p>
     * Проверка идёт в стороне, а окна показываются в потоке отрисовки: Swing
     * модальных окон из чужих потоков не прощает.
     */
    static void checkForUpdates(java.awt.Component owner, Runnable done) {
        Thread probe = new Thread(() -> {
            java.util.Optional<Updates.Available> found = Updates.check(VERSION);
            javax.swing.SwingUtilities.invokeLater(() -> {
                // Кнопку возвращаем в исходное до показа ответа: иначе человек
                // читает «У вас последняя версия», а рядом всё ещё «Проверяю…».
                done.run();
                if (found.isPresent()) {
                    proposeInstall(owner, found.get());
                } else {
                    javax.swing.JOptionPane.showMessageDialog(owner,
                            "У вас последняя версия: " + VERSION + ".");
                }
            });
        }, "updates-manual");
        probe.setDaemon(true);
        probe.start();
    }

    /** Спрашивает и ставит. Вызывается в потоке отрисовки. */
    private static void proposeInstall(java.awt.Component owner, Updates.Available update) {
        int answer = ask(owner, "Доступна версия " + update.version() + ", у вас "
                + VERSION + ".\nОбновить сейчас? Приложение закроется и откроется заново.",
                "Обновление", new String[]{"Обновить", "Позже"});
        if (answer != 0) return;

        String failure = withProgress(owner, "Обновляю до " + update.version() + "…",
                Updates::install);
        if (failure == null) return;
        if (!failure.isBlank()) {
            javax.swing.JOptionPane.showMessageDialog(owner,
                    "Обновить не удалось: " + failure
                            + "\n\nМожно обновиться вручную: brew upgrade live-translator");
            return;
        }
        Updates.relaunch();
        System.exit(0);
    }

    /**
     * Показывает окно ожидания, пока работа идёт в стороне.
     * <p>
     * Обновление занимает минуту и больше, и без этого окна приложение выглядит
     * зависшим.
     *
     * @return результат работы или null, если что-то пошло совсем не так
     */
    private static String withProgress(java.awt.Component owner, String caption,
                                      java.util.function.Supplier<String> work) {
        // При запуске владельца нет, и это законно: окна ещё не открыты.
        java.awt.Window parent = owner == null
                ? null : javax.swing.SwingUtilities.getWindowAncestor(owner);
        javax.swing.JDialog dialog = new javax.swing.JDialog(parent, "Live Translator",
                java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        javax.swing.JPanel panel = new javax.swing.JPanel(new java.awt.BorderLayout(0, 12));
        panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(18, 20, 18, 20));
        panel.add(new javax.swing.JLabel(caption), java.awt.BorderLayout.NORTH);
        javax.swing.JProgressBar bar = new javax.swing.JProgressBar();
        bar.setIndeterminate(true);
        panel.add(bar, java.awt.BorderLayout.CENTER);
        dialog.setContentPane(panel);
        dialog.setAlwaysOnTop(true);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setDefaultCloseOperation(javax.swing.JDialog.DO_NOTHING_ON_CLOSE);

        java.util.concurrent.atomic.AtomicReference<String> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                result.set(work.get());
            } finally {
                javax.swing.SwingUtilities.invokeLater(dialog::dispose);
            }
        }, "update-worker");
        worker.setDaemon(true);
        worker.start();
        dialog.setVisible(true);
        return result.get();
    }

    /** Модальный вопрос поверх всех окон: под окном перевода его было бы не видно. */
    private static int ask(java.awt.Component owner, String message, String title,
                           String[] options) {
        javax.swing.JOptionPane pane = new javax.swing.JOptionPane(message,
                javax.swing.JOptionPane.QUESTION_MESSAGE,
                javax.swing.JOptionPane.DEFAULT_OPTION, null, options, options[0]);
        javax.swing.JDialog dialog = pane.createDialog(owner, title);
        dialog.setAlwaysOnTop(true);
        dialog.setVisible(true);
        dialog.dispose();
        Object choice = pane.getValue();
        for (int i = 0; i < options.length; i++) {
            if (options[i].equals(choice)) return i;
        }
        return -1;
    }

    /**
     * То, что слушает речь. Движка два: прежний Yandex и Gemini, который
     * слышит звук сам и сразу отвечает по-русски. Панель и захват звука не
     * должны знать, какой из них работает.
     */
    private interface Listening extends AutoCloseable {
        void feed(byte[] chunk);

        void pause();

        void resume();

        boolean isPaused();

        /** Языки поменяли в настройках — применить. */
        default void languagesChanged() {}

        @Override
        void close();
    }

    /** Устройство теперь задаётся в .env, поэтому опечатка в имени — рядовой случай. */
    private static CaptureController openCapture(Config config,
                                                 AtomicReference<Listening> sessionRef,
                                                 SilenceGate gate) {
        try {
            return new CaptureController(config, chunk -> {
                Listening listening = sessionRef.get();
                if (listening != null) listening.feed(chunk);
            });
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
    private static OverlayWindow.Control controlFor(CaptureController capture, SilenceGate gate,
                                                    AtomicReference<Listening> sessionRef) {
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
                // Порог может подбираться на ходу — показываем действующий.
                return gate == null ? Config.DEFAULT_VAD_THRESHOLD : gate.currentThreshold();
            }

            @Override
            public void languagesChanged() {
                Listening session = sessionRef.get();
                if (session != null) session.languagesChanged();
            }

            @Override
            public boolean isPaused() {
                Listening session = sessionRef.get();
                // Сессии ещё нет — значит, ничего и не переводится.
                return session == null || session.isPaused();
            }

            @Override
            public void setPaused(boolean paused) {
                Listening session = sessionRef.get();
                if (session == null) return;
                if (paused) session.pause();
                else session.resume();
            }
        };
    }

    /**
     * Показывает готовый кусок и отправляет его на перевод.
     * <p>
     * Реплика появляется на экране сразу, а перевод заменяет её, когда придёт:
     * так видно, что происходит, пока модель думает.
     */
    private static void showAndTranslate(TranscriptView view, Translator translator,
                                         Config config, long id, String text, String lang) {
        view.phrase(id, text, lang);
        if (translator == null) return;
        if (isTargetLanguage(lang, config.targetLang)) {
            // Говорят на языке перевода — переводить нечего. Лишний вызов
            // стоил бы денег и портил бы формулировки.
            view.translation(id, text, TranslatedBy.SOURCE);
            return;
        }
        translator.translate(text, lang,
                (ru, by) -> view.translation(id, TextCleanup.collapseRepeats(ru), by),
                error -> view.status("перевод не удался: " + error));
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
            view.translation(i, phrases[i][1], TranslatedBy.MODEL);
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
            public void phrase(long id, String source, String language,
                               java.time.LocalTime spokenAt) {
                views.forEach(v -> safely(() -> v.phrase(id, source, language, spokenAt)));
            }

            @Override
            public void translation(long id, String translated, TranslatedBy by) {
                views.forEach(v -> safely(() -> v.translation(id, translated, by)));
            }

            @Override
            public void status(String message) {
                views.forEach(v -> safely(() -> v.status(message)));
            }

            @Override
            public void state(String state) {
                views.forEach(v -> safely(() -> v.state(state)));
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

    /** Есть ли аргумент с таким началом — для ключей вида {@code --bench=файл}. */
    private static boolean hasValue(String[] args, String prefix) {
        for (String arg : args) {
            if (arg.startsWith(prefix)) return true;
        }
        return false;
    }

    private static void printUsage() {
        System.out.println("""
                Live Translator — живой перевод речи на встрече.

                Переменные окружения:
                  LT_GEMINI_KEY  ключ Gemini (движок по умолчанию)
                  YC_API_KEY     Api-Key Yandex, если выбран движок yandex
                  YC_FOLDER_ID   идентификатор каталога Yandex

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
                  --engine=gemini       чем слушать речь: gemini или yandex
                  --chunk-seconds=10    предел длины куска звука для Gemini
                  --no-llm              переводить обычным переводчиком, без модели
                  --merge-words=25      сколько слов копить перед переводом
                  --merge-quiet=1800    сколько ждать продолжения фразы, мс
                  --glossary=путь       словарь терминов (по умолчанию ./glossary.txt)
                  --try="фраза"         перевести фразу без словаря и со словарём
                  --pause=600           пауза (мс), после которой фраза считается законченной
                  --eou-default         реже резать фразы (для речи с чёткими паузами)
                  --max-phrase=25       предел длины фразы, с: дольше — закрываем принудительно
                  --no-literature       без литературной нормализации (она включена по умолчанию)
                  --no-vad              слать тишину как звук (отключить фильтр пауз)
                  --vad-threshold=180   порог тишины, RMS 0..32767
                  --font-size=20        размер шрифта в окне
                  --bench=запись.wav    прогнать запись через все движки распознавания
                  --bench-help          подробности про стенд сравнения движков
                """);
    }
}
