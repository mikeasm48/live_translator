package com.mikeasm.livetranslator;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Запуск внешних команд так, чтобы они не могли подвесить приложение.
 * <p>
 * Каждое правило здесь куплено ценой замершего окна обновления. Ввод закрыт:
 * ни {@code git}, ни {@code brew} не дождутся пароля и не станут ждать его
 * вечно. У каждой команды свой срок, и он короткий: лучше сказать «не
 * уложилось за минуту», чем показывать полоску, за которой ничего не
 * происходит. Вывод возвращается целиком — по трём последним строкам причину
 * обычно не видно.
 */
final class Shell {

    private Shell() {}

    record Result(int code, String output, boolean timedOut) {
        boolean ok() {
            return code == 0 && !timedOut;
        }
    }

    static Result run(int seconds, String... command) {
        return run(seconds, Map.of(), null, command);
    }

    /**
     * @param started вызывается с запущенным процессом: через него команду
     *                можно прервать, пока она идёт
     */
    static Result run(int seconds, Map<String, String> env, Consumer<Process> started,
                      String... command) {
        List<String> line = new ArrayList<>(Arrays.asList(command));
        ProcessBuilder builder = new ProcessBuilder(line)
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        builder.environment().putAll(env);

        try {
            Process process = builder.start();
            if (started != null) started.accept(process);

            AtomicReference<String> output = new AtomicReference<>("");
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    output.set(new String(process.getInputStream().readAllBytes(),
                            StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // процесс закрылся раньше — читать больше нечего
                }
            });

            boolean finished = process.waitFor(seconds, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            reader.join(2000);
            return new Result(finished ? process.exitValue() : -1,
                    output.get().strip(), !finished);
        } catch (IOException e) {
            return new Result(-1, String.valueOf(e.getMessage()), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "прервано", false);
        }
    }
}
