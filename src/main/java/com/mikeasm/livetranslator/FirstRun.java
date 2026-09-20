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

        // Связка ключей общая для системы. Если ключ туда уже положила другая
        // копия приложения, спрашивать его заново незачем — достаточно
        // записать у себя, откуда его брать.
        if (!haveKey && Keychain.exists()) {
            Settings.save("YC_API_KEY_CMD", Keychain.READ_COMMAND);
            System.out.println("Ключ найден в связке ключей.");
            haveKey = true;
        }

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
        // Высоту не задаём: при фиксированной второе поле обрезалось, и ключ
        // уходил в поле каталога — ровно эта путаница и случалась.
        panel.setPreferredSize(null);

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
            String complaint = checkFolder(folder);
            if (complaint != null) {
                JOptionPane.showMessageDialog(null, complaint);
                return false;
            }
            Settings.save("YC_FOLDER_ID", folder);
        }
        if (!haveKey) {
            char[] key = keyField.getPassword();
            try {
                String complaint = checkKey(key);
                if (complaint != null) {
                    JOptionPane.showMessageDialog(null, complaint);
                    return false;
                }
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

    /**
     * Проверяет, что в поле каталога не оказался ключ.
     * <p>
     * У обоих значений узнаваемая форма: идентификатор каталога начинается с
     * {@code b1}, ключ — с {@code AQVN}. Перепутать поля легко, а последствия
     * невнятные: сервер отвечает про несовпадение каталога, и разбираться
     * приходится по коду ошибки.
     *
     * @return жалоба для показа пользователю или null, если всё в порядке
     */
    /**
     * Спрашивает ключ Gemini, если его ещё нет.
     * <p>
     * Окно, а не сообщение в терминале: приложение запускают двойным щелчком из
     * «Программ», и человек, которому оно предназначено, консоли не увидит
     * вовсе — для него программа просто не откроется.
     *
     * @return ложь, если ключа так и нет
     */
    public static boolean ensureGeminiKey(boolean graphical) {
        if (!Settings.get("LT_GEMINI_KEY").isBlank()) return true;
        if (Keychain.exists(Keychain.GEMINI_SERVICE)) {
            // Ключ уже в связке от прошлой установки — достаточно записать,
            // как его оттуда брать.
            Settings.save("LT_GEMINI_KEY_CMD", Keychain.readCommand(Keychain.GEMINI_SERVICE));
            return true;
        }
        if (!graphical || java.awt.GraphicsEnvironment.isHeadless()) return false;

        JPasswordField field = new JPasswordField();
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(caption("Ключ Gemini"));
        panel.add(field);
        panel.add(Box.createVerticalStrut(10));
        JLabel where = new JLabel("<html><body style='width:360px'>"
                + "Ключ берётся бесплатно на aistudio.google.com/apikey.<br>"
                + "Он сохранится в связке ключей macOS, а не в файле.</body></html>");
        where.setFont(where.getFont().deriveFont(java.awt.Font.PLAIN, 12f));
        panel.add(where);

        // Окно перевода держится поверх всех окон, и диалог без владельца
        // уходит под него: человек видит панель, а спросить ключ будто бы никто
        // не спросил. Поэтому диалог тоже поверх всех и сам выходит на передний
        // план.
        JOptionPane pane = new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE,
                JOptionPane.OK_CANCEL_OPTION);
        javax.swing.JDialog dialog = pane.createDialog("Live Translator — ключ Gemini");
        dialog.setAlwaysOnTop(true);
        dialog.toFront();
        dialog.requestFocus();
        dialog.setVisible(true);
        dialog.dispose();
        Object choice = pane.getValue();
        if (!(choice instanceof Integer answer) || answer != JOptionPane.OK_OPTION) {
            return false;
        }

        char[] key = field.getPassword();
        try {
            String complaint = checkGeminiKey(key);
            if (complaint != null) {
                JOptionPane.showMessageDialog(null, complaint);
                return ensureGeminiKey(true);
            }
            return saveGeminiKey(key);
        } finally {
            java.util.Arrays.fill(key, ' ');
        }
    }

    /** Кладёт ключ Gemini в связку и запоминает, как его оттуда читать. */
    public static boolean saveGeminiKey(char[] key) {
        try {
            Keychain.store(Keychain.GEMINI_SERVICE, key);
            Settings.save("LT_GEMINI_KEY_CMD", Keychain.readCommand(Keychain.GEMINI_SERVICE));
            // Ключ открытым текстом больше не нужен: команда его вытеснит,
            // но значение из файла главнее, поэтому строку надо убрать.
            Settings.save("LT_GEMINI_KEY", "");
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("Не удалось сохранить ключ: " + e.getMessage());
            return false;
        }
    }

    /**
     * Проверяет ключ на очевидные ошибки. Перепутать ключи легко: оба длинные,
     * оба набираются вслепую в поле с точками.
     */
    public static String checkGeminiKey(char[] key) {
        String value = new String(key).trim();
        if (value.isEmpty()) return "Ключ пустой.";
        if (value.contains(" ") || value.contains("\n")) {
            return "В ключе пробелы — похоже, скопировалось лишнее.";
        }
        if (value.startsWith("AQVN") || value.startsWith("b1g")) {
            return "Это похоже на ключ Yandex, а нужен ключ Gemini.\n"
                    + "Ключ Gemini берётся на aistudio.google.com/apikey.";
        }
        if (value.length() < 20) return "Ключ короче, чем бывает у Gemini.";
        return null;
    }

    public static String checkFolder(String folder) {
        if (folder.isEmpty()) return "Каталог не указан.";
        if (folder.startsWith("AQVN")) {
            return "Похоже, это API-ключ, а не каталог.\n"
                    + "Каталог начинается с b1 и виден в консоли Yandex Cloud\n"
                    + "в адресе: console.yandex.cloud/folders/<каталог>";
        }
        if (!folder.startsWith("b1")) {
            return "Идентификатор каталога должен начинаться с b1.\nВы указали: " + folder;
        }
        return null;
    }

    /** Симметричная проверка: в поле ключа не должен оказаться каталог. */
    public static String checkKey(char[] key) {
        if (key == null || key.length == 0) return "Ключ не указан.";
        String text = new String(key);
        if (text.startsWith("b1") && text.length() <= 24) {
            return "Похоже, это идентификатор каталога, а не ключ.\n"
                    + "API-ключ длиннее и начинается с AQVN.";
        }
        return null;
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
            String complaint = checkFolder(folder);
            if (complaint != null) {
                System.err.println(complaint);
                return false;
            }
            Settings.save("YC_FOLDER_ID", folder);
        }
        if (!haveKey) {
            // readPassword не отображает вводимое на экране.
            char[] key = console.readPassword("API-ключ (ввод скрыт): ");
            try {
                String complaint = checkKey(key);
                if (complaint != null) {
                    System.err.println(complaint);
                    return false;
                }
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
