package com.mikeasm.livetranslator;

import java.util.HashMap;
import java.util.Map;

/**
 * Настройки приложения: переменные окружения + аргументы командной строки.
 * Аргументы имеют приоритет над окружением.
 */
public final class Config {

    /** Языки по умолчанию, когда файл настроек ещё не создан. */
    public static final String DEFAULT_LANGS = "uz-UZ,ru-RU,en-US";
    /** Источник звука по умолчанию: для созвонов нужен виртуальный кабель. */
    public static final String DEFAULT_DEVICE = "BlackHole";

    /**
     * Значения настроек распознавания и перевода по умолчанию.
     * <p>
     * Вынесены сюда, потому что нужны в трёх местах: при чтении настроек, при
     * показе в окне и при сбросе. Разъехавшись, они дали бы кнопку «сбросить»,
     * которая восстанавливает не то, с чего всё начиналось.
     */
    public static final int DEFAULT_PAUSE_MS = 600;
    public static final boolean DEFAULT_EOU_HIGH = true;
    public static final boolean DEFAULT_LITERATURE = true;
    public static final int DEFAULT_MAX_PHRASE_SECONDS = 25;
    public static final double DEFAULT_VAD_THRESHOLD = 180;
    public static final boolean DEFAULT_VAD_AUTO = true;
    /**
     * Сколько слов копить перед отправкой на перевод.
     * <p>
     * Выбрано не по качеству перевода, а по счёту за облако. С каждым вызовом
     * модели заново уезжает инструкция, словарь терминов и контекст — около
     * 1700 знаков служебного текста на 150 знаков полезных. По замеру за
     * 20.09.2026 входящие токены стоили 104 ₽ против 4 ₽ исходящих: платим за
     * конверт, а не за письмо. Вдвое более крупные куски означают вдвое меньше
     * конвертов, и перевод от этого скорее выигрывает — модель видит за раз
     * больше связной речи. Плата — задержка: реплика появляется позже.
     */
    public static final int DEFAULT_MERGE_WORDS = 25;
    public static final long DEFAULT_MERGE_QUIET_MS = 1800;
    public static final boolean DEFAULT_USE_LLM = true;
    public static final String DEFAULT_LLM_MODEL = "yandexgpt/latest";

    /** Имена настроек, которые правятся в окне и сбрасываются одной кнопкой. */
    private static final String[] TUNING_KEYS = {
            "LT_PAUSE_MS", "LT_EOU_HIGH", "LT_LITERATURE", "LT_MAX_PHRASE",
            "LT_VAD_THRESHOLD", "LT_VAD_AUTO", "LT_MERGE_WORDS", "LT_MERGE_QUIET_MS",
            "LT_LLM", "LT_LLM_MODEL",
    };

    /** Api-Key сервисного аккаунта (YC_API_KEY). */
    public final String apiKey;
    /** IAM-токен, альтернатива Api-Key (YC_IAM_TOKEN). */
    public final String iamToken;
    /** Идентификатор каталога, обязателен для Translate (YC_FOLDER_ID). */
    public final String folderId;

    /**
     * Языки распознавания. Несколько — через запятую: на встрече могут
     * неожиданно перейти на другой язык, и модель выберет его сама по каждой
     * фразе. Значение «auto» отдаёт выбор полностью на откуп сервису, но явный
     * список точнее: он работает подсказкой.
     */
    private volatile java.util.List<String> sourceLangs;
    /** Язык перевода. */
    public final String targetLang;

    public final int sampleRate;
    /** Подстрока имени аудиоустройства; пусто — устройство по умолчанию. */
    public final String device;

    public final boolean showUi;
    /** Показывать исходную фразу под переводом — состояние галочки в панели. */
    public final boolean showSource;
    public final boolean vadEnabled;
    /** Порог RMS (0..32767), ниже которого фрагмент считается тишиной. */
    private volatile double vadThreshold;
    /** Подбирать порог тишины по собственному шуму источника. */
    private volatile boolean vadAuto;
    public final int fontSize;
    public final boolean translate;
    /** Пауза между словами, после которой фраза считается законченной, мс. */
    private volatile int pauseMs;
    /** Повышенная чувствительность к концу фразы — короче реплики, быстрее вывод. */
    private volatile boolean eouHigh;
    /** Литературная нормализация: сервер причёсывает фразу и ставит знаки препинания. */
    private volatile boolean literature;
    /** Предел длины одной фразы, с. Дольше — принудительно закрываем. */
    private volatile int maxPhraseSeconds;
    /** Начинать запись звука сразу при старте. */
    public final boolean recordFromStart;
    /** Переводить языковой моделью: она чинит ошибки распознавания по контексту. */
    private volatile boolean useLlm;
    private volatile String llmModel;
    public final int llmTimeoutSeconds;
    /** Сколько слов копить перед отправкой на перевод. */
    private volatile int mergeWords;
    /** Сколько ждать продолжения фразы, мс. */
    private volatile long mergeQuietMs;
    /** Путь к файлу словаря терминов. */
    public final String glossaryPath;
    /** Истина, если путь задан пользователем: тогда отсутствие файла — ошибка. */
    public final boolean glossaryExplicit;

