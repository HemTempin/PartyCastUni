package com.example.partycastmvp;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

import javax.net.SocketFactory;

public final class PartyProtocol {
    private PartyProtocol() {}

    public static final int DEFAULT_PORT = 45678;
    public static final int MAGIC = 0x50434153; // PCAS: PartyCast Stream
    public static final int VERSION = 3;

    public static final int TYPE_AUDIO_FILE = 1;
    public static final int TYPE_LINK = 2;
    public static final int TYPE_CONTROL = 3;
    public static final int TYPE_PING = 4;
    public static final int TYPE_PONG = 5;

    public static final String CMD_SKIP = "SKIP";
    public static final String CMD_PAUSE_RESUME = "PAUSE_RESUME";
    public static final String CMD_STOP_CLEAR = "STOP_CLEAR";
    public static final String CMD_MOVE_UP = "MOVE_UP";
    public static final String CMD_MOVE_DOWN = "MOVE_DOWN";
    public static final String CMD_REMOVE = "REMOVE";

    public static final int BUFFER_SIZE = 64 * 1024;
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 6000;

    public interface ProgressListener {
        void onProgress(long sentBytes, long totalBytes);
    }

    public static void sendAudioFile(
            String host,
            int port,
            String roomCode,
            ContentResolver resolver,
            Uri uri,
            String displayName,
            long sizeBytes,
            ProgressListener listener
    ) throws IOException {
        sendAudioFile(host, port, roomCode, resolver, uri, displayName, sizeBytes, listener, null);
    }

    public static void sendAudioFile(
            String host,
            int port,
            String roomCode,
            ContentResolver resolver,
            Uri uri,
            String displayName,
            long sizeBytes,
            ProgressListener listener,
            SocketFactory socketFactory
    ) throws IOException {
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = "audio_from_client";
        }

        InputStream rawIn = resolver.openInputStream(uri);
        if (rawIn == null) {
            throw new IOException("Не удалось открыть выбранный аудиофайл");
        }

