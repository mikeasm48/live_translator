package com.mikeasm.livetranslator;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Окно «поверх всех окон»: панель управления, лента реплик и текущая гипотеза.
 * <p>
 * Реплики перерисовываются целиком при каждом изменении: фразу может уточнить
 * final_refinement, а перевод приходит позже распознавания, поэтому строку
 * нужно уметь переписать.
 */
public final class OverlayWindow implements TranscriptView {

    private static final int MAX_LINES = 200;

    /**
     * Сколько символов гипотезы показывать. Пока сервер не признал фразу
     * законченной, гипотеза растёт неограниченно — интересен только хвост.
     */
    private static final int MAX_PARTIAL_CHARS = 160;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final Color BACKGROUND = new Color(0x14, 0x16, 0x1A);
    private static final Color PANEL = new Color(0x1C, 0x1F, 0x26);
    private static final Color TEXT = new Color(0xEC, 0xEF, 0xF4);
    private static final Color MUTED = new Color(0x8A, 0x92, 0xA6);
    private static final Color RECORDING = new Color(0xE0, 0x5A, 0x5A);
    private static final Color PAUSED = new Color(0xF5, 0xC4, 0x4E);
    private static final Color BUTTON = new Color(0x2A, 0x2F, 0x3A);
    private static final Color BUTTON_EDGE = new Color(0x3C, 0x43, 0x52);

    /** Что окно умеет попросить у приложения. */
    public interface Control {
        List<String> availableDevices();

        String currentDevice();

        void switchDevice(String device) throws Exception;

        boolean isRecording();

        Path startRecording() throws Exception;

        long stopRecording();

        long recordedSeconds();

        /** Текущий уровень входного сигнала и порог, ниже которого это тишина. */
        int inputLevel();

        double silenceThreshold();

        /** Языки изменили в настройках — распознавание должно их подхватить. */
        void languagesChanged();

        boolean isPaused();

        void setPaused(boolean paused);
    }

    /**
     * Полоска уровня звука.
     * <p>
     * Без неё «микрофон ничего не слышит» и «приложение сломалось» выглядят
     * одинаково — пустым окном. Порог тишины отмечен риской: всё, что левее
     * неё, в облако не уходит.
     */
    private static final class LevelBar extends JPanel {
        private int level;
        private double threshold = 180;

        LevelBar() {
            setBackground(PANEL);
            setPreferredSize(new Dimension(90, 12));
            setMaximumSize(new Dimension(90, 18));
            setToolTipText("Уровень входного сигнала. Риска — порог тишины.");
        }

        void update(int level, double threshold) {
            this.level = level;
            this.threshold = threshold;
            repaint();
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            int w = getWidth();
            int h = getHeight();
            // Логарифмическая шкала: речь и тишина отличаются в сотни раз.
            double filled = Math.min(1.0, Math.log10(1 + level) / Math.log10(8000));
            g.setColor(new Color(0x2A, 0x2E, 0x38));
            g.fillRect(0, h / 2 - 3, w, 6);
            g.setColor(level >= threshold ? new Color(0x6B, 0xCB, 0x77) : MUTED);
            g.fillRect(0, h / 2 - 3, (int) (w * filled), 6);
            int mark = (int) (w * Math.log10(1 + threshold) / Math.log10(8000));
            g.setColor(new Color(0xB0, 0xB6, 0xC4));
            g.fillRect(mark, h / 2 - 6, 1, 12);
        }
    }

    private record Line(long id, String time, String source, String translated, String language) {}

    private final List<Line> lines = new ArrayList<>();
    private final JTextArea area = new JTextArea();
    private final JTextArea partialArea = new JTextArea(" ", 2, 1);
    private final JCheckBox showSource = new JCheckBox("оригинал", false);
    private final Config config;
    private final JButton pauseButton = new JButton();
    private final JButton recordButton = new JButton();
    private final JComboBox<String> deviceBox = new JComboBox<>();
    private final JLabel statusLabel = new JLabel(" ");
    private final LevelBar levelBar = new LevelBar();
    private final JPanel statusLine = new JPanel();
    /** Гасит устаревшее сообщение: оно описывает момент, а не состояние. */
    private Timer statusTimer;
    private final JScrollPane scroll = new JScrollPane();
    private final JFrame frame;
    private final Control control;

