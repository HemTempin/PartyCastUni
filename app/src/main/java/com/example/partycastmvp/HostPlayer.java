package com.example.partycastmvp;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HostPlayer {
    public interface Listener {
        void onLog(String message);
        void onQueueChanged(String queueText, List<QueueEntry> entries);
        void onLocalNowPlayingChanged(String text, int progressMs, int durationMs, boolean visible);
    }

    public static final class QueueEntry {
        public final String title;
        public final String artist;
        public final String source;
        public final boolean actionable;
        public final int queueIndex;
        public final String badge;

        QueueEntry(String title, String artist, String source) {
            this(title, artist, source, true, -1, "");
        }

        QueueEntry(String title, String artist, String source, boolean actionable, int queueIndex, String badge) {
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
            this.source = source == null ? "" : source;
            this.actionable = actionable;
            this.queueIndex = queueIndex;
            this.badge = badge == null ? "" : badge;
        }
    }

    private static final class VirtualYandexTrack {
        final String title;
        final String artist;
        final String token;

        VirtualYandexTrack(String title, String artist, String token) {
            this.title = title == null || title.trim().isEmpty() ? "ĞĞ²Ñ‚Ğ¾Ğ¿Ğ¾Ğ´Ğ±Ğ¾Ñ€ Ğ¯Ğ½Ğ´ĞµĞºÑ ĞœÑƒĞ·Ñ‹ĞºĞ¸" : title.trim();
            this.artist = artist == null ? "" : artist.trim();
            this.token = token == null ? "" : token;
        }
    }

    private static final int KIND_FILE = 1;
    private static final int KIND_LINK = 2;
    private static final int PREPARATION_NONE = 0;
    private static final int PREPARATION_RUNNING = 1;
    private static final int PREPARATION_READY = 2;
    private static final int PREPARATION_FAILED = 3;

    private static final class QueueItem {
        final int kind;
        String title;
        String artist;
        final File file;
        String url;
        final int service;
        boolean metadataPending;
        int preparationState;

        QueueItem(int kind, String title, String artist, File file, String url, int service) {
            this.kind = kind;
            this.title = title;
            this.artist = artist == null ? "" : artist;
            this.file = file;
            this.url = url;
            this.service = service;
            this.metadataPending = false;
            this.preparationState = PREPARATION_NONE;
        }

        static QueueItem file(String title, String artist, File file) {
            String safeTitle = title == null || title.trim().isEmpty() ? file.getName() : title.trim();
            return new QueueItem(KIND_FILE, safeTitle, artist, file, null, OnlineLinkHelper.SERVICE_UNKNOWN);
        }

        static QueueItem link(String url) {
            return link(url, OnlineLinkHelper.buildDisplayTitle(url), "");
        }

        static QueueItem link(String url, String title, String artist) {
            int service = OnlineLinkHelper.detectService(url);
            String safeTitle = title == null || title.trim().isEmpty()
                    ? OnlineLinkHelper.buildDisplayTitle(url)
                    : title.trim();
            return new QueueItem(KIND_LINK, safeTitle, artist, null, url, service);
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
    private QueueItem preparedYandexItem;
    private QueueItem failedYandexItem;
    private boolean yandexAutomationRunning = false;
    private boolean startPreparedYandexWhenReady = false;
    private boolean promotePreparedWhenTokenAppears = false;
    private VirtualYandexTrack suspendedYandexTrack;
    private VirtualYandexTrack activeAutomaticYandexTrack;
    private boolean resumeSuspendedAfterPreparation = false;
    private boolean yandexRepausedDuringFile = false;

    private final Runnable prepareYandexDuringFile = () -> {
        if (mediaPlayer == null || currentItem == null || currentItem.kind != KIND_FILE || queue.isEmpty()) {
            return;
        }
        QueueItem head = queue.get(0);
        if (!isManagedYandexItem(head) || head.metadataPending) {
            return;
        }
        log("Ğ¤Ğ°Ğ¹Ğ» ÑƒĞ¶Ğµ Ğ¸Ğ³Ñ€Ğ°ĞµÑ‚: Ğ·Ğ°Ñ€Ğ°Ğ½ĞµĞµ Ğ³Ğ¾Ñ‚Ğ¾Ğ²Ğ»Ñ ÑĞ»ĞµĞ´ÑƒÑÑ‰Ğ¸Ğ¹ Ñ‚Ñ€ĞµĞº Ğ¯Ğ½Ğ´ĞµĞºÑ ĞœÑƒĞ·Ñ‹ĞºĞ¸");
        prepareYandexHeadIfNeeded(false);
    };

    private final Runnable localProgressTicker = new Runnable() {
        @Override
        public void run() {
            keepYandexPausedDuringLocalFile();
            updateLocalNowPlaying(false);
            if (mediaPlayer != null && currentItem != null && currentItem.kind == KIND_FILE) {
                mainHandler.postDelayed(this, 500);
            }
        }
    };
    private final Runnable yandexQueueWatcher = new Runnable() {
        @Override
        public void run() {
            if (!waitingForYandexTrackEnd) {
                return;
            }
            if (mediaPlayer != null || (currentItem != null && !isManagedYandexItem(currentItem))) {
                stopWaitingForYandexTrackEnd();
                return;
            }

            String currentToken = getYandexTrackToken();
            boolean yandexPlaying = isYandexPlaying();
            boolean tokenAppeared = waitingYandexTrackToken.isEmpty() && !currentToken.isEmpty();
            if (waitingYandexTrackToken.isEmpty() && !currentToken.isEmpty()) {
                waitingYandexTrackToken = currentToken;
            }

            boolean trackChanged = !waitingYandexTrackToken.isEmpty()
                    && !currentToken.isEmpty()
                    && !waitingYandexTrackToken.equals(currentToken);
            boolean transitionObserved = trackChanged || (promotePreparedWhenTokenAppears && tokenAppeared);
            if (transitionObserved && yandexAutomationRunning) {
                mainHandler.postDelayed(this, 350);
                return;
            }
            if (transitionObserved && preparedYandexItem != null && !yandexPlaying) {
                // Opening a deep link can replace MediaSession metadata while playback is still
                // paused. Do not consume the PartyCast item until Yandex confirms PLAYING.
                mainHandler.postDelayed(this, 350);
                return;
            }
            if (transitionObserved) {
                onYandexTrackChanged(currentToken);
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
            log("Ğ¤Ğ°Ğ¹Ğ» Ğ½Ğµ Ğ½Ğ°Ğ¹Ğ´ĞµĞ½, Ğ¿Ñ€Ğ¾Ğ¿ÑƒÑĞºĞ°Ñ");
            return;
        }

        String[] metadata = readFileMetadata(file, title);
        QueueItem item = QueueItem.file(metadata[0], metadata[1], file);
        queue.add(item);
        log("Ğ”Ğ¾Ğ±Ğ°Ğ²Ğ»ĞµĞ½Ğ¾ Ğ² Ğ¾Ñ‡ĞµÑ€ĞµĞ´ÑŒ: " + item.title);
        notifyQueueChanged();

        dispatchQueue();
    }

    public void enqueueLink(String url) {
        if (!OnlineLinkHelper.looksLikeHttpUrl(url)) {
            log("Ğ¡ÑÑ‹Ğ»ĞºĞ° Ğ½Ğµ Ğ¿Ğ¾Ñ…Ğ¾Ğ¶Ğ° Ğ½Ğ° http/https URL, Ğ¿Ñ€Ğ¾Ğ¿ÑƒÑĞºĞ°Ñ: " + url);
            return;
        }

        QueueItem unresolved = QueueItem.link(url);
        if (unresolved.service == OnlineLinkHelper.SERVICE_YANDEX_MUSIC && yandexQueueAutomationEnabled) {
            unresolved.title = "ĞŸÑ€Ğ¾Ğ²ĞµÑ€ÑĞµĞ¼ ÑÑÑ‹Ğ»ĞºÑƒ Ğ¯Ğ½Ğ´ĞµĞºÑ ĞœÑƒĞ·Ñ‹ĞºĞ¸â€¦";
            unresolved.metadataPending = true;
            queue.add(unresolved);
            log("ĞŸÑ€Ğ¾Ğ²ĞµÑ€ÑÑ ÑÑÑ‹Ğ»ĞºÑƒ Ğ¯Ğ½Ğ´ĞµĞºÑ ĞœÑƒĞ·Ñ‹ĞºĞ¸ Ğ¸ Ğ¿Ğ¾Ğ»ÑƒÑ‡Ğ°Ñ Ğ½Ğ°Ğ·Ğ²Ğ°Ğ½Ğ¸Ğµ Ñ‚Ñ€ĞµĞºĞ°â€¦");
            notifyQueueChanged();
            YandexMusicBridge.resolveTrackMetadata(url, (valid, title, artist, normalizedUrl, message) ->
                    mainHandler.post(() -> {
                        if (!valid) {
                            queue.remove(unresolved);
                            log("Ğ¡ÑÑ‹Ğ»ĞºĞ° Ğ¾Ñ‚ĞºĞ»Ğ¾Ğ½ĞµĞ½Ğ°: " + message);
                            notifyQueueChanged();
                            dispatchQueue();
                            return;
                        }
                        unresolved.title = title;
                        unresolved.artist = artist == null ? "" : artist;
                        unresolved.url = normalizedUrl;
                        unresolved.metadataPending = false;
                        log("Ğ”Ğ¾Ğ±Ğ°Ğ²Ğ»ĞµĞ½Ğ¾ Ğ² Ğ¾Ñ‡ĞµÑ€ĞµĞ´ÑŒ: " + title
                                + (artist == null || artist.trim().isEmpty() ? "" : " â€” " + artist));
                        if (message != null && !message.trim().isEmpty()) {
                            log("Ğ¯.ĞœÑƒĞ·Ñ‹ĞºĞ° API: " + message);
                        }
                        notifyQueueChanged();
                        dispatchQueue();
                    }));
            return;
        }

        queue.add(unresolved);
        log("Ğ¡ÑÑ‹Ğ»ĞºĞ° Ğ´Ğ¾Ğ±Ğ°Ğ²Ğ»ĞµĞ½Ğ° Ğ² Ğ¾Ñ‡ĞµÑ€ĞµĞ´ÑŒ PartyCast: " + unresolved.title);
        notifyQueueChanged();
        dispatchQueue();
    }

    public void setYandexQueueAutomationEnabled(boolean enabled) {
        yandexQueueAutomationEnabled = enabled;
        log("Ğ ĞµĞ¶Ğ¸Ğ¼ Ğ¯Ğ½Ğ´ĞµĞºÑ ĞœÑƒĞ·Ñ‹ĞºĞ¸: " + (enabled
                ? "ÑĞ¾Ñ…Ñ€Ğ°Ğ½ÑÑ‚ÑŒ ÑÑÑ‹Ğ»ĞºĞ¸ Ğ² PartyCast Ğ¸ Ğ³Ğ¾Ñ‚Ğ¾Ğ²Ğ¸Ñ‚ÑŒ Ğ² Ğ¯Ğ½Ğ´ĞµĞºÑ ĞœÑƒĞ·Ñ‹ĞºĞµ Ñ‚Ğ¾Ğ»ÑŒĞºĞ¾ ÑĞ»ĞµĞ´ÑƒÑÑ‰Ğ¸Ğ¹ Ñ‚Ñ€ĞµĞº"
                : "Ğ¾Ñ‚ĞºÑ€Ñ‹Ğ²Ğ°Ñ‚ÑŒ ÑÑÑ‹Ğ»ĞºĞ¸ ĞºĞ°Ğº Ğ¾Ğ±Ñ‹Ñ‡Ğ½Ñ‹Ğµ Ğ²Ğ½ĞµÑˆĞ½Ğ¸Ğµ ÑÑÑ‹Ğ»ĞºĞ¸"));
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
        } else if (PartyProtocol.CMD_REPREPARE.equals(command)) {
            reprepareYandexTrack(queueIndex);
        } else if (PartyProtocol.CMD_PREVIOUS.equals(command)) {
            previous();
        } else if (command != null && command.startsWith(PartyProtocol.CMD_MOVE_TO_PREFIX)) {
            try {
                int target = Integer.parseInt(command.substring(PartyProtocol.CMD_MOVE_TO_PREFIX.length()));
                moveTo(queueIndex, target);
            } catch (NumberFormatException e) {
                log("ĞĞµĞºĞ¾Ñ€Ñ€ĞµĞºÑ‚Ğ½Ğ°Ñ ĞºĞ¾Ğ¼Ğ°Ğ½Ğ´Ğ° Ğ¿ĞµÑ€ĞµĞ¼ĞµÑ‰ĞµĞ½Ğ¸Ñ: " + command);
            }
        } else {
            log("ĞĞµĞ¸Ğ·Ğ²ĞµÑÑ‚Ğ½Ğ°Ñ ĞºĞ¾Ğ¼Ğ°Ğ½Ğ´Ğ° ÑƒĞ¿Ñ€Ğ°Ğ²Ğ»ĞµĞ½Ğ¸Ñ: " + command);
        }
    }

    public void skip() {
        if (isManagedYandexItem(currentItem)) {
            log("ĞŸÑ€Ğ¾Ğ¿ÑƒÑĞº Ñ‚ĞµĞºÑƒÑ‰ĞµĞ³Ğ¾ Ñ‚Ñ€ĞµĞºĞ° Ğ¯Ğ½Ğ´ĞµĞºÑ ĞœÑƒĞ·Ñ‹ĞºĞ¸ Ğ¸Ğ· Ğ¾Ñ‡ĞµÑ€ĞµĞ´Ğ¸ PartyCast");
            rememberCurrentItem();
            currentItem = null;
            if (preparedYandexItem != null && !queue.isEmpty() && queue.get(0) == preparedYandexItem) {
                YandexMusicBridge.next(context);
                waitForYandexTrackEnd();
            } else if (!queue.isEmpty() && queue.get(0).kind == KIND_FILE) {
                YandexMusicBridge.pause(context);
                resumeYandexAfterLocalQueue = true;
                playNext();
            } else if (!queue.isEmpty()) {
                prepareYandexHeadIfNeeded(true);
                waitForYandexTrackEnd();
            } else {
                stopWaitingForYandexTrackEnd();
                YandexMusicBridge.next(context);
                notifyQueueChanged();
            }
            return;
        }

        if (currentItem != null && currentItem.kind == KIND_FILE) {
            log("ĞŸÑ€Ğ¾Ğ¿ÑƒÑĞº Ñ‚ĞµĞºÑƒÑ‰ĞµĞ³Ğ¾ Ğ°ÑƒĞ´Ğ¸Ğ¾Ñ„Ğ°Ğ¹Ğ»Ğ° PartyCast");
            finishLocalFileAndAdvance(true);
            return;
        }

        if (activeAutomaticYandexTrack != null
                || (currentItem == null && mediaPlayer == null && isYandexPlaying())) {
            String title = activeAutomaticYandexTrack == null
                    ? "Ğ°ĞºÑ‚Ğ¸Ğ²Ğ½Ñ‹Ğ¹ Ñ‚Ñ€ĞµĞº Ğ¯Ğ½Ğ´ĞµĞºÑ ĞœÑƒĞ·Ñ‹ĞºĞ¸"
                    : activeAutomaticYandexTrack.title;
            waitingYandexTrackTokenó»¶‰ËkºwµçU¹‘•‘™Ñ•ÉAÉ•Á…É…Ñ¥½¸€ôÑÉÕ”ì(€€€€€€€€€€€±½œ ‹B“BÃBçBìƒBßBÃBËB×FF#FGBôèƒBÛBÓFƒBóB×FBÃBÓBÃB÷B÷F/BÔƒFBïB×BÓFF;F'B×BäƒFFF/BïBëBàƒBÿB×FB×BĞƒBËBûBßBËFBÃFBûBğƒBÈƒB¿B÷BÓB×BëFƒBsFBßF/BëFˆ¤ì(€€€€€€€€€€€¹½Ñ¥™åEÕ•Õ•¡…¹• ¤ì(€€€€€€€€€€€É•ÑÕÉ¸ì(€€€€€€€ô(€€€€€€€¥˜€¡¡•…¹­¥¹€ôô-%9}%1¤ì(€€€€€€€€€€€Á±…å9•áĞ ¤ì(€€€€€€€€€€€É•ÑÕÉ¸ì(€€€€€€€ô(€€€€€€€¥˜€ …¥Í5…¹…•‘e…¹‘•á%Ñ•´¡¡•…¤¤ì(€€€€€€€€€€€Á±…å9•áĞ ¤ì(€€€€€€€€€€€É•ÑÕÉ¸ì(€€€€€€€ô((€€€€€€€¥˜€¡ÁÉ•Á…É•‘e…¹‘•á%Ñ•´€ôô¡•…€˜˜€…å…¹‘•áÕÑ½µ…Ñ¥½¹IÕ¹¹¥¹œ¤ì(€€€€€€€€€€€É•ÍÕµ•MÕÍÁ•¹‘•‘e…¹‘•à¡™…±Í”¤ì(€€€€€€€ô•±Í”¥˜€¡å…¹‘•áÕÑ½µ…Ñ¥½¹IÕ¹¹¥¹œ¤ì(€€€€€€€€€€€É•ÍÕµ•MÕÍÁ•¹‘•‘™Ñ•ÉAÉ•Á…É…Ñ¥½¸€ôÑÉÕ”ì(€€€€€€€€€€€±½œ ‹B“BÃBçBìƒBßBÃBËB×FF#FGBôèƒBßBÃBëBÃB÷FBãBËBÃF8ƒBÿBûBÓBÏBûFBûBËBëFƒFBïB×BÓFF;F'B×BÏBøƒFFB×BëBÀƒB¿B÷BÓB×BëFƒBsFBßF/BëBàˆ¤ì(€€€€€€€€€€€¹½Ñ¥™åEÕ•Õ•¡…¹• ¤ì(€€€€€€€ô•±Í”ì(€€€€€€€€€€€É•ÍÕµ•MÕÍÁ•¹‘•‘™Ñ•ÉAÉ•Á…É…Ñ¥½¸€ôÑÉÕ”ì(€€€€€€€€€€€™…¥±•‘e…¹‘•á%Ñ•´€ô¹Õ±°ì(€€€€€€€€€€€ÁÉ•Á…É•e…¹‘•á!•…‘%™9••‘•¡™…±Í”¤ì(€€€€€€€ô(€€€ô((€€€ÁÉ¥Ù…Ñ”Ù½¥É•ÍÕµ•MÕÍÁ•¹‘•‘e…¹‘•à¡‰½½±•…¸É•ÑÉåAÉ•Á…É…Ñ¥½¸¤ì(€€€€€€€Y¥ÉÑÕ…±e…¹‘•áQÉ…¬É•ÍÕµ•€ôÍÕÍÁ•¹‘•‘e…¹‘•áQÉ…¬ì(€€€€€€€¥˜€¡É•ÍÕµ•€ôô¹Õ±°¤ì(€€€€€€€€€€€‘¥ÍÁ…Ñ¡EÕ•Õ” ¤ì(€€€€€€€€€€€É•ÑÕÉ¸ì(€€€€€€€ô((€€€€€€€ÍÕÍÁ•¹‘•‘e…¹‘•áQÉ…¬€ô¹Õ±°ì(€€€€€€€…Ñ¥Ù•ÕÑ½µ…Ñ¥e…¹‘•áQÉ…¬€ôÉ•ÍÕµ•ì(€€€€€€€É•ÍÕµ•e…¹‘•á™Ñ•É1½…±EÕ•Õ”€ô™…±Í”ì(€€€€€€€É•ÍÕµ•MÕÍÁ•¹‘•‘™Ñ•ÉAÉ•Á…É…Ñ¥½¸€ô™…±Í”ì(€€€€€€€å…¹‘•áI•Á…ÕÍ•‘ÕÉ¥¹¥±”€ô™…±Í”ì(€€€€€€€İ…¥Ñ¥¹e…¹‘•áQÉ…­Q½­•¸€ôÉ•ÍÕµ•¹Ñ½­•¸¹¥ÍµÁÑä ¤€ü•Ñe…¹‘•áQÉ…­Q½­•¸ ¤€èÉ•ÍÕµ•¹Ñ½­•¸ì(€€€€€€€¹½Ñ¥™åEÕ•Õ•¡…¹• ¤ì(€€€€€€€e…¹‘•á5ÕÍ¥	É¥‘”¹Á±…ä¡½¹Ñ•áĞ¤ì(€€€€€€€±½œ ‹BKBûBßBûBÇB÷BûBËBïF?F8ƒBÃBËFBûFFB×BèƒB¿B÷BÓB×BëFƒBsFBßF/BëBàè€ˆ€¬É•ÍÕµ•¹Ñ¥Ñ±”¤ì(€€€€€€€İ…¥Ñ½Ée…¹‘•áQÉ…­¹ ¤ì((€€€€€€€µ…¥¹!…¹‘±•È¹Á½ÍÑ•±…å•  ¤€´øì(€€€€€€€€€€€MÑÉ¥¹œÑ½­•¸€ô•Ñe…¹‘•áQÉ…­Q½­•¸ ¤ì(€€€€€€€€€€€¥˜€¡ÁÉ•Á…É•‘e…¹‘•á%Ñ•´€„ô¹Õ±°€˜˜¥Íe…¹‘•áA±…å¥¹œ ¤€˜˜ÕÉÉ•¹Ñe…¹‘•á5…Ñ¡•Ì¡ÁÉ•Á…É•‘e…¹‘•á%Ñ•´¤(€€€€€€€€€€€€€€€€€€€€˜˜€…Ñ½­•¸¹¥ÍµÁÑä ¤€˜˜€…Ñ½­•¸¹•ÅÕ…±Ì¡É•ÍÕµ•¹Ñ½­•¸¤¤ì(€€€€€€€€€€€€€€€±½œ ‹B¿B÷BÓB×BëFƒBsFBßF/BëBÀƒBßBÃBÿFFFBãBïBÀƒBÿBûBÓBÏBûFBûBËBïB×B÷B÷F/BäƒFFB×BèƒBËBóB×FFBøƒBÃBËFBûBÿBûBÓBÇBûFBÀìƒFBãB÷FFBûB÷BãBßBãFFF8ƒBûFB×FB×BÓF0ˆ¤ì(€€€€€€€€€€€€€€€½¹e…¹‘•áQÉ…­¡…¹•¡Ñ½­•¸¤ì(€€€€€€€€€€€ô(€€€€€€€ô°€ÄÈÀÁ0¤ì((€€€€€€€¥˜€¡É•ÑÉåAÉ•Á…É…Ñ¥½¸€˜˜€…ÅÕ•Õ”¹¥ÍµÁÑä ¤€˜˜¥Í5…¹…•‘e…¹‘•á%Ñ•´¡ÅÕ•Õ”¹•Ğ À¤¤¤ì(€€€€€€€€€€€™…¥±•‘e…¹‘•á%Ñ•´€ô¹Õ±°ì(€€€€€€€€€€€µ…¥¹!…¹‘±•È¹Á½ÍÑ•±…å•  ¤€´øÁÉ•Á…É•e…¹‘•á!•…‘%™9••‘•¡™…±Í”¤°€ÄØÀÁ0¤ì(€€€€€€€ô(€€€ô((€€€ÁÉ¥Ù…Ñ”Ù½¥­••Áe…¹‘•áA…ÕÍ•‘ÕÉ¥¹1½…±¥±” ¤ì(€€€€€€€¥˜€¡µ•‘¥…A±…å•È€ôô¹Õ±°ñğÕÉÉ•¹Ñ%Ñ•´€ôô¹Õ±°ñğÕÉÉ•¹Ñ%Ñ•´¹­¥¹€„ô-%9}%1ñğÍÕÍÁ•¹‘•‘e…¹‘•áQÉ…¬€ôô¹Õ±°¤ì(€€€€€€€€€€€É•ÑÕÉ¸ì(€€€€€€€ô(€€€€€€€¥˜€¡¥Íe…¹‘•áA±…å¥¹œ ¤¤ì(€€€€€€€€€€€e…¹‘•á5ÕÍ¥	É¥‘”¹Á…ÕÍ”¡½¹Ñ•áĞ¤ì(€€€€€€€€€€€¥˜€ …å…¹‘•áI•Á…ÕÍ•‘ÕÉ¥¹¥±”¤ì(€€€€€€€€€€€€€€€å…¹‘•áI•Á…ÕÍ•‘ÕÉ¥¹¥±”€ôÑÉÕ”ì(€€€€€€€€€€€€€€€±½œ ‹B¿B÷BÓB×BëFƒBsFBßF/BëBÀƒBÿBûBÿF/FBÃBïBÃFF0ƒBÿFBûBÓBûBïBÛBãFF0ƒBËBøƒBËFB×BóF<ƒFBÃBçBïBÀƒŠPƒBËBûBßBËFBÃF'BÃF8ƒB×FDƒB÷BÀƒBÿBÃFBßFˆ¤ì(€€€€€€€€€€€ô(€€€€€€€ô(€€€ô(4(€€€ÁÉ¥Ù…Ñ”Ù½¥Á…ÕÍ•e…¹‘•á	•™½É•1½…±¥±•%™9••‘• ¤ì4(€€€€€€€¥˜€ …É•ÍÕµ•e…¹‘•á™Ñ•É1½…±EÕ•Õ”€˜˜¥Íe…¹‘•áA±…å¥¹œ ¤¤ì4(€€€€€€€€€€€e…¹‘•á5ÕÍ¥	É¥‘”¹Á…ÕÍ”¡½¹Ñ•áĞ¤ì4(€€€€€€€€€€€É•ÍÕµ•e…¹‘•á™Ñ•É1½…±EÕ•Õ”€ôÑÉÕ”ì4(€€€€€€€€€€€±½œ ‹B¿B÷BÓB×BëFƒBsFBßF/BëBÀƒBãBÏFBÃBïBÀƒBÓBøƒBïBûBëBÃBïF3B÷BûBÏBøƒFBÃBçBïBÀèƒBÿBûFFBÃBËBãBìƒB×FDƒB÷BÀƒBÿBÃFBßFƒBàƒBËB×FB÷FƒBÿBûFBïBÔƒBûFB×FB×BÓBàA…ÉÑå…ÍĞˆ¤ì4(€€€€€€€ô4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”Ù½¥É•ÍÕµ•e…¹‘•á%™9••‘•¡MÑÉ¥¹œÉ•…Í½¸¤ì4(€€€€€€€¥˜€¡É•ÍÕµ•e…¹‘•á™Ñ•É1½…±EÕ•Õ”¤ì4(€€€€€€€€€€€É•ÍÕµ•e…¹‘•á™Ñ•É1½…±EÕ•Õ”€ô™…±Í”ì4(€€€€€€€€€€€e…¹‘•á5ÕÍ¥	É¥‘”¹Á±…ä¡½¹Ñ•áĞ¤ì4(€€€€€€€€€€€±½œ¡É•…Í½¸¤ì4(€€€€€€€ô4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”Ù½¥ÍÑ…ÉÑ1½…±AÉ½É•ÍÍUÁ‘…Ñ•Ì ¤ì4(€€€€€€€µ…¥¹!…¹‘±•È¹É•µ½Ù•…±±‰…­Ì¡±½…±AÉ½É•ÍÍQ¥­•È¤ì4(€€€€€€€ÕÁ‘…Ñ•1½…±9½İA±…å¥¹œ¡™…±Í”¤ì4(€€€€€€€µ…¥¹!…¹‘±•È¹Á½ÍÑ•±…å•¡±½…±AÉ½É•ÍÍQ¥­•È°€ÔÀÀ¤ì4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”Ù½¥ÍÑ½Á1½…±AÉ½É•ÍÍUÁ‘…Ñ•Ì¡‰½½±•…¸±•…ÉU¤¤ì4(€€€€€€€µ…¥¹!…¹‘±•È¹É•µ½Ù•…±±‰…­Ì¡±½…±AÉ½É•ÍÍQ¥­•È¤ì4(€€€€€€€¥˜€¡±•…ÉU¤¤ì4(€€€€€€€€€€€±¥ÍÑ•¹•È¹½¹1½…±9½İA±…å¥¹¡…¹• ‹BoBûBëBÃBïF3B÷BÃF<¿BÿB×FB×BÓBÃB÷B÷BÃF<ƒBóFBßF/BëBÀƒFB×BçFBÃFƒB÷BÔƒBãBÏFBÃB×Fˆ°€À°€À°™…±Í”¤ì4(€€€€€€€ô4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”Ù½¥ÕÁ‘…Ñ•1½…±9½İA±…å¥¹œ¡‰½½±•…¸™½É•±•…È¤ì4(€€€€€€€¥˜€¡™½É•±•…Èñğµ•‘¥…A±…å•È€ôô¹Õ±°ñğÕÉÉ•¹Ñ%Ñ•´€ôô¹Õ±°ñğÕÉÉ•¹Ñ%Ñ•´¹­¥¹€„ô-%9}%1¤ì4(€€€€€€€€€€€±¥ÍÑ•¹•È¹½¹1½…±9½İA±…å¥¹¡…¹• ‹BoBûBëBÃBïF3B÷BÃF<¿BÿB×FB×BÓBÃB÷B÷BÃF<ƒBóFBßF/BëBÀƒFB×BçFBÃFƒB÷BÔƒBãBÏFBÃB×Fˆ°€À°€À°™…±Í”¤ì4(€€€€€€€€€€€É•ÑÕÉ¸ì4(€€€€€€€ô4(4(€€€€€€€ÑÉäì4(€€€€€€€€€€€¥¹Ğ‘ÕÉ…Ñ¥½¸€ôµ•‘¥…A±…å•È¹•ÑÕÉ…Ñ¥½¸ ¤ì4(€€€€€€€€€€€¥¹ĞÁ½Í¥Ñ¥½¸€ôµ•‘¥…A±…å•È¹•ÑÕÉÉ•¹ÑA½Í¥Ñ¥½¸ ¤ì4(€€€€€€€€€€€‰½½±•…¸Á±…å¥¹œ€ôµ•‘¥…A±…å•È¹¥ÍA±…å¥¹œ ¤ì4(€€€€€€€€€€€MÑÉ¥¹œÑ•áĞ€ôÕÉÉ•¹Ñ%Ñ•´¹Ñ¥Ñ±”4(€€€€€€€€€€€€€€€€€€€€¬€‰q¸ˆ€¬™½Éµ…ÑQ¥µ”¡Á½Í¥Ñ¥½¸¤€¬€ˆ€¼€ˆ€¬™½Éµ…ÑQ¥µ”¡‘ÕÉ…Ñ¥½¸¤4(€€€€€€€€€€€€€€€€€€€€¬€¡Á±…å¥¹œ€ü€ˆˆ€è€ˆ€ƒ
Ü€ƒBÿBÃFBßBÀˆ¤ì4(€€€€€€€€€€€±¥ÍÑ•¹•È¹½¹1½…±9½İA±…å¥¹¡…¹•¡Ñ•áĞ°Á½Í¥Ñ¥½¸°‘ÕÉ…Ñ¥½¸°ÑÉÕ”¤ì4(€€€€€€€ô…Ñ €¡%±±•…±MÑ…Ñ•á•ÁÑ¥½¸”¤ì4(€€€€€€€€€€€±¥ÍÑ•¹•È¹½¹1½…±9½İA±…å¥¹¡…¹• ‹BoBûBëBÃBïF3B÷BÃF<¿BÿB×FB×BÓBÃB÷B÷BÃF<ƒBóFBßF/BëBÀƒFB×BçFBÃFƒB÷BÔƒBãBÏFBÃB×Fˆ°€À°€À°™…±Í”¤ì4(€€€€€€€ô4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”ÍÑ…Ñ¥ŒMÑÉ¥¹œ™½Éµ…ÑQ¥µ”¡¥¹ĞµÌ¤ì4(€€€€€€€¥˜€¡µÌ€ğ€À¤ì4(€€€€€€€€€€€µÌ€ô€Àì4(€€€€€€€ô4(€€€€€€€¥¹ĞÑ½Ñ…±M•½¹‘Ì€ôµÌ€¼€ÄÀÀÀì4(€€€€€€€¥¹Ğµ¥¹ÕÑ•Ì€ôÑ½Ñ…±M•½¹‘Ì€¼€ØÀì4(€€€€€€€¥¹ĞÍ•½¹‘Ì€ôÑ½Ñ…±M•½¹‘Ì€”€ØÀì4(€€€€€€€É•ÑÕÉ¸MÑÉ¥¹œ¹™½Éµ…Ğ¡©…Ù„¹ÕÑ¥°¹1½…±”¹UL°€ˆ•è”ÀÉˆ°µ¥¹ÕÑ•Ì°Í•½¹‘Ì¤ì4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”‰½½±•…¸É•ÅÕ•ÍÑÕ‘¥½½ÕÌ ¤ì4(€€€€€€€¥˜€¡…Õ‘¥½5…¹…•È€ôô¹Õ±°¤ì4(€€€€€€€€€€€É•ÑÕÉ¸ÑÉÕ”ì4(€€€€€€€ô4(4(€€€€€€€¥˜€¡	Õ¥±¹YIM%=8¹M-}%9P€øô	Õ¥±¹YIM%=9}=L¹<¤ì4(€€€€€€€€€€€Õ‘¥½ÑÑÉ¥‰ÕÑ•Ì…ÑÑÉÌ€ô¹•ÜÕ‘¥½ÑÑÉ¥‰ÕÑ•Ì¹	Õ¥±‘•È ¤4(€€€€€€€€€€€€€€€€€€€€¹Í•ÑUÍ…”¡Õ‘¥½ÑÑÉ¥‰ÕÑ•Ì¹UM}5%¤4(€€€€€€€€€€€€€€€€€€€€¹Í•Ñ½¹Ñ•¹ÑQåÁ”¡Õ‘¥½ÑÑÉ¥‰ÕÑ•Ì¹=9Q9Q}QeA}5UM%¤4(€€€€€€€€€€€€€€€€€€€€¹‰Õ¥± ¤ì4(€€€€€€€€€€€™½ÕÍI•ÅÕ•ÍĞ€ô¹•ÜÕ‘¥½½ÕÍI•ÅÕ•ÍĞ¹	Õ¥±‘•È¡Õ‘¥½5…¹…•È¹U%==UM}%8¤4(€€€€€€€€€€€€€€€€€€€€¹Í•ÑÕ‘¥½ÑÑÉ¥‰ÕÑ•Ì¡…ÑÑÉÌ¤4(€€€€€€€€€€€€€€€€€€€€¹Í•Ñ=¹Õ‘¥½½ÕÍ¡…¹•1¥ÍÑ•¹•È¡™½ÕÍ¡…¹•1¥ÍÑ•¹•È°µ…¥¹!…¹‘±•È¤4(€€€€€€€€€€€€€€€€€€€€¹‰Õ¥± ¤ì4(€€€€€€€€€€€¥¹ĞÉ•ÍÕ±Ğ€ô…Õ‘¥½5…¹…•È¹É•ÅÕ•ÍÑÕ‘¥½½ÕÌ¡™½ÕÍI•ÅÕ•ÍĞ¤ì4(€€€€€€€€€€€É•ÑÕÉ¸É•ÍÕ±Ğ€ôôÕ‘¥½5…¹…•È¹U%==UM}IEUMQ}I9Qì4(€€€€€€€ô•±Í”ì4(€€€€€€€€€€€¥¹ĞÉ•ÍÕ±Ğ€ô…Õ‘¥½5…¹…•È¹É•ÅÕ•ÍÑÕ‘¥½½ÕÌ 4(€€€€€€€€€€€€€€€€€€€™½ÕÍ¡…¹•1¥ÍÑ•¹•È°4(€€€€€€€€€€€€€€€€€€€Õ‘¥½5…¹…•È¹MQI5}5UM%°4(€€€€€€€€€€€€€€€€€€€Õ‘¥½5…¹…•È¹U%==UM}%84(€€€€€€€€€€€€¤ì4(€€€€€€€€€€€É•ÑÕÉ¸É•ÍÕ±Ğ€ôôÕ‘¥½5…¹…•È¹U%==UM}IEUMQ}I9Qì4(€€€€€€€ô4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”Ù½¥…‰…¹‘½¹Õ‘¥½½ÕÌ ¤ì4(€€€€€€€¥˜€¡…Õ‘¥½5…¹…•È€ôô¹Õ±°¤ì4(€€€€€€€€€€€É•ÑÕÉ¸ì4(€€€€€€€ô4(4(€€€€€€€¥˜€¡	Õ¥±¹YIM%=8¹M-}%9P€øô	Õ¥±¹YIM%=9}=L¹<€˜˜™½ÕÍI•ÅÕ•ÍĞ€„ô¹Õ±°¤ì4(€€€€€€€€€€€…Õ‘¥½5…¹…•È¹…‰…¹‘½¹Õ‘¥½½ÕÍI•ÅÕ•ÍĞ¡™½ÕÍI•ÅÕ•ÍĞ¤ì4(€€€€€€€€€€€™½ÕÍI•ÅÕ•ÍĞ€ô¹Õ±°ì4(€€€€€€€ô•±Í”ì4(€€€€€€€€€€€…Õ‘¥½5…¹…•È¹…‰…¹‘½¹Õ‘¥½½ÕÌ¡™½ÕÍ¡…¹•1¥ÍÑ•¹•È¤ì4(€€€€€€€ô4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”Ù½¥½¹Õ‘¥½½ÕÍ¡…¹”¡¥¹Ğ™½ÕÍ¡…¹”¤ì4(€€€€€€€¥˜€¡µ•‘¥…A±…å•È€ôô¹Õ±°¤ì4(€€€€€€€€€€€É•ÑÕÉ¸ì4(€€€€€€€ô4(4(€€€€€€€¥˜€¡™½ÕÍ¡…¹”€ôôÕ‘¥½5…¹…•È¹U%==UM}1=ML¤ì4(€€€€€€€€€€€±½œ ‹BCFBÓBãBûFBûBëFFƒBÿBûFB×FF?BôèƒFFBÃBËBïF8ƒBóFBßF/BëFƒB÷BÀƒBÿBÃFBßFˆ¤ì4(€€€€€€€€€€€Á…ÕÍ•½É½ÕÍ1½ÍÌ ¤ì4(€€€€€€€ô•±Í”¥˜€¡™½ÕÍ¡…¹”€ôôÕ‘¥½5…¹…•È¹U%==UM}1=MM}QI9M%9P¤ì4(€€€€€€€€€€€±½œ ‹BKFB×BóB×B÷B÷BÃF<ƒBÿBûFB×FF<ƒBÃFBÓBãBûFBûBëFFBÀèƒBÿBÃFBßBÀˆ¤ì4(€€€€€€€€€€€Á…ÕÍ•½É½ÕÍ1½ÍÌ ¤ì4(€€€€€€€ô•±Í”¥˜€¡™½ÕÍ¡…¹”€ôôÕ‘¥½5…¹…•È¹U%==UM}1=MM}QI9M%9Q}9}U,¤ì4(€€€€€€€€€€€±½œ ‹B‡BãFFB×BóB÷F/BäƒBßBËFBèƒBÿBûBËB×FFƒBóFBßF/BëBàèƒBËFB×BóB×B÷B÷BøƒBÿFBãBÏBïFF#BÃF8ˆ¤ì4(€€€€€€€€€€€ÑÉäì4(€€€€€€€€€€€€€€€µ•‘¥…A±…å•È¹Í•ÑY½±Õµ” À¸ÈÕ˜°€À¸ÈÕ˜¤ì4(€€€€€€€€€€€ô…Ñ €¡%±±•…±MÑ…Ñ•á•ÁÑ¥½¸¥¹½É•¤ì4(€€€€€€€€€€€ô4(€€€€€€€ô•±Í”¥˜€¡™½ÕÍ¡…¹”€ôôÕ‘¥½5…¹…•È¹U%==UM}%8¤ì4(€€€€€€€€€€€±½œ ‹BCFBÓBãBûFBûBëFFƒBËBûBßBËFBÃF'FGBôˆ¤ì4(€€€€€€€€€€€ÑÉäì4(€€€€€€€€€€€€€€€µ•‘¥…A±…å•È¹Í•ÑY½±Õµ” Ä¸Á˜°€Ä¸Á˜¤ì4(€€€€€€€€€€€€€€€¥˜€¡Á…ÕÍ•‘	•…ÕÍ•½ÕÍ1½ÍÌ¤ì4(€€€€€€€€€€€€€€€€€€€µ•‘¥…A±…å•È¹ÍÑ…ÉĞ ¤ì4(€€€€€€€€€€€€€€€€€€€Á…ÕÍ•‘	•…ÕÍ•½ÕÍ1½ÍÌ€ô™…±Í”ì4(€€€€€€€€€€€€€€€€€€€ÍÑ…ÉÑ1½…±AÉ½É•ÍÍUÁ‘…Ñ•Ì ¤ì4(€€€€€€€€€€€€€€€ô4(€€€€€€€€€€€ô…Ñ €¡%±±•…±MÑ…Ñ•á•ÁÑ¥½¸¥¹½É•¤ì4(€€€€€€€€€€€ô4(€€€€€€€ô4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”Ù½¥Á…ÕÍ•½É½ÕÍ1½ÍÌ ¤ì4(€€€€€€€ÑÉäì4(€€€€€€€€€€€¥˜€¡µ•‘¥…A±…å•È€„ô¹Õ±°€˜˜µ•‘¥…A±…å•È¹¥ÍA±…å¥¹œ ¤¤ì4(€€€€€€€€€€€€€€€µ•‘¥…A±…å•È¹Á…ÕÍ” ¤ì4(€€€€€€€€€€€€€€€Á…ÕÍ•‘	•…ÕÍ•½ÕÍ1½ÍÌ€ôÑÉÕ”ì4(€€€€€€€€€€€€€€€ÕÁ‘…Ñ•1½…±9½İA±…å¥¹œ¡™…±Í”¤ì4(€€€€€€€€€€€ô4(€€€€€€€ô…Ñ €¡%±±•…±MÑ…Ñ•á•ÁÑ¥½¸¥¹½É•¤ì4(€€€€€€€ô4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”Ù½¥É•±•…Í•ÕÉÉ•¹ÑA±…å‰…¬¡‰½½±•…¸…‰…¹‘½¹½ÕÌ¤ì4(€€€€€€€ÍÑ½Á1½…±AÉ½É•ÍÍUÁ‘…Ñ•Ì¡ÑÉÕ”¤ì4(€€€€€€€¥˜€¡µ•‘¥…A±…å•È€„ô¹Õ±°¤ì4(€€€€€€€€€€€ÑÉäì4(€€€€€€€€€€€€€€€µ•‘¥…A±…å•È¹É•Í•Ğ ¤ì4(€€€€€€€€€€€€€€€µ•‘¥…A±…å•È¹É•±•…Í” ¤ì4(€€€€€€€€€€€ô…Ñ €¡%±±•…±MÑ…Ñ•á•ÁÑ¥½¸¥¹½É•¤ì4(€€€€€€€€€€€ô4(€€€€€€€€€€€µ•‘¥…A±…å•È€ô¹Õ±°ì4(€€€€€€€ô4(€€€€€€€ÕÉÉ•¹Ñ%Ñ•´€ô¹Õ±°ì4(€€€€€€€Á…ÕÍ•‘	•…ÕÍ•½ÕÍ1½ÍÌ€ô™…±Í”ì4(€€€€€€€¥˜€¡…‰…¹‘½¹½ÕÌ¤ì4(€€€€€€€€€€€…‰…¹‘½¹Õ‘¥½½ÕÌ ¤ì4(€€€€€€€ô4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”Ù½¥É•µ•µ‰•ÉÕÉÉ•¹Ñ%Ñ•´ ¤ì4(€€€€€€€¥˜€¡ÕÉÉ•¹Ñ%Ñ•´€ôô¹Õ±°¤ì4(€€€€€€€€€€€É•ÑÕÉ¸ì4(€€€€€€€ô4(€€€€€€€¡¥ÍÑ½Éä¹…‘¡ÕÉÉ•¹Ñ%Ñ•´¤ì4(€€€€€€€¥˜€¡¡¥ÍÑ½Éä¹Í¥é” ¤€ø€ÔÀ¤ì4(€€€€€€€€€€€¡¥ÍÑ½Éä¹É•µ½Ù” À¤ì4(€€€€€€€ô4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”Ù½¥±½œ¡MÑÉ¥¹œµ•ÍÍ…”¤ì4(€€€€€€€±¥ÍÑ•¹•È¹½¹1½œ¡µ•ÍÍ…”¤ì4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”Ù½¥¹½Ñ¥™åEÕ•Õ•¡…¹• ¤ì(€€€€€€€MÑÉ¥¹	Õ¥±‘•ÈÍˆ€ô¹•ÜMÑÉ¥¹	Õ¥±‘•È ¤ì4(€€€€€€€¥˜€¡ÕÉÉ•¹Ñ%Ñ•´€„ô¹Õ±°¤ì4(€€€€€€€€€€€Íˆ¹…ÁÁ•¹ ‹B‡B×BçFBÃFè€ˆ¤¹…ÁÁ•¹¡ÕÉÉ•¹Ñ%Ñ•´¹Ñ¥Ñ±”¤ì4(€€€€€€€€€€€¥˜€¡ÕÉÉ•¹Ñ%Ñ•´¹­¥¹€ôô-%9}1%9,¤ì4(€€€€€€€€€€€€€€€Íˆ¹…ÁÁ•¹ ‰q»B‹BãBüèƒBûB÷BïBÃBçBô·FFF/BïBëBÀ€¼ƒBËB÷B×F#B÷BãBäƒFB×FBËBãFˆ¤ì4(€€€€€€€€€€€ô4(€€€€€€€€€€€Íˆ¹…ÁÁ•¹ q¸œ¤ì4(€€€€€€€ô•±Í”¥˜€¡¥Íe…¹‘•áA±…å¥¹œ ¤¤ì4(€€€€€€€€€€€Íˆ¹…ÁÁ•¹ ‹B‡B×BçFBÃFèƒB¿B÷BÓB×BëFƒBsFBßF/BëBÀƒBãBÏFBÃB×FƒBËBøƒBËB÷B×F#B÷B×BğƒBÿFBãBïBûBÛB×B÷BãBáq¸ˆ¤ì4(€€€€€€€€€€€¥˜€ …ÅÕ•Õ”¹¥ÍµÁÑä ¤¤ì4(€€€€€€€€€€€€€€€Íˆ¹…ÁÁ•¹ ‹B{FB×FB×BÓF0A…ÉÑå…ÍĞƒBÛBÓFGFƒBàƒB÷BÔƒBÿB×FB×BÇBãBËBÃB×FƒFB×BëFF'BãBäƒFFB×Béq¸ˆ¤ì4(€€€€€€€€€€€ô4(€€€€€€€ô•±Í”ì4(€€€€€€€€€€€Íˆ¹…ÁÁ•¹ ‹B‡B×BçFBÃFƒB÷BãFB×BÏBøƒB÷BÔƒBãBÏFBÃB×F	q¸ˆ¤ì4(€€€€€€€ô4(4(€€€€€€€Íˆ¹…ÁÁ•¹ ‰q»B¿B÷BÓB×BëF·FFF/BïBëBàè€ˆ¤¹…ÁÁ•¹¡å…¹‘•áEÕ•Õ•ÕÑ½µ…Ñ¥½¹¹…‰±•(€€€€€€€€€€€€€€€€ü€‹BÈƒBûFB×FB×BÓBàA…ÉÑå…ÍĞìƒBÈƒB¿B÷BÓB×BëFƒBsFBßF/BëBÔƒBÏBûFBûBËBãFFF<ƒFBûBïF3BëBøƒFBïB×BÓFF;F'BãBäƒFFB×Bèˆ(€€€€€€€€€€€€€€€€è€‹BëBÃBèƒBûBÇF/FB÷F/BÔƒBËB÷B×F#B÷BãBÔƒFFF/BïBëBàˆ¤ì(€€€€€€€¥˜€¡É•ÍÕµ•e…¹‘•á™Ñ•É1½…±EÕ•Õ”¤ì4(€€€€€€€€€€€Íˆ¹…ÁÁ•¹ ‰q»BBûFBïBÔƒBïBûBëBÃBïF3B÷F/FƒFBÃBçBïBûBÈèƒBËB×FB÷FFF0ƒB¿B÷BÓB×BëFƒBsFBßF/BëFƒFB×FB×BÜ5•‘¥…M•ÍÍ¥½¸ˆ¤ì4(€€€€€€€ô4(4(€€€€€€€Íˆ¹…ÁÁ•¹ ‰q¹q»B{FB×FB×BÓF0ƒBûBÛBãBÓBÃB÷BãF<A…ÉÑå…ÍĞéq¸ˆ¤ì4(€€€€€€€¥˜€¡ÅÕ•Õ”¹¥ÍµÁÑä ¤¤ì4(€€€€€€€€€€€Íˆ¹…ÁÁ•¹ ‹ŠPƒBÿFFFBøˆ¤ì4(€€€€€€€ô•±Í”ì4(€€€€€€€€€€€™½È€¡¥¹Ğ¤€ô€Àì¤€ğÅÕ•Õ”¹Í¥é” ¤ì¤¬¬¤ì4(€€€€€€€€€€€€€€€EÕ•Õ•%Ñ•´¥Ñ•´€ôÅÕ•Õ”¹•Ğ¡¤¤ì4(€€€€€€€€€€€€€€€Íˆ¹…ÁÁ•¹¡¤€¬€Ä¤¹…ÁÁ•¹ ˆ¸€ˆ¤¹…ÁÁ•¹¡¥Ñ•´¹Ñ¥Ñ±”¤ì4(€€€€€€€€€€€€€€€¥˜€¡¥Ñ•´¹­¥¹€ôô-%9}1%9,¤ì4(€€€€€€€€€€€€€€€€€€€Íˆ¹…ÁÁ•¹ ˆm±¥¹­tˆ¤ì4(€€€€€€€€€€€€€€€ô4(€€€€€€€€€€€€€€€Íˆ¹…ÁÁ•¹ q¸œ¤ì4(€€€€€€€€€€€ô4(€€€€€€€ô4(€€€€€€€1¥ÍĞñEÕ•Õ•¹ÑÉäø•¹ÑÉ¥•Ì€ô¹•ÜÉÉ…å1¥ÍĞğø ¤ì(€€€€€€€¥˜€¡ÍÕÍÁ•¹‘•‘e…¹‘•áQÉ…¬€„ô¹Õ±°¤ì(€€€€€€€€€€€•¹ÑÉ¥•Ì¹…‘¡¹•ÜEÕ•Õ•¹ÑÉä (€€€€€€€€€€€€€€€€€€€ÍÕÍÁ•¹‘•‘e…¹‘•áQÉ…¬¹Ñ¥Ñ±”°(€€€€€€€€€€€€€€€€€€€ÍÕÍÁ•¹‘•‘e…¹‘•áQÉ…¬¹…ÉÑ¥ÍĞ°(€€€€€€€€€€€€€€€€€€€€‹B¿B÷BÓB×BëFƒBsFBßF/BëBÀˆ°(€€€€€€€€€€€€€€€€€€€™…±Í”°(€€€€€€€€€€€€€€€€€€€€´Ä°(€€€€€€€€€€€€€€€€€€€€‹BBûFBïBÔƒFBÃBçBïBÀƒ
ÜƒBCBËFBûBÿBûBÓBÇBûF ˆ(€€€€€€€€€€€€¤¤ì(€€€€€€€ô(€€€€€€€™½È€¡¥¹ĞÅÕ•Õ•%¹‘•à€ô€ÀìÅÕ•Õ•%¹‘•à€ğÅÕ•Õ”¹Í¥é” ¤ìÅÕ•Õ•%¹‘•à¬¬¤ì(€€€€€€€€€€€EÕ•Õ•%Ñ•´¥Ñ•´€ôÅÕ•Õ”¹•Ğ¡ÅÕ•Õ•%¹‘•à¤ì(€€€€€€€€€€€MÑÉ¥¹œÍ½ÕÉ”ì(€€€€€€€€€€€MÑÉ¥¹œ…ÉÑ¥ÍĞ€ô¥Ñ•´¹…ÉÑ¥ÍĞì(€€€€€€€€€€€¥˜€¡¥Ñ•´¹­¥¹€ôô-%9}%1¤ì(€€€€€€€€€€€€€€€Í½ÕÉ”€ô€‹B“BÃBçBìˆì(€€€€€€€€€€€ô•±Í”ì(€€€€€€€€€€€€€€€Í½ÕÉ”€ô=¹±¥¹•1¥¹­!•±Á•È¹‘•Ñ•ÑM•ÉÙ¥•9…µ”¡¥Ñ•´¹ÕÉ°¤ì(€€€€€€€€€€€ô(€€€€€€€€€€€MÑÉ¥¹œ‰…‘”ì(€€€€€€€€€€€¥˜€¡¥Ñ•´¹µ•Ñ…‘…Ñ…A•¹‘¥¹œ¤ì(€€€€€€€€€€€€€€€‰…‘”€ô€‹BFBûBËB×FBëBÀƒFFF/BïBëBàˆì(€€€€€€€€€€€ô•±Í”¥˜€¡¥Ñ•´¹ÁÉ•Á…É…Ñ¥½¹MÑ…Ñ”€ôôAIAIQ%=9}IU99%9¤ì(€€€€€€€€€€€€€€€‰…‘”€ô€‹BBûBÓBÏBûFBûBËBëBÃŠ˜ˆì(€€€€€€€€€€€ô•±Í”¥˜€¡¥Ñ•´¹ÁÉ•Á…É…Ñ¥½¹MÑ…Ñ”€ôôAIAIQ%=9}Id¤ì(€€€€€€€€€€€€€€€‰…‘”€ô€‹BBûBÓBÏBûFBûBËBïB×Bôˆì(€€€€€€€€€€€ô•±Í”¥˜€¡¥Ñ•´¹ÁÉ•Á…É…Ñ¥½¹MÑ…Ñ”€ôôAIAIQ%=9}%1¤ì(€€€€€€€€€€€€€€€‰…‘”€ô€‹BwBÔƒBÿBûBÓBÏBûFBûBËBïB×Bôˆì(€€€€€€€€€€€ô•±Í”¥˜€¡¥Í5…¹…•‘e…¹‘•á%Ñ•´¡¥Ñ•´¤¤ì(€€€€€€€€€€€€€€€‰…‘”€ô€‹B{BÛBãBÓBÃB×FƒBÿBûBÓBÏBûFBûBËBëBàˆì(€€€€€€€€€€€ô•±Í”ì(€€€€€€€€€€€€€€€‰…‘”€ô€ˆˆì(€€€€€€€€€€€ô(€€€€€€€€€€€•¹ÑÉ¥•Ì¹…‘¡¹•ÜEÕ•Õ•¹ÑÉä (€€€€€€€€€€€€€€€€€€€±•…¹¥ÍÁ±…åQ¥Ñ±”¡¥Ñ•´¹Ñ¥Ñ±”°Í½ÕÉ”¤°(€€€€€€€€€€€€€€€€€€€…ÉÑ¥ÍĞ°(€€€€€€€€€€€€€€€€€€€Í½ÕÉ”°(€€€€€€€€€€€€€€€€€€€ÑÉÕ”°(€€€€€€€€€€€€€€€€€€€ÅÕ•Õ•%¹‘•à°(€€€€€€€€€€€€€€€€€€€‰…‘”(€€€€€€€€€€€€¤¤ì(€€€€€€€ô(€€€€€€€±¥ÍÑ•¹•È¹½¹EÕ•Õ•¡…¹•¡Íˆ¹Ñ½MÑÉ¥¹œ ¤°½±±•Ñ¥½¹Ì¹Õ¹µ½‘¥™¥…‰±•1¥ÍĞ¡•¹ÑÉ¥•Ì¤¤ì(€€€ô((€€€ÁÉ¥Ù…Ñ”ÍÑ…Ñ¥ŒMÑÉ¥¹mtÉ•…‘¥±•5•Ñ…‘…Ñ„¡¥±”™¥±”°MÑÉ¥¹œ™…±±‰…­Q¥Ñ±”¤ì(€€€€€€€MÑÉ¥¹œÑ¥Ñ±”€ô™…±±‰…­Q¥Ñ±”€ôô¹Õ±°ñğ™…±±‰…­Q¥Ñ±”¹ÑÉ¥´ ¤¹¥ÍµÁÑä ¤€ü™¥±”¹•Ñ9…µ” ¤€è™…±±‰…­Q¥Ñ±”¹ÑÉ¥´ ¤ì(€€€€€€€MÑÉ¥¹œ…ÉÑ¥ÍĞ€ô€ˆˆì(€€€€€€€5•‘¥…5•Ñ…‘…Ñ…I•ÑÉ¥•Ù•ÈÉ•ÑÉ¥•Ù•È€ô¹•Ü5•‘¥…5•Ñ…‘…Ñ…I•ÑÉ¥•Ù•È ¤ì(€€€€€€€ÑÉäì(€€€€€€€€€€€É•ÑÉ¥•Ù•È¹Í•Ñ…Ñ…M½ÕÉ”¡™¥±”¹•Ñ‰Í½±ÕÑ•A…Ñ  ¤¤ì(€€€€€€€€€€€MÑÉ¥¹œµ•Ñ…‘…Ñ…Q¥Ñ±”€ôÉ•ÑÉ¥•Ù•È¹•áÑÉ…Ñ5•Ñ…‘…Ñ„¡5•‘¥…5•Ñ…‘…Ñ…I•ÑÉ¥•Ù•È¹5QQ}-e}Q%Q1¤ì(€€€€€€€€€€€MÑÉ¥¹œµ•Ñ…‘…Ñ…ÉÑ¥ÍĞ€ôÉ•ÑÉ¥•Ù•È¹•áÑÉ…Ñ5•Ñ…‘…Ñ„¡5•‘¥…5•Ñ…‘…Ñ…I•ÑÉ¥•Ù•È¹5QQ}-e}IQ%MP¤ì(€€€€€€€€€€€¥˜€¡µ•Ñ…‘…Ñ…Q¥Ñ±”€„ô¹Õ±°€˜˜€…µ•Ñ…‘…Ñ…Q¥Ñ±”¹ÑÉ¥´ ¤¹¥ÍµÁÑä ¤¤ì(€€€€€€€€€€€€€€€Ñ¥Ñ±”€ôµ•Ñ…‘…Ñ…Q¥Ñ±”¹ÑÉ¥´ ¤ì(€€€€€€€€€€€ô(€€€€€€€€€€€¥˜€¡µ•Ñ…‘…Ñ…ÉÑ¥ÍĞ€„ô¹Õ±°€˜˜€…µ•Ñ…‘…Ñ…ÉÑ¥ÍĞ¹ÑÉ¥´ ¤¹¥ÍµÁÑä ¤¤ì(€€€€€€€€€€€€€€€…ÉÑ¥ÍĞ€ôµ•Ñ…‘…Ñ…ÉÑ¥ÍĞ¹ÑÉ¥´ ¤ì(€€€€€€€€€€€ô(€€€€€€€ô…Ñ €¡á•ÁÑ¥½¸¥¹½É•¤ì(€€€€€€€ô™¥¹…±±äì(€€€€€€€€€€€ÑÉäì(€€€€€€€€€€€€€€€É•ÑÉ¥•Ù•È¹É•±•…Í” ¤ì(€€€€€€€€€€€ô…Ñ €¡á•ÁÑ¥½¸¥¹½É•¤ì(€€€€€€€€€€€ô(€€€€€€€ô(€€€€€€€É•ÑÕÉ¸¹•ÜMÑÉ¥¹muíÑ¥Ñ±”°…ÉÑ¥ÍÑôì(€€€ô((€€€ÁÉ¥Ù…Ñ”ÍÑ…Ñ¥ŒMÑÉ¥¹œ±•…¹¥ÍÁ±…åQ¥Ñ±”¡MÑÉ¥¹œÙ…±Õ”°MÑÉ¥¹œÍ½ÕÉ”¤ì(€€€€€€€MÑÉ¥¹œÑ¥Ñ±”€ôÙ…±Õ”€ôô¹Õ±°€ü€‹B‹FB×Bèˆ€èÙ…±Õ”¹ÑÉ¥´ ¤ì(€€€€€€€¥˜€¡Í½ÕÉ”€„ô¹Õ±°€˜˜€…Í½ÕÉ”¹¥ÍµÁÑä ¤€˜˜Ñ¥Ñ±”¹ÍÑ…ÉÑÍ]¥Ñ ¡Í½ÕÉ”€¬€ˆè€ˆ¤¤ì(€€€€€€€€€€€Ñ¥Ñ±”€ôÑ¥Ñ±”¹ÍÕ‰ÍÑÉ¥¹œ¡Í½ÕÉ”¹±•¹Ñ  ¤€¬€È¤ì(€€€€€€€ô(€€€€€€€¥¹ĞÅÕ•Éä€ôÑ¥Ñ±”¹¥¹‘•á=˜ œüœ¤ì(€€€€€€€¥˜€¡ÅÕ•Éä€ø€À¤ì(€€€€€€€€€€€Ñ¥Ñ±”€ôÑ¥Ñ±”¹ÍÕ‰ÍÑÉ¥¹œ À°ÅÕ•Éä¤ì(€€€€€€€ô(€€€€€€€¥˜€¡Ñ¥Ñ±”¹±•¹Ñ  ¤€ø€ÔĞ¤ì(€€€€€€€€€€€Ñ¥Ñ±”€ôÑ¥Ñ±”¹ÍÕ‰ÍÑÉ¥¹œ À°€ÔÄ¤€¬€‹Š˜ˆì(€€€€€€€ô(€€€€€€€É•ÑÕÉ¸Ñ¥Ñ±”¹¥ÍµÁÑä ¤€ü€‹B‹FB×Bèˆ€èÑ¥Ñ±”ì(€€€ô)ô(