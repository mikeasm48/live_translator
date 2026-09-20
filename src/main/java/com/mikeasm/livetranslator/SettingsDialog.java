package com.mikeasm.livetranslator;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Окно настроек (Cmd + ,): языки, доступы и инструкция по BlackHole.
 * <p>
 * Всё, что здесь меняется, сразу пишется в {@code .env}, чтобы при следующем
 * запуске не настраивать заново.
 */
public final class SettingsDialog {

    /** Языки, которые имеет смысл предлагать в списке. */
    private static final Map<String, String> LANGUAGES = new LinkedHashMap<>();

    static {
        LANGUAGES.put("uz-UZ", "узбекский");
        LANGUAGES.put("ru-RU", "русский");
        LANGUAGES.put("en-US", "английский");
        LANGUAGES.put("kk-KZ", "казахский");
        LANGUAGES.put("tr-TR", "турецкий");
        LANGUAGES.put("de-DE", "немецкий");
    }

    private SettingsDialog() {}

    /** @param onRecognitionChanged перезапускает распознавание с новыми настройками */
    public static void show(Frame owner, Config config, Runnable onRecognitionChanged) {
        JDialog dialog = new JDialog(owner, "Настройки", true);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Языки", languagesTab(config, onRecognitionChanged));
        tabs.addTab("Качество", tuningTab(config, onRecognitionChanged));
        tabs.addTab("Доступ", accessTab(config));
        tabs.addTab("Файлы", filesTab());
        tabs.addTab("Звук из созвона", blackHoleTab());

        dialog.setContentPane(tabs);
        dialog.setSize(620, 460);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private static JPanel languagesTab(Config config, Runnable onChanged) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));

        panel.add(note(
                "Отметьте языки, которые звучат на встрече — язык определяется",
                "для каждой фразы отдельно. Лишние языки ухудшают определение,",
                "поэтому отмечайте только те, что действительно звучат."));
        panel.add(Box.createVerticalStrut(12));

        List<JCheckBox> boxes = new ArrayList<>();
        for (Map.Entry<String, String> entry : LANGUAGES.entrySet()) {
            JCheckBox box = new JCheckBox(entry.getValue() + "  (" + entry.getKey() + ")");
            box.setSelected(config.sourceLangs().contains(entry.getKey()));
            box.setAlignmentX(0);
            box.putClientProperty("code", entry.getKey());
            boxes.add(box);
            panel.add(box);
        }

        panel.add(Box.createVerticalStrut(14));
        JTextField targetField = new JTextField(config.targetLang, 6);
        targetField.setMaximumSize(new Dimension(90, 28));
        JPanel targetRow = new JPanel();
        targetRow.setLayout(new BoxLayout(targetRow, BoxLayout.X_AXIS));
        targetRow.setAlignmentX(0);
        targetRow.add(new JLabel("Переводить на:  "));
        targetRow.add(targetField);
        targetRow.add(Box.createHorizontalGlue());
        panel.add(targetRow);

        panel.add(Box.createVerticalStrut(16));
        JButton save = new JButton("Сохранить");
        save.setAlignmentX(0);
        save.addActionListener(e -> {
            List<String> chosen = boxes.stream()
                    .filter(JCheckBox::isSelected)
                    .map(box -> (String) box.getClientProperty("code"))
                    .toList();
            if (chosen.isEmpty()) {
                JOptionPane.showMessageDialog(null, "Нужен хотя бы один язык.");
                return;
            }
            config.setSourceLangs(chosen);
            Settings.save("LT_LANGS", String.join(",", chosen));
            Settings.save("LT_TARGET", targetField.getText().trim());
            onChanged.run();
            JOptionPane.showMessageDialog(null,
                    "Сохранено: " + String.join(", ", chosen)
                    + "\nЯзык перевода применится после перезапуска.");
        });
        panel.add(save);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    /**
     * Параметры нарезки речи и перевода.
     * <p>
     * Все они — размен между скоростью и качеством, и верных значений «вообще»
     * не существует: они зависят от того, как говорит конкретная команда.
     * Поэтому крутятся на ходу, без перезапуска: изменение настроек
     * распознавания пересоздаёт поток, остальное подхватывается само.
     */
    private static JPanel tuningTab(Config config, Runnable onRecognitionChanged) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

        panel.add(note(
                "Меньше задержка — короче куски и хуже перевод; больше — наоборот.",
                "Значения применяются сразу, перезапуск не нужен."));
        panel.add(Box.createVerticalStrut(12));

        JTextField pause = field(String.valueOf(config.pauseMs()));
        JTextField maxPhrase = field(String.valueOf(config.maxPhraseSeconds()));
        JTextField vad = field(String.valueOf((int) config.vadThreshold()));
        JTextField mergeWords = field(String.valueOf(config.mergeWords()));
        JTextField mergeQuiet = field(String.valueOf(config.mergeQuietMs()));
        JTextField model = field(config.llmModel(), 180);
        JCheckBox eouHigh = new JCheckBox("чаще резать фразы", config.eouHigh());
        JCheckBox literature = new JCheckBox("расставлять знаки препинания", config.literature());
        JCheckBox useLlm = new JCheckBox("переводить языковой моделью", config.useLlm());

        panel.add(row("Пауза между словами, мс", pause,
                "после неё фраза считается законченной"));
        panel.add(row("Предел длины фразы, с", maxPhrase,
                "дольше — закрываем принудительно"));
        panel.add(row("Порог тишины", vad,
                "ниже — в облако не уходит"));
        panel.add(row("Копить слов перед переводом", mergeWords,
                "обрывки переводятся плохо"));
        panel.add(row("Ждать продолжения, мс", mergeQuiet,
                "главный вклад в задержку"));
        panel.add(row("Модель перевода", model, "например yandexgpt/latest"));

        for (JCheckBox box : new JCheckBox[]{eouHigh, literature, useLlm}) {
            box.setAlignmentX(0);
            panel.add(box);
        }

        panel.add(Box.createVerticalStrut(14));
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setAlignmentX(0);

        JButton save = new JButton("Применить");
        save.addActionListener(e -> {
            try {
                config.setRecognitionTuning(
                        Integer.parseInt(pause.getText().trim()),
                        eouHigh.isSelected(),
                        literature.isSelected(),
                        Integer.parseInt(maxPhrase.getText().trim()),
                        Double.parseDouble(vad.getText().trim()));
                config.setTranslationTuning(
                        Integer.parseInt(mergeWords.getText().trim()),
                        Long.parseLong(mergeQuiet.getText().trim()),
                        useLlm.isSelected(),
                        model.getText().trim());
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(null, "Числовое поле заполнено неверно.");
                return;
            }
            onRecognitionChanged.run();
            JOptionPane.showMessageDialog(null, "Применено.");
        });

        JButton reset = new JButton("Сбросить к значениям по умолчанию");
        reset.addActionListener(e -> {
            config.resetTuning();
            pause.setText(String.valueOf(config.pauseMs()));
            maxPhrase.setText(String.valueOf(config.maxPhraseSeconds()));
            vad.setText(String.valueOf((int) config.vadThreshold()));
            mergeWords.setText(String.valueOf(config.mergeWords()));
            mergeQuiet.setText(String.valueOf(config.mergeQuietMs()));
            model.setText(config.llmModel());
            eouHigh.setSelected(config.eouHigh());
            literature.setSelected(config.literature());
            useLlm.setSelected(config.useLlm());
            onRecognitionChanged.run();
            JOptionPane.showMessageDialog(null, "Настройки возвращены к исходным.");
        });

        buttons.add(save);
        buttons.add(Box.createHorizontalStrut(10));
        buttons.add(reset);
        buttons.add(Box.createHorizontalGlue());
        panel.add(buttons);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private static JTextField field(String value) {
        return field(value, 90);
    }

    /** Ширина задаётся жёстко: иначе BoxLayout выравнивает поля по содержимому
     *  и колонка разъезжается, а длинное значение обрезается. */
    private static JTextField field(String value, int width) {
        JTextField field = new JTextField(value);
        Dimension size = new Dimension(width, 26);
        field.setPreferredSize(size);
        field.setMinimumSize(size);
        field.setMaximumSize(size);
        return field;
    }

    /** Строка «подпись — поле — пояснение» одинаковой высоты. */
    private static JPanel row(String caption, JTextField field, String hint) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(0);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        JLabel name = new JLabel(caption);
        name.setFont(name.getFont().deriveFont(Font.PLAIN, 12f));
        // Минимум задаётся наравне с остальными размерами: без него подпись
        // сжимается, когда строка не помещается, и колонка полей разъезжается.
        Dimension captionSize = new Dimension(230, 24);
        name.setPreferredSize(captionSize);
        name.setMinimumSize(captionSize);
        name.setMaximumSize(captionSize);

        JLabel note = new JLabel("  " + hint);
        note.setFont(note.getFont().deriveFont(Font.PLAIN, 11f));
        note.setForeground(new java.awt.Color(0x6B, 0x70, 0x7B));

        row.add(name);
        row.add(field);
        row.add(note);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private static JPanel accessTab(Config config) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));

        panel.add(note(
                "Ключ хранится в связке ключей macOS — в файле настроек остаётся",
                "только команда его чтения.",
                "Источник текущего ключа: " + config.credentialOrigin() + "."));
        panel.add(Box.createVerticalStrut(14));

        panel.add(label("Новый API-ключ (AQVN…)"));
        JPasswordField keyField = new JPasswordField();
        keyField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        keyField.setAlignmentX(0);
        panel.add(keyField);
        panel.add(Box.createVerticalStrut(12));

        panel.add(label("Идентификатор каталога"));
        JTextField folderField = new JTextField(config.folderId);
        folderField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        folderField.setAlignmentX(0);
        panel.add(folderField);
        panel.add(Box.createVerticalStrut(16));

        JButton save = new JButton("Сохранить");
        save.setAlignmentX(0);
        save.addActionListener(e -> {
            String folder = folderField.getText().trim();
            if (!folder.isEmpty() && !folder.equals(config.folderId)) {
                String complaint = FirstRun.checkFolder(folder);
                if (complaint != null) {
                    JOptionPane.showMessageDialog(null, complaint);
                    return;
                }
                Settings.save("YC_FOLDER_ID", folder);
            }
            char[] key = keyField.getPassword();
            try {
                if (key.length > 0) {
                    String complaint = FirstRun.checkKey(key);
                    if (complaint != null) {
                        JOptionPane.showMessageDialog(null, complaint);
                        return;
                    }
                }
                if (key.length > 0 && !FirstRun.changeKey(key)) {
                    JOptionPane.showMessageDialog(null, "Ключ сохранить не удалось.");
                    return;
                }
            } finally {
                Arrays.fill(key, '\0');
            }
            keyField.setText("");
            JOptionPane.showMessageDialog(null,
                    "Сохранено. Доступы подхватятся при следующем запуске.");
        });
        panel.add(save);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    /** Показывает, где лежат файлы, и позволяет переназначить папку записей. */
    private static JPanel filesTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));

        panel.add(note(
                "Сюда приложение пишет расшифровки встреч и записи звука.",
                "Папка создаётся сама, если её ещё нет."));
        panel.add(Box.createVerticalStrut(12));

        JTextField dirField = new JTextField(AppPaths.logsDir().toString());
        dirField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        dirField.setAlignmentX(0);
        panel.add(label("Расшифровки и записи"));
        panel.add(dirField);
        panel.add(Box.createVerticalStrut(8));

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setAlignmentX(0);

        JButton choose = new JButton("Выбрать…");
        choose.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(dirField.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Папка для расшифровок и записей");
            if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
                dirField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        JButton open = new JButton("Открыть в Finder");
        open.addActionListener(e -> reveal(AppPaths.expand(dirField.getText())));

        JButton reset = new JButton("По умолчанию");
        reset.addActionListener(e -> dirField.setText(AppPaths.defaultLogsDir().toString()));

        buttons.add(choose);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(open);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(reset);
        buttons.add(Box.createHorizontalGlue());
        panel.add(buttons);

        panel.add(Box.createVerticalStrut(18));
        panel.add(label("Настройки и словарь терминов"));
        JTextField configField = new JTextField(AppPaths.configDir().toString());
        configField.setEditable(false);
        configField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        configField.setAlignmentX(0);
        panel.add(configField);
        panel.add(Box.createVerticalStrut(8));
        JButton openConfig = new JButton("Открыть в Finder");
        openConfig.setAlignmentX(0);
        openConfig.addActionListener(e -> reveal(AppPaths.configDir()));
        panel.add(openConfig);

        panel.add(Box.createVerticalStrut(18));
        JButton save = new JButton("Сохранить");
        save.setAlignmentX(0);
        save.addActionListener(e -> {
            java.nio.file.Path dir = AppPaths.expand(dirField.getText());
            try {
                java.nio.file.Files.createDirectories(dir);
            } catch (java.io.IOException ex) {
                JOptionPane.showMessageDialog(null, "Не удалось создать папку:\n" + ex.getMessage());
                return;
            }
            // Пустое значение возвращает путь по умолчанию, а не пишет его в файл:
            // так настройка переживёт переезд домашнего каталога.
            if (dir.equals(AppPaths.defaultLogsDir())) {
                Settings.save("LT_LOGS_DIR", "");
            } else {
                Settings.save("LT_LOGS_DIR", dir.toString());
            }
            JOptionPane.showMessageDialog(null,
                    "Сохранено.\nЗаписи звука пойдут туда сразу, расшифровка — "
                            + "со следующего запуска.");
        });
        panel.add(save);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private static void reveal(java.nio.file.Path path) {
        try {
            java.nio.file.Files.createDirectories(path);
            new ProcessBuilder("open", path.toString()).start();
        } catch (java.io.IOException ex) {
            JOptionPane.showMessageDialog(null, "Не удалось открыть папку: " + ex.getMessage());
        }
    }

    private static JScrollPane blackHoleTab() {
        JEditorPane pane = new JEditorPane("text/html", """
                <html><body style="font-family:-apple-system,Helvetica;font-size:12px;
                margin:14px;line-height:1.5">
                <p><b>Зачем это нужно.</b> Микрофон слышит только вас и комнату.
                Чтобы приложение слышало собеседников в Zoom, Meet или видео в браузере,
                нужен виртуальный аудиокабель — BlackHole.</p>

                <p><b>1. Установить</b> (в Терминале):<br>
                <code>brew install blackhole-2ch</code><br>
                После установки нужно перезагрузить Mac либо выйти и снова войти в систему.</p>

                <p><b>2. Создать устройство с несколькими выходами.</b><br>
                Откройте «Настройка Audio-MIDI» (Программы → Утилиты).<br>
                Кнопка <b>+</b> слева внизу → «Создать устройство с несколькими выходами».<br>
                Отметьте галочками ваши наушники или динамики <b>и</b> BlackHole 2ch.<br>
                В колонке «Главное устройство» выберите настоящее железо, не BlackHole.<br>
                Поставьте «Коррекция сдвига» напротив BlackHole, но не напротив главного.</p>

                <p><b>3. Направить туда звук.</b><br>
                Системные настройки → Звук → Выход → «Устройство с несколькими выходами».<br>
                Браузер следует за системным выходом; уже играющее видео надо перезапустить.<br>
                У Zoom есть свой выбор динамика — там можно выбрать это устройство,
                не меняя системный вывод.</p>

                <p><b>4. Проверить.</b> В панели приложения выберите источник
                <b>BlackHole 2ch</b> и включите звук: полоска уровня должна позеленеть.
                Если она стоит на месте — звук в кабель не идёт, вернитесь к шагу 3.</p>

                <p><b>Побочный эффект.</b> При выбранном устройстве с несколькими
                выходами перестают работать клавиши громкости: система не знает,
                чью громкость менять. Регулируйте в плеере или в «Настройке Audio-MIDI».</p>
                </body></html>
                """);
        pane.setEditable(false);
        pane.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(pane);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        return scroll;
    }

    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
        label.setAlignmentX(0);
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        return label;
    }

    /**
     * Пояснение над полями. Переносы задаются явно: ограничение ширины через
     * стиль HTML в Swing не срабатывает, и длинная строка уезжает за край.
     */
    private static JLabel note(String... lines) {
        JLabel label = new JLabel("<html>" + String.join("<br>", lines) + "</html>");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
        label.setAlignmentX(0);
        return label;
    }
}
