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
            Path plistFile = bundle.resolve("Contents/Info.plist");
            String script = launcherScript(jar);
            String plist = plist(version);

            // Переписываем при любом расхождении: поменяться могли и пути после
            // обновления, и сам состав Info.plist в новой версии приложения.
            if (Files.exists(launcher) && Files.exists(plistFile)
                    && script.equals(Files.readString(launcher))
                    && plist.equals(Files.readString(plistFile))) {
                return;
            }

            create(bundle, jar, plist, script);
            System.out.println("Приложение добавлено в «Программы»: " + bundle);
        } catch (IOException | URISyntaxException e) {
            // Значок — удобство, а не условие работы: молча продолжаем.
            System.err.println("Не удалось создать значок приложения: " + e.getMessage());
        }
    }

    private static void create(Path bundle, Path jar, String plist, String script)
            throws IOException {
        Files.createDirectories(bundle.resolve("Contents/MacOS"));
        Files.createDirectories(bundle.resolve("Contents/Resources"));

        try (InputStream icon = AppBundle.class.getResourceAsStream("/live-translator.icns")) {
            if (icon != null) {
                Files.copy(icon, bundle.resolve("Contents/Resources/live-translator.icns"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }

        Files.writeString(bundle.resolve("Contents/Info.plist"), plist, StandardCharsets.UTF_8);

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
        Path java = stablePath(Path.of(System.getProperty("java.home"), "bin", "java"));
        jar = stablePath(jar);
        Path icon = Path.of(System.getProperty("user.home"), "Applications",
                NAME + ".app/Contents/Resources/live-translator.icns");
        return """
                #!/bin/bash
                # Создано приложением при первом запуске. Имя и иконка в Dock
                # задаются явно, иначе система покажет процесс как «java».
                #
                # Java запускается дочерним процессом, а не через exec, и это
                # не мелочь. С exec процессом значка становится сам java, и
                # система, спрашивая разрешение управлять «Почтой», показывает
                # человеку «java хочет управлять Почтой» — от постороннего
                # имени, которого он не знает, такое разрешение дают неохотно.
                # Оставаясь родителем, скрипт держит на себе опознание, а его
                # опознают по значку, из которого он запущен.
                "%s" \\
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
                  <!-- Исполняемый файл значка — скрипт, а не бинарник, и
                       архитектуру из него система прочитать не может: без этих
                       двух ключей она считает приложение Intel-овым и просит
                       поставить Rosetta. -->
                  <key>LSRequiresNativeExecution</key><true/>
                  <key>LSArchitecturePriority</key>
                  <array>
                    <string>arm64</string>
                    <string>x86_64</string>
                  </array>
                  <key>NSMicrophoneUsageDescription</key>
                  <string>Приложение слушает микрофон или виртуальный аудиокабель, чтобы переводить речь.</string>
                </dict>
                </plist>
                """.formatted(NAME, NAME, version, version);
    }

    /**
     * Переводит путь внутрь Homebrew на устойчивый вид.
     * <p>
     * Каталог {@code Cellar} содержит номер версии и исчезает при обновлении,
     * поэтому записанный в значок путь после {@code brew upgrade} указывал бы
     * в никуда. Симлинк {@code opt} ведёт на текущую версию всегда.
     */
    public static Path stablePath(Path path) {
        String text = path.toString();
        var matcher = java.util.regex.Pattern
                .compile("^(.*)/Cellar/([^/]+)/[^/]+/(.*)$")
                .matcher(text);
        if (!matcher.matches()) return path;

        Path stable = Path.of(matcher.group(1), "opt", matcher.group(2), matcher.group(3));
        return Files.exists(stable) ? stable : path;
    }

    /** Путь к jar, из которого запущено приложение. */
    private static Path currentJar() throws URISyntaxException {
        var source = AppBundle.class.getProtectionDomain().getCodeSource();
        if (source == null) return null;
        Path path = Path.of(source.getLocation().toURI());
        return Files.isRegularFile(path) ? path : null;
    }
}
