package com.example.partycastmvp;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ServerController {
    public interface Listener {
        void onLog(String message);
        void onFileReceived(String title, File file);
        void onLinkReceived(String url);
        void onControlReceived(String command, int queueIndex);
        void onServerStateChanged(boolean running);
    }

    public interface RoomCodeProvider {
        String getRoomCode();
    }

    private final File receiveDir;
    private final Listener listener;
    private final RoomCodeProvider roomCodeProvider;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // Preserve the exact arrival order and finish one upload before reading the next one.
    private final ExecutorService clientExecutor = Executors.newSingleThreadExecutor();

    private volatile boolean running = false;
    private volatile PartyProtocol.QueueSnapshot queueSnapshot = PartyProtocol.QueueSnapshot.empty("");
    private ServerSocket serverSocket;
    private Thread serverThread;

    public ServerController(File receiveDir, RoomCodeProvider roomCodeProvider, Listener listener) {
        this.receiveDir = receiveDir;
        this.roomCodeProvider = roomCodeProvider;
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    public void setQueueSnapshot(PartyProtocol.QueueSnapshot snapshot) {
        queueSnapshot = snapshot == null ? PartyProtocol.QueueSnapshot.empty("") : snapshot;
    }

    public void start(int port) {
        if (running) {
            postLog("Хост уже запущен");
            return;
        }

        running = true;
        postServerState(true);
        serverThread = new Thread(() -> runServer(port), "partycast-server");
        serverThread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
                serverSocket = null;
            }
        } catch (IOException ignored) {
        }
        postServerState(false);
        postLog("Хост остановлен");
    }

    private void runServer(int port) {
        try (ServerSocket ss = new ServerSocket(port)) {
            serverSocket = ss;
            postLog("Хост слушает порт " + port);

            while (running) {
                Socket socket = ss.accept();
                clientExecutor.execute(() -> handleClient(socket));
            }
        } catch (IOException e) {
            if (running) {
                postLog("Ошибка сервера: " + e.getMessage());
            }
        } finally {
            running = false;
            serverSocket = null;
            postServerState(false);
        }
    }

    private void handleClient(Socket socket) {
        String remote = socket.getInetAddress() != null ? socket.getInetAddress().getHostAddress() : "unknown";
        postLog("Подключился клиент: " + remote);

        try (Socket closable = socket) {
            PartyProtocol.IncomingMessage msg = PartyProtocol.receiveMessage(closable, receiveDir);
            String expectedRoomCode = PartyProtocol.normalizeRoomCode(roomCodeProvider.getRoomCode());
            if (!expectedRoomCode.isEmpty() && !expectedRoomCode.equals(msg.roomCode)) {
                if (msg.type == PartyProtocol.TYPE_AUDIO_FILE && msg.audioFile != null && msg.audioFile.exists()) {
                    // receiveMessage has already written the stream; do not retain rejected uploads.
                    msg.audioFile.delete();
                    sendFileAck(closable, expectedRoomCode, false, "Неверный код комнаты");
                } else if (msg.type == PartyProtocol.TYPE_LINK) {
                    PartyProtocol.sendAck(closable, expectedRoomCode, false, "Неверный код комнаты");
                }
                postLog("Отклонено сообщение от " + remote + ": неверный код комнаты");
                return;
            }

            if (msg.type == PartyProtocol.TYPE_PING) {
                PartyProtocol.sendPong(closable, expectedRoomCode, "PartyCast Host");
                postLog("PING от клиента " + remote + " — ответил PONG");
            } else if (msg.type == PartyProtocol.TYPE_QUEUE_REQUEST) {
                PartyProtocol.sendQueueSnapshot(closable, expectedRoomCode, queueSnapshot);
            } else if (msg.type == PartyProtocol.TYPE_AUDIO_FILE) {
                postLog("Получен аудиофайл: " + msg.titleOrUrl);
                postFileReceived(msg.titleOrUrl, msg.audioFile);
                sendFileAck(closable, expectedRoomCode, true, "Файл принят");
            } else if (msg.type == PartyProtocol.TYPE_LINK) {
                postLog("Получена ссылка: " + msg.titleOrUrl);
                postLinkReceived(msg.titleOrUrl);
                PartyProtocol.sendAck(closable, expectedRoomCode, true, "Ссылка принята");
            } else if (msg.type == PartyProtocol.TYPE_CONTROL) {
                postLog("Получена команда: " + msg.command + " index=" + msg.queueIndex);
                postControlReceived(msg.command, msg.queueIndex);
            }
        } catch (IOException e) {
            postLog("Ошибка клиента " + remote + ": " + e.getMessage());
        }
    }

    private void sendFileAck(Socket socket, String roomCode, boolean accepted, String message) {
        try {
            PartyProtocol.sendAck(socket, roomCode, accepted, message);
        } catch (IOException e) {
            postLog("Не удалось подтвердить приём файла: " + e.getMessage());
        }
    }

    private void postLog(String message) {
        mainHandler.post(() -> listener.onLog(message));
    }

    private void postServerState(boolean running) {
        mainHandler.post(() -> listener.onServerStateChanged(running));
    }

    private void postFileReceived(String title, File file) {
        mainHandler.post(() -> listener.onFileReceived(title, file));
    }

    private void postLinkReceived(String url) {
        mainHandler.post(() -> listener.onLinkReceived(url));
    }

    private void postControlReceived(String command, int queueIndex) {
        mainHandler.post(() -> listener.onControlReceived(command, queueIndex));
    }
}