    private Config(Map<String, String> a) {
        this.apiKey = env("YC_API_KEY", a.get("api-key"));
        this.iamToken = env("YC_IAM_TOKEN", a.get("iam-token"));
        this.folderId = env("YC_FOLDER_ID", a.get("folder-id"));
        String langs = a.containsKey("lang")
                ? a.get("lang") : settingOr("LT_LANGS", DEFAULT_LANGS);
        this.sourceLangs = java.util.Arrays.stream(langs.split(","))
                .map(String::trim)
                .filter(lang -> !lang.isEmpty())
                .toList();
        this.targetLang = a.containsKey("target")
                ? a.get("target") : settingOr("LT_TARGET", "ru");
        this.sampleRate = Integer.parseInt(a.getOrDefault("rate", "16000"));
        // Аргумент главнее сохранённого значения: разовый запуск с другим
        // источником не должен требовать правки файла настроек.
        this.device = a.containsKey("device")
                ? a.get("device").trim() : settingOr("LT_DEVICE", DEFAULT_DEVICE);
        this.showUi = !a.containsKey("no-ui");
        this.showSource = Boolean.parseBoolean(settingOr("LT_SHOW_SOURCE", "false"));
        this.vadEnabled = !a.containsKey("no-vad");
        this.vadAuto = !a.containsKey("vad-threshold")
                && Boolean.parseBoolean(settingOr("LT_VAD_AUTO", String.valueOf(DEFAULT_VAD_AUTO)));
        this.vadThreshold = Double.parseDouble(a.getOrDefault("vad-threshold",
                settingOr("LT_VAD_THRESHOLD", String.valueOf(DEFAULT_VAD_THRESHOLD))));
        this.fontSize = Integer.parseInt(a.getOrDefault("font-size", "20"));
        this.translate = !a.containsKey("no-translate");
        this.pauseMs = Integer.parseInt(a.getOrDefault("pause",
                settingOr("LT_PAUSE_MS", String.valueOf(DEFAULT_PAUSE_MS))));
        this.eouHigh = a.containsKey("eou-default")
                ? false : Boolean.parseBoolean(settingOr("LT_EOU_HIGH",
                        String.valueOf(DEFAULT_EOU_HIGH)));
        this.maxPhraseSeconds = Integer.parseInt(a.getOrDefault("max-phrase",
                settingOr("LT_MAX_PHRASE", String.valueOf(DEFAULT_MAX_PHRASE_SECONDS))));
        this.recordFromStart = a.containsKey("record");
        this.useLlm = !a.containsKey("no-llm")
                && Boolean.parseBoolean(settingOr("LT_LLM", String.valueOf(DEFAULT_USE_LLM)));
        this.llmModel = settingOr("LT_LLM_MODEL", DEFAULT_LLM_MODEL);
        this.llmTimeoutSeconds = Integer.parseInt(a.getOrDefault("llm-timeout", "20"));
        this.mergeWords = Integer.parseInt(a.getOrDefault("merge-words",
                settingOr("LT_MERGE_WORDS", String.valueOf(DEFAULT_MERGE_WORDS))));
        this.mergeQuietMs = Long.parseLong(a.getOrDefault("merge-quiet",
                settingOr("LT_MERGE_QUIET_MS", String.valueOf(DEFAULT_MERGE_QUIET_MS))));
        // Проверено на живой речи: с нормализацией перевод заметно связнее,
        // потому что переводчик видит границы предложений.
        this.literature = a.containsKey("no-literature")
                ? false : Boolean.parseBoolean(settingOr("LT_LITERATURE",
                        String.valueOf(DEFAULT_LITERATURE)));
        this.glossaryExplicit = a.containsKey("glossary");
        this.glossaryPath = a.containsKey("glossary")
                ? a.get("glossary") : AppPaths.glossaryFile().toString();
    }

    /** Аргумент командной строки главнее сохранённых настроек. */
    private static String env(String name, String override) {
        if (override != null && !override.isBlank()) return override.trim();
        return Settings.get(name);
    }

    public java.util.List<String> sourceLangs() {
        return sourceLangs;
    }

    /** Применяется к следующему открытому потоку распознавания. */
    public void setSourceLangs(java.util.List<String> langs) {
        if (!langs.isEmpty()) this.sourceLangs = java.util.List.copyOf(langs);
    }

    /** Значение из настроек или разумное умолчание, если ничего не задано. */
    private static String settingOr(String name, String fallback) {
        String value = Settings.get(name);
        return value.isBlank() ? fallback : value;
    }

