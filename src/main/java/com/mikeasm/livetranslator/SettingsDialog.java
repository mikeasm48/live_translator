package com.mikeasm.livetranslator;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
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

    /** @param onLanguagesChanged вызывается, когда набор языков сохранён */
    public static void show(Frame owner, Config config, Runnable onLanguagesChanged) {
        JDialog dialog = new JDialog(owner, "Настройки", true);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Языки", languagesTab(config, onLanguagesChanged));
        tabs.addTab("Доступ", accessTab(config));
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

        panel.add(note("Отметьте языки, которые звучат на встрече. Язык определяется "
                + "для каждой фразы отдельно. Лишние языки в списке ухудшают определение, "
                + "поэтому отмечайте только те, что действительно звучат."));
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

    private static JPanel accessTab(Config config) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));

        panel.add(note("Ключ хранится в связке ключей macOS — в файле настроек "
                + "остаётся только команда его чтения. Источник текущего ключа: "
                + config.credentialOrigin() + "."));
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
                Settings.save("YC_FOLDER_ID", folder);
            }
            char[] key = keyField.getPassword();
            try {
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

    private static JLabel note(String text) {
        JLabel label = new JLabel("<html><body style='width:540px'>" + text + "</body></html>");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
        label.setAlignmentX(0);
        return label;
    }
}
