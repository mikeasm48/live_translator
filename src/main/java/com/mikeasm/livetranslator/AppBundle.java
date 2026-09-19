package com.mikeasm.livetranslator;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.nio.file.attribute.PosixFilePermission;

/**
 * Создаёт значок приложения в «Программах» пользователя.
 * <p>
 * Сделать это при установке нельзя: Homebrew ставит формулы в песочнице с
 * подменённым домашним каталогом, а общий {@code /Applications} macOS защищает
 * механизмом App Management и без разрешения туда не пускает. Поэтому значок
 * создаёт само приложение при первом запуске — своих-то прав на домашнюю папку
 * у него достаточно.
 * <p>
 * Внутри значка лежат только иконка и запускающий скрипт: сам jar остаётся там,
 * куда его положил Homebrew, поэтому копия занимает килобайты и не устаревает
 * при обновлении.
 */
public final class AppBundle {

    private static final String NAME = "Live Translator";

    private AppBundle() {}

    /** Создаёт или обновляет значок, если это нужно. Тихо ничего не делает при сбое. */
    public static void ensureInstalled(String version) {
        if (AppPaths.development()) return;
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) return;

        try {
            Path jar = currentJar();
            if (jar == null) return;

            Path bundle = Path.of(System.getProperty("user.home"), "Applications", NAME + ".app");
            Path launcher = bundle.resolve("Contents/MacOS/live-translator");
            String script = launcherScript(jar);

            // Переписываем только при изменениях: путь к jar или версия Java
            // могли поменяться после обновления.
            if (Files.exists(launcher) && script.equals(Files.readString(launcher))) return;

            create(bundle, jar, version, script);
            System.out.println("Приложение добавлено в «Программы»: " + bundle);
        } catch (IOException | URISyntaxException e) {
            // Значок — удобство, а не условие работы: молча продолжаем.
            System.err.println("Не удалось создать значок приложения: " + e.getMessage());
        }
    }

    private static void create(Path bundle, Path jar, String version, String script)
            throws IOException {
        Files.createDirectories(bundle.resolve("Contents/MacOS"));
        Files.createDirectories(bundle.resolve("Contents/Resources"));

        try (InputStream icon = AppBundle.class.getResourceAsStream("/live-translator.icns")) {
            if (icon != null) {
                Files.copy(icon, bundle.resolve("Contents/Resources/live-translator.icns"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }

        Files.writeString(bundle.resolve("Contents/Info.plist"), plist(version),
                StandardCharsets.UTF_8);

        Path launcher = bundle.resolve("Contents/MacOS/live-translator");
        Files.writeString(launcher, script, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(launcher, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE));
    }

    /** Запускает тем же интерпретатором Java, которым запущено приложение сейчас. */
    private static String launcherScript(Path jar) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path icon = Path.of(System.getProperty("user.home"), "Applications",
                NAME + ".app/Contents/Resources/live-translator.icns");
        return """
                #!/bin/bash
                # Создано приложением при первом запуске. Имя и иконка в Dock
                # задаются явно, иначе система покажет процесс как «java».
                exec "%s" \\
                  -Xdock:name="%s" \\
                  -Xdock:icon="%s" \\
                  -Dapple.awt.application.name="%s" \\
                  -jar "%s" "$@"
                """.formatted(java, NAME, icon, NAME, jar);
    }

    private static String plist(String version) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                  <key>CFBundleName</key><string>%s</string>
                  <key>CFBundleDisplayName</key><string>%s</string>
                  <key>CFBundleIdentifier</key><string>com.mikeasm.livetranslator</string>
                  <key>CFBundleExecutable</key><string>live-translator</string>
                  <key>CFBundleIconFile</key><string>live-translator</string>
                  <key>CFBundlePackageType</key><string>APPL</string>
                  <key>CFBundleShortVersionString</key><string>%s</string>
                  <key>CFBundleVersion</key><string>%s</string>
                  <key>LSMinimumSystemVersion</key><string>12.0</string>
                  <key>NSHighResolutionCapable</key><true/>
                  <key>NSMicrophoneUsageDescription</key>
                  <string>Приложение слушает микрофон или виртуальный аудиокабель, чтобы переводить речь.</string>
                </dict>
                </plist>
                """.formatted(NAME, NAME, version, version);
    }

    /** Путь к jar, из которого запущено приложение. */
    private static Path currentJar() throws URISyntaxException {
        var source = AppBundle.class.getProtectionDomain().getCodeSource();
        if (source == null) return null;
        Path path = Path.of(source.getLocation().toURI());
        return Files.isRegularFile(path) ? path : null;
    }
}