        try (Socket socket = openSocket(host, port, socketFactory, DEFAULT_CONNECT_TIMEOUT_MS);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             BufferedInputStream in = new BufferedInputStream(rawIn)) {

            writeHeader(out, roomCode, TYPE_AUDIO_FILE);
            out.writeUTF(displayName);
            out.writeLong(sizeBytes);

            byte[] buffer = new byte[BUFFER_SIZE];
            long sent = 0L;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                sent += read;
                if (listener != null) {
                    listener.onProgress(sent, sizeBytes);
                }
            }
            out.flush();
        }
    }

    public static void sendLink(String host, int port, String roomCode, String url) throws IOException {
        sendLink(host, port, roomCode, url, null);
    }

    public static void sendLink(String host, int port, String roomCode, String url, SocketFactory socketFactory) throws IOException {
        if (url == null || url.trim().isEmpty()) {
            throw new IOException("Пустая ссылка");
        }

        try (Socket socket = openSocket(host, port, socketFactory, DEFAULT_CONNECT_TIMEOUT_MS);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
            writeHeader(out, roomCode, TYPE_LINK);
            out.writeUTF(url.trim());
            out.flush();
        }
    }

    public static void sendControl(String host, int port, String roomCode, String command, int queueIndex) throws IOException {
        sendControl(host, port, roomCode, command, queueIndex, null);
    }

    public static void sendControl(String host, int port, String roomCode, String command, int queueIndex, SocketFactory socketFactory) throws IOException {
        if (command == null || command.trim().isEmpty()) {
            throw new IOException("Пустая команда");
        }

        try (Socket socket = openSocket(host, port, socketFactory, DEFAULT_CONNECT_TIMEOUT_MS);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
            writeHeader(out, roomCode, TYPE_CONTROL);
            out.writeUTF(command.trim());
            out.writeInt(queueIndex);
            out.flush();
        }
    }

    public static PingResult pingHost(String host, int port, String roomCode, SocketFactory socketFactory, int timeoutMs) throws IOException {
        try (Socket socket = openSocket(host, port, socketFactory, timeoutMs);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            socket.setSoTimeout(timeoutMs);
            writeHeader(out, roomCode, TYPE_PING);
            out.writeUTF("PartyCast client ping");
            out.flush();

            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IOException("Неверный PONG: magic=" + magic);
            }

            int version = in.readInt();
            if (version < 2 || version > VERSION) {
                throw new IOException("Неподдерживаемый PONG version=" + version);
            }

            String responseRoomCode = normalizeRoomCode(in.readUTF());
            int type = in.readInt();
            if (type != TYPE_PONG) {
                throw new IOException("Ожидался PONG, получен type=" + type);
            }

            String label = in.readUTF();
            return new PingResult(host, port, responseRoomCode, label);
        }
    }

    public static void sendPong(Socket socket, String roomCode, String label) throws IOException {
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        writeHeader(out, roomCode, TYPE_PONG);
        out.writeUTF(label == null || label.trim().isEmpty() ? "PartyCast Host" : label.trim());
        out.flush();
    }

    private static Socket openSocket(String host, int port, SocketFactory socketFactory, int timeoutMs) throws IOException {
        Socket socket = socketFactory != null ? socketFactory.createSocket() : new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        socket.setTcpNoDelay(true);
        return socket;
    }

    private static void writeHeader(DataOutputStream out, String roomCode, int type) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeUTF(normalizeRoomCode(roomCode));
        out.writeInt(type);
    }

    public static IncomingMessage receiveMessage(Socket socket, File receiveDir) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException("Неверный протокол: magic=" + magic);
        }

        int version = in.readInt();
        if (version < 1 || version > VERSION) {
            throw new IOException("Неподдерживаемая версия протокола: " + version);
        }

        String roomCode = "";
        if (version >= 2) {
            roomCode = normalizeRoomCode(in.readUTF());
        }

        int type = in.readInt();
        if (type == TYPE_AUDIO_FILE) {
            String originalName = in.readUTF();
            long expectedSize = in.readLong();

            if (!receiveDir.exists() && !receiveDir.mkdirs()) {
                throw new IOException("Не удалось создать папку приёма: " + receiveDir.getAbsolutePath());
            }

            String safeName = sanitizeFileName(originalName);
            File outFile = new File(receiveDir, System.currentTimeMillis() + "_" + safeName);
            try (FileOutputStream fileOut = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long remaining = expectedSize;

                if (expectedSize >= 0) {
                    while (remaining > 0) {
                        int toRead = (int) Math.min(buffer.length, remaining);
                        int read = in.read(buffer, 0, toRead);
                        if (read == -1) {
                            throw new IOException("Соединение оборвалось до конца файла");
                        }
                        fileOut.write(buffer, 0, read);
                        remaining -= read;
                    }
                } else {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        fileOut.write(buffer, 0, read);
                    }
                }
            }

            return IncomingMessage.audioFile(roomCode, originalName, outFile);
        }

        if (type == TYPE_LINK) {
            String url = in.readUTF();
            return IncomingMessage.link(roomCode, url);
        }

        if (type == TYPE_CONTROL) {
            String command = in.readUTF();
            int queueIndex = in.readInt();
            return IncomingMessage.control(roomCode, command, queueIndex);
        }

        if (type == TYPE_PING) {
            String clientLabel = in.readUTF();
            return IncomingMessage.ping(roomCode, clientLabel);
        }

        throw new IOException("Неизвестный тип сообщения: " + type);
    }

    public static String normalizeRoomCode(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "received_audio";
        }
        return name.replaceAll("[^a-zA-Z0-9а-яА-ЯёЁ._ -]", "_");
    }

    public static final class IncomingMessage {
        public final int type;
        public final String roomCode;
        public final String titleOrUrl;
        public final File audioFile;
        public final String command;
        public final int queueIndex;
        public final String pingLabel;

        private IncomingMessage(int type, String roomCode, String titleOrUrl, File audioFile, String command, int queueIndex, String pingLabel) {
            this.type = type;
            this.roomCode = normalizeRoomCode(roomCode);
            this.titleOrUrl = titleOrUrl;
            this.audioFile = audioFile;
            this.command = command;
            this.queueIndex = queueIndex;
            this.pingLabel = pingLabel;
        }

        public static IncomingMessage audioFile(String roomCode, String title, File audioFile) {
            return new IncomingMessage(TYPE_AUDIO_FILE, roomCode, title, audioFile, null, -1, null);
        }

        public static IncomingMessage link(String roomCode, String url) {
            return new IncomingMessage(TYPE_LINK, roomCode, url, null, null, -1, null);
        }

        public static IncomingMessage control(String roomCode, String command, int queueIndex) {
            return new IncomingMessage(TYPE_CONTROL, roomCode, null, null, command, queueIndex, null);
        }

        public static IncomingMessage ping(String roomCode, String pingLabel) {
            return new IncomingMessage(TYPE_PING, roomCode, null, null, null, -1, pingLabel);
        }
    }

    public static final class PingResult {
        public final String host;
        public final int port;
        public final String roomCode;
        public final String label;

        public PingResult(String host, int port, String roomCode, String label) {
            this.host = host;
            this.port = port;
            this.roomCode = normalizeRoomCode(roomCode);
            this.label = label == null || label.trim().isEmpty() ? "PartyCast Host" : label.trim();
        }
    }
}
