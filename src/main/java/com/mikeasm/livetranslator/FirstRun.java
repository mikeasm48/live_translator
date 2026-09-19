package com.mikeasm.livetranslator;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import java.awt.Dimension;
import java.awt.Font;
import java.io.Console;
import java.io.IOException;
import java.util.Arrays;

/**
 * Первый запуск: спрашивает то, без чего приложение не работает, и запоминает.
 * <p>
 * Ключ доступа сохраняется в связке ключей macOS, а в {@code .env} попадает
 * только команда его чтения. Идентификатор каталога секретом не является и
 * хранится в файле как есть.
 */
public final class FirstRun {

    private FirstRun() {}

    /** @return true, если всё необходимое теперь настроено */
    public static boolean ensureConfigured(boolean graphical) {
        boolean haveKey = Settings.has("YC_API_KEY")
                || Settings.has("YC_IAM_TOKEN")
                || Settings.has("YC_API_KEY_CMD");
        boolean haveFolder = Settings.has("YC_FOLDER_ID");
        if (haveKey && haveFolder) return true;

        if (graphical && !java.awt.GraphicsEnvironment.isHeadless()) {
            return askGraphically(haveKey, haveFolder);
        }
        return askInConsole(haveKey, haveFolder);
    }

    private static boolean askGraphically(boolean haveKey, boolean haveFolder) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setPreferredSize(new Dimension(460, haveKey || haveFolder ? 190 : 240));

        JLabel intro = new JLabel("<html><body style='width:430px'>"
                + "Для работы нужны доступы Yandex AI Studio. Ключ будет сохранён "
                + "в связке ключей macOS, в файле настроек останется только "
                + "команда его чтения.</body></html>");
        intro.setAlignmentX(0);
        panel.add(intro);
        panel.add(Box.createVerticalStrut(14));

        JTextField folderField = new JTextField();
        if (!haveFolder) {
            panel.add(caption("Идентификатор каталога (b1g…)"));
            folderField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
            folderField.setAlignmentX(0);
            panel.add(folderField);
            panel.add(Box.createVerticalStrut(12));
        }

        JPasswordField keyField = new JPasswordField();
        if (!haveKey) {
            panel.add(caption("API-ключ сервисного аккаунта (AQVN…)"));
            keyField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
            keyField.setAlignmentX(0);
            panel.add(keyField);
        }

        int answer = JOptionPane.showConfirmDialog(null, panel, "Live Translator — настройка",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return false;

        if (!haveFolder) {
            String folder = folderField.getText().trim();
            if (folder.isEmpty()) {
                JOptionPane.showMessageDialog(null, "Каталог не указан.");
                return false;
            }
            Settings.save("YC_FOLDER_ID", folder);
        }
        if (!haveKey) {
            char[] key = keyField.getPassword();
            try {
                if (!saveKey(key)) {
                    JOptionPane.showMessageDialog(null, "Ключ не сохранён.");
                    return false;
                }
            } finally {
                Arrays.fill(key, '\0');
            }
        }
        return true;
    }

    private static JLabel caption(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
        label.setAlignmentX(0);
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        return label;
    }

    private static boolean askInConsole(boolean haveKey, boolean haveFolder) {
        Console console = System.console();
        if (console == null) {
            System.err.println("Не настроены доступы, а запросить их в этом режиме нельзя.");
            System.err.println("Заполните .env вручную — см. .env.example.");
            return false;
        }
        System.out.println("Первая настройка Live Translator.");
        if (!haveFolder) {
            String folder = console.readLine("Идентификатор каталога (b1g…): ").trim();
            if (folder.isEmpty()) return false;
            Settings.save("YC_FOLDER_ID", folder);
        }
        if (!haveKey) {
            // readPassword не отображает вводимое на экране.
            char[] key = console.readPassword("API-ключ (ввод скрыт): ");
            try {
                return saveKey(key);
            } finally {
                Arrays.fill(key, '\0');
            }
        }
        return true;
    }

    /** Кладёт ключ в связку ключей, а в .env — команду чтения. */
    private static boolean saveKey(char[] key) {
        if (key == null || key.length == 0) return false;
        if (!Keychain.available()) {
            System.err.println("Связка ключей доступна только в macOS. "
                    + "Пропишите YC_API_KEY в .env вручную.");
            return false;
        }
        try {
            Keychain.store(key);
            Settings.save("YC_API_KEY_CMD", Keychain.READ_COMMAND);
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("Не удалось сохранить ключ: " + e.getMessage());
            return false;
        }
    }

    /** Смена ключа из окна настроек. */
    public static boolean changeKey(char[] key) {
        return saveKey(key);
    }
}
