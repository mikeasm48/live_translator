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
    public final double vadThreshold;
    public final int fontSize;
    public final boolean translate;
    /** Пауза между словами, после которой фраза считается законченной, мс. */
    public final int pauseMs;
    /** Повышенная чувствительность к концу фразы — короче реплики, быстрее вывод. */
    public final boolean eouHigh;
    /** Литературная нормализация: сервер причёсывает фразу и ставит знаки препинания. */
    public final boolean literature;
    /** Предел длины одной фразы, с. Дольше — принудительно закрываем. */
    public final int maxPhraseSeconds;
    /** Начинать запись звука сразу при старте. */
    public final boolean recordFromStart;
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
        this.vadThreshold = Double.parseDouble(a.getOrDefault("vad-threshold", "180"));
        this.fontSize = Integer.parseInt(a.getOrDefault("font-size", "20"));
        this.translate = !a.containsKey("no-translate");
        this.pauseMs = Integer.parseInt(a.getOrDefault("pause", "600"));
        this.eouHigh = !a.containsKey("eou-default");
        this.maxPhraseSeconds = Integer.parseInt(a.getOrDefault("max-phrase", "25"));
        this.recordFromStart = a.containsKey("record");
        // Проверено на живой речи: с нормализацией перевод заметно связнее,
        // потому что переводчик видит границы предложений.
        this.literature = !a.containsKey("no-literature");
        this.glossaryExplicit = a.containsKey("glossary");
        this.glossaryPath = a.getOrDefault("glossary", Glossary.DEFAULT_FILE);
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