    // --- Настройки, которые правятся на ходу ---------------------------------
    // Компоненты читают их при каждом использовании, а не запоминают при старте,
    // поэтому изменение применяется без перезапуска приложения.

    public double vadThreshold() {
        return vadThreshold;
    }

    public boolean vadAuto() {
        return vadAuto;
    }

    public int pauseMs() {
        return pauseMs;
    }

    public boolean eouHigh() {
        return eouHigh;
    }

    public boolean literature() {
        return literature;
    }

    public int maxPhraseSeconds() {
        return maxPhraseSeconds;
    }

    public boolean useLlm() {
        return useLlm;
    }

    public String llmModel() {
        return llmModel;
    }

    public int mergeWords() {
        return mergeWords;
    }

    public long mergeQuietMs() {
        return mergeQuietMs;
    }

    /** Применяет и запоминает настройки распознавания. */
    public void setRecognitionTuning(int pauseMs, boolean eouHigh, boolean literature,
                                     int maxPhraseSeconds, double vadThreshold,
                                     boolean vadAuto) {
        this.vadAuto = vadAuto;
        Settings.save("LT_VAD_AUTO", String.valueOf(vadAuto));
        this.pauseMs = pauseMs;
        this.eouHigh = eouHigh;
        this.literature = literature;
        this.maxPhraseSeconds = maxPhraseSeconds;
        this.vadThreshold = vadThreshold;
        Settings.save("LT_PAUSE_MS", String.valueOf(pauseMs));
        Settings.save("LT_EOU_HIGH", String.valueOf(eouHigh));
        Settings.save("LT_LITERATURE", String.valueOf(literature));
        Settings.save("LT_MAX_PHRASE", String.valueOf(maxPhraseSeconds));
        Settings.save("LT_VAD_THRESHOLD", String.valueOf(vadThreshold));
    }

    /** Применяет и запоминает настройки перевода. */
    public void setTranslationTuning(int mergeWords, long mergeQuietMs,
                                     boolean useLlm, String llmModel) {
        this.mergeWords = mergeWords;
        this.mergeQuietMs = mergeQuietMs;
        this.useLlm = useLlm;
        this.llmModel = llmModel.isBlank() ? DEFAULT_LLM_MODEL : llmModel;
        Settings.save("LT_MERGE_WORDS", String.valueOf(mergeWords));
        Settings.save("LT_MERGE_QUIET_MS", String.valueOf(mergeQuietMs));
        Settings.save("LT_LLM", String.valueOf(useLlm));
        Settings.save("LT_LLM_MODEL", this.llmModel);
    }

    /**
     * Возвращает настройки к исходным значениям.
     * <p>
     * Записи стираются, а не переписываются значениями по умолчанию: если
     * умолчание однажды поменяется, сброшенная настройка должна поехать вместе
     * с ним, а не остаться замороженной.
     */
    public void resetTuning() {
        this.pauseMs = DEFAULT_PAUSE_MS;
        this.eouHigh = DEFAULT_EOU_HIGH;
        this.literature = DEFAULT_LITERATURE;
        this.maxPhraseSeconds = DEFAULT_MAX_PHRASE_SECONDS;
        this.vadThreshold = DEFAULT_VAD_THRESHOLD;
        this.vadAuto = DEFAULT_VAD_AUTO;
        this.mergeWords = DEFAULT_MERGE_WORDS;
        this.mergeQuietMs = DEFAULT_MERGE_QUIET_MS;
        this.useLlm = DEFAULT_USE_LLM;
        this.llmModel = DEFAULT_LLM_MODEL;
        for (String key : TUNING_KEYS) Settings.save(key, "");
    }

    /** Первый язык списка — он же язык по умолчанию, когда метка не пришла. */
    public String primaryLang() {
        return sourceLangs.isEmpty() ? "uz-UZ" : sourceLangs.get(0);
    }

    /** Список языков для показа пользователю. */
    public String langsLabel() {
        return String.join(", ", sourceLangs);
    }

    /** Откуда взялся ключ — показываем при старте, не раскрывая значение. */
    public String credentialOrigin() {
        return apiKey.isBlank() ? Settings.origin("YC_IAM_TOKEN") : Settings.origin("YC_API_KEY");
    }

    /** Разбирает аргументы вида --key=value и --flag. */
    public static Config parse(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) continue;
            String body = arg.substring(2);
            int eq = body.indexOf('=');
            if (eq < 0) map.put(body, "");
            else map.put(body.substring(0, eq), body.substring(eq + 1));
        }
        return new Config(map);
    }

    /** Заголовок авторизации для gRPC-вызовов. */
    public String authHeader() {
        if (!apiKey.isBlank()) return "Api-Key " + apiKey;
        if (!iamToken.isBlank()) return "Bearer " + iamToken;
        throw new IllegalStateException(
                "Не задан ключ доступа: установите YC_API_KEY (или YC_IAM_TOKEN)");
    }
}