    /** Создаёт окно в потоке диспетчеризации событий, как требует Swing. */
    public static OverlayWindow create(Config config, Control control) throws Exception {
        AtomicReference<OverlayWindow> holder = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> holder.set(new OverlayWindow(config, control)));
        return holder.get();
    }

    private OverlayWindow(Config config, Control control) {
        this.control = control;
        this.config = config;
        showSource.setSelected(config.showSource);
        frame = new JFrame("Live Translator — " + config.langsLabel() + " → " + config.targetLang);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setAlwaysOnTop(true);
        frame.setSize(780, 470);
        frame.setLocationByPlatform(true);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BACKGROUND);
        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildTranscript(config), BorderLayout.CENTER);
        root.add(buildPartial(config), BorderLayout.SOUTH);
        root.setPreferredSize(new Dimension(780, 470));

        frame.setJMenuBar(buildMenu());
        frame.setContentPane(root);
        frame.setVisible(true);

        if (control != null) new Timer(250, e -> tick()).start();
    }

    /**
     * Панель управления и строка состояния друг под другом.
     * <p>
     * Раньше сообщения жили внутри панели и отъедали ширину у выбора источника
     * и полоски уровня, а длинные — обрезались на краю окна. Собственная строка
     * появляется только когда есть что сказать.
     */
    private JPanel buildHeader() {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBackground(PANEL);
        header.add(buildToolbar());
        header.add(buildStatusLine());
        return header;
    }

    private JPanel buildStatusLine() {
        statusLabel.setForeground(MUTED);
        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));

        statusLine.setLayout(new BorderLayout());
        statusLine.setBackground(new Color(0x23, 0x27, 0x31));
        statusLine.setBorder(BorderFactory.createEmptyBorder(5, 14, 6, 14));
        statusLine.add(statusLabel, BorderLayout.CENTER);
        statusLine.setVisible(false);
        return statusLine;
    }

    /** Меню нужно ради стандартного Cmd + , — настройки ищут именно там. */
    private javax.swing.JMenuBar buildMenu() {
        javax.swing.JMenuBar menuBar = new javax.swing.JMenuBar();
        javax.swing.JMenu menu = new javax.swing.JMenu("Live Translator");
        javax.swing.JMenuItem settings = new javax.swing.JMenuItem("Настройки…");
        settings.setAccelerator(javax.swing.KeyStroke.getKeyStroke(',',
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        settings.addActionListener(e -> openSettings());
        menu.add(settings);
        menuBar.add(menu);
        return menuBar;
    }

    private void openSettings() {
        SettingsDialog.show(frame, config, () -> {
            if (control != null) control.languagesChanged();
            frame.setTitle("Live Translator — " + config.langsLabel()
                    + " → " + config.targetLang);
            setStatus("языки: " + config.langsLabel());
        });
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBackground(PANEL);
        bar.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        Font small = new Font(Font.SANS_SERIF, Font.PLAIN, 12);

        JLabel deviceCaption = new JLabel("Источник ");
        deviceCaption.setForeground(MUTED);
        deviceCaption.setFont(small);

        deviceBox.setFont(small);
        deviceBox.setMaximumSize(new Dimension(300, 26));
        if (control != null) {
            List<String> devices = control.availableDevices();
            deviceBox.setModel(new DefaultComboBoxModel<>(devices.toArray(new String[0])));
            selectCurrentDevice(devices);
            deviceBox.addActionListener(e -> onDeviceChosen());
        } else {
            deviceBox.setEnabled(false);
        }

        styleButton(pauseButton, small);
        pauseButton.setEnabled(control != null);
        pauseButton.addActionListener(e -> {
            control.setPaused(!control.isPaused());
            updatePauseButton();
            setStatus(control.isPaused() ? "перевод на паузе" : "перевод идёт");
        });
        updatePauseButton();

        styleButton(recordButton, small);
        recordButton.setEnabled(control != null);
        recordButton.addActionListener(e -> onRecordToggled());
        updateRecordButton();

        showSource.setBackground(PANEL);
        showSource.setForeground(MUTED);
        showSource.setFont(small);
        showSource.setFocusPainted(false);
        showSource.addActionListener(e -> {
            Settings.save("LT_SHOW_SOURCE", String.valueOf(showSource.isSelected()));
            render();
        });

        bar.add(pauseButton);
        bar.add(Box.createHorizontalStrut(14));
        bar.add(deviceCaption);
        bar.add(deviceBox);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(levelBar);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(recordButton);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(showSource);
        bar.add(Box.createHorizontalGlue());
        return bar;
    }

    /**
     * Делает кнопку плоской и тёмной под цвет панели.
     * <p>
     * По умолчанию macOS рисует кнопку на светлой подложке, и светлый текст на
     * ней не читается. Простая смена цвета текста не помогает: подложку задаёт
     * оформление системы, поэтому её отключаем и красим сами.
     */
    private static void styleButton(JButton button, Font font) {
        button.setFont(font);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(true);
        button.setBackground(BUTTON);
        button.setForeground(TEXT);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BUTTON_EDGE, 1, true),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)));
    }

    private JScrollPane buildTranscript(Config config) {
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBackground(BACKGROUND);
        area.setForeground(TEXT);
        area.setCaretColor(BACKGROUND);
        area.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, config.fontSize));
        area.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        scroll.setViewportView(area);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(BACKGROUND);
        return scroll;
    }

    private JPanel buildPartial(Config config) {
        partialArea.setEditable(false);
        partialArea.setLineWrap(true);
        partialArea.setWrapStyleWord(true);
        partialArea.setBackground(BACKGROUND);
        partialArea.setForeground(MUTED);
        partialArea.setCaretColor(BACKGROUND);
        partialArea.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, config.fontSize - 3));
        partialArea.setBorder(BorderFactory.createEmptyBorder(6, 14, 10, 14));

        JPanel holder = new JPanel(new BorderLayout());
        holder.setBackground(BACKGROUND);
        holder.add(partialArea, BorderLayout.CENTER);
        // Жёсткий потолок высоты: иначе растущая гипотеза заберёт себе окно.
        holder.setPreferredSize(new Dimension(780, 3 * (config.fontSize + 4)));
        return holder;
    }

    private void selectCurrentDevice(List<String> devices) {
        String current = control.currentDevice();
        for (String name : devices) {
            if (name.toLowerCase().contains(current.toLowerCase())) {
                deviceBox.setSelectedItem(name);
                return;
            }
        }
    }

    private void onDeviceChosen() {
        Object selected = deviceBox.getSelectedItem();
        if (selected == null) return;
        String name = selected.toString();
        deviceBox.setEnabled(false);
        setStatus("переключаю…");
        // Открытие линии может подвиснуть на занятом устройстве — не в EDT.
        Thread.ofVirtual().start(() -> {
            String result;
            try {
                control.switchDevice(name);
                // Запоминаем выбор: при следующем запуске переключать не придётся.
                Settings.save("LT_DEVICE", name);
                result = "источник: " + name;
            } catch (Exception e) {
                result = "не удалось: " + e.getMessage();
            }
            String message = result;
            SwingUtilities.invokeLater(() -> {
                setStatus(message);
                deviceBox.setEnabled(true);
            });
        });
    }

    private void onRecordToggled() {
        try {
            if (control.isRecording()) {
                long seconds = control.stopRecording();
                setStatus("запись сохранена, " + seconds + " с");
            } else {
                Path path = control.startRecording();
                setStatus("пишу в " + path.getFileName());
            }
        } catch (Exception e) {
            setStatus("запись не удалась: " + e.getMessage());
        }
        updateRecordButton();
    }

    private void tick() {
        levelBar.update(control.inputLevel(), control.silenceThreshold());
        updateRecordButton();
        updatePauseButton();
    }

    private void updatePauseButton() {
        if (control == null) return;
        boolean paused = control.isPaused();
        pauseButton.setText(paused ? "▶ продолжить" : "⏸ пауза");
        pauseButton.setForeground(paused ? PAUSED : TEXT);
    }

    private void updateRecordButton() {
        boolean recording = control != null && control.isRecording();
        if (recording) {
            long seconds = control.recordedSeconds();
            recordButton.setText(String.format("● запись %d:%02d", seconds / 60, seconds % 60));
            recordButton.setForeground(RECORDING);
        } else {
            recordButton.setText("○ записать звук");
            recordButton.setForeground(TEXT);
        }
    }

    private void setStatus(String message) {
        if (message == null || message.isBlank()) {
            statusLine.setVisible(false);
            return;
        }
        statusLabel.setText(message);
        statusLine.setVisible(true);
        statusLine.revalidate();

        if (statusTimer != null) statusTimer.stop();
        statusTimer = new Timer(25_000, e -> setStatus(null));
        statusTimer.setRepeats(false);
        statusTimer.start();
    }

    @Override
    public void partial(String text) {
        String tail = text.length() > MAX_PARTIAL_CHARS
                ? "…" + text.substring(text.length() - MAX_PARTIAL_CHARS)
                : text;
        SwingUtilities.invokeLater(() -> partialArea.setText(tail.isBlank() ? " " : tail));
    }

    @Override
    public void phrase(long id, String source, String language) {
        SwingUtilities.invokeLater(() -> {
            int index = indexOf(id);
            if (index >= 0) {
                Line old = lines.get(index);
                lines.set(index, new Line(id, old.time(), source, old.translated(), language));
            } else {
                lines.add(new Line(id, LocalTime.now().format(CLOCK), source, null, language));
                if (lines.size() > MAX_LINES) lines.remove(0);
            }
            partialArea.setText(" ");
            render();
        });
    }

    @Override
    public void translation(long id, String translated) {
        SwingUtilities.invokeLater(() -> {
            int index = indexOf(id);
            if (index < 0) return;
            Line old = lines.get(index);
            lines.set(index, new Line(id, old.time(), old.source(), translated, old.language()));
            render();
        });
    }

    @Override
    public void status(String message) {
        SwingUtilities.invokeLater(() -> setStatus(message));
    }

    private int indexOf(long id) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).id() == id) return i;
        }
        return -1;
    }

    private void render() {
        StringBuilder text = new StringBuilder();
        boolean withSource = showSource.isSelected();
        for (Line line : lines) {
            String translated = line.translated();
            // Пустая строка между репликами: без неё соседние фразы сливаются
            // в сплошную простыню, особенно когда говорят без пауз.
            if (!text.isEmpty()) text.append('\n');
            String tag = line.language() == null || line.language().isBlank()
                    ? "" : line.language().split("-")[0] + "  ";
            text.append(line.time()).append("  ").append(tag)
                    .append(translated == null ? "… " + line.source() : translated)
                    .append('\n');
            if (withSource && translated != null) {
                text.append("          ").append(line.source()).append('\n');
            }
        }
        area.setText(text.toString());
        JScrollBar bar = scroll.getVerticalScrollBar();
        bar.setValue(bar.getMaximum());
    }
}
