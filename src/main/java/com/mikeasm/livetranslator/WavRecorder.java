package com.mikeasm.livetranslator;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Пишет захваченный звук в WAV.
 * <p>
 * Размер данных в заголовке WAV указывается заранее, а во время записи он
 * неизвестен. Поэтому заголовок сначала пишется с нулями, а корректные числа
 * проставляются при закрытии файла. Если приложение убьют, файл останется с
 * нулевой длиной в заголовке — {@link #repair(Path)} чинит такой файл по
 * фактическому размеру.
 */
public final class WavRecorder implements AutoCloseable {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final int HEADER_BYTES = 44;

    private final RandomAccessFile file;
    private final Path path;
    private final int sampleRate;
    private long dataBytes;
    private boolean closed;

    public WavRecorder(Path directory, int sampleRate) throws IOException {
        Files.createDirectories(directory);
        this.path = directory.resolve("audio-" + LocalDateTime.now().format(STAMP) + ".wav");
        this.sampleRate = sampleRate;
        this.file = new RandomAccessFile(path.toFile(), "rw");
        writeHeader(0);
    }

    public Path path() {
        return path;
    }

    public synchronized long bytesWritten() {
        return dataBytes;
    }

    /** Длительность записи в секундах: 16 бит, один канал. */
    public synchronized long seconds() {
        return dataBytes / (2L * sampleRate);
    }

    public synchronized void write(byte[] pcm) throws IOException {
        if (closed) return;
        file.write(pcm);
        dataBytes += pcm.length;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        file.seek(0);
        writeHeader(dataBytes);
        file.close();
    }

    private void writeHeader(long dataLength) throws IOException {
        int byteRate = sampleRate * 2;
        file.writeBytes("RIFF");
        writeIntLE((int) (36 + dataLength));
        file.writeBytes("WAVE");
        file.writeBytes("fmt ");
        writeIntLE(16);                 // размер блока fmt
        writeShortLE((short) 1);        // PCM без сжатия
        writeShortLE((short) 1);        // один канал
        writeIntLE(sampleRate);
        writeIntLE(byteRate);
        writeShortLE((short) 2);        // выравнивание блока
        writeShortLE((short) 16);       // бит на отсчёт
        file.writeBytes("data");
        writeIntLE((int) dataLength);
    }

    private void writeIntLE(int value) throws IOException {
        file.write(new byte[]{
                (byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)});
    }

    private void writeShortLE(short value) throws IOException {
        file.write(new byte[]{(byte) value, (byte) (value >> 8)});
    }

    /**
     * Восстанавливает заголовок оборванной записи по фактическому размеру файла.
     * Возвращает длительность в секундах или -1, если чинить нечего.
     */
    public static long repair(Path path) throws IOException {
        long size = Files.size(path);
        if (size <= HEADER_BYTES) return -1;
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.seek(24);
            int sampleRate = readIntLE(file);
            long dataLength = size - HEADER_BYTES;

            file.seek(4);
            writeIntLE(file, (int) (36 + dataLength));
            file.seek(40);
            writeIntLE(file, (int) dataLength);
            return dataLength / (2L * Math.max(sampleRate, 1));
        }
    }

    private static int readIntLE(RandomAccessFile file) throws IOException {
        byte[] b = new byte[4];
        file.readFully(b);
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24);
    }

    private static void writeIntLE(RandomAccessFile file, int value) throws IOException {
        file.write(new byte[]{
                (byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)});
    }
}
