package com.mikeasm.livetranslator;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
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

    /**
     * Вкладка умеет применить свои изменения.
     * <p>
     * Кнопки живут в окне, а не внутри вкладок: иначе на каждой оказывается
     * свой «Сохранить», а привычных «ОК» и «Закрыть» нет вовсе.
     *
     * @return ложь, если значения неверны и окно закрывать нельзя
     */
    private interface Applier {
        boolean apply();
    }

    private SettingsDialog() {}

    /** @param onRecognitionChanged перезапускает распознавание с новыми настройками */
    public static void show(Frame owner, Config config, Runnable onRecognitionChanged) {
        JDialog dialog = new JDialog(owner, "Настройки", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        List<Applier> appliers = new ArrayList<>();
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Языки", languagesTab(config, onRecognitionChanged, appliers));
        tabs.addTab("Качество", tuningTab(config, onRecognitionChanged, appliers));
        tabs.addTab("Доступ", accessTab(config, appliers));
        tabs.addTab("Файлы", filesTab(appliers));
        tabs.addTab("Звук из созвона", blackHoleTab());
        tabs.addTab("О программе", aboutTab(config, appliers));

        JPanel root = new JPanel(new BorderLayout());
        root.add(tabs, BorderLayout.CENTER);
        root.add(buttons(dialog, appliers), BorderLayout.SOUTH);

        dialog.setContentPane(root);
        // Escape закрывает окно — привычка сильнее любой кнопки.
        root.registerKeyboardAction(e -> dialog.dispose(),
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JPanel.WHEN_IN_FOCUSED_WINDOW);

        dialog.setSize(640, 520);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private static JPanel buttons(JDialog dialog, List<Applier> appliers) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(BorderFactory.createEmptyBorder(10, 14, 12, 14));

        JButton close = new JButton("Закрыть");
        close.addActionListener(e -> dialog.dispose());

        JButton apply = new JButton("Применить");
        apply.addActionListener(e -> applyAll(appliers));

        JButton ok = new JButton("ОК");
        ok.addActionListener(e -> {
            if (applyAll(appliers)) dialog.dispose();
        });
        dialog.getRootPane().setDefaultButton(ok);

        row.add(Box.createHorizontalGlue());
        row.add(close);
        row.add(Box.createHorizontalStrut(8));
        row.add(apply);
        row.add(Box.createHorizontalStrut(8));
        row.add(ok);
        return row;
    }

    /** Применяет все вкладки; несохранённое на соседней не должно пропасть. */
    private static boolean applyAll(List<Applier> appliers) {
        for (Applier applier : appliers) {
            if (!applier.apply()) return false;
        }
        return true;
    }

    private static JPanel languagesTab(Config config, Runnable onChanged,
                                       List<Applier> appliers) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));

        // Gemini определяет язык сам, и списка от нас не ждёт. Подсказывать
        // пробовали — замер на живой записи улучшения не показал, скорее
        // наоборот. Поэтому в этом режиме выбирать нечего, и показывать список
        // значит предлагать крутить то, что ни на что не влияет.
        boolean picksLanguages = !config.usesGemini();
        panel.add(picksLanguages
                ? note("Отметьте языки, которые звучат на встрече — язык определяется",
                        "для каждой фразы отдельно. Лишние языки ухудшают определение,",
                        "поэтому отмечайте только те, что действительно звучат.")
                : note("Gemini определяет язык сам, перечислять их не нужно.",
                        "Задать нужно только язык, на который переводить."));
        panel.add(Box.createVerticalStrut(12));

        List<JCheckBox> boxes = new ArrayList<>();
        if (picksLanguages) {
            for (Map.Entry<String, String> entry : LANGUAGES.entrySet()) {
                JCheckBox box = new JCheckBox(entry.getValue() + "  (" + entry.getKey() + ")");
                box.setSelected(config.sourceLangs().contains(entry.getKey()));
                box.setAlignmentX(0);
                box.putClientProperty("code", entry.getKey());
                boxes.add(box);
                panel.add(box);
            }
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

        appliers.add(() -> {
            Settings.save("LT_TARGET", targetField.getText().trim());
            if (!picksLanguages) return true;

            List<String> chosen = boxes.stream()
                    .filter(JCheckBox::isSelected)
                    .map(box -> (String) box.getClientProperty("code"))
                    .toList();
            if (chosen.isEmpty()) {
                JOptionPane.showMessageDialog(null, "Нужен хотя бы один язык.");
                return false;
            }
            boolean changed = !chosen.equals(config.sourceLangs());
            config.setSourceLangs(chosen);
            Settings.save("LT_LANGS", String.join(",", chosen));
            if (changed) onChanged.run();
            return true;
        });
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
    private static JPanel tuningTab(Config config, Runnable onRecognitionChanged,
                                    List<Applier> appliers) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

        JComboBox<String> engine = new JComboBox<>(new String[]{ENGINE_GEMINI, ENGINE_YANDEX});
        engine.setSelectedItem(config.usesGemini() ? ENGINE_GEMINI : ENGINE_YANDEX);
        engine.setMaximumSize(new Dimension(220, 26));
        panel.add(row("Чем слушать речь", engine, "применится при следующем запуске"));
        panel.add(Box.createVerticalStrut(12));

        appliers.add(() -> {
            String chosen = ENGINE_GEMINI.equals(engine.getSelectedItem()) ? "gemini" : "yandex";
            if (!chosen.equals(config.engine())) {
                config.setEngine(chosen);
                JOptionPane.showMessageDialog(null,
                        "Движок сменится после перезапуска приложения.");
            }
            return true;
        });

        // Настройки у движков разные, и показывать чужие — значит предлагать
        // крутить то, что ни на что не влияет.
        panel.add(config.usesGemini()
                ? geminiTuning(config, appliers)
                : yandexTuning(config, onRecognitionChanged, appliers));
        return panel;
    }

    private static final String ENGINE_GEMINI = "Gemini (Google)";
    private static final String ENGINE_YANDEX = "Yandex SpeechKit";

    /**
     * Настройки Gemini.
     * <p>
     * Длина куска — главный размен. Отставание перевода складывается из длины
     * куска и примерно четырёх секунд обработки, но кусок закрывается на первой
     * же паузе после половины срока, поэтому на живой речи с паузами разница
     * между десятью и двадцатью секундами невелика. Зато на докладчике без пауз
     * она решает всё.
     */
    private static JPanel geminiTuning(Config config, List<Applier> appliers) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(0);

        panel.add(note(
                "Кусок звука закрывается на ближайшей паузе, а если пауз нет —",
                "по пределу ниже. Короче куски — быстрее перевод, но дороже:",
                "задание модели отправляется чаще."));
        panel.add(Box.createVerticalStrut(12));

        JComboBox<String> chunk = new JComboBox<>(new String[]{"10 секунд", "20 секунд"});
        chunk.setSelectedItem(config.chunkSeconds() >= 20 ? "20 секунд" : "10 секунд");
        chunk.setMaximumSize(new Dimension(130, 26));

        JComboBox<String> thinking = new JComboBox<>(
                new String[]{"minimal", "low", "medium", "high"});
        thinking.setSelectedItem(config.geminiThinking().isBlank()
                ? "low" : config.geminiThinking());
        thinking.setMaximumSize(new Dimension(130, 26));

        JTextField model = field(config.geminiModel(), 180);
        JTextField vad = field(String.valueOf((int) config.vadThreshold()));
        JCheckBox vadAuto = new JCheckBox("подбирать порог тишины автоматически",
                config.vadAuto());
        vadAuto.setAlignmentX(0);
        vadAuto.addActionListener(e -> vad.setEnabled(!vadAuto.isSelected()));
        vad.setEnabled(!config.vadAuto());

        panel.add(row("Предел длины куска", chunk, "если пауз в речи не случилось"));
        panel.add(row("Размышления модели", thinking, "на расшифровке они только тратят время"));
        panel.add(row("Модель", model, "например gemini-3.5-flash"));
        panel.add(row("Порог тишины", vad, "ниже — в облако не уходит"));
        panel.add(vadAuto);

        appliers.add(() -> {
            try {
                config.setGeminiTuning(
                        "20 секунд".equals(chunk.getSelectedItem()) ? 20 : 10,
                        model.getText().trim(),
                        String.valueOf(thinking.getSelectedItem()));
                config.setVadTuning(Double.parseDouble(vad.getText().trim()),
                        vadAuto.isSelected());
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(null, "Порог тишины должен быть числом.");
                return false;
            }
            return true;
        });

        panel.add(Box.createVerticalStrut(14));
        JButton reset = new JButton("Сбросить к значениям по умолчанию");
        reset.setAlignmentX(0);
        reset.addActionListener(e -> {
            config.resetTuning();
            chunk.setSelectedItem(config.chunkSeconds() >= 20 ? "20 секунд" : "10 секунд");
            thinking.setSelectedItem(config.geminiThinking());
            model.setText(config.geminiModel());
            vad.setText(String.valueOf((int) config.vadThreshold()));
            vadAuto.setSelected(config.vadAuto());
            vad.setEnabled(!config.vadAuto());
        });
        panel.add(reset);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private static JPanel yandexTuning(Config config, Runnable onRecognitionChanged,
                                       List<Applier> appliers) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(0);

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
        JCheckBox vadAuto = new JCheckBox("подбирать порог тишины автоматически", config.vadAuto());
        vadAuto.addActionListener(e -> vad.setEnabled(!vadAuto.isSelected()));
        vad.setEnabled(!config.vadAuto());

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

        for (JCheckBox box : new JCheckBox[]{vadAuto, eouHigh, literature, useLlm}) {
            box.setAlignmentX(0);
            panel.add(box);
        }

        panel.add(Box.createVerticalStrut(14));
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setAlignmentX(0);

        appliers.add(() -> {
            try {
                config.setRecognitionTuning(
                        Integer.parseInt(pause.getText().trim()),
                        eouHigh.isSelected(),
                        literature.isSelected(),
                        Integer.parseInt(maxPhrase.getText().trim()),
                        Double.parseDouble(vad.getText().trim()),
                        vadAuto.isSelected());
                config.setTranslationTuning(
                        Integer.parseInt(mergeWords.getText().trim()),
                        Long.parseLong(mergeQuiet.getText().trim()),
                        useLlm.isSelected(),
                        model.getText().trim());
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(null,
                        "Числовое поле заполнено неверно — проверьте вкладку «Качество».");
                return false;
            }
            onRecognitionChanged.run();
            return true;
        });

        JButton reset = new JButton("Сбросить к значениям по умолчанию");
        reset.addActionListener(e -> {
            config.resetTuning();
            pause.setText(String.valueOf(config.pauseMs()));
            maxPhrase.setText(String.valueOf(config.maxPhraseSeconds()));
            vad.setText(String.valueOf((int) config.vadThreshold()));
            vadAuto.setSelected(config.vadAuto());
            vad.setEnabled(!config.vadAuto());
            mergeWords.setText(String.valueOf(config.mergeWords()));
            mergeQuiet.setText(String.valueOf(config.mergeQuietMs()));
            model.setText(config.llmModel());
            eouHigh.setSelected(config.eouHigh());
            literature.setSelected(config.literature());
            useLlm.setSelected(config.useLlm());
            onRecognitionChanged.run();
        });

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
    private static JPanel row(String caption, JComponent field, String hint) {
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

    /**
     * Версия и обновление.
     * <p>
     * Кнопка здесь нужна не вместо автоматической проверки при запуске, а
     * вместе с ней: человек, которому сказали «обновись», должен найти, где это
     * сделать, не выясняя, что такое терминал.
     */
    private static JPanel aboutTab(Config config, List<Applier> appliers) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));

        panel.add(row("Версия", plain(Main.VERSION), ""));
        panel.add(row("Движок", plain(config.usesGemini()
                ? "Gemini, " + config.geminiModel()
                : "Yandex SpeechKit"), ""));
        panel.add(Box.createVerticalStrut(16));

        JButton check = new JButton("Проверить обновление");
        check.setAlignmentX(0);
        check.addActionListener(e -> {
            check.setEnabled(false);
            check.setText("Проверяю…");
            Main.checkForUpdates(() -> {
                check.setText("Проверить обновление");
                check.setEnabled(true);
            });
        });
        panel.add(check);
        panel.add(Box.createVerticalStrut(14));

        JCheckBox auto = new JCheckBox("проверять обновления при запуске",
                !"false".equalsIgnoreCase(Settings.get("LT_CHECK_UPDATES")));
        auto.setAlignmentX(0);
        panel.add(auto);
        panel.add(Box.createVerticalStrut(12));
        panel.add(note("Обновление ставится через Homebrew — тем же способом,",
                "что и вручную, поэтому установка не разъезжается."));

        appliers.add(() -> {
            Settings.save("LT_CHECK_UPDATES", auto.isSelected() ? "" : "false");
            return true;
        });
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    /** Нередактируемое значение в колонке полей: версия, имя движка. */
    private static JLabel plain(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
        Dimension size = new Dimension(220, 26);
        label.setPreferredSize(size);
        label.setMinimumSize(size);
        label.setMaximumSize(size);
        return label;
    }

    private static JPanel accessTab(Config config, List<Applier> appliers) {
        // Ключи у движков разные, и показывать оба разом значит предлагать
        // заполнить тот, который сейчас ни на что не влияет.
        return config.usesGemini() ? geminiAccessTab(appliers) : yandexAccessTab(config, appliers);
    }

    /**
     * Ключ Gemini.
     * <p>
     * Поле нужно потому, что приложением пользуются не из терминала: его
     * открывают двойным щелчком из «Программ». Инструкция вида «выполните
     * security add-generic-password» для такого человека равносильна тому, что
     * программа не работает.
     */
    private static JPanel geminiAccessTab(List<Applier> appliers) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));

        boolean haveKey = !Settings.get("LT_GEMINI_KEY").isBlank();
        panel.add(note(
                "Ключ хранится в связке ключей macOS — в файле настроек остаётся",
                "только команда его чтения.",
                haveKey ? "Источник текущего ключа: " + Settings.origin("LT_GEMINI_KEY") + "."
                        : "Ключ пока не задан — без него перевод не работает."));
        panel.add(Box.createVerticalStrut(14));

        panel.add(label("Новый ключ Gemini"));
        JPasswordField keyField = new JPasswordField();
        keyField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        keyField.setAlignmentX(0);
        panel.add(keyField);
        panel.add(Box.createVerticalStrut(10));
        panel.add(note("Ключ берётся бесплатно на aistudio.google.com/apikey.",
                "Он применится при следующем запуске приложения."));
        panel.add(Box.createVerticalStrut(16));

        appliers.add(() -> {
            char[] key = keyField.getPassword();
            try {
                if (key.length == 0) return true;
                String complaint = FirstRun.checkGeminiKey(key);
                if (complaint != null) {
                    JOptionPane.showMessageDialog(null, complaint);
                    return false;
                }
                if (!FirstRun.saveGeminiKey(key)) {
                    JOptionPane.showMessageDialog(null, "Ключ сохранить не удалось.");
                    return false;
                }
            } finally {
                Arrays.fill(key, ' ');
            }
            keyField.setText("");
            JOptionPane.showMessageDialog(null,
                    "Ключ сохранён. Он подхватится при следующем запуске.");
            return true;
        });
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private static JPanel yandexAccessTab(Config config, List<Applier> appliers) {
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

        appliers.add(() -> {
            String folder = folderField.getText().trim();
            if (!folder.isEmpty() && !folder.equals(config.folderId)) {
                String complaint = FirstRun.checkFolder(folder);
                if (complaint != null) {
                    JOptionPane.showMessageDialog(null, complaint);
                    return false;
                }
                Settings.save("YC_FOLDER_ID", folder);
            }
            char[] key = keyField.getPassword();
            try {
                if (key.length == 0) return true;
                String complaint = FirstRun.checkKey(key);
                if (complaint != null) {
                    JOptionPane.showMessageDialog(null, complaint);
                    return false;
                }
                if (!FirstRun.changeKey(key)) {
                    JOptionPane.showMessageDialog(null, "Ключ сохранить не удалось.");
                    return false;
                }
            } finally {
                Arrays.fill(key, ' ');
            }
            keyField.setText("");
            JOptionPane.showMessageDialog(null,
                    "Ключ сохранён. Он подхватится при следующем запуске.");
            return true;
        });
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    /** Показывает, где лежат файлы, и позволяет переназначить папку записей. */
    private static JPanel filesTab(List<Applier> appliers) {
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

        appliers.add(() -> {
            java.nio.file.Path dir = AppPaths.expand(dirField.getText());
            if (dir.equals(AppPaths.logsDir())) return true;
            try {
                java.nio.file.Files.createDirectories(dir);
            } catch (java.io.IOException ex) {
                JOptionPane.showMessageDialog(null,
                        "Не удалось создать папку: " + ex.getMessage());
                return false;
            }
            // Пустое значение возвращает путь по умолчанию, а не пишет его в
            // файл: так настройка переживёт переезд домашнего каталога.
            Settings.save("LT_LOGS_DIR",
                    dir.equals(AppPaths.defaultLogsDir()) ? "" : dir.toString());
            return true;
        });
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
