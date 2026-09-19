package com.mikeasm.livetranslator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Где приложение держит свои файлы.
 * <p>
 * Различаются два случая. Запуск из папки с исходниками — всё лежит рядом,
 * как привык разработчик. Установленное через Homebrew приложение запускается
 * из произвольного каталога, и складывать настройки в текущую папку нельзя:
 * они расходятся по стандартным местам macOS.
 */
public final class AppPaths {

    private static final String APP = "live-translator";

    /** Признак работы из репозитория: рядом лежит файл сборки. */
    private static final boolean DEVELOPMENT = Files.isRegularFile(Path.of("pom.xml"));

    private AppPaths() {}

    public static boolean development() {
        return DEVELOPMENT;
    }

    /** Файл настроек, в который пишутся изменения. */
    public static Path settingsFile() {
        return DEVELOPMENT
                ? Path.of(".env")
                : configDir().resolve("config");
    }

    /** Расшифровки встреч и записи звука. */
    public static Path logsDir() {
        return DEVELOPMENT
                ? Path.of("logs")
                : Path.of(System.getProperty("user.home"), "Documents", "LiveTranslator");
    }

    /**
     * Словарь терминов. У установленного приложения он живёт в настройках
     * пользователя, потому что его правят руками; при первом запуске туда
     * кладётся заготовка из поставки.
     */
    public static Path glossaryFile() {
        if (DEVELOPMENT) return Path.of(Glossary.DEFAULT_FILE);
        Path file = configDir().resolve("glossary.txt");
        if (!Files.exists(file)) seedGlossary(file);
        return file;
    }

    public static Path configDir() {
        return Path.of(System.getProperty("user.home"), ".config", APP);
    }

    private static void seedGlossary(Path target) {
        try (InputStream bundled = AppPaths.class.getResourceAsStream("/glossary.txt")) {
            if (bundled == null) return;
            Files.createDirectories(target.getParent());
            Files.copy(bundled, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Не удалось создать словарь терминов: " + e.getMessage());
        }
    }
}
