package com.example.partycastmvp;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends Activity {
    public static final String EXTRA_AUTOMATION_STATUS = "com.example.partycastmvp.EXTRA_AUTOMATION_STATUS";

    private static final int PICK_AUDIO_REQUEST = 101;
    private static final int PICK_HOST_TEST_AUDIO_REQUEST = 102;

    private static final String PREFS = "partycast_settings";
    private static final String PREF_TEST_MODE = "test_mode_enabled";

    private static final int COLOR_BG = Color.rgb(246, 248, 245);
    private static final int COLOR_CARD = Color.WHITE;
    private static final int COLOR_TEXT = Color.rgb(27, 38, 48);
    private static final int COLOR_MUTED = Color.rgb(105, 118, 128);
    private static final int COLOR_GREEN = Color.rgb(32, 158, 78);
    private static final int COLOR_SOFT_GREEN = Color.rgb(236, 248, 239);
    private static final int COLOR_LINE = Color.rgb(224, 230, 226);

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
    private CheckBox yandexQueueAutomationCheckBox;
    private CheckBox testModeCheckBox;

    private ServerController serverController;
    private HostPlayer hostPlayer;
    private HostDiscovery hostDiscovery;

    private SharedPreferences prefs;
    private final StringBuilder logBuffer = new StringBuilder();
    private String currentRoomCode = "";
    private String lastHostIp = "";
    private boolean testModeEnabled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        testModeEnabled = prefs.getBoolean(PREF_TEST_MODE, false);
        currentRoomCode = generateRoomCode();
        buildUi();
        NetworkUtils.clearProcessNetworkBinding(this);

        hostPlayer = new HostPlayer(this, new HostPlayer.Listener() {
            @Override
            public void onLog(String message) {
                log(message);
            }

            @Override
            public void onQueueChanged(String queueTextValue) {
                if (queueText != null) {
                    queueText.setText(queueTextValue);
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
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readSharedText(intent);
        readAutomationStatus(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (hostDiscovery != null) {
            hostDiscovery.stopResponder();
        }
        if (serverController != null) {
            serverController.stop();
        }
        if (hostPlayer != null) {
            hostPlayer.stopAll();
        }
    }

    private void buildUi() {
        scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(COLOR_BG);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(24));
        scrollView.addView(root, matchWrap());

        setContentView(scrollView);
        showStartScreen();
    }

    private void showStartScreen() {
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

    private void showHostScreen() {
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

        if (testModeEnabled) {
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
                smallButton("◀", v -> YandexMusicBridge.previous(this)),
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
        testCard.addView(sectionHeader("ТЕСТОВЫЙ РЕЖИМ", "включён"), matchWrap());
        testCard.addView(bodyText("Хост может сам добавить файл или ссылку в очередь, чтобы проверить воспроизведение без второго телефона."), matchWrap());

        hostTestLinkInput = editText("https://music.yandex.ru/...", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        testCard.addView(hostTestLinkInput, matchWrap());
        testCard.addView(primaryButton("Добавить ссылку в очередь хоста", v -> addHostTestLink()), matchWrap());
        testCard.addView(secondaryButton("Выбрать аудиофайл на этом телефоне", v -> openAudioPicker(PICK_HOST_TEST_AUDIO_REQUEST)), matchWrap());
        root.addView(testCard, cardParams());
    }

    private void showListenerScreen() {
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
                smallButton("Пауза", v -> sendControlToHost(PartyProtocol.CMD_PAUSE_RESUME, readQueueIndex())),
                smallButton("Далее", v -> sendControlToHost(PartyProtocol.CMD_SKIP, readQueueIndex())),
                smallButton("Удалить", v -> sendControlToHost(PartyProtocol.CMD_REMOVE, readQueueIndex()))
        ), matchWrap());
        controlCard.addView(buttonRow(
                smallButton("Выше", v -> sendControlToHost(PartyProtocol.CMD_MOVE_UP, readQueueIndex())),
                smallButton("Ниже", v -> sendControlToHost(PartyProtocol.CMD_MOVE_DOWN, readQueueIndex()))
        ), matchWrap());
        root.addView(controlCard, cardParams());

        addBottomNav("listener");
        addLogPanel(true);
    }

    private void showSettingsScreen() {
        clearScreen();
        addTopBar("НАСТРОЙКИ", "");

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

        LinearLayout playbackCard = card();
        playbackCard.addView(sectionLabel("ВОСПРОИЗВЕДЕНИЕ"), matchWrap());
        testModeCheckBox = new CheckBox(this);
        testModeCheckBox.setText("Тестовый режим хоста");
        testModeCheckBox.setTextColor(COLOR_TEXT);
        testModeCheckBox.setChecked(testModeEnabled);
        testModeCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            testModeEnabled = isChecked;
            prefs.edit().putBoolean(PREF_TEST_MODE, isChecked).apply();
            log(isChecked
                    ? "Тестовый режим включён. На экране Host появится карточка добавления треков."
                    : "Тестовый режим выключен.");
        });
        playbackCard.addView(testModeCheckBox, matchWrap());
        playbackCard.addView(bodyText("Когда режим включён, хост может сам добавлять ссылки и аудиофайлы в очередь для проверки."), matchWrap());
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

    private void clearScreen() {
        if (root != null) {
            root.removeAllViews();
        }
        hostIpInput = null;
        linkInput = null;
        hostTestLinkInput = null;
        queueIndexInput = null;
        statusText = null;
        queueText = null;
        nowPlayingText = null;
        nowPlayingProgress = null;
        hostInfoText = null;
        yandexQueueAutomationCheckBox = null;
        testModeCheckBox = null;
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
        if (hostInfoText == null) {
            return;
        }

        if (!running) {
            String oldIpLine = lastHostIp.isEmpty() ? "" : "\nПоследний IP: " + lastHostIp;
            hostInfoText.setText("● Комната остановлена\nСлушатели пока не могут отправлять файлы и ссылки." + oldIpLine);
            return;
        }

        String ip = getLocalIpv4Address();
        if (ip == null) {
            hostInfoText.setText("● Комната активна, но IP не найден\nПроверьте Wi‑Fi или Hotspot.\nПорт: "
                    + PartyProtocol.DEFAULT_PORT + "\nКод: " + getRoomCode());
            log("Не удалось определить локальный IP. Возможно, телефон не подключён к Wi‑Fi/Hotspot.");
        } else {
            lastHostIp = ip;
            if (hostIpInput != null) {
                hostIpInput.setText(ip);
            }
            hostInfoText.setText("● Комната активна\nIP: " + ip
                    + "\nПорт: " + PartyProtocol.DEFAULT_PORT
                    + "\nКод: " + getRoomCode()
                    + "\nСлушатели могут отправлять файлы и ссылки.");
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

        new Thread(() -> {
            try {
                File copied = copyUriToCache(uri, displayName);
                runOnUiThread(() -> hostPlayer.enqueueFile(displayName, copied));
            } catch (Exception e) {
                runOnUiThread(() -> log("Не удалось добавить тестовый файл: " + e.getMessage()));
            }
        }, "partycast-host-test-file").start();
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
        log("Отправка файла: " + displayName + " → " + host);

        new Thread(() -> {
            final int[] lastPercent = {-10};
            try {
                PartyProtocol.sendAudioFile(
                        host,
                        PartyProtocol.DEFAULT_PORT,
                        getRoomCode(),
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
        }, "partycast-send-file").start();
    }

    private void sendLinkToHost() {
        String host = readHostIp();
        String url = linkInput == null ? "" : linkInput.getText().toString().trim();
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
        if (linkInput != null) {
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
        if (nowPlayingText == null || nowPlayingProgress == null) {
            return;
        }

        if (!visible) {
            nowPlayingText.setText("Локальная/переданная музыка сейчас не играет");
            nowPlayingProgress.setMax(1000);
            nowPlayingProgress.setProgress(0);
            return;
        }

        nowPlayingText.setText(text);
        if (durationMs > 0) {
            nowPlayingProgress.setMax(durationMs);
            nowPlayingProgress.setProgress(Math.max(0, Math.min(progressMs, durationMs)));
        } else {
            nowPlayingProgress.setMax(1000);
            nowPlayingProgress.setProgress(0);
        }
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

    private LinearLayout.LayoutParams fixedParams(int widthDp, int heightDp) {
        return new LinearLayout.LayoutParams(dp(widthDp), dp(heightDp));
    }

    private LinearLayout.LayoutParams cardParams() {
        return marginParams(0, 0, 0, 12);
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
