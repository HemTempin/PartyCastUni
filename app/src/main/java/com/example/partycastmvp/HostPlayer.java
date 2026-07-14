package com.example.partycastmvp;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class HostPlayer {
    public interface Listener {
        void onLog(String message);
        void onQueueChanged(String queueText);
        void onLocalNowPlayingChanged(String text, int progressMs, int durationMs, boolean visible);
    }

    private static final int KIND_FILE = 1;
    private static final int KIND_LINK = 2;

    private static final class QueueItem {
        final int kind;
        final String title;
        final File file;
        final String url;
        final int service;

        QueueItem(int kind, String title, File file, String url, int service) {
            this.kind = kind;
            this.title = title;
            this.file = file;
            this.url = url;
            this.service = service;
        }

        static QueueItem file(String title, File file) {
            String safeTitle = title == null || title.trim().isEmpty() ? file.getName() : title.trim();
            return new QueueItem(KIND_FILE, safeTitle, file, null, OnlineLinkHelper.SERVICE_UNKNOWN);
        }

        static QueueItem link(String url) {
            int service = OnlineLinkHelper.detectService(url);
            return new QueueItem(KIND_LINK, OnlineLinkHelper.buildDisplayTitle(url), null, url, service);
        }
    }

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<QueueItem> queue = new ArrayList<>();
    private final List<QueueItem> history = new ArrayList<>();
    private final AudioManager audioManager;
    private final ExternalServicePlaybackController externalPlaybackController;

    private boolean yandexQueueAutomationEnabled = true;
    private boolean resumeYandexAfterLocalQueue = false;

    private MediaPlayer mediaPlayer;
    private QueueItem currentItem;
    private boolean pausedBecauseFocusLoss = false;
    private AudioFocusRequest focusRequest;
    private boolean waitingForYandexTrackEnd = false;
    private String waitingYandexTrackToken = "";

    private final Runnable localProgressTicker = new Runnable() {
        @Override
        public void run() {
            updateLocalNowPlaying(false);
            if (mediaPlayer != null && currentItem != null && currentItem.kind == KIND_FILE) {
                mainHandler.postDelayed(this, 500);
            }
        }
    };
    private final Runnable yandexQueueWatcher = new Runnable() {
        @Override
        public void run() {
            if (!waitingForYandexTrackEnd || queue.isEmpty() || !isIdleInsidePartyCast()) {
                stopWaitingForYandexTrackEnd();
                return;
            }

            boolean yandexPlaying = isYandexPlaying();
            String currentToken = getYandexTrackToken();
            if (waitingYandexTrackToken.isEmpty() && !currentToken.isEmpty()) {
                waitingYandexTrackToken = currentToken;
            }

            boolean trackChanged = !waitingYandexTrackToken.isEmpty()
                    && !currentToken.isEmpty()
                    && !waitingYandexTrackToken.equals(currentToken);
            if (!yandexPlaying || trackChanged) {
                log(trackChanged
                        ? "Трек Яндекс Музыки завершился: запускаю ожидающий файл PartyCast"
                        : "Яндекс Музыка остановилась: запускаю ожидающий файл PartyCast");
                stopWaitingForYandexTrackEnd();
                playNext();
                return;
            }

            mainHandler.postDelayed(this, 750);
        }
    };
    private final AudioManager.OnAudioFocusChangeListener focusChangeListener = this::onAudioFocusChange;

    public HostPlayer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.externalPlaybackController = new ExternalServicePlaybackController(this.context, new ExternalServicePlaybackController.Listener() {
            @Override
            public void onLog(String message) {
                log(message);
            }

            @Override
            public void onError(String message) {
                log(message);
                currentItem = null;
                notifyQueueChanged();
                playNext();
            }
        });
    }

    public void enqueueFile(String title, File file) {
        if (file == null || !file.exists()) {
            log("Файл не найден, пропускаю");
            return;
        }

        QueueItem item = QueueItem.file(title, file);
        queue.add(item);
        log("Добавлено в очередь: " + item.title);
        notifyQueueChanged();

        startQueueIfAllowed("Файл останется в очереди PartyCast и не будет перебивать текущий трек Яндекс Музыки.");
    }

    public void enqueueLink(String url) {
        if (!OnlineLinkHelper.looksLikeHttpUrl(url)) {
            log("Ссылка не похожа на http/https URL, пропускаю: " + url);
            return;
        }

        QueueItem item = QueueItem.link(url);
        if (item.service == OnlineLinkHelper.SERVICE_YANDEX_MUSIC && yandexQueueAutomationEnabled) {
            log("Получена ссылка Яндекс Музыки: добавляю её следующим треком в очередь приложения Яндекс Музыки");
            YandexMusicBridge.addTrackAsNext(context, url, this::log);
            notifyQueueChanged();
            return;
        }

        queue.add(item);
        log("Ссылка добавлена в очередь PartyCast: " + item.title);
        notifyQueueChanged();
        startQueueIfAllowed("Ссылка останется в очереди PartyCast и не будет перебивать текущий трек Яндекс Музыки.");
    }

    public void setYandexQueueAutomationEnabled(boolean enabled) {
        yandexQueueAutomationEnabled = enabled;
        log("Режим Яндекс Музыки: " + (enabled
                ? "добавлять ссылки напрямую в очередь Яндекс Музыки"
                : "открывать ссылки как обычные внешние ссылки"));
        notifyQueueChanged();
    }

    public boolean isYandexQueueAutomationEnabled() {
        return yandexQueueAutomationEnabled;
    }

    public void refreshQueue() {
        notifyQueueChanged();
        updateLocalNowPlaying(false);
    }

    public void applyCommand(String command, int queueIndex) {
        if (PartyProtocol.CMD_SKIP.equals(command)) {
            skip();
        } else if (PartyProtocol.CMD_PAUSE_RESUME.equals(command)) {
            pauseOrResume();
        } else if (PartyProtocol.CMD_STOP_CLEAR.equals(command)) {
            stopAll();
        } else if (PartyProtocol.CMD_MOVE_UP.equals(command)) {
            moveUp(queueIndex);
        } else if (PartyProtocol.CMD_MOVE_DOWN.equals(command)) {
            moveDown(queueIndex);
        } else if (PartyProtocol.CMD_REMOVE.equals(command)) {
            removeFromQueue(queueIndex);
        } else if (PartyProtocol.CMD_PREVIOUS.equals(command)) {
            previous();
        } else {
            log("Неизвестная команда управления: " + command);
        }
    }

    public void skip() {
        if (currentItem == null && mediaPlayer == null && queue.isEmpty()) {
            YandexMusicBridge.next(context);
            log("Яндекс Музыка: отправлена команда Следующий через MediaSession");
            return;
        }

        log("Пропуск текущего трека PartyCast");
        rememberCurrentItem();
        releaseCurrentPlayback(false);
        playNext();
    }

    public void previous() {
        if (mediaPlayer != null && currentItem != null && currentItem.kind == KIND_FILE) {
            try {
                if (mediaPlayer.getCurrentPosition() > 3000) {
                    mediaPlayer.seekTo(0);
                    startLocalProgressUpdates();
                    log("Текущий трек запущен сначала");
                    return;
                }
            } catch (IllegalStateException ignored) {
            }
        }

        if (history.isEmpty()) {
            if (currentItem == null) {
                YandexMusicBridge.previous(context);
                log("Яндекс Музыка: отправлена команда Предыдущий трек");
            } else {
                log("Предыдущего трека PartyCast пока нет");
            }
            return;
        }

        QueueItem previousItem = history.remove(history.size() - 1);
        if (currentItem != null) {
            queue.add(0, currentItem);
        }
        releaseCurrentPlayback(false);
        stopWaitingForYandexTrackEnd();
        currentItem = previousItem;
        log("Возвращаю предыдущий трек: " + previousItem.title);
        notifyQueueChanged();
        if (previousItem.kind == KIND_LINK) {
            playCurrentLink();
        } else {
            playCurrentFile();
        }
    }

    public void pauseOrResume() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    updateLocalNowPlaying(false);
                    log("Пауза");
                } else {
                    mediaPlayer.start();
                    pausedBecauseFocusLoss = false;
                    startLocalProgressUpdates();
                    log("Продолжение воспроизведения");
                }
                notifyQueueChanged();
            } catch (IllegalStateException e) {
                log("Не удалось переключить паузу: " + e.getMessage());
            }
            return;
        }

        boolean nowPlaying = YandexMusicBridge.togglePlayPause(context);
        log(nowPlaying
                ? "Отправлена команда Play активному медиаплееру Android"
                : "Отправлена команда Pause активному медиаплееру Android");
        notifyQueueChanged();
    }

    public void stopAll() {
        queue.clear();
        history.clear();
        stopWaitingForYandexTrackEnd();
        resumeYandexAfterLocalQueue = false;
        releaseCurrentPlayback(true);
        notifyQueueChanged();
        log("Очередь очищена, воспроизведение остановлено");
    }

    public void moveUp(int oneBasedIndex) {
        int index = oneBasedIndex - 1;
        if (index <= 0 || index >= queue.size()) {
            log("Нельзя поднять элемент №" + oneBasedIndex);
            return;
        }
        QueueItem item = queue.remove(index);
        queue.add(index - 1, item);
        log("Поднято в очереди: " + item.title);
        notifyQueueChanged();
    }

    public void moveDown(int oneBasedIndex) {
        int index = oneBasedIndex - 1;
        if (index < 0 || index >= queue.size() - 1) {
            log("Нельзя опустить элемент №" + oneBasedIndex);
            return;
        }
        QueueItem item = queue.remove(index);
        queue.add(index + 1, item);
        log("Опущено в очереди: " + item.title);
        notifyQueueChanged();
    }

    public void removeFromQueue(int oneBasedIndex) {
        int index = oneBasedIndex - 1;
        if (index < 0 || index >= queue.size()) {
            log("Нет элемента очереди №" + oneBasedIndex);
            return;
        }
        QueueItem item = queue.remove(index);
        log("Удалено из очереди: " + item.title);
        notifyQueueChanged();
    }

    public void onOnlinePlaybackFinished() {
        if (currentItem != null && currentItem.kind == KIND_LINK) {
            log("Онлайн-трек завершён: " + currentItem.title);
            rememberCurrentItem();
            releaseCurrentPlayback(false);
            playNext();
        }
    }

    public void onOnlinePlaybackError(String message) {
        log("Ошибка онлайн-плеера: " + message + ". Пропускаю трек.");
        releaseCurrentPlayback(false);
        playNext();
    }

    private void startQueueIfAllowed(String waitMessage) {
        if (!isIdleInsidePartyCast()) {
            return;
        }

        if (isYandexPlaying()) {
            log(waitMessage + " Чтобы вручную перейти к очереди PartyCast, нажмите «Следующий трек» на хосте.");
            waitForYandexTrackEnd();
            notifyQueueChanged();
            return;
        }

        stopWaitingForYandexTrackEnd();
        playNext();
    }

    private void waitForYandexTrackEnd() {
        if (!waitingForYandexTrackEnd) {
            waitingForYandexTrackEnd = true;
            waitingYandexTrackToken = getYandexTrackToken();
        }
        mainHandler.removeCallbacks(yandexQueueWatcher);
        mainHandler.postDelayed(yandexQueueWatcher, 750);
    }

    private void stopWaitingForYandexTrackEnd() {
        waitingForYandexTrackEnd = false;
        waitingYandexTrackToken = "";
        mainHandler.removeCallbacks(yandexQueueWatcher);
    }

    private String getYandexTrackToken() {
        try {
            String value = YandexMusicBridge.currentTrackToken(context);
            return value == null ? "" : value;
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean isIdleInsidePartyCast() {
        return currentItem == null && mediaPlayer == null;
    }

    private boolean isYandexPlaying() {
        try {
            return YandexMusicBridge.isPlaying(context);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void playNext() {
        stopWaitingForYandexTrackEnd();
        if (queue.isEmpty()) {
            boolean shouldResumeYandex = resumeYandexAfterLocalQueue;
            releaseCurrentPlayback(true);
            if (shouldResumeYandex) {
                resumeYandexAfterLocalQueue = true;
                resumeYandexIfNeeded("PartyCast-файлы закончились: возвращаю воспроизведение Яндекс Музыки");
            }
            log("Очередь PartyCast пуста");
            notifyQueueChanged();
            return;
        }

        currentItem = queue.remove(0);
        notifyQueueChanged();

        if (currentItem.kind == KIND_LINK) {
            playCurrentLink();
        } else {
            playCurrentFile();
        }
    }

    private void playCurrentLink() {
        if (currentItem == null || currentItem.url == null) {
            playNext();
            return;
        }

        if (!requestAudioFocus()) {
            log("Не удалось получить аудиофокус для онлайн-ссылки. Трек вернулся в конец очереди.");
            queue.add(currentItem);
            currentItem = null;
            notifyQueueChanged();
            return;
        }

        log(OnlineLinkHelper.detectServiceName(currentItem.url)
                + ": открываю ссылку во внешнем приложении и пробую отправить MEDIA_PLAY");
        externalPlaybackController.openLinkAndTryPlay(currentItem.url, currentItem.service);
        notifyQueueChanged();
    }

    private void playCurrentFile() {
        if (currentItem == null || currentItem.file == null) {
            playNext();
            return;
        }

        pauseYandexBeforeLocalFileIfNeeded();

        if (!requestAudioFocus()) {
            log("Не удалось получить аудиофокус. Трек вернулся в конец очереди.");
            queue.add(currentItem);
            currentItem = null;
            resumeYandexIfNeeded("Аудиофокус для локального файла не получен: возвращаю Яндекс Музыку");
            notifyQueueChanged();
            return;
        }

        MediaPlayer player = new MediaPlayer();
        mediaPlayer = player;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();
                player.setAudioAttributes(attrs);
            } else {
                player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            }

            player.setDataSource(currentItem.file.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                log("Играет: " + currentItem.title);
                mp.start();
                startLocalProgressUpdates();
                notifyQueueChanged();
            });
            player.setOnCompletionListener(mp -> {
                log("Трек завершён: " + currentItem.title);
                rememberCurrentItem();
                releaseCurrentPlayback(false);
                playNext();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                log("Ошибка MediaPlayer: what=" + what + ", extra=" + extra + ". Пропускаю трек.");
                releaseCurrentPlayback(false);
                playNext();
                return true;
            });
            player.prepareAsync();
        } catch (IOException | IllegalStateException e) {
            log("Не удалось запустить трек: " + e.getMessage());
            releaseCurrentPlayback(false);
            playNext();
        }
    }

    private void pauseYandexBeforeLocalFileIfNeeded() {
        if (!resumeYandexAfterLocalQueue && isYandexPlaying()) {
            YandexMusicBridge.pause(context);
            resumeYandexAfterLocalQueue = true;
            log("Яндекс Музыка играла до локального файла: поставил её на паузу и верну после очереди PartyCast");
        }
    }

    private void resumeYandexIfNeeded(String reason) {
        if (resumeYandexAfterLocalQueue) {
            resumeYandexAfterLocalQueue = false;
            YandexMusicBridge.play(context);
            log(reason);
        }
    }

    private void startLocalProgressUpdates() {
        mainHandler.removeCallbacks(localProgressTicker);
        updateLocalNowPlaying(false);
        mainHandler.postDelayed(localProgressTicker, 500);
    }

    private void stopLocalProgressUpdates(boolean clearUi) {
        mainHandler.removeCallbacks(localProgressTicker);
        if (clearUi) {
            listener.onLocalNowPlayingChanged("Локальная/переданная музыка сейчас не играет", 0, 0, false);
        }
    }

    private void updateLocalNowPlaying(boolean forceClear) {
        if (forceClear || mediaPlayer == null || currentItem == null || currentItem.kind != KIND_FILE) {
            listener.onLocalNowPlayingChanged("Локальная/переданная музыка сейчас не играет", 0, 0, false);
            return;
        }

        try {
            int duration = mediaPlayer.getDuration();
            int position = mediaPlayer.getCurrentPosition();
            boolean playing = mediaPlayer.isPlaying();
            String text = currentItem.title
                    + "\n" + formatTime(position) + " / " + formatTime(duration)
                    + (playing ? "" : "  ·  пауза");
            listener.onLocalNowPlayingChanged(text, position, duration, true);
        } catch (IllegalStateException e) {
            listener.onLocalNowPlayingChanged("Локальная/переданная музыка сейчас не играет", 0, 0, false);
        }
    }

    private static String formatTime(int ms) {
        if (ms < 0) {
            ms = 0;
        }
        int totalSeconds = ms / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds);
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) {
            return true;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
                    .build();
            int result = audioManager.requestAudioFocus(focusRequest);
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            int result = audioManager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
            );
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        } else {
            audioManager.abandonAudioFocus(focusChangeListener);
        }
    }

    private void onAudioFocusChange(int focusChange) {
        if (mediaPlayer == null) {
            return;
        }

        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            log("Аудиофокус потерян: ставлю музыку на паузу");
            pauseForFocusLoss();
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            log("Временная потеря аудиофокуса: пауза");
            pauseForFocusLoss();
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            log("Системный звук поверх музыки: временно приглушаю");
            try {
                mediaPlayer.setVolume(0.25f, 0.25f);
            } catch (IllegalStateException ignored) {
            }
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            log("Аудиофокус возвращён");
            try {
                mediaPlayer.setVolume(1.0f, 1.0f);
                if (pausedBecauseFocusLoss) {
                    mediaPlayer.start();
                    pausedBecauseFocusLoss = false;
                    startLocalProgressUpdates();
                }
            } catch (IllegalStateException ignored) {
            }
        }
    }

    private void pauseForFocusLoss() {
        try {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                pausedBecauseFocusLoss = true;
                updateLocalNowPlaying(false);
            }
        } catch (IllegalStateException ignored) {
        }
    }

    private void releaseCurrentPlayback(boolean abandonFocus) {
        stopLocalProgressUpdates(true);
        if (mediaPlayer != null) {
            try {
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (IllegalStateException ignored) {
            }
            mediaPlayer = null;
        }
        currentItem = null;
        pausedBecauseFocusLoss = false;
        if (abandonFocus) {
            abandonAudioFocus();
        }
    }

    private void rememberCurrentItem() {
        if (currentItem == null) {
            return;
        }
        history.add(currentItem);
        if (history.size() > 50) {
            history.remove(0);
        }
    }

    private void log(String message) {
        listener.onLog(message);
    }

    private void notifyQueueChanged() {
        StringBuilder sb = new StringBuilder();
        if (currentItem != null) {
            sb.append("Сейчас: ").append(currentItem.title);
            if (currentItem.kind == KIND_LINK) {
                sb.append("\nТип: онлайн-ссылка / внешний сервис");
            }
            sb.append('\n');
        } else if (isYandexPlaying()) {
            sb.append("Сейчас: Яндекс Музыка играет во внешнем приложении\n");
            if (!queue.isEmpty()) {
                sb.append("Очередь PartyCast ждёт и не перебивает текущий трек\n");
            }
        } else {
            sb.append("Сейчас ничего не играет\n");
        }

        sb.append("\nЯндекс-ссылки: ").append(yandexQueueAutomationEnabled
                ? "напрямую в очередь Яндекс Музыки"
                : "как обычные внешние ссылки");
        if (resumeYandexAfterLocalQueue) {
            sb.append("\nПосле локальных файлов: вернуть Яндекс Музыку через MediaSession");
        }

        sb.append("\n\nОчередь ожидания PartyCast:\n");
        if (queue.isEmpty()) {
            sb.append("— пусто");
        } else {
            for (int i = 0; i < queue.size(); i++) {
                QueueItem item = queue.get(i);
                sb.append(i + 1).append(". ").append(item.title);
                if (item.kind == KIND_LINK) {
                    sb.append(" [link]");
                }
                sb.append('\n');
            }
        }
        listener.onQueueChanged(sb.toString());
    }
}
