package com.example.partycastmvp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.InputType;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    public static final String EXTRA_AUTOMATION_STATUS = "com.example.partycastmvp.EXTRA_AUTOMATION_STATUS";

    private static final int PICK_AUDIO_REQUEST = 101;
    private static final int PICK_HOST_TEST_AUDIO_REQUEST = 102;

    private static final String PREFS = "partycast_settings";
    private static final String PREF_TEST_MODE = "test_mode_enabled";
    private static final String PREF_TECHNICAL_MODE = "technical_mode_enabled";

    private static final int COLOR_BG = Color.rgb(246, 248, 245);
    private static final int COLOR_CARD = Color.WHITE;
    private static final int COLOR_TEXT = Color.rgb(27, 38, 48);
    private static final int COLOR_MUTED = Color.rgb(105, 118, 128);
    private static final int COLOR_GREEN = Color.rgb(32, 158, 78);
    private static final int COLOR_SOFT_GREEN = Color.rgb(236, 248, 239);
    private static final int COLOR_LINE = Color.rgb(224, 230, 226);

    private static final int MODERN_PURPLE = Color.rgb(118, 81, 201);
    private static final int MODERN_PURPLE_DARK = Color.rgb(86, 58, 154);
    private static final int MODERN_LAVENDER = Color.rgb(238, 232, 250);
    private static final int MODERN_GREEN = Color.rgb(120, 168, 91);
    private static final int MODERN_GREEN_SOFT = Color.rgb(237, 244, 232);
    private static final int MODERN_CREAM = Color.rgb(247, 241, 231);
    private static final int MODERN_PAPER = Color.rgb(255, 252, 247);
    private static final int MODERN_TEXT = Color.rgb(40, 37, 33);
    private static final int MODERN_MUTED = Color.rgb(116, 110, 102);
    private static final int MODERN_DIVIDER = Color.rgb(230, 221, 209);
    private static final int MODERN_DARK_BG = Color.rgb(31, 29, 34);
    private static final int MODERN_DARK_PAPER = Color.rgb(43, 40, 47);
    private static final int MODERN_DARK_TEXT = Color.rgb(248, 244, 238);
    private static final int MODERN_DARK_MUTED = Color.rgb(190, 182, 194);
    private static final int MODERN_DARK_DIVIDER = Color.rgb(70, 65, 75);

    private FrameLayout screenContainer;
    private ScrollView scrollView;
    private LinearLayout root;

    private EditText hostIpInput;
    private EditText roomCodeInput;
    private EditText linkInput;
    private EditText hostTestLinkInput;
    private EditText queueIndexInput;
    private TextView statusText;
    private TextView queueText;
    private TextView nowPlayingText;
    private ProgressBar nowPlayingProgress;
    private TextView hostInfoText;
    private TextView modernHostStatusChip;
    private Button modernHostToggleButton;
    private LinearLayout modernQueueContainer;
    private TextView modernQueueEmpty;
    private TextView modernNowTitle;
    private TextView modernNowArtist;
    private TextView modernNowTimeStart;
    private TextView modernNowTimeEnd;
    private Button modernPlayButton;
    private TextView modernListenerStatusChip;
    private TextView technicalAccessibilityStatusText;
    private CheckBox yandexQueueAutomationCheckBox;
    private CheckBox testModeCheckBox;

    private ServerController serverController;
    private HostPlayer hostPlayer;
    private HostDiscovery hostDiscovery;

    private SharedPreferences prefs;
    private final StringBuilder logBuffer = new StringBuilder();
    private String currentRoomCode = "";
    private String lastHostIp = "";
    private boolean technicalModeEnabled = false;
    private String currentScreen = "start";
    private final List<HostPlayer.QueueEntry> latestQueueEntries = new ArrayList<>();
    private final List<HostPlayer.QueueEntry> listenerQueueEntries = new ArrayList<>();
    private String latestNowTitle = "";
    private String latestNowArtist = "";
    private String latestNowSource = "";
    private boolean latestNowPlaying = false;
    private boolean localNowVisible = false;
    private int latestNowProgressMs = 0;
    private int latestNowDurationMs = 0;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean listenerPolling = false;
    private final ExecutorService fileTransferExecutor = Executors.newSingleThreadExecutor();

    private final Runnable mediaStateTicker = new Runnable() {
        @Override
        public void run() {
            refreshModernNowPlayingFromYandex();
            if ("host".equals(currentScreen) && !technicalModeEnabled) {
                uiHandler.postDelayed(this, 1000L);
            }
        }
    };

    private final Runnable listenerQueuePoller = new Runnable() {
        @Override
        public void run() {
            if (!listenerPolling || !"listener".equals(currentScreen) || technicalModeEnabled) {
                return;
            }
            requestListenerQueueSnapshot();
            uiHandler.postDelayed(this, 1500L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        technicalModeEnabled = prefs.getBoolean(PREF_TECHNICAL_MODE, prefs.getBoolean(PREF_TEST_MODE, false));
        currentRoomCode = generateRoomCode();
        buildUi();
        NetworkUtils.clearProcessNetworkBinding(this);

        hostPlayer = new HostPlayer(this, new HostPlayer.Listener() {
            @Override
            public void onLog(String message) {
                log(message);
            }

            @Override
            public void onQueueChanged(String queueTextValue, List<HostPlayer.QueueEntry> entries) {
                if (queueText != null) {
                    queueText.setText(queueTextValue);
                }
                latestQueueEntries.clear();
                latestQueueEntries.addAll(entries);
                publishQueueSnapshot();
                if (modernQueueContainer != null && "host".equals(currentScreen) && !technicalModeEnabled) {
                    renderModernQueue(modernQueueContainer, latestQueueEntries, true);
                }
            }

            @Override
            public void onLocalNowPlayingChanged(String text, int progressMs, int durationMs, boolean visible) {
                updateLocalNowPlaying(text, progressMs, durationMs, visible);
            }
        });

        File receiveDir = new File(getCacheDir(), "received_audio");
        serverController = new ServerController(receiveDir, this::getRoomCode, new ServerController.Listener() {
            @Override
            public void onLog(String message) {
                log(message);
            }

            @Override
            public void onFileReceived(String title, File file) {
                hostPlayer.enqueueFile(title, file);
            }

            @Override
            public void onLinkReceived(String url) {
                hostPlayer.enqueueLink(url);
            }

            @Override
            public void onControlReceived(String command, int queueIndex) {
                hostPlayer.applyCommand(command, queueIndex);
            }

            @Override
            public void onServerStateChanged(boolean running) {
                updateHostStatus(running);
            }
        });

        hostDiscovery = new HostDiscovery(this, new HostDiscovery.Listener() {
            @Override
            public void onLog(String message) {
                log(message);
            }

            @Override
            public void onHostFound(String hostIp, int tcpPort, String label) {
                lastHostIp = hostIp;
                if (hostIpInput != null) {
                    hostIpInput.setText(hostIp);
                }
                log("Найден хост: " + label + " / " + hostIp + ":" + tcpPort);
                toast("Хост найден: " + hostIp);
                updateModernListenerStatus(true, "Подключён");
                startListenerQueuePolling();
            }
        });

        log("Готово. Выберите роль: хост запускает комнату, слушатель отправляет музыку.");
        readSharedText(getIntent());
        readAutomationStatus(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        NetworkUtils.clearProcessNetworkBinding(this);
        if ("host".equals(currentScreen) && !technicalModeEnabled) {
            startMediaStateTicker();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readSharedText(intent);
        readAutomationStatus(intent);
    }

    @Override
    public void onBackPressed() {
        if (!"start".equals(currentScreen)) {
            showStartScreen();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacksAndMessages(null);
        if (hostDiscovery != null) {
            hostDiscovery.stopResponder();
        }
        if (serverController != null) {
            serverController.stop();
        }
        if (hostPlayer != null) {
            hostPlayer.stopAll();
        }
        fileTransferExecutor.shutdownNow();
    }

    private void buildUi() {
        screenContainer = new FrameLayout(this);
        scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(root, matchWrap());
        screenContainer.addView(scrollView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(screenContainer);
        showStartScreen();
    }

    private void showStartScreen() {
        currentScreen = "start";
        if (technicalModeEnabled) {
            showTechnicalStartScreen();
            applyTechnicalSystemBars();
        } else {
            showModernStartScreen();
        }
    }

    private void showHostScreen() {
        currentScreen = "host";
        if (technicalModeEnabled) {
            showTechnicalHostScreen();
            applyTechnicalSystemBars();
        } else {
            showModernHostScreen();
        }
    }

    private void showListenerScreen() {
        currentScreen = "listener";
        if (technicalModeEnabled) {
            showTechnicalListenerScreen();
            applyTechnicalSystemBars();
        } else {
            showModernListenerScreen();
        }
    }

    private void showSettingsScreen() {
        currentScreen = "settings";
        if (technicalModeEnabled) {
            showTechnicalSettingsScreen();
            applyTechnicalSystemBars();
        } else {
            showModernSettingsScreen();
        }
    }

    private void showTechnicalStartScreen() {
        String room = getRoomCode();
        clearScreen();

        TextView logo = new TextView(this);
        logo.setText("♪");
        logo.setTextSize(44f);
        logo.setTextColor(COLOR_GREEN);
        logo.setGravity(Gravity.CENTER);
        root.addView(logo, matchWrap());

        TextView title = title("PartyCast", 30f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView subtitle = subtitle("Делитесь музыкой на одной волне");
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(4), 0, dp(24));
        root.addView(subtitle, matchWrap());

        LinearLayout hostCard = actionCard("ХОСТ", "Запустить комнату и управлять воспроизведением", "👥", true);
        hostCard.setOnClickListener(v -> showHostScreen());
        root.addView(hostCard, cardParams());

        LinearLayout listenerCard = actionCard("СЛУШАТЕЛЬ", "Подключиться к комнате и отправлять треки", "🎧", false);
        listenerCard.setOnClickListener(v -> showListenerScreen());
        root.addView(listenerCard, cardParams());

        LinearLayout codeCard = card();
        codeCard.addView(sectionLabel("КОД КОМНАТЫ"), matchWrap());
        roomCodeInput = editText("например 7F3K9Q", InputType.TYPE_CLASS_TEXT);
        roomCodeInput.setText(room);
        codeCard.addView(roomCodeInput, matchWrap());
        codeCard.addView(secondaryButton("Сгенерировать новый код", v -> {
            currentRoomCode = generateRoomCode();
            roomCodeInput.setText(currentRoomCode);
            log("Новый код комнаты: " + currentRoomCode);
        }), matchWrap());
        root.addView(codeCard, cardParams());

        Button settings = secondaryButton("⚙  НАСТРОЙКИ", v -> showSettingsScreen());
        root.addView(settings, centeredButtonParams());

        addLogPanel(false);
    }

    private void showTechnicalHostScreen() {
        String room = getRoomCode();
        clearScreen();
        addTopBar("ХОСТ", serverController != null && serverController.isRunning() ? "• АКТИВЕН" : "• ОСТАНОВЛЕН");

        LinearLayout codeCard = card();
        codeCard.addView(sectionLabel("КОД ДЛЯ ПОДКЛЮЧЕНИЯ"), matchWrap());

        LinearLayout codeRow = row();
        roomCodeInput = editText("код комнаты", InputType.TYPE_CLASS_TEXT);
        roomCodeInput.setText(room);
        roomCodeInput.setTextSize(26f);
        roomCodeInput.setTypeface(Typeface.DEFAULT_BOLD);
        roomCodeInput.setGravity(Gravity.CENTER);
        codeRow.addView(roomCodeInput, weightParams(1f));
        codeRow.addView(secondaryButton("КОПИЯ", v -> copyRoomCode()), wrapParams());
        codeCard.addView(codeRow, matchWrap());
        root.addView(codeCard, cardParams());

        LinearLayout statusCard = card();
        statusCard.addView(sectionLabel("СТАТУС"), matchWrap());
        hostInfoText = bodyText("");
        statusCard.addView(hostInfoText, matchWrap());
        statusCard.addView(primaryButton("Запустить комнату", v -> startHost()), matchWrap());
        statusCard.addView(secondaryButton("Остановить хост", v -> stopHost()), matchWrap());
        root.addView(statusCard, cardParams());
        updateHostStatus(serverController != null && serverController.isRunning());

        if (technicalModeEnabled) {
            addHostTestCard();
        }

        LinearLayout queueCard = card();
        queueCard.addView(sectionHeader("ОЧЕРЕДЬ", "управление"), matchWrap());
        queueIndexInput = editText("номер трека", InputType.TYPE_CLASS_NUMBER);
        queueIndexInput.setText("1");
        queueCard.addView(queueIndexInput, matchWrap());
        queueCard.addView(buttonRow(
                smallButton("↑", v -> hostPlayer.moveUp(readQueueIndex())),
                smallButton("↓", v -> hostPlayer.moveDown(readQueueIndex())),
                smallButton("Удалить", v -> hostPlayer.removeFromQueue(readQueueIndex()))
        ), matchWrap());
        queueText = bodyText("Сейчас ничего не играет\n\nОчередь ожидания:\n— пусто");
        queueText.setTextIsSelectable(true);
        queueCard.addView(queueText, matchWrap());
        root.addView(queueCard, cardParams());

        LinearLayout playingCard = card();
        playingCard.addView(sectionLabel("СЕЙЧАС ИГРАЕТ"), matchWrap());
        nowPlayingText = bodyText("Локальная/переданная музыка сейчас не играет");
        playingCard.addView(nowPlayingText, matchWrap());
        nowPlayingProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        nowPlayingProgress.setMax(1000);
        nowPlayingProgress.setProgress(0);
        playingCard.addView(nowPlayingProgress, matchWrap());
        playingCard.addView(buttonRow(
                smallButton("◀", v -> hostPlayer.previous()),
                roundPlayButton(v -> hostPlayer.pauseOrResume()),
                smallButton("▶", v -> hostPlayer.skip())
        ), matchWrap());
        playingCard.addView(secondaryButton("Очистить очередь", v -> hostPlayer.stopAll()), matchWrap());
        root.addView(playingCard, cardParams());

        addBottomNav("host");
        addLogPanel(true);

        if (hostPlayer != null) {
            hostPlayer.refreshQueue();
        }
    }

    private void addHostTestCard() {
        LinearLayout testCard = card();
        testCard.addView(sectionHeader("ТЕХНИЧЕСКИЙ РЕЖИМ", "тестовая отправка хоста"), matchWrap());
        testCard.addView(bodyText("Хост может сам добавить файл или ссылку в очередь, чтобы проверить воспроизведение без второго телефона."), matchWrap());

        hostTestLinkInput = editText("https://music.yandex.ru/...", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        testCard.addView(hostTestLinkInput, matchWrap());
        testCard.addView(primaryButton("Добавить ссылку в очередь хоста", v -> addHostTestLink()), matchWrap());
        testCard.addView(secondaryButton("Выбрать аудиофайл на этом телефоне", v -> openAudioPicker(PICK_HOST_TEST_AUDIO_REQUEST)), matchWrap());
        root.addView(testCard, cardParams());
    }

    private void showTechnicalListenerScreen() {
        String room = getRoomCode();
        clearScreen();
        addTopBar("СЛУШАТЕЛЬ", lastHostIp.isEmpty() ? "• НЕ ПОДКЛЮЧЕН" : "• ПОДКЛЮЧЕН");

        LinearLayout connectCard = card();
        connectCard.addView(sectionLabel("ПОДКЛЮЧЕНИЕ"), matchWrap());
        roomCodeInput = editText("код комнаты", InputType.TYPE_CLASS_TEXT);
        roomCodeInput.setText(room);
        connectCard.addView(roomCodeInput, matchWrap());

        hostIpInput = editText("IP хоста, например 192.168.1.35", InputType.TYPE_CLASS_TEXT);
        hostIpInput.setText(lastHostIp);
        connectCard.addView(hostIpInput, matchWrap());
        connectCard.addView(primaryButton("Найти хост", v -> hostDiscovery.discoverByNsd(getRoomCode())), matchWrap());
        connectCard.addView(secondaryButton("Сканировать сеть", v -> hostDiscovery.scanLocalSubnet(getRoomCode())), matchWrap());
        connectCard.addView(secondaryButton("Подключиться к Hotspot-хосту", v -> hostDiscovery.connectToHotspotHost(getRoomCode())), matchWrap());
        root.addView(connectCard, cardParams());

        LinearLayout sendCard = card();
        sendCard.addView(sectionLabel("ОТПРАВИТЬ МУЗЫКУ"), matchWrap());
        sendCard.addView(listAction("▣", "ОТПРАВИТЬ ФАЙЛ", "Выберите аудиофайл на устройстве", v -> openAudioPicker(PICK_AUDIO_REQUEST)), matchWrap());
        linkInput = editText("Ссылка на трек", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        sendCard.addView(linkInput, matchWrap());
        sendCard.addView(listAction("🔗", "ОТПРАВИТЬ ССЫЛКУ", "Яндекс Музыка или другой сервис", v -> sendLinkToHost()), matchWrap());
        root.addView(sendCard, cardParams());

        LinearLayout controlCard = card();
        controlCard.addView(sectionLabel("УПРАВЛЕНИЕ ОЧЕРЕДЬЮ"), matchWrap());
        queueIndexInput = editText("номер трека", InputType.TYPE_CLASS_NUMBER);
        queueIndexInput.setText("1");
        controlCard.addView(queueIndexInput, matchWrap());
        controlCard.addView(buttonRow(
                smallButton("Назад", v -> sendControlToHost(PartyProtocol.CMD_PREVIOUS, readQueueIndex())),
                smallButton("Пауза", v -> sendControlToHost(PartyProtocol.CMD_PAUSE_RESUME, readQueueIndex())),
                smallButton("Далее", v -> sendControlToHost(PartyProtocol.CMD_SKIP, readQueueIndex()))
        ), matchWrap());
        controlCard.addView(buttonRow(
                smallButton("Выше", v -> sendControlToHost(PartyProtocol.CMD_MOVE_UP, readQueueIndex())),
                smallButton("Ниже", v -> sendControlToHost(PartyProtocol.CMD_MOVE_DOWN, readQueueIndex())),
                smallButton("Удалить", v -> sendControlToHost(PartyProtocol.CMD_REMOVE, readQueueIndex()))
        ), matchWrap());
        root.addView(controlCard, cardParams());

        addBottomNav("listener");
        addLogPanel(true);
    }

    private void showTechnicalSettingsScreen() {
        clearScreen();
        addTopBar("НАСТРОЙКИ", "");

        LinearLayout appCard = card();
        appCard.addView(sectionLabel("О ПРИЛОЖЕНИИ"), matchWrap());
        appCard.addView(bodyText("PartyCast\nВерсия " + getAppVersionLabel()), matchWrap());
        root.addView(appCard, cardParams());

        LinearLayout accessCard = card();
        accessCard.addView(sectionLabel("ДОСТУПЫ"), matchWrap());
        accessCard.addView(settingRow("▣", "Доступ к файлам и медиа", "Для отправки и тестового выбора файлов", true, null), matchWrap());
        accessCard.addView(settingRow("🔔", "Уведомления", "Показывать уведомления о треках", true, null), matchWrap());
        accessCard.addView(settingRow("♿", "Специальные возможности", "Для управления очередью Яндекс Музыки", false, v -> YandexMusicBridge.openAccessibilitySettings(this)), matchWrap());
        root.addView(accessCard, cardParams());

        LinearLayout integrationCard = card();
        integrationCard.addView(sectionLabel("ИНТЕГРАЦИЯ"), matchWrap());
        yandexQueueAutomationCheckBox = new CheckBox(this);
        yandexQueueAutomationCheckBox.setText("Яндекс Музыка: добавлять ссылки следующим треком");
        yandexQueueAutomationCheckBox.setTextColor(COLOR_TEXT);
        yandexQueueAutomationCheckBox.setChecked(hostPlayer == null || hostPlayer.isYandexQueueAutomationEnabled());
        yandexQueueAutomationCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (hostPlayer != null) {
                hostPlayer.setYandexQueueAutomationEnabled(isChecked);
            }
        });
        integrationCard.addView(yandexQueueAutomationCheckBox, matchWrap());
        integrationCard.addView(secondaryButton("Разрешить доступ к уведомлениям / MediaSession", v -> YandexMusicBridge.openNotificationListenerSettings(this)), matchWrap());
        integrationCard.addView(secondaryButton("Открыть Яндекс Музыку", v -> {
            if (!YandexMusicBridge.openYandexMusic(this)) {
                toast("Яндекс Музыка не найдена на устройстве");
            }
        }), matchWrap());
        integrationCard.addView(secondaryButton("Показать статус Яндекс Музыки", v -> log(YandexMusicBridge.status(this))), matchWrap());
        root.addView(integrationCard, cardParams());

        LinearLayout accessibilityTestCard = card();
        accessibilityTestCard.addView(sectionLabel("ДИАГНОСТИКА ACCESSIBILITY"), matchWrap());
        technicalAccessibilityStatusText = bodyText(YandexMusicBridge.accessibilityDiagnosticStatus());
        accessibilityTestCard.addView(technicalAccessibilityStatusText, matchWrap());
        accessibilityTestCard.addView(secondaryButton("Обновить статус сервиса", v -> {
            String status = YandexMusicBridge.accessibilityDiagnosticStatus();
            technicalAccessibilityStatusText.setText(status);
            log("Accessibility: " + status);
        }), matchWrap());
        accessibilityTestCard.addView(primaryButton("Запустить проверку в Яндекс Музыке", v -> {
            boolean started = YandexMusicBridge.runAccessibilityDiagnostic(this);
            if (started) {
                log("Accessibility: проверка запущена. Яндекс Музыка откроется, PartyCast прочитает её интерфейс и вернётся с отчётом без нажатий по экрану.");
            } else {
                String status = YandexMusicBridge.accessibilityDiagnosticStatus();
                technicalAccessibilityStatusText.setText(status);
                log("Accessibility: проверка не запущена. " + status);
                toast("Сервис Accessibility не подключён или уже занят");
            }
        }), matchWrap());
        accessibilityTestCard.addView(secondaryButton("Скопировать последний отчёт", v -> copyAccessibilityDiagnostic()), matchWrap());
        accessibilityTestCard.addView(bodyText("Тест не нажимает кнопки в Яндекс Музыке. Он проверяет подключение сервиса, получение событий и возможность прочитать элементы активного экрана."), matchWrap());
        root.addView(accessibilityTestCard, cardParams());

        LinearLayout playbackCard = card();
        playbackCard.addView(sectionLabel("ВОСПРОИЗВЕДЕНИЕ"), matchWrap());
        testModeCheckBox = new CheckBox(this);
        testModeCheckBox.setText("Технический режим");
        testModeCheckBox.setTextColor(COLOR_TEXT);
        testModeCheckBox.setChecked(technicalModeEnabled);
        testModeCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            technicalModeEnabled = isChecked;
            prefs.edit()
                    .putBoolean(PREF_TECHNICAL_MODE, isChecked)
                    .putBoolean(PREF_TEST_MODE, isChecked)
                    .apply();
            log(isChecked
                    ? "Технический режим включён. Доступны журнал, диагностика и тестовая отправка хоста."
                    : "Технический режим выключен.");
            if (!isChecked) {
                showSettingsScreen();
            }
        });
        playbackCard.addView(testModeCheckBox, matchWrap());
        playbackCard.addView(bodyText("Возвращает технический интерфейс, журнал и возможность добавлять тестовые треки с телефона хоста."), matchWrap());
        root.addView(playbackCard, cardParams());

        LinearLayout deviceCard = card();
        deviceCard.addView(sectionLabel("УСТРОЙСТВО"), matchWrap());
        deviceCard.addView(secondaryButton("Показать диагностику Wi‑Fi / Hotspot", v -> log(NetworkUtils.describeNetwork(this))), matchWrap());
        deviceCard.addView(secondaryButton("Открыть настройки точки доступа", v -> openHotspotSettings()), matchWrap());
        deviceCard.addView(secondaryButton("Открыть настройки Не беспокоить", v -> openDndSettings()), matchWrap());
        root.addView(deviceCard, cardParams());

        addBottomNav("settings");
        addLogPanel(true);
    }

    private void showModernStartScreen() {
        clearScreen();
        prepareModernRoot(20, 18, 20, 24);

        LinearLayout header = row();
        header.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button settings = modernIconButton("⚙", "Настройки", v -> showSettingsScreen());
        header.addView(settings, fixedParams(48, 48));
        root.addView(header, matchWrap());

        TextView title = modernTitle("PartyCast", 38f);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(20), 0, dp(4));
        root.addView(title, matchWrap());

        TextView subtitle = modernSubtitle("Музыка для вашей компании", 15f);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle, matchWrap());

        CozyRoomView roomArt = new CozyRoomView(this);
        root.addView(roomArt, marginParams(0, 16, 0, 18, 245));

        Button host = modernPrimaryButton("＋   Создать комнату", v -> showHostScreen());
        host.setTextSize(17f);
        root.addView(host, matchHeightParams(56));
        TextView hostHelp = modernSubtitle("Стать хостом и управлять музыкой", 13f);
        hostHelp.setGravity(Gravity.CENTER);
        hostHelp.setPadding(0, dp(7), 0, dp(15));
        root.addView(hostHelp, matchWrap());

        Button listener = modernSecondaryButton("♧   Присоединиться", v -> showListenerScreen());
        listener.setTextSize(16f);
        root.addView(listener, matchHeightParams(56));
        TextView listenerHelp = modernSubtitle("Подключиться и добавить трек", 13f);
        listenerHelp.setGravity(Gravity.CENTER);
        listenerHelp.setPadding(0, dp(7), 0, 0);
        root.addView(listenerHelp, matchWrap());
    }

    private void showModernHostScreen() {
        clearScreen();
        prepareModernRoot(14, 8, 14, 178);
        addModernTopBar("Комната", serverController != null && serverController.isRunning()
                ? "Активна" : "Остановлена", true);

        LinearLayout codeCard = modernCard(12);
        TextView codeLabel = modernSubtitle("Код комнаты", 11f);
        codeCard.addView(codeLabel, matchWrap());
        LinearLayout codeRow = row();
        codeRow.setGravity(Gravity.CENTER_VERTICAL);
        roomCodeInput = modernEditText("Код комнаты", InputType.TYPE_CLASS_TEXT);
        roomCodeInput.setText(getRoomCode());
        roomCodeInput.setTextSize(24f);
        roomCodeInput.setTypeface(Typeface.DEFAULT_BOLD);
        roomCodeInput.setFocusable(false);
        roomCodeInput.setBackgroundColor(Color.TRANSPARENT);
        roomCodeInput.setPadding(0, 0, dp(8), 0);
        codeRow.addView(roomCodeInput, weightNoMargin(1f));
        Button newCode = modernOutlineButton("Новый код", MODERN_GREEN, v -> {
            currentRoomCode = generateRoomCode();
            roomCodeInput.setText(currentRoomCode);
            toast("Создан новый код комнаты");
            publishQueueSnapshot();
        });
        codeRow.addView(newCode, wrapParams());
        codeCard.addView(codeRow, matchWrap());
        root.addView(codeCard, modernCardParams(8));

        boolean running = serverController != null && serverController.isRunning();
        modernHostToggleButton = modernPrimaryButton(running ? "■   Остановить комнату" : "▶   Запустить комнату", v -> toggleHost());
        root.addView(modernHostToggleButton, marginHeightParams(0, 0, 0, 10, 52));

        LinearLayout queueCard = modernCard(10);
        TextView queueTitle = modernTitle("Очередь", 17f);
        queueCard.addView(queueTitle, marginParams(2, 0, 2, 4));
        modernQueueContainer = new LinearLayout(this);
        modernQueueContainer.setOrientation(LinearLayout.VERTICAL);
        modernQueueContainer.setOnDragListener(this::handleQueueDrag);
        queueCard.addView(modernQueueContainer, matchWrap());
        root.addView(queueCard, modernCardParams(8));
        renderModernQueue(modernQueueContainer, latestQueueEntries, true);

        if (hostPlayer != null) {
            hostPlayer.refreshQueue();
        }
        addModernPlayerOverlay();
        updateHostStatus(running);
        startMediaStateTicker();
    }

    private void showModernListenerScreen() {
        clearScreen();
        prepareModernRoot(14, 8, 14, 24);
        addModernTopBar("Слушатель", lastHostIp.isEmpty() ? "Не подключён" : "Подключён", false);

        LinearLayout connectCard = modernCard(10);
        roomCodeInput = modernEditText("Код комнаты", InputType.TYPE_CLASS_TEXT);
        roomCodeInput.setText(getRoomCode());
        connectCard.addView(roomCodeInput, marginHeightParams(0, 0, 0, 7, 48));
        hostIpInput = modernEditText("IP-адрес", InputType.TYPE_CLASS_TEXT);
        hostIpInput.setText(lastHostIp);
        connectCard.addView(hostIpInput, marginHeightParams(0, 0, 0, 9, 48));
        connectCard.addView(modernPrimaryButton("Подключиться", v -> connectToEnteredHost()), matchHeightParams(50));
        Button find = modernFlatButton("Найти хоста", MODERN_GREEN, v -> findHostAutomatically());
        connectCard.addView(find, marginHeightParams(0, 3, 0, 0, 44));
        root.addView(connectCard, modernCardParams(9));

        LinearLayout sendRow = row();
        sendRow.addView(modernActionButton("▣  Выбрать файл", MODERN_GREEN, v -> openAudioPicker(PICK_AUDIO_REQUEST)), weightParams(1f));
        sendRow.addView(modernActionButton("⌁  Добавить ссылку", MODERN_GREEN, v -> showLinkDialog()), weightParams(1f));
        root.addView(sendRow, marginParams(0, 0, 0, 9));

        LinearLayout queueCard = modernCard(10);
        queueCard.addView(modernTitle("Очередь", 17f), marginParams(2, 0, 2, 4));
        modernQueueContainer = new LinearLayout(this);
        modernQueueContainer.setOrientation(LinearLayout.VERTICAL);
        modernQueueContainer.setOnDragListener(this::handleQueueDrag);
        queueCard.addView(modernQueueContainer, matchWrap());
        root.addView(queueCard, modernCardParams(8));
        renderModernQueue(modernQueueContainer, listenerQueueEntries, false);

        if (!lastHostIp.isEmpty()) {
            startListenerQueuePolling();
        }
    }

    private void showModernSettingsScreen() {
        clearScreen();
        prepareModernRoot(14, 8, 14, 26);
        addModernTopBar("Настройки", "", false);

        addModernSettingsSection("Разрешения");
        root.addView(modernValueRow("♧", "Доступ к MediaSession", "Настроить",
                v -> YandexMusicBridge.openNotificationListenerSettings(this)), matchWrap());
        root.addView(modernDivider(), matchHeightParams(1));
        root.addView(modernSettingRow("□", "Доступ к файлам", "Для отправки музыки", true, null), matchWrap());
        root.addView(modernDivider(), matchHeightParams(1));
        root.addView(modernValueRow("◇", "Специальные возможности", "Настроить",
                v -> YandexMusicBridge.openAccessibilitySettings(this)), matchWrap());

        addModernSettingsSection("Интеграции");
        root.addView(modernValueRow("♫", "Яндекс Музыка", "Открыть", v -> {
            if (!YandexMusicBridge.openYandexMusic(this)) {
                toast("Яндекс Музыка не найдена на устройстве");
            }
        }), matchWrap());

        LinearLayout automationRow = modernSettingRow(
                "↳",
                "Добавлять следующим треком",
                "Автоматизация очереди Яндекс Музыки",
                hostPlayer == null || hostPlayer.isYandexQueueAutomationEnabled(),
                null
        );
        Switch automationSwitch = (Switch) automationRow.getChildAt(automationRow.getChildCount() - 1);
        automationSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (hostPlayer != null) {
                hostPlayer.setYandexQueueAutomationEnabled(isChecked);
            }
        });
        root.addView(modernDivider(), matchHeightParams(1));
        root.addView(automationRow, matchWrap());

        addModernSettingsSection("Воспроизведение");
        root.addView(modernValueRow("◖", "Качество потоковой передачи", "Высокое", null), matchWrap());
        root.addView(modernDivider(), matchHeightParams(1));
        root.addView(modernValueRow("⇄", "Кроссфейд между треками", "3 сек", null), matchWrap());
        root.addView(modernDivider(), matchHeightParams(1));
        root.addView(modernSettingRow("↻", "Автовоспроизведение", "", true, null), matchWrap());

        addModernSettingsSection("Для разработчика");
        LinearLayout technicalRow = modernSettingRow("</>", "Технический режим", "Диагностика и тестовые функции", false, null);
        Switch technicalSwitch = (Switch) technicalRow.getChildAt(technicalRow.getChildCount() - 1);
        technicalSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isChecked) {
                return;
            }
            technicalModeEnabled = true;
            prefs.edit()
                    .putBoolean(PREF_TECHNICAL_MODE, true)
                    .putBoolean(PREF_TEST_MODE, true)
                    .apply();
            toast("Технический режим включён");
            showSettingsScreen();
        });
        root.addView(technicalRow, matchWrap());

        addModernSettingsSection("О приложении");
        root.addView(modernValueRow("ⓘ", "О PartyCast", "Версия " + getAppVersionLabel(), null), matchWrap());
        root.addView(modernDivider(), matchHeightParams(1));
        root.addView(modernValueRow("?", "Помощь и обратная связь", "", null), matchWrap());
    }

    private void clearScreen() {
        listenerPolling = false;
        uiHandler.removeCallbacks(listenerQueuePoller);
        uiHandler.removeCallbacks(mediaStateTicker);
        if (screenContainer != null) {
            while (screenContainer.getChildCount() > 1) {
                screenContainer.removeViewAt(screenContainer.getChildCount() - 1);
            }
        }
        if (root != null) {
            root.removeAllViews();
        }
        boolean modern = !technicalModeEnabled;
        int background = modern ? modernBackground() : COLOR_BG;
        scrollView.setBackgroundColor(background);
        root.setBackgroundColor(background);
        root.setPadding(dp(16), dp(18), dp(16), dp(24));
        hostIpInput = null;
        linkInput = null;
        hostTestLinkInput = null;
        queueIndexInput = null;
        statusText = null;
        queueText = null;
        nowPlayingText = null;
        nowPlayingProgress = null;
        hostInfoText = null;
        modernHostStatusChip = null;
        modernHostToggleButton = null;
        modernQueueContainer = null;
        modernQueueEmpty = null;
        modernNowTitle = null;
        modernNowArtist = null;
        modernNowTimeStart = null;
        modernNowTimeEnd = null;
        modernPlayButton = null;
        modernListenerStatusChip = null;
        technicalAccessibilityStatusText = null;
        yandexQueueAutomationCheckBox = null;
        testModeCheckBox = null;
    }

    private void prepareModernRoot(int left, int top, int right, int bottom) {
        root.setPadding(dp(left), dp(top), dp(right), dp(bottom));
        getWindow().setStatusBarColor(modernBackground());
        getWindow().setNavigationBarColor(modernBackground());
        if (isDarkMode()) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private void applyTechnicalSystemBars() {
        getWindow().setStatusBarColor(Color.rgb(128, 128, 128));
        getWindow().setNavigationBarColor(COLOR_BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    private void addModernTopBar(String titleText, String state, boolean host) {
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        Button home = modernIconButton("⌂", "На главную", v -> showStartScreen());
        bar.addView(home, fixedParams(48, 48));

        TextView title = modernTitle(titleText, 19f);
        title.setGravity(Gravity.CENTER);
        bar.addView(title, weightNoMargin(1f));

        if (state == null || state.isEmpty()) {
            View spacer = new View(this);
            bar.addView(spacer, fixedParams(48, 48));
        } else {
            TextView chip = modernStatusChip(state, "Активна".equals(state) || "Подключён".equals(state));
            if (host) {
                modernHostStatusChip = chip;
            } else {
                modernListenerStatusChip = chip;
            }
            bar.addView(chip, wrapParams());
        }
        root.addView(bar, marginHeightParams(0, 0, 0, 7, 50));
    }

    private LinearLayout modernCard(int paddingDp) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp));
        card.setBackground(rounded(modernPaper(), dp(14), modernDividerColor()));
        return card;
    }

    private TextView modernTitle(String text, float size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(modernTextColor());
        view.setTextSize(size);
        view.setTypeface(Typeface.create("sans", Typeface.BOLD));
        return view;
    }

    private TextView modernSubtitle(String text, float size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(modernMutedColor());
        view.setTextSize(size);
        return view;
    }

    private EditText modernEditText(String hint, int inputType) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setSingleLine(true);
        edit.setInputType(inputType);
        edit.setTextColor(modernTextColor());
        edit.setHintTextColor(modernMutedColor());
        edit.setTextSize(14f);
        edit.setPadding(dp(12), 0, dp(12), 0);
        edit.setBackground(rounded(modernPaper(), dp(10), modernDividerColor()));
        return edit;
    }

    private Button modernPrimaryButton(String text, View.OnClickListener listener) {
        Button button = modernBaseButton(text, listener);
        button.setTextColor(Color.WHITE);
        button.setBackground(rounded(MODERN_PURPLE, dp(12), 0));
        return button;
    }

    private Button modernSecondaryButton(String text, View.OnClickListener listener) {
        Button button = modernBaseButton(text, listener);
        button.setTextColor(modernTextColor());
        button.setBackground(rounded(modernPaper(), dp(12), modernDividerColor()));
        return button;
    }

    private Button modernOutlineButton(String text, int color, View.OnClickListener listener) {
        Button button = modernBaseButton(text, listener);
        button.setTextColor(color);
        button.setTextSize(12f);
        button.setMinHeight(dp(38));
        button.setBackground(rounded(modernPaper(), dp(9), color));
        return button;
    }

    private Button modernFlatButton(String text, int color, View.OnClickListener listener) {
        Button button = modernBaseButton(text, listener);
        button.setTextColor(color);
        button.setBackgroundColor(Color.TRANSPARENT);
        return button;
    }

    private Button modernActionButton(String text, int color, View.OnClickListener listener) {
        Button button = modernSecondaryButton(text, listener);
        button.setTextColor(color);
        button.setTextSize(13f);
        button.setMinHeight(dp(52));
        return button;
    }

    private Button modernIconButton(String glyph, String description, View.OnClickListener listener) {
        Button button = modernBaseButton(glyph, listener);
        button.setContentDescription(description);
        button.setTextColor(modernTextColor());
        button.setTextSize(25f);
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setBackgroundColor(Color.TRANSPARENT);
        return button;
    }

    private Button modernBaseButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14f);
        button.setTypeface(Typeface.create("sans", Typeface.NORMAL));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setMinHeight(dp(44));
        button.setOnClickListener(listener);
        return button;
    }

    private TextView modernStatusChip(String state, boolean positive) {
        TextView chip = modernSubtitle(state, 11f);
        chip.setGravity(Gravity.CENTER);
        chip.setTextColor(positive ? MODERN_GREEN : modernMutedColor());
        chip.setPadding(dp(10), dp(5), dp(10), dp(5));
        chip.setBackground(rounded(positive ? MODERN_GREEN_SOFT : mutedChipBackground(), dp(13), 0));
        return chip;
    }

    private void addModernSettingsSection(String title) {
        TextView label = modernTitle(title, 13f);
        label.setTextColor(MODERN_PURPLE);
        root.addView(label, marginParams(4, 16, 4, 5));
    }

    private LinearLayout modernSettingRow(String icon, String title, String helper, boolean checked, View.OnClickListener listener) {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(6), dp(2), dp(6));
        if (listener != null) {
            row.setOnClickListener(listener);
        }
        TextView iconView = modernSubtitle(icon, 20f);
        iconView.setTextColor(modernTextColor());
        iconView.setGravity(Gravity.CENTER);
        row.addView(iconView, fixedParams(38, 48));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(modernTitle(title, 14f), matchWrap());
        if (helper != null && !helper.isEmpty()) {
            texts.addView(modernSubtitle(helper, 11f), matchWrap());
        }
        row.addView(texts, weightNoMargin(1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        tintSwitch(toggle);
        if (listener != null) {
            toggle.setOnClickListener(listener);
        }
        row.addView(toggle, wrapParams());
        return row;
    }

    private LinearLayout modernValueRow(String icon, String title, String value, View.OnClickListener listener) {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(7), dp(4), dp(7));
        if (listener != null) {
            row.setOnClickListener(listener);
        }
        TextView iconView = modernSubtitle(icon, 20f);
        iconView.setTextColor(modernTextColor());
        iconView.setGravity(Gravity.CENTER);
        row.addView(iconView, fixedParams(38, 48));
        row.addView(modernTitle(title, 14f), weightNoMargin(1f));
        if (value != null && !value.isEmpty()) {
            TextView valueView = modernSubtitle(value, 12f);
            valueView.setTextColor(MODERN_GREEN);
            row.addView(valueView, wrapParams());
        }
        if (listener != null) {
            TextView arrow = modernSubtitle("›", 24f);
            arrow.setPadding(dp(8), 0, 0, 0);
            row.addView(arrow, wrapParams());
        }
        return row;
    }

    private View modernDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(modernDividerColor());
        return divider;
    }

    private void tintSwitch(Switch toggle) {
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        int[] thumb = new int[]{Color.WHITE, Color.rgb(245, 245, 245)};
        int[] track = new int[]{MODERN_PURPLE, isDarkMode() ? Color.rgb(95, 91, 100) : Color.rgb(195, 191, 187)};
        toggle.setThumbTintList(new ColorStateList(states, thumb));
        toggle.setTrackTintList(new ColorStateList(states, track));
    }

    private boolean isDarkMode() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private int modernBackground() { return isDarkMode() ? MODERN_DARK_BG : MODERN_CREAM; }
    private int modernPaper() { return isDarkMode() ? MODERN_DARK_PAPER : MODERN_PAPER; }
    private int modernTextColor() { return isDarkMode() ? MODERN_DARK_TEXT : MODERN_TEXT; }
    private int modernMutedColor() { return isDarkMode() ? MODERN_DARK_MUTED : MODERN_MUTED; }
    private int modernDividerColor() { return isDarkMode() ? MODERN_DARK_DIVIDER : MODERN_DIVIDER; }
    private int mutedChipBackground() { return isDarkMode() ? Color.rgb(58, 54, 62) : Color.rgb(239, 235, 228); }

    private void renderModernQueue(LinearLayout container, List<HostPlayer.QueueEntry> entries, boolean host) {
        if (container == null) {
            return;
        }
        container.removeAllViews();
        if (entries == null || entries.isEmpty()) {
            modernQueueEmpty = modernSubtitle("Очередь пока пуста", 13f);
            modernQueueEmpty.setGravity(Gravity.CENTER);
            modernQueueEmpty.setPadding(0, dp(22), 0, dp(22));
            container.addView(modernQueueEmpty, matchWrap());
            return;
        }

        for (int i = 0; i < entries.size(); i++) {
            HostPlayer.QueueEntry entry = entries.get(i);
            final int index = i;
            LinearLayout item = row();
            item.setTag(index);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(2), dp(7), dp(2), dp(7));

            TextView handle = modernSubtitle("⠿", 22f);
            handle.setTextColor(modernMutedColor());
            handle.setGravity(Gravity.CENTER);
            handle.setContentDescription("Перетащить трек");
            handle.setTag(index);
            handle.setOnLongClickListener(v -> {
                ClipData data = ClipData.newPlainText("queue-index", String.valueOf(index));
                v.startDragAndDrop(data, new View.DragShadowBuilder(item), item, 0);
                return true;
            });
            item.addView(handle, fixedParams(34, 50));

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView title = modernTitle(entry.title, 14f);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title, matchWrap());
            String meta = buildQueueMeta(entry);
            TextView subtitle = modernSubtitle(meta, 11f);
            subtitle.setMaxLines(1);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitle, matchWrap());
            item.addView(texts, weightNoMargin(1f));

            Button menu = modernIconButton("⋮", "Действия с треком", v -> showQueueMenu(v, index, entries.size(), host));
            item.addView(menu, fixedParams(42, 50));
            container.addView(item, matchWrap());

            if (i < entries.size() - 1) {
                container.addView(modernDivider(), matchHeightParams(1));
            }
        }
    }

    private String buildQueueMeta(HostPlayer.QueueEntry entry) {
        String artist = entry.artist == null ? "" : entry.artist.trim();
        String source = entry.source == null ? "" : entry.source.trim();
        if (artist.isEmpty()) {
            return source.isEmpty() ? "PartyCast" : source;
        }
        return source.isEmpty() ? artist : artist + "  •  " + source;
    }

    private void showQueueMenu(View anchor, int index, int size, boolean host) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add("В начало");
        popup.getMenu().add("В конец");
        popup.getMenu().add("Удалить");
        popup.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if ("В начало".equals(title)) {
                moveQueueItem(index, 0, host);
            } else if ("В конец".equals(title)) {
                moveQueueItem(index, Math.max(0, size - 1), host);
            } else if ("Удалить".equals(title)) {
                if (host) {
                    hostPlayer.removeFromQueue(index + 1);
                } else {
                    sendControlToHost(PartyProtocol.CMD_REMOVE, index + 1);
                }
            }
            return true;
        });
        popup.show();
    }

    private boolean handleQueueDrag(View view, DragEvent event) {
        if (!(view instanceof LinearLayout)) {
            return false;
        }
        if (event.getAction() == DragEvent.ACTION_DRAG_ENTERED) {
            view.setBackgroundColor(isDarkMode() ? Color.rgb(55, 48, 68) : MODERN_LAVENDER);
            return true;
        }
        if (event.getAction() == DragEvent.ACTION_DRAG_EXITED || event.getAction() == DragEvent.ACTION_DRAG_ENDED) {
            view.setBackgroundColor(Color.TRANSPARENT);
            return true;
        }
        if (event.getAction() == DragEvent.ACTION_DROP) {
            view.setBackgroundColor(Color.TRANSPARENT);
            int from;
            try {
                from = Integer.parseInt(event.getClipData().getItemAt(0).getText().toString());
            } catch (Exception ignored) {
                return false;
            }
            LinearLayout container = (LinearLayout) view;
            int to = findQueueDropIndex(container, event.getY());
            moveQueueItem(from, to, "host".equals(currentScreen));
            return true;
        }
        return event.getAction() == DragEvent.ACTION_DRAG_STARTED;
    }

    private int findQueueDropIndex(LinearLayout container, float y) {
        int logicalIndex = 0;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (!(child.getTag() instanceof Integer)) {
                continue;
            }
            if (y < child.getBottom()) {
                return logicalIndex;
            }
            logicalIndex++;
        }
        return Math.max(0, logicalIndex - 1);
    }

    private void moveQueueItem(int from, int to, boolean host) {
        if (from == to) {
            return;
        }
        if (host) {
            hostPlayer.moveTo(from + 1, to + 1);
        } else {
            sendControlToHost(PartyProtocol.CMD_MOVE_TO_PREFIX + (to + 1), from + 1);
        }
    }

    private void addModernPlayerOverlay() {
        LinearLayout player = modernCard(12);
        player.setBackground(rounded(isDarkMode() ? Color.rgb(54, 47, 67) : MODERN_PAPER, dp(16), MODERN_PURPLE));

        LinearLayout titleRow = row();
        modernNowTitle = modernTitle("Сейчас ничего не играет", 15f);
        modernNowTitle.setMaxLines(1);
        modernNowTitle.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(modernNowTitle, weightNoMargin(1f));
        TextView chevron = modernSubtitle("⌃", 20f);
        titleRow.addView(chevron, wrapParams());
        player.addView(titleRow, matchWrap());

        modernNowArtist = modernSubtitle("PartyCast", 12f);
        modernNowArtist.setMaxLines(1);
        player.addView(modernNowArtist, matchWrap());

        nowPlayingProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        nowPlayingProgress.setMax(1000);
        nowPlayingProgress.setProgressTintList(ColorStateList.valueOf(MODERN_PURPLE));
        nowPlayingProgress.setProgressBackgroundTintList(ColorStateList.valueOf(modernDividerColor()));
        player.addView(nowPlayingProgress, marginHeightParams(0, 4, 0, 0, 4));

        LinearLayout times = row();
        modernNowTimeStart = modernSubtitle("0:00", 10f);
        modernNowTimeEnd = modernSubtitle("0:00", 10f);
        times.addView(modernNowTimeStart, weightNoMargin(1f));
        times.addView(modernNowTimeEnd, wrapParams());
        player.addView(times, matchWrap());

        LinearLayout controls = row();
        controls.setGravity(Gravity.CENTER);
        Button previous = modernIconButton("◀", "Предыдущий трек", v -> hostPlayer.previous());
        controls.addView(previous, fixedParams(58, 48));
        modernPlayButton = modernPrimaryButton("Ⅱ", v -> hostPlayer.pauseOrResume());
        modernPlayButton.setTextSize(21f);
        controls.addView(modernPlayButton, fixedParams(66, 52));
        Button next = modernIconButton("▶", "Следующий трек", v -> hostPlayer.skip());
        controls.addView(next, fixedParams(58, 48));
        player.addView(controls, matchWrap());

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        params.setMargins(dp(14), 0, dp(14), dp(12));
        screenContainer.addView(player, params);
        updateModernNowPlayingViews();
    }

    private void toggleHost() {
        if (serverController != null && serverController.isRunning()) {
            stopHost();
        } else {
            startHost();
        }
    }

    private void connectToEnteredHost() {
        String host = readHostIp();
        if (host.isEmpty()) {
            toast("Введите IP-адрес хоста");
            return;
        }
        updateModernListenerStatus(false, "Подключение…");
        new Thread(() -> {
            try {
                PartyProtocol.PingResult result = PartyProtocol.pingHost(
                        host,
                        PartyProtocol.DEFAULT_PORT,
                        getRoomCode(),
                        NetworkUtils.getWifiSocketFactory(this),
                        2500
                );
                runOnUiThread(() -> {
                    lastHostIp = result.host;
                    if (hostIpInput != null) {
                        hostIpInput.setText(result.host);
                    }
                    updateModernListenerStatus(true, "Подключён");
                    startListenerQueuePolling();
                    toast("Подключено к комнате");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    updateModernListenerStatus(false, "Ошибка");
                    toast("Не удалось подключиться: " + e.getMessage());
                });
            }
        }, "partycast-direct-connect").start();
    }

    private void findHostAutomatically() {
        updateModernListenerStatus(false, "Поиск…");
        String code = getRoomCode();
        hostDiscovery.discoverByNsd(code);
        hostDiscovery.connectToHotspotHost(code);
    }

    private void showLinkDialog() {
        showLinkDialog("");
    }

    private void showLinkDialog(String initialValue) {
        EditText input = modernEditText("Ссылка на трек", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(initialValue == null ? "" : initialValue.trim());
        input.setSelection(input.getText().length());
        input.setPadding(dp(14), 0, dp(14), 0);
        new AlertDialog.Builder(this)
                .setTitle("Добавить ссылку")
                .setView(input)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Отправить", (dialog, which) -> {
                    sendLinkToHost(input.getText().toString());
                })
                .show();
    }

    private void updateModernListenerStatus(boolean connected, String text) {
        if (modernListenerStatusChip == null) {
            return;
        }
        modernListenerStatusChip.setText(text);
        modernListenerStatusChip.setTextColor(connected ? MODERN_GREEN : modernMutedColor());
        modernListenerStatusChip.setBackground(rounded(connected ? MODERN_GREEN_SOFT : mutedChipBackground(), dp(13), 0));
    }

    private void startListenerQueuePolling() {
        listenerPolling = true;
        uiHandler.removeCallbacks(listenerQueuePoller);
        uiHandler.post(listenerQueuePoller);
    }

    private void requestListenerQueueSnapshot() {
        String host = readHostIp();
        if (host.isEmpty()) {
            return;
        }
        new Thread(() -> {
            try {
                PartyProtocol.QueueSnapshot snapshot = PartyProtocol.requestQueueSnapshot(
                        host,
                        PartyProtocol.DEFAULT_PORT,
                        getRoomCode(),
                        NetworkUtils.getWifiSocketFactory(this),
                        2200
                );
                List<HostPlayer.QueueEntry> converted = new ArrayList<>();
                for (PartyProtocol.QueueItemData item : snapshot.items) {
                    converted.add(new HostPlayer.QueueEntry(item.title, item.artist, item.source));
                }
                runOnUiThread(() -> {
                    listenerQueueEntries.clear();
                    listenerQueueEntries.addAll(converted);
                    updateModernListenerStatus(true, "Подключён");
                    if (modernQueueContainer != null && "listener".equals(currentScreen)) {
                        renderModernQueue(modernQueueContainer, listenerQueueEntries, false);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> updateModernListenerStatus(false, "Связь потеряна"));
            }
        }, "partycast-queue-snapshot").start();
    }

    private void startMediaStateTicker() {
        uiHandler.removeCallbacks(mediaStateTicker);
        uiHandler.post(mediaStateTicker);
    }

    private void refreshModernNowPlayingFromYandex() {
        if (localNowVisible) {
            updateModernNowPlayingViews();
            return;
        }
        try {
            String[] details = YandexMusicBridge.currentTrackDetails(this);
            if (details.length >= 5) {
                latestNowTitle = details[0];
                latestNowArtist = details[1];
                latestNowDurationMs = safeLongToInt(details[2]);
                latestNowProgressMs = safeLongToInt(details[3]);
                latestNowPlaying = Boolean.parseBoolean(details[4]);
                latestNowSource = "Яндекс Музыка";
            } else {
                latestNowTitle = "";
                latestNowArtist = "";
                latestNowSource = "";
                latestNowPlaying = false;
                latestNowProgressMs = 0;
                latestNowDurationMs = 0;
            }
        } catch (Exception ignored) {
        }
        updateModernNowPlayingViews();
        publishQueueSnapshot();
    }

    private int safeLongToInt(String value) {
        try {
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, Long.parseLong(value)));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void updateModernNowPlayingViews() {
        if (modernNowTitle == null || nowPlayingProgress == null) {
            return;
        }
        boolean hasTrack = latestNowTitle != null && !latestNowTitle.trim().isEmpty();
        modernNowTitle.setText(hasTrack ? latestNowTitle : "Сейчас ничего не играет");
        String artist = latestNowArtist == null ? "" : latestNowArtist.trim();
        String source = latestNowSource == null ? "" : latestNowSource.trim();
        modernNowArtist.setText(hasTrack
                ? (artist.isEmpty() ? source : source.isEmpty() ? artist : artist + "  •  " + source)
                : "PartyCast");
        int max = latestNowDurationMs > 0 ? latestNowDurationMs : 1000;
        nowPlayingProgress.setMax(max);
        nowPlayingProgress.setProgress(Math.min(max, Math.max(0, latestNowProgressMs)));
        modernNowTimeStart.setText(formatDuration(latestNowProgressMs));
        modernNowTimeEnd.setText(formatDuration(latestNowDurationMs));
        modernPlayButton.setText(latestNowPlaying ? "Ⅱ" : "▶");
    }

    private String formatDuration(int ms) {
        int total = Math.max(0, ms) / 1000;
        return String.format(Locale.getDefault(), "%d:%02d", total / 60, total % 60);
    }

    private void publishQueueSnapshot() {
        if (serverController == null) {
            return;
        }
        List<PartyProtocol.QueueItemData> items = new ArrayList<>();
        for (HostPlayer.QueueEntry entry : latestQueueEntries) {
            items.add(new PartyProtocol.QueueItemData(entry.title, entry.artist, entry.source));
        }
        serverController.setQueueSnapshot(new PartyProtocol.QueueSnapshot(
                currentRoomCode,
                latestNowTitle,
                latestNowArtist,
                latestNowSource,
                latestNowPlaying,
                items
        ));
    }

    private void addTopBar(String titleValue, String state) {
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView menu = bodyText("☰");
        menu.setTextSize(20f);
        bar.addView(menu, wrapParams());

        TextView title = title(titleValue, 18f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(title, weightParams(1f));

        if (state != null && !state.isEmpty()) {
            TextView chip = chip(state);
            bar.addView(chip, wrapParams());
        }

        root.addView(bar, marginParams(0, 0, 0, 12));
    }

    private void addBottomNav(String active) {
        LinearLayout nav = row();
        nav.setGravity(Gravity.CENTER);
        nav.addView(navButton("Start", active.equals("start"), v -> showStartScreen()), weightParams(1f));
        nav.addView(navButton("Host", active.equals("host"), v -> showHostScreen()), weightParams(1f));
        nav.addView(navButton("Listener", active.equals("listener"), v -> showListenerScreen()), weightParams(1f));
        nav.addView(navButton("Settings", active.equals("settings"), v -> showSettingsScreen()), weightParams(1f));
        root.addView(nav, marginParams(0, 4, 0, 8));
    }

    private void addLogPanel(boolean compact) {
        LinearLayout logCard = card();
        logCard.addView(sectionLabel("ЖУРНАЛ"), matchWrap());
        statusText = bodyText(logBuffer.toString());
        statusText.setTextIsSelectable(true);
        if (compact && logBuffer.length() == 0) {
            statusText.setText("Пока событий нет");
        }
        logCard.addView(statusText, matchWrap());
        root.addView(logCard, cardParams());
    }

    private void startHost() {
        if (serverController == null || hostDiscovery == null) {
            log("Хост ещё не готов, попробуйте снова через секунду");
            return;
        }
        serverController.start(PartyProtocol.DEFAULT_PORT);
        hostDiscovery.startResponder(PartyProtocol.DEFAULT_PORT, "PartyCast Host");
        updateHostStatus(true);
    }

    private void stopHost() {
        if (hostDiscovery != null) {
            hostDiscovery.stopResponder();
        }
        if (serverController != null) {
            serverController.stop();
        }
        updateHostStatus(false);
        log("Сервер остановлен вручную");
    }

    private void startHotspotHostMode() {
        startHost();
        log("Hotspot mode: включите точку доступа Android, подключите участников к ней и используйте поиск Hotspot-хоста.");
    }

    private void updateHostStatus(boolean running) {
        if (modernHostStatusChip != null) {
            modernHostStatusChip.setText(running ? "Активна" : "Остановлена");
            modernHostStatusChip.setTextColor(running ? MODERN_GREEN : modernMutedColor());
            modernHostStatusChip.setBackground(rounded(running ? MODERN_GREEN_SOFT : mutedChipBackground(), dp(13), 0));
        }
        if (modernHostToggleButton != null) {
            modernHostToggleButton.setText(running ? "■   Остановить комнату" : "▶   Запустить комнату");
            modernHostToggleButton.setBackground(rounded(running ? MODERN_PURPLE_DARK : MODERN_PURPLE, dp(12), 0));
        }

        if (!running) {
            String oldIpLine = lastHostIp.isEmpty() ? "" : "\nПоследний IP: " + lastHostIp;
            if (hostInfoText != null) {
                hostInfoText.setText("● Комната остановлена\nСлушатели пока не могут отправлять файлы и ссылки." + oldIpLine);
            }
            return;
        }

        String ip = getLocalIpv4Address();
        if (ip == null) {
            if (hostInfoText != null) {
                hostInfoText.setText("● Комната активна, но IP не найден\nПроверьте Wi‑Fi или Hotspot.\nПорт: "
                        + PartyProtocol.DEFAULT_PORT + "\nКод: " + getRoomCode());
            }
            log("Не удалось определить локальный IP. Возможно, телефон не подключён к Wi‑Fi/Hotspot.");
        } else {
            lastHostIp = ip;
            if (hostIpInput != null) {
                hostIpInput.setText(ip);
            }
            if (hostInfoText != null) {
                hostInfoText.setText("● Комната активна\nIP: " + ip
                        + "\nПорт: " + PartyProtocol.DEFAULT_PORT
                        + "\nКод: " + getRoomCode()
                        + "\nСлушатели могут отправлять файлы и ссылки.");
            }
        }
    }

    private void copyRoomCode() {
        String code = getRoomCode();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("PartyCast room code", code));
            toast("Код скопирован");
        }
    }

    private void copyAccessibilityDiagnostic() {
        String report = YandexMusicBridge.lastAccessibilityDiagnostic();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("PartyCast Accessibility diagnostic", report));
            toast("Отчёт Accessibility скопирован");
        }
        log("Accessibility — последний отчёт:\n" + report);
    }

    private void openAudioPicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        if (requestCode == PICK_AUDIO_REQUEST) {
            sendSelectedAudio(uri);
        } else if (requestCode == PICK_HOST_TEST_AUDIO_REQUEST) {
            addHostTestAudio(uri);
        }
    }

    private void addHostTestLink() {
        if (hostPlayer == null) {
            toast("Хост-плеер ещё не готов");
            return;
        }
        String url = hostTestLinkInput == null ? "" : hostTestLinkInput.getText().toString().trim();
        if (url.isEmpty()) {
            toast("Вставьте ссылку");
            return;
        }
        hostPlayer.enqueueLink(url);
        if (hostTestLinkInput != null) {
            hostTestLinkInput.setText("");
        }
    }

    private void addHostTestAudio(Uri uri) {
        if (hostPlayer == null) {
            toast("Хост-плеер ещё не готов");
            return;
        }

        String displayName = queryDisplayName(uri);
        log("Тестовый режим: добавляю файл на хосте: " + displayName);

        fileTransferExecutor.execute(() -> {
            try {
                File copied = copyUriToCache(uri, displayName);
                runOnUiThread(() -> hostPlayer.enqueueFile(displayName, copied));
            } catch (Exception e) {
                runOnUiThread(() -> log("Не удалось добавить тестовый файл: " + e.getMessage()));
            }
        });
    }

    private File copyUriToCache(Uri uri, String displayName) throws Exception {
        File dir = new File(getCacheDir(), "host_test_audio");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("не удалось создать папку тестовых файлов");
        }

        File outFile = new File(dir, System.currentTimeMillis() + "_" + sanitizeFileName(displayName));
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(outFile)) {
            if (in == null) {
                throw new IllegalStateException("не удалось открыть файл");
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return outFile;
    }

    private void sendSelectedAudio(Uri uri) {
        String host = readHostIp();
        if (host.isEmpty()) {
            toast("Введите IP хоста или найдите хост автоматически");
            return;
        }

        String displayName = queryDisplayName(uri);
        long size = querySize(uri);
        String roomCode = getRoomCode();
        log("Отправка файла: " + displayName + " → " + host);

        fileTransferExecutor.execute(() -> {
            final int[] lastPercent = {-10};
            try {
                PartyProtocol.sendAudioFile(
                        host,
                        PartyProtocol.DEFAULT_PORT,
                        roomCode,
                        getContentResolver(),
                        uri,
                        displayName,
                        size,
                        (sentBytes, totalBytes) -> {
                            if (totalBytes > 0) {
                                int percent = (int) ((sentBytes * 100L) / totalBytes);
                                if (percent >= lastPercent[0] + 10 || percent == 100) {
                                    lastPercent[0] = percent;
                                    runOnUiThread(() -> log("Передано: " + percent + "% (" + humanBytes(sentBytes) + " / " + humanBytes(totalBytes) + ")"));
                                }
                            }
                        },
                        NetworkUtils.getWifiSocketFactory(this)
                );
                runOnUiThread(() -> log("Файл отправлен: " + displayName));
            } catch (Exception e) {
                runOnUiThread(() -> log("Ошибка отправки файла: " + e.getMessage()));
            }
        });
    }

    private void sendLinkToHost() {
        sendLinkToHost(linkInput == null ? "" : linkInput.getText().toString());
    }

    private void sendLinkToHost(String rawUrl) {
        String host = readHostIp();
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (host.isEmpty()) {
            toast("Введите IP хоста или найдите хост автоматически");
            return;
        }
        if (url.isEmpty()) {
            toast("Введите ссылку");
            return;
        }

        log("Отправка ссылки → " + host + ": " + url);
        new Thread(() -> {
            try {
                PartyProtocol.sendLink(host, PartyProtocol.DEFAULT_PORT, getRoomCode(), url, NetworkUtils.getWifiSocketFactory(this));
                runOnUiThread(() -> {
                    if (linkInput != null) {
                        linkInput.setText("");
                    }
                    log("Ссылка отправлена");
                    toast("Ссылка принята хостом");
                });
            } catch (Exception e) {
                runOnUiThread(() -> log("Ошибка отправки ссылки: " + e.getMessage()));
            }
        }, "partycast-send-link").start();
    }

    private void sendControlToHost(String command, int queueIndex) {
        String host = readHostIp();
        if (host.isEmpty()) {
            toast("Введите IP хоста или найдите хост автоматически");
            return;
        }

        log("Отправка команды → " + host + ": " + command + " index=" + queueIndex);
        new Thread(() -> {
            try {
                PartyProtocol.sendControl(host, PartyProtocol.DEFAULT_PORT, getRoomCode(), command, queueIndex, NetworkUtils.getWifiSocketFactory(this));
                runOnUiThread(() -> log("Команда отправлена: " + command));
            } catch (Exception e) {
                runOnUiThread(() -> log("Ошибка отправки команды: " + e.getMessage()));
            }
        }, "partycast-send-control").start();
    }

    private void readSharedText(Intent intent) {
        String shared = extractSharedText(intent);
        if (shared == null) {
            return;
        }

        showListenerScreen();
        if (!technicalModeEnabled) {
            showLinkDialog(shared);
        } else if (linkInput != null) {
            linkInput.setText(shared);
        }
        log("Получена ссылка через Share: " + shared);
    }

    private String extractSharedText(Intent intent) {
        if (intent == null) {
            return null;
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null && !text.trim().isEmpty()) {
                return text.trim();
            }
        } else if (intent.getData() != null) {
            String text = intent.getData().toString();
            if (!text.trim().isEmpty()) {
                return text.trim();
            }
        }
        return null;
    }

    private void readAutomationStatus(Intent intent) {
        if (intent == null) {
            return;
        }
        String status = intent.getStringExtra(EXTRA_AUTOMATION_STATUS);
        if (status != null && !status.trim().isEmpty()) {
            log("YMQueueController: " + status.trim());
            if (technicalAccessibilityStatusText != null) {
                technicalAccessibilityStatusText.setText(YandexMusicBridge.accessibilityDiagnosticStatus());
            }
        }
    }

    private int readQueueIndex() {
        try {
            if (queueIndexInput != null) {
                return Integer.parseInt(queueIndexInput.getText().toString().trim());
            }
        } catch (Exception ignored) {
        }
        return 1;
    }

    private String readHostIp() {
        if (hostIpInput != null) {
            lastHostIp = hostIpInput.getText().toString().trim();
        }
        return lastHostIp == null ? "" : lastHostIp.trim();
    }

    private String getRoomCode() {
        if (roomCodeInput != null) {
            String value = PartyProtocol.normalizeRoomCode(roomCodeInput.getText().toString()).toUpperCase(Locale.ROOT);
            if (!value.isEmpty()) {
                currentRoomCode = value;
            }
        }
        if (currentRoomCode == null || currentRoomCode.trim().isEmpty()) {
            currentRoomCode = generateRoomCode();
        }
        return currentRoomCode;
    }

    private String generateRoomCode() {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private void openHotspotSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
            startActivity(intent);
            log("Откройте Hotspot / Mobile Hotspot, включите точку доступа, затем вернитесь в PartyCast.");
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                toast("Не удалось открыть настройки");
            }
        }
    }

    private void openDndSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                toast("Не удалось открыть настройки");
            }
        }
    }

    private String queryDisplayName(Uri uri) {
        String result = "audio_from_client";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    String value = cursor.getString(nameIndex);
                    if (value != null && !value.trim().isEmpty()) {
                        result = value;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    @SuppressWarnings("deprecation")
    private String getAppVersionLabel() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            String name = info.versionName == null || info.versionName.trim().isEmpty() ? "неизвестна" : info.versionName;
            return name + " (" + info.versionCode + ")";
        } catch (Exception ignored) {
            return "неизвестна";
        }
    }

    private long querySize(Uri uri) {
        long result = -1L;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    result = cursor.getLong(sizeIndex);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private String getLocalIpv4Address() {
        String wifiIp = NetworkUtils.getWifiIpv4Address(this);
        if (wifiIp != null) {
            return wifiIp;
        }

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }

                Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                        String hostAddress = address.getHostAddress();
                        if (hostAddress != null && !hostAddress.startsWith("169.254.")) {
                            return hostAddress;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void updateLocalNowPlaying(String text, int progressMs, int durationMs, boolean visible) {
        localNowVisible = visible;

        if (!visible) {
            if (nowPlayingText != null) {
                nowPlayingText.setText("Локальная/переданная музыка сейчас не играет");
            }
            if (nowPlayingProgress != null && modernNowTitle == null) {
                nowPlayingProgress.setMax(1000);
                nowPlayingProgress.setProgress(0);
            }
            refreshModernNowPlayingFromYandex();
            return;
        }

        String[] parts = text == null ? new String[0] : text.split("\\n", 2);
        latestNowTitle = parts.length > 0 ? parts[0] : "Трек";
        latestNowArtist = "";
        latestNowSource = "Файл";
        latestNowPlaying = text == null || !text.contains("пауза");
        latestNowProgressMs = progressMs;
        latestNowDurationMs = durationMs;
        if (nowPlayingText != null) {
            nowPlayingText.setText(text);
        }
        if (durationMs > 0) {
            if (nowPlayingProgress != null && modernNowTitle == null) {
                nowPlayingProgress.setMax(durationMs);
                nowPlayingProgress.setProgress(Math.max(0, Math.min(progressMs, durationMs)));
            }
        } else {
            if (nowPlayingProgress != null && modernNowTitle == null) {
                nowPlayingProgress.setMax(1000);
                nowPlayingProgress.setProgress(0);
            }
        }
        updateModernNowPlayingViews();
        publishQueueSnapshot();
    }

    private void log(String message) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> log(message));
            return;
        }
        String line = "• " + message + "\n";
        logBuffer.append(line);
        if (statusText != null) {
            statusText.append(line);
        }
        if (!technicalModeEnabled && message != null && message.startsWith("Я.Музыка:")) {
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("ссылка открыта")) {
                toast("Открываю трек в Яндекс Музыке");
            } else if (lower.contains("не удалось") || lower.contains("ошиб") || lower.contains("отменено")) {
                toast(message.length() > 110 ? message.substring(0, 107) + "…" : message);
            }
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private LinearLayout actionCard(String titleText, String subtitleText, String icon, boolean primary) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        if (primary) {
            card.setBackground(rounded(COLOR_GREEN, dp(12), 0));
        }

        TextView iconView = new TextView(this);
        iconView.setText(icon);
        iconView.setTextSize(30f);
        iconView.setGravity(Gravity.CENTER);
        iconView.setTextColor(primary ? Color.WHITE : COLOR_GREEN);
        card.addView(iconView, fixedParams(52, 64));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = title(titleText, 16f);
        title.setTextColor(primary ? Color.WHITE : COLOR_TEXT);
        TextView subtitle = subtitle(subtitleText);
        subtitle.setTextColor(primary ? Color.rgb(227, 248, 232) : COLOR_MUTED);
        texts.addView(title, matchWrap());
        texts.addView(subtitle, matchWrap());
        card.addView(texts, weightParams(1f));
        return card;
    }

    private View listAction(String icon, String titleText, String subtitleText, View.OnClickListener listener) {
        LinearLayout item = row();
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(0, dp(10), 0, dp(10));
        item.setOnClickListener(listener);

        TextView iconView = new TextView(this);
        iconView.setText(icon);
        iconView.setTextSize(24f);
        iconView.setTextColor(COLOR_GREEN);
        item.addView(iconView, fixedParams(42, 48));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(title(titleText, 14f), matchWrap());
        texts.addView(subtitle(subtitleText), matchWrap());
        item.addView(texts, weightParams(1f));

        TextView arrow = bodyText("›");
        arrow.setTextSize(28f);
        arrow.setGravity(Gravity.CENTER);
        item.addView(arrow, fixedParams(24, 48));
        return item;
    }

    private View settingRow(String icon, String titleText, String subtitleText, boolean checked, View.OnClickListener listener) {
        LinearLayout item = row();
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(0, dp(8), 0, dp(8));
        if (listener != null) {
            item.setOnClickListener(listener);
        }

        TextView iconView = new TextView(this);
        iconView.setText(icon);
        iconView.setTextSize(20f);
        iconView.setTextColor(COLOR_GREEN);
        item.addView(iconView, fixedParams(38, 44));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(title(titleText, 14f), matchWrap());
        texts.addView(subtitle(subtitleText), matchWrap());
        item.addView(texts, weightParams(1f));

        if (listener == null) {
            CheckBox checkBox = new CheckBox(this);
            checkBox.setChecked(checked);
            checkBox.setEnabled(false);
            item.addView(checkBox, wrapParams());
        } else {
            TextView arrow = bodyText("›");
            arrow.setTextSize(26f);
            item.addView(arrow, wrapParams());
        }
        return item;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(rounded(COLOR_CARD, dp(12), COLOR_LINE));
        card.setElevation(dp(2));
        return card;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout buttonRow(View... buttons) {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER);
        for (View button : buttons) {
            row.addView(button, weightParams(1f));
        }
        return row;
    }

    private TextView title(String text, float size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(COLOR_TEXT);
        view.setTextSize(size);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView subtitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(COLOR_MUTED);
        view.setTextSize(13f);
        return view;
    }

    private TextView bodyText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(COLOR_TEXT);
        view.setTextSize(14f);
        view.setLineSpacing(dp(2), 1.0f);
        return view;
    }

    private TextView sectionLabel(String text) {
        TextView view = title(text, 12f);
        view.setTextColor(COLOR_MUTED);
        view.setPadding(0, 0, 0, dp(8));
        return view;
    }

    private TextView sectionHeader(String left, String right) {
        return sectionLabel(left + (right == null || right.isEmpty() ? "" : " · " + right));
    }

    private TextView chip(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(11f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(COLOR_GREEN);
        view.setPadding(dp(10), dp(5), dp(10), dp(5));
        view.setBackground(rounded(COLOR_SOFT_GREEN, dp(14), 0));
        return view;
    }

    private EditText editText(String hint, int inputType) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setSingleLine(true);
        editText.setInputType(inputType);
        editText.setTextColor(COLOR_TEXT);
        editText.setHintTextColor(COLOR_MUTED);
        editText.setTextSize(15f);
        editText.setPadding(dp(12), dp(8), dp(12), dp(8));
        editText.setBackground(rounded(Color.rgb(248, 251, 249), dp(8), COLOR_LINE));
        return editText;
    }

    private Button primaryButton(String text, View.OnClickListener listener) {
        Button button = baseButton(text, listener);
        button.setTextColor(Color.WHITE);
        button.setBackground(rounded(COLOR_GREEN, dp(10), 0));
        return button;
    }

    private Button secondaryButton(String text, View.OnClickListener listener) {
        Button button = baseButton(text, listener);
        button.setTextColor(COLOR_TEXT);
        button.setBackground(rounded(Color.WHITE, dp(10), COLOR_LINE));
        return button;
    }

    private Button smallButton(String text, View.OnClickListener listener) {
        Button button = secondaryButton(text, listener);
        button.setTextSize(13f);
        return button;
    }

    private Button roundPlayButton(View.OnClickListener listener) {
        Button button = baseButton("Ⅱ", listener);
        button.setTextColor(Color.WHITE);
        button.setTextSize(22f);
        button.setBackground(rounded(COLOR_GREEN, dp(28), 0));
        return button;
    }

    private Button navButton(String text, boolean active, View.OnClickListener listener) {
        Button button = baseButton(text, listener);
        button.setTextSize(12f);
        button.setTextColor(active ? Color.WHITE : COLOR_TEXT);
        button.setBackground(rounded(active ? COLOR_GREEN : Color.WHITE, dp(10), active ? 0 : COLOR_LINE));
        return button;
    }

    private Button baseButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinHeight(dp(44));
        button.setPadding(dp(10), dp(6), dp(10), dp(6));
        button.setOnClickListener(listener);
        return button;
    }

    private GradientDrawable rounded(int fillColor, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(radius);
        if (strokeColor != 0) {
            drawable.setStroke(dp(1), strokeColor);
        }
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams wrapParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weightParams(float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        return params;
    }

    private LinearLayout.LayoutParams weightNoMargin(float weight) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
    }

    private LinearLayout.LayoutParams fixedParams(int widthDp, int heightDp) {
        return new LinearLayout.LayoutParams(dp(widthDp), dp(heightDp));
    }

    private LinearLayout.LayoutParams cardParams() {
        return marginParams(0, 0, 0, 12);
    }

    private LinearLayout.LayoutParams modernCardParams(int bottom) {
        return marginParams(0, 0, 0, bottom);
    }

    private LinearLayout.LayoutParams matchHeightParams(int heightDp) {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(heightDp)
        );
    }

    private LinearLayout.LayoutParams marginHeightParams(int left, int top, int right, int bottom, int heightDp) {
        LinearLayout.LayoutParams params = matchHeightParams(heightDp);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams marginParams(int left, int top, int right, int bottom, int heightDp) {
        return marginHeightParams(left, top, right, bottom, heightDp);
    }

    private LinearLayout.LayoutParams centeredButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.setMargins(0, dp(10), 0, dp(12));
        return params;
    }

    private LinearLayout.LayoutParams marginParams(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private final class CozyRoomView extends View {
        private final Paint purplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint greenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint softPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shadePath = new Path();
        private final Path cushionPath = new Path();
        private final Path picturePath = new Path();

        CozyRoomView(Context context) {
            super(context);
            purplePaint.setStyle(Paint.Style.STROKE);
            purplePaint.setStrokeWidth(dp(1));
            purplePaint.setStrokeCap(Paint.Cap.ROUND);
            purplePaint.setStrokeJoin(Paint.Join.ROUND);
            purplePaint.setColor(MODERN_PURPLE);

            greenPaint.set(purplePaint);
            greenPaint.setColor(MODERN_GREEN);

            softPaint.setStyle(Paint.Style.FILL);
            softPaint.setColor(isDarkMode() ? Color.rgb(61, 55, 72) : Color.rgb(251, 247, 240));
            setContentDescription("Уютная гостиная с музыкальной колонкой");
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            canvas.drawRoundRect(w * .19f, h * .43f, w * .77f, h * .76f, dp(18), dp(18), softPaint);

            // Floor and lamp.
            canvas.drawLine(w * .04f, h * .78f, w * .94f, h * .78f, purplePaint);
            canvas.drawLine(w * .11f, h * .37f, w * .11f, h * .78f, purplePaint);
            canvas.drawLine(w * .07f, h * .78f, w * .15f, h * .78f, purplePaint);
            shadePath.reset();
            shadePath.moveTo(w * .065f, h * .37f);
            shadePath.lineTo(w * .155f, h * .37f);
            shadePath.lineTo(w * .14f, h * .26f);
            shadePath.lineTo(w * .08f, h * .26f);
            shadePath.close();
            canvas.drawPath(shadePath, purplePaint);

            // Sofa.
            canvas.drawRoundRect(w * .20f, h * .46f, w * .73f, h * .72f, dp(16), dp(16), purplePaint);
            canvas.drawRoundRect(w * .16f, h * .55f, w * .25f, h * .76f, dp(11), dp(11), purplePaint);
            canvas.drawRoundRect(w * .69f, h * .55f, w * .78f, h * .76f, dp(11), dp(11), purplePaint);
            canvas.drawLine(w * .46f, h * .46f, w * .46f, h * .66f, purplePaint);
            canvas.drawLine(w * .23f, h * .67f, w * .71f, h * .67f, purplePaint);
            canvas.drawLine(w * .24f, h * .72f, w * .23f, h * .80f, purplePaint);
            canvas.drawLine(w * .70f, h * .72f, w * .72f, h * .80f, purplePaint);

            // Cushion and wall picture.
            cushionPath.reset();
            cushionPath.moveTo(w * .25f, h * .52f);
            cushionPath.lineTo(w * .34f, h * .50f);
            cushionPath.lineTo(w * .36f, h * .62f);
            cushionPath.lineTo(w * .27f, h * .64f);
            cushionPath.close();
            canvas.drawPath(cushionPath, purplePaint);
            canvas.drawRect(w * .61f, h * .22f, w * .76f, h * .41f, purplePaint);
            canvas.drawRect(w * .63f, h * .24f, w * .74f, h * .39f, purplePaint);
            picturePath.reset();
            picturePath.moveTo(w * .64f, h * .36f);
            picturePath.cubicTo(w * .67f, h * .31f, w * .70f, h * .38f, w * .74f, h * .29f);
            canvas.drawPath(picturePath, purplePaint);

            // Table and speaker.
            canvas.drawOval(w * .31f, h * .70f, w * .64f, h * .82f, purplePaint);
            canvas.drawLine(w * .37f, h * .79f, w * .34f, h * .94f, purplePaint);
            canvas.drawLine(w * .58f, h * .79f, w * .61f, h * .94f, purplePaint);
            canvas.drawRoundRect(w * .44f, h * .59f, w * .53f, h * .73f, dp(7), dp(7), purplePaint);
            canvas.drawCircle(w * .485f, h * .68f, dp(8), purplePaint);
            canvas.drawCircle(w * .485f, h * .625f, dp(3), purplePaint);
            canvas.drawText("♪", w * .455f, h * .56f, purplePaint);
            canvas.drawText("♪", w * .515f, h * .53f, purplePaint);

            // Plant and pot.
            canvas.drawRoundRect(w * .82f, h * .69f, w * .91f, h * .83f, dp(4), dp(4), greenPaint);
            canvas.drawLine(w * .865f, h * .70f, w * .865f, h * .38f, greenPaint);
            canvas.drawOval(w * .78f, h * .43f, w * .865f, h * .54f, greenPaint);
            canvas.drawOval(w * .865f, h * .36f, w * .94f, h * .50f, greenPaint);
            canvas.drawOval(w * .79f, h * .54f, w * .865f, h * .65f, greenPaint);
            canvas.drawOval(w * .865f, h * .51f, w * .94f, h * .63f, greenPaint);
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 0) {
            return "unknown";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        return String.format(Locale.US, "%.1f MB", mb);
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "audio_from_host";
        }
        return name.replaceAll("[^a-zA-Z0-9а-яА-ЯёЁ._ -]", "_");
    }
}
