package com.mikeasm.livetranslator.bench;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Минимальный HTTP-клиент для движков, у которых нет gRPC.
 * <p>
 * Отдельная зависимость ради четырёх запросов не нужна, а вот внятная ошибка
 * нужна: без тела ответа отладка чужого API превращается в гадание по коду
 * состояния.
 */
final class Http {

    /** Запрос может быть долгим: час записи режется на куски, но куски крупные. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(20);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Http() {}

    static String get(String url, Map<String, String> headers) throws IOException {
        return send(request(url, headers).GET().build(), url);
    }

    static String postJson(String url, Map<String, String> headers, String json) throws IOException {
        HttpRequest request = request(url, headers)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return send(request, url);
    }

    static String postBytes(String url, Map<String, String> headers, byte[] body,
                            String contentType) throws IOException {
        HttpRequest request = request(url, headers)
                .header("content-type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return send(request, url);
    }

    /** Загрузка файла вместе с текстовыми полями — как её ждут STT-сервисы. */
    static String postMultipart(String url, Map<String, String> headers,
                                Map<String, String> fields,
                                String fileField, String fileName, byte[] file,
                                String fileType) throws IOException {
        String boundary = "----live-translator-" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            for (Map.Entry<String, String> field : fields.entrySet()) {
                if (field.getValue() == null || field.getValue().isBlank()) continue;
                write(body, "--" + boundary + "\r\n");
                write(body, "Content-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n");
                write(body, field.getValue() + "\r\n");
            }
            write(body, "--" + boundary + "\r\n");
            write(body, "Content-Disposition: form-data; name=\"" + fileField
                    + "\"; filename=\"" + fileName + "\"\r\n");
            write(body, "Content-Type: " + fileType + "\r\n\r\n");
            body.write(file);
            write(body, "\r\n--" + boundary + "--\r\n");
        } catch (IOException e) {
            throw new IOException("Не удалось собрать запрос: " + e.getMessage(), e);
        }

        HttpRequest request = request(url, headers)
                .header("content-type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return send(request, url);
    }

    private static void write(ByteArrayOutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static HttpRequest.Builder request(String url, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT);
        headers.forEach(builder::header);
        return builder;
    }

    private static String send(HttpRequest request, String url) throws IOException {
        try {
            HttpResponse<String> response = CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode() + " от " + host(url)
                        + ": " + shorten(response.body()));
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Запрос прерван");
        }
    }

    private static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (RuntimeException e) {
            return url;
        }
    }

    /** Чужие сервисы умеют отвечать страницей на сто килобайт — это в лог не нужно. */
    private static String shorten(String body) {
        String text = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return text.length() <= 400 ? text : text.substring(0, 400) + "…";
    }

    /**
     * Собирает слова в реплики: по паузе между словами или по накопленной длине.
     * Движки, отдающие только слова с временами, иначе дали бы одну простыню,
     * которую не с чем сопоставить.
     */
    static List<Transcript.Segment> groupWords(List<Word> words, String language) {
        List<Transcript.Segment> segments = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int start = 0;
        int count = 0;
        double previousEnd = -1;

        for (Word word : words) {
            if (word.text() == null || word.text().isBlank()) continue;
            boolean pause = previousEnd >= 0 && word.startMs() - previousEnd > GAP_MS;
            if (!line.isEmpty() && (pause || count >= MAX_WORDS)) {
                segments.add(new Transcript.Segment(start, line.toString().trim(), language));
                line.setLength(0);
                count = 0;
            }
            if (line.isEmpty()) start = (int) word.startMs();
            if (!line.isEmpty() && !word.text().startsWith(" ")) line.append(' ');
            line.append(word.text().trim());
            previousEnd = word.endMs();
            count++;
        }
        if (!line.isEmpty()) {
            segments.add(new Transcript.Segment(start, line.toString().trim(), language));
        }
        return segments;
    }

    /** Пауза, после которой начинается новая реплика. */
    private static final double GAP_MS = 700;

    /** Предел длины реплики в словах. */
    private static final int MAX_WORDS = 14;

    /** Слово с временами в миллисекундах от начала куска. */
    record Word(String text, double startMs, double endMs) {}
}
