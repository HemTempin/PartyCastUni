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
    private TextView accessSetupStatusText;
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
                log("ĞĞ°Ğ¹Ğ´ĞµĞ½ Ñ…Ğ¾ÑÑ‚: " + label + " / " + hostIp + ":" + tcpPort);
                toast("Ğ¥Ğ¾ÑÑ‚ Ğ½Ğ°Ğ¹Ğ´ĞµĞ½: " + hostIp);
                updateModernListenerStatus(true, "ĞŸĞ¾Ğ´ĞºĞ»ÑÑ‡Ñ‘Ğ½");
                startListenerQueuePolling();
            }
        });

        log("Ğ“Ğ¾Ñ‚Ğ¾Ğ²Ğ¾. Ğ’Ñ‹Ğ±ĞµÑ€Ğ¸Ñ‚Ğµ Ñ€Ğ¾Ğ»ÑŒ: Ñ…Ğ¾ÑÑ‚ Ğ·Ğ°Ğ¿ÑƒÑĞºĞ°ĞµÑ‚ ĞºĞ¾Ğ¼Ğ½Ğ°Ñ‚Ñƒ, ÑĞ»ÑƒÑˆĞ°Ñ‚ĞµĞ»ÑŒ Ğ¾Ñ‚Ğ¿Ñ€Ğ°Ğ²Ğ»ÑĞµÑ‚ Ğ¼ÑƒĞ·Ñ‹ĞºÑƒ.");
        readSharedText(getIntent());
        readAutomationStatus(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        NetworkUtils.clearProcessNetworkBinding(this);
        refreshAccessSetupStatus();
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
        logo.setText("â™ª");
        logo.setTextSize(44f);
        logo.setTextColor(COLOR_GREEN);
        logo.setGravity(Gravity.CENTER);
        root.addView(logo, matchWrap());

        TextView title = title("PartyCast", 30f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView subtitle = subtitle("Ğ”ĞµĞ»Ğ¸Ñ‚ĞµÑÑŒ Ğ¼ÑƒĞ·Ñ‹ĞºĞ¾Ğ¹ Ğ½Ğ° Ğ¾Ğ´Ğ½Ğ¾Ğ¹ Ğ²Ğ¾Ğ»Ğ½Ğµ");
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(4), 0, dp(24));
        root.addView(subtitle, matchWrap());

        LinearLayout hostCard = actionCard("Ğ¥ĞĞ¡Ğ¢", "Ğ—Ğ°Ğ¿ÑƒÑÑ‚Ğ¸Ñ‚ÑŒ ĞºĞ¾Ğ¼Ğ½Ğ°Ñ‚Ñƒ Ğ¸ ÑƒĞ¿Ñ€Ğ°Ğ²Ğ»ÑÑ‚ÑŒ Ğ²Ğ¾ÑĞ¿Ñ€Ğ¾Ğ¸Ğ·Ğ²ĞµĞ´ĞµĞ½Ğ¸ĞµĞ¼", "ğŸ‘¥", true);
        hostCard.setOnClickListener(v -> showHostScreen());
        root.addView(hostCard, cardParams());

        LinearLayout listenerCard = actionCard("Ğ¡Ğ›Ğ£Ğ¨ĞĞ¢Ğ•Ğ›Ğ¬", "ĞŸĞ¾Ğ´ĞºĞ»ÑÑ‡Ğ¸Ñ‚ÑŒÑÑ Ğº ĞºĞ¾Ğ¼Ğ½Ğ°Ñ‚Ğµ Ğ¸ Ğ¾Ñ‚Ğ¿Ñ€Ğ°Ğ²Ğ»ÑÑ‚ÑŒ Ñ‚Ñ€ĞµĞºĞ¸", "ğŸ§", false);
        listenerCard.setOnClickListener(v -> showListenerScreen());
        root.addView(listenerCard, cardParams());

        LinearLayout codeCard = card();
        codeCard.addView(sectionLabel("ĞšĞĞ” ĞšĞĞœĞĞĞ¢Ğ«"), matchWrap());
        roomCodeInput = editText("Ğ½Ğ°Ğ¿Ñ€Ğ¸Ğ¼ĞµÑ€ 7F3K9Q", InputType.TYPE_CLASS_TEXT);
        roomCodeInput.setText(room);
        codeCard.addView(roomCodeInput, matchWrap());
        codeCard.addView(secondaryButton("Ğ¡Ğ³ĞµĞ½ĞµÑ€Ğ¸Ñ€Ğ¾Ğ²Ğ°Ñ‚ÑŒ Ğ½Ğ¾Ğ²Ñ‹Ğ¹ ĞºĞ¾Ğ´", v -> {
            currentRoomCode = generateRoomCode();
            roomCodeInput.setText(currentRoomCode);
            log("ĞĞ¾Ğ²Ñ‹Ğ¹ ĞºĞ¾Ğ´ ĞºĞ¾Ğ¼Ğ½Ğ°Ñ‚Ñ‹: " + currentRoomCode);
        }), matchWrap());
        root.addView(codeCard, cardParams());

        Button settings = secondaryButton("âš™  ĞĞĞ¡Ğ¢Ğ ĞĞ™ĞšĞ˜", v -> showSettingsScreen());
        root.addView(settings, centeredButtonParams());

        addLogPanel(false);
    }

    private void showTechnicalHostScreen() {
        String room = getRoomCode();
        clearScreen();
        addTopBar(ÛÏwŞÚ$z{-®éÜj×–6öâ“°Ğ¢–6öåf–Wrç6WEFW‡E6—¦Rƒ#Fb“°Ğ¢–6öåf–Wrç6WEFW‡D6öÆ÷"„4ôÄõ%ôu$TTâ“°Ğ¢—FVÒæFEf–Wr†–6öåf–WrÂf—†VE&×2ƒC"ÂC‚’“°Ğ Ğ¢Æ–æV$Æ–÷WBFW‡G2ÒæWrÆ–æV$Æ–÷WB‡F†—2“°Ğ¢FW‡G2ç6WD÷&–VçFF–öâ„Æ–æV$Æ–÷WBådU%D”4Â“°Ğ¢FW‡G2æFEf–Wr‡F—FÆR‡F—FÆUFW‡BÂFb’ÂÖF6…w&‚’“°Ğ¢FW‡G2æFEf–Wr‡7V'F—FÆR‡7V'F—FÆUFW‡B’ÂÖF6…w&‚’“°Ğ¢—FVÒæFEf–Wr‡FW‡G2ÂvV–v‡E&×2ƒb’“°Ğ Ğ¢FW‡Ef–Wr'&÷rÒ&öG•FW‡B‚.(¢"“°Ğ¢'&÷rç6WEFW‡E6—¦Rƒ#†b“°Ğ¢'&÷rç6WDw&f—G’„w&f—G’ä4TåDU"“°Ğ¢—FVÒæFEf–Wr†'&÷rÂf—†VE&×2ƒ#BÂC‚’“°Ğ¢&WGW&â—FVÓ°Ğ¢ĞĞ Ğ¢&—fFRf–Wr6WGF–æu&÷r…7G&–ær–6öâÂ7G&–ærF—FÆUFW‡BÂ7G&–ær7V'F—FÆUFW‡BÂ&ööÆVâ6†V6¶VBÂf–Wräöä6Æ–6´Æ—7FVæW"Æ—7FVæW"’°Ğ¢Æ–æV$Æ–÷WB—FVÒÒ&÷r‚“°Ğ¢—FVÒç6WDw&f—G’„w&f—G’ä4TåDU%õdU%D”4Â“°Ğ¢—FVÒç6WEFF–ærƒÂGƒ‚’ÂÂGƒ‚’“°Ğ¢–b†Æ—7FVæW"ÒçVÆÂ’°Ğ¢—FVÒç6WDöä6Æ–6´Æ—7FVæW"†Æ—7FVæW"“°Ğ¢ĞĞ Ğ¢FW‡Ef–Wr–6öåf–WrÒæWrFW‡Ef–Wr‡F†—2“°Ğ¢–6öåf–Wrç6WEFW‡B†–6öâ“°Ğ¢–6öåf–Wrç6WEFW‡E6—¦Rƒ#b“°Ğ¢–6öåf–Wrç6WEFW‡D6öÆ÷"„4ôÄõ%ôu$TTâ“°Ğ¢—FVÒæFEf–Wr†–6öåf–WrÂf—†VE&×2ƒ3‚ÂCB’“°Ğ Ğ¢Æ–æV$Æ–÷WBFW‡G2ÒæWrÆ–æV$Æ–÷WB‡F†—2“°Ğ¢FW‡G2ç6WD÷&–VçFF–öâ„Æ–æV$Æ–÷WBådU%D”4Â“°Ğ¢FW‡G2æFEf–Wr‡F—FÆR‡F—FÆUFW‡BÂFb’ÂÖF6…w&‚’“°Ğ¢FW‡G2æFEf–Wr‡7V'F—FÆR‡7V'F—FÆUFW‡B’ÂÖF6…w&‚’“°Ğ¢—FVÒæFEf–Wr‡FW‡G2ÂvV–v‡E&×2ƒb’“°Ğ Ğ¢–b†Æ—7FVæW"ÓÒçVÆÂ’°Ğ¢6†V6´&÷‚6†V6´&÷‚ÒæWr6†V6´&÷‚‡F†—2“°Ğ¢6†V6´&÷‚ç6WD6†V6¶VB†6†V6¶VB“°Ğ¢6†V6´&÷‚ç6WDVæ&ÆVB†fÇ6R“°Ğ¢—FVÒæFEf–Wr†6†V6´&÷‚Âw&&×2‚’“°Ğ¢ÒVÇ6R°Ğ¢FW‡Ef–Wr'&÷rÒ&öG•FW‡B‚.(¢"“°Ğ¢'&÷rç6WEFW‡E6—¦Rƒ#fb“°Ğ¢—FVÒæFEf–Wr†'&÷rÂw&&×2‚’“°Ğ¢ĞĞ¢&WGW&â—FVÓ°Ğ¢ĞĞ Ğ¢&—fFRÆ–æV$Æ–÷WB6&B‚’°Ğ¢Æ–æV$Æ–÷WB6&BÒæWrÆ–æV$Æ–÷WB‡F†—2“°Ğ¢6&Bç6WD÷&–VçFF–öâ„Æ–æV$Æ–÷WBådU%D”4Â“°Ğ¢6&Bç6WEFF–ær†GƒB’ÂGƒ"’ÂGƒB’ÂGƒ"’“°Ğ¢6&Bç6WD&6¶w&÷VæB‡&÷VæFVB„4ôÄõ%ô4$BÂGƒ"’Â4ôÄõ%ôÄ”äR’“°Ğ¢6&Bç6WDVÆWfF–öâ†Gƒ"’“°Ğ¢&WGW&â6&C°Ğ¢ĞĞ Ğ¢&—fFRÆ–æV$Æ–÷WB&÷r‚’°Ğ¢Æ–æV$Æ–÷WB&÷rÒæWrÆ–æV$Æ–÷WB‡F†—2“°Ğ¢&÷rç6WD÷&–VçFF–öâ„Æ–æV$Æ–÷WBä„õ$•¤ôåDÂ“°Ğ¢&WGW&â&÷s°Ğ¢ĞĞ Ğ¢&—fFRÆ–æV$Æ–÷WB'WGFöå&÷r…f–Wrâââ'WGFöç2’°Ğ¢Æ–æV$Æ–÷WB&÷rÒ&÷r‚“°Ğ¢&÷rç6WDw&f—G’„w&f—G’ä4TåDU"“°Ğ¢f÷"…f–Wr'WGFöâ¢'WGFöç2’°Ğ¢&÷ræFEf–Wr†'WGFöâÂvV–v‡E&×2ƒb’“°Ğ¢ĞĞ¢&WGW&â&÷s°Ğ¢ĞĞ Ğ¢&—fFRFW‡Ef–WrF—FÆR…7G&–ærFW‡BÂfÆöB6—¦R’°Ğ¢FW‡Ef–Wrf–WrÒæWrFW‡Ef–Wr‡F†—2“°Ğ¢f–Wrç6WEFW‡B‡FW‡B“°Ğ¢f–Wrç6WEFW‡D6öÆ÷"„4ôÄõ%õDU…B“°Ğ¢f–Wrç6WEFW‡E6—¦R‡6—¦R“°Ğ¢f–Wrç6WEG—Vf6R…G—Vf6RäDTdTÅEô$ôÄB“°Ğ¢&WGW&âf–Ws°Ğ¢ĞĞ Ğ¢&—fFRFW‡Ef–Wr7V'F—FÆR…7G&–ærFW‡B’°Ğ¢FW‡Ef–Wrf–WrÒæWrFW‡Ef–Wr‡F†—2“°Ğ¢f–Wrç6WEFW‡B‡FW‡B“°Ğ¢f–Wrç6WEFW‡D6öÆ÷"„4ôÄõ%ôÕUDTB“°Ğ¢f–Wrç6WEFW‡E6—¦Rƒ6b“°Ğ¢&WGW&âf–Ws°Ğ¢ĞĞ Ğ¢&—fFRFW‡Ef–Wr&öG•FW‡B…7G&–ærFW‡B’°Ğ¢FW‡Ef–Wrf–WrÒæWrFW‡Ef–Wr‡F†—2“°Ğ¢f–Wrç6WEFW‡B‡FW‡B“°Ğ¢f–Wrç6WEFW‡D6öÆ÷"„4ôÄõ%õDU…B“°Ğ¢f–Wrç6WEFW‡E6—¦RƒFb“°Ğ¢f–Wrç6WDÆ–æU76–ær†Gƒ"’Âãb“°Ğ¢&WGW&âf–Ws°Ğ¢ĞĞ Ğ¢&—fFRFW‡Ef–Wr6V7F–öäÆ&VÂ…7G&–ærFW‡B’°Ğ¢FW‡Ef–Wrf–WrÒF—FÆR‡FW‡BÂ&b“°Ğ¢f–Wrç6WEFW‡D6öÆ÷"„4ôÄõ%ôÕUDTB“°Ğ¢f–Wrç6WEFF–ærƒÂÂÂGƒ‚’“°Ğ¢&WGW&âf–Ws°Ğ¢ĞĞ Ğ¢&—fFRFW‡Ef–Wr6V7F–öä†VFW"…7G&–ærÆVgBÂ7G&–ær&–v‡B’°Ğ¢&WGW&â6V7F–öäÆ&VÂ†ÆVgB²‡&–v‡BÓÒçVÆÂÇÂ&–v‡Bæ—4V×G’‚’ò""¢"+r"²&–v‡B’“°Ğ¢ĞĞ Ğ¢&—fFRFW‡Ef–Wr6†—…7G&–ærFW‡B’°Ğ¢FW‡Ef–Wrf–WrÒæWrFW‡Ef–Wr‡F†—2“°Ğ¢f–Wrç6WEFW‡B‡FW‡B“°Ğ¢f–Wrç6WEFW‡E6—¦Rƒb“°Ğ¢f–Wrç6WEG—Vf6R…G—Vf6RäDTdTÅEô$ôÄB“°Ğ¢f–Wrç6WEFW‡D6öÆ÷"„4ôÄõ%ôu$TTâ“°Ğ¢f–Wrç6WEFF–ær†Gƒ’ÂGƒR’ÂGƒ’ÂGƒR’“°Ğ¢f–Wrç6WD&6¶w&÷VæB‡&÷VæFVB„4ôÄõ%õ4ôeEôu$TTâÂGƒB’Â’“°Ğ¢&WGW&âf–Ws°Ğ¢ĞĞ Ğ¢&—fFRVF—EFW‡BVF—EFW‡B…7G&–ær†–çBÂ–çB–çWEG—R’°Ğ¢VF—EFW‡BVF—EFW‡BÒæWrVF—EFW‡B‡F†—2“°Ğ¢VF—EFW‡Bç6WD†–çB††–çB“°Ğ¢VF—EFW‡Bç6WE6–ævÆTÆ–æR‡G'VR“°Ğ¢VF—EFW‡Bç6WD–çWEG—R†–çWEG—R“°Ğ¢VF—EFW‡Bç6WEFW‡D6öÆ÷"„4ôÄõ%õDU…B“°Ğ¢VF—EFW‡Bç6WD†–çEFW‡D6öÆ÷"„4ôÄõ%ôÕUDTB“°Ğ¢VF—EFW‡Bç6WEFW‡E6—¦RƒVb“°Ğ¢VF—EFW‡Bç6WEFF–ær†Gƒ"’ÂGƒ‚’ÂGƒ"’ÂGƒ‚’“°Ğ¢VF—EFW‡Bç6WD&6¶w&÷VæB‡&÷VæFVB„6öÆ÷"ç&v"ƒ#C‚Â#SÂ#C’’ÂGƒ‚’Â4ôÄõ%ôÄ”äR’“°Ğ¢&WGW&âVF—EFW‡C°Ğ¢ĞĞ Ğ¢&—fFR'WGFöâ&–Ö'”'WGFöâ…7G&–ærFW‡BÂf–Wräöä6Æ–6´Æ—7FVæW"Æ—7FVæW"’°Ğ¢'WGFöâ'WGFöâÒ&6T'WGFöâ‡FW‡BÂÆ—7FVæW"“°Ğ¢'WGFöâç6WEFW‡D6öÆ÷"„6öÆ÷"åt„•DR“°Ğ¢'WGFöâç6WD&6¶w&÷VæB‡&÷VæFVB„4ôÄõ%ôu$TTâÂGƒ’Â’“°Ğ¢&WGW&â'WGFöã°Ğ¢ĞĞ Ğ¢&—fFR'WGFöâ6V6öæF'”'WGFöâ…7G&–ærFW‡BÂf–Wräöä6Æ–6´Æ—7FVæW"Æ—7FVæW"’°Ğ¢'WGFöâ'WGFöâÒ&6T'WGFöâ‡FW‡BÂÆ—7FVæW"“°Ğ¢'WGFöâç6WEFW‡D6öÆ÷"„4ôÄõ%õDU…B“°Ğ¢'WGFöâç6WD&6¶w&÷VæB‡&÷VæFVB„6öÆ÷"åt„•DRÂGƒ’Â4ôÄõ%ôÄ”äR’“°Ğ¢&WGW&â'WGFöã°Ğ¢ĞĞ Ğ¢&—fFR'WGFöâ6ÖÆÄ'WGFöâ…7G&–ærFW‡BÂf–Wräöä6Æ–6´Æ—7FVæW"Æ—7FVæW"’°Ğ¢'WGFöâ'WGFöâÒ6V6öæF'”'WGFöâ‡FW‡BÂÆ—7FVæW"“°Ğ¢'WGFöâç6WEFW‡E6—¦Rƒ6b“°Ğ¢&WGW&â'WGFöã°Ğ¢ĞĞ Ğ¢&—fFR'WGFöâ&÷VæEÆ”'WGFöâ…f–Wräöä6Æ–6´Æ—7FVæW"Æ—7FVæW"’°Ğ¢'WGFöâ'WGFöâÒ&6T'WGFöâ‚.(Z"ÂÆ—7FVæW"“°Ğ¢'WGFöâç6WEFW‡D6öÆ÷"„6öÆ÷"åt„•DR“°Ğ¢'WGFöâç6WEFW‡E6—¦Rƒ#&b“°Ğ¢'WGFöâç6WD&6¶w&÷VæB‡&÷VæFVB„4ôÄõ%ôu$TTâÂGƒ#‚’Â’“°Ğ¢&WGW&â'WGFöã°Ğ¢ĞĞ Ğ¢&—fFR'WGFöâæd'WGFöâ…7G&–ærFW‡BÂ&ööÆVâ7F—fRÂf–Wräöä6Æ–6´Æ—7FVæW"Æ—7FVæW"’°Ğ¢'WGFöâ'WGFöâÒ&6T'WGFöâ‡FW‡BÂÆ—7FVæW"“°Ğ¢'WGFöâç6WEFW‡E6—¦Rƒ&b“°Ğ¢'WGFöâç6WEFW‡D6öÆ÷"†7F—fRò6öÆ÷"åt„•DR¢4ôÄõ%õDU…B“°Ğ¢'WGFöâç6WD&6¶w&÷VæB‡&÷VæFVB†7F—fRò4ôÄõ%ôu$TTâ¢6öÆ÷"åt„•DRÂGƒ’Â7F—fRò¢4ôÄõ%ôÄ”äR’“°Ğ¢&WGW&â'WGFöã°Ğ¢ĞĞ Ğ¢&—fFR'WGFöâ&6T'WGFöâ…7G&–ærFW‡BÂf–Wräöä6Æ–6´Æ—7FVæW"Æ—7FVæW"’°Ğ¢'WGFöâ'WGFöâÒæWr'WGFöâ‡F†—2“°Ğ¢'WGFöâç6WEFW‡B‡FW‡B“°Ğ¢'WGFöâç6WDÆÄ62†fÇ6R“°Ğ¢'WGFöâç6WDÖ–ä†V–v‡B†GƒCB’“°Ğ¢'WGFöâç6WEFF–ær†Gƒ’ÂGƒb’ÂGƒ’ÂGƒb’“°Ğ¢'WGFöâç6WDöä6Æ–6´Æ—7FVæW"†Æ—7FVæW"“°Ğ¢&WGW&â'WGFöã°Ğ¢ĞĞ Ğ¢&—fFRw&F–VçDG&v&ÆR&÷VæFVB†–çBf–ÆÄ6öÆ÷"Â–çB&F—W2Â–çB7G&ö¶T6öÆ÷"’°Ğ¢w&F–VçDG&v&ÆRG&v&ÆRÒæWrw&F–VçDG&v&ÆR‚“°Ğ¢G&v&ÆRç6WD6öÆ÷"†f–ÆÄ6öÆ÷"“°Ğ¢G&v&ÆRç6WD6÷&æW%&F—W2‡&F—W2“°Ğ¢–b‡7G&ö¶T6öÆ÷"Ò’°Ğ¢G&v&ÆRç6WE7G&ö¶R†Gƒ’Â7G&ö¶T6öÆ÷"“°Ğ¢ĞĞ¢&WGW&âG&v&ÆS°Ğ¢ĞĞ Ğ¢&—fFRÆ–æV$Æ–÷WBäÆ–÷WE&×2ÖF6…w&‚’°Ğ¢&WGW&âæWrÆ–æV$Æ–÷WBäÆ–÷WE&×2€Ğ¢Æ–æV$Æ–÷WBäÆ–÷WE&×2äÔD4…õ$TåBÀĞ¢Æ–æV$Æ–÷WBäÆ–÷WE&×2åu$ô4ôåDTå@Ğ¢“°Ğ¢ĞĞ Ğ¢&—fFRÆ–æV$Æ–÷WBäÆ–÷WE&×2w&&×2‚’°Ğ¢&WGW&âæWrÆ–æV$Æ–÷WBäÆ–÷WE&×2€Ğ¢Æ–æV$Æ–÷WBäÆ–÷WE&×2åu$ô4ôåDTåBÀĞ¢Æ–æV$Æ–÷WBäÆ–÷WE&×2åu$ô4ôåDTå@Ğ¢“°Ğ¢ĞĞ Ğ¢&—fFRÆ–æV$Æ–÷WBäÆ–÷WE&×2vV–v‡E&×2†fÆöBvV–v‡B’°¢Æ–æV$Æ–÷WBäÆ–÷WE&×2&×2ÒæWrÆ–æV$Æ–÷WBäÆ–÷WE&×2ƒÂÆ–æV$Æ–÷WBäÆ–÷WE&×2åu$ô4ôåDTåBÂvV–v‡B“°Ğ¢&×2ç6WDÖ&v–ç2†Gƒ2’ÂGƒ2’ÂGƒ2’ÂGƒ2’“°Ğ¢&WGW&â&×3°¢Ğ ¢&—fFRÆ–æV$Æ–÷WBäÆ–÷WE&×2vV–v‡DæôÖ&v–â†fÆöBvV–v‡B’°¢&WGW&âæWrÆ–æV$Æ–÷WBäÆ–÷WE&×2ƒÂÆ–æV$Æ–÷WBäÆ–÷WE&×2åu$ô4ôåDTåBÂvV–v‡B“°¢Ğ Ğ¢&—fFRÆ–æV$Æ–÷WBäÆ–÷WE&×2f—†VE&×2†–çBv–GF„GÂ–çB†V–v‡DG’°Ğ¢&WGW&âæWrÆ–æV$Æ–÷WBäÆ–÷WE&×2†G‡v–GF„G’ÂG††V–v‡DG’“°Ğ¢ĞĞ Ğ¢&—fFRÆ–æV$Æ–÷WBäÆ–÷WE&×26&E&×2‚’°¢&WGW&âÖ&v–å&×2ƒÂÂÂ"“°¢Ğ ¢&—fFRÆ–æV$Æ–÷WBäÆ–÷WE&×2ÖöFW&ä6&E&×2†–çB&÷GFöÒ’°¢&WGW&âÖ&v–å&×2ƒÂÂÂ&÷GFöÒ“°¢Ğ ¢&—fFRÆ–æV$Æ–÷WBäÆ–÷WE&×2ÖF6„†V–v‡E&×2†–çB†V–v‡DG’°¢&WGW&âæWrÆ–æV$Æ–÷WBäÆ–÷WE&×2€¢Æ–æV$Æ–÷WBäÆ–÷WE&×2äÔD4…õ$TåBÀ¢G††V–v‡DG¢“°¢Ğ ¢&—fFRÆ–æV$Æ–÷WBäÆ–÷WE&×2Ö&v–ä†V–v‡E&×2†–çBÆVgBÂ–çBF÷Â–çB&–v‡BÂ–çB&÷GFöÒÂ–çB†V–v‡DG’°¢Æ–æV$Æ–÷WBäÆ–÷WE&×2&×2ÒÖF6„†V–v‡E&×2††V–v‡DG“°¢&×2ç6WDÖ&v–ç2†G†ÆVgB’ÂG‡F÷’ÂG‡&–v‡B’ÂG†&÷GFöÒ’“°¢&WGW&â&×3°¢Ğ ¢&—fFRÆ–æV$Æ–÷WBäÆ–÷WE&×2Ö&v–å&×2†–çBÆVgBÂ–çBF÷Â–çB&–v‡BÂ–çB&÷GFöÒÂ–çB†V–v‡DG’°¢&WGW&âÖ&v–ä†V–v‡E&×2†ÆVgBÂF÷Â&–v‡BÂ&÷GFöÒÂ†V–v‡DG“°¢Ğ Ğ¢&—fFRÆ–æV$Æ–÷WBäÆ–÷WE&×26VçFW&VD'WGFöå&×2‚’°Ğ¢Æ–æV$Æ–÷WBäÆ–÷WE&×2&×2ÒæWrÆ–æV$Æ–÷WBäÆ–÷WE&×2€Ğ¢Æ–æV$Æ–÷WBäÆ–÷WE&×2åu$ô4ôåDTåBÀĞ¢Æ–æV$Æ–÷WBäÆ–÷WE&×2åu$ô4ôåDTå@Ğ¢“°Ğ¢&×2æw&f—G’Òw&f—G’ä4TåDU%ô„õ$•¤ôåDÃ°Ğ¢&×2ç6WDÖ&v–ç2ƒÂGƒ’ÂÂGƒ"’“°Ğ¢&WGW&â&×3°Ğ¢ĞĞ Ğ¢&—fFRÆ–æV$Æ–÷WBäÆ–÷WE&×2Ö&v–å&×2†–çBÆVgBÂ–çBF÷Â–çB&–v‡BÂ–çB&÷GFöÒ’°Ğ¢Æ–æV$Æ–÷WBäÆ–÷WE&×2&×2ÒÖF6…w&‚“°Ğ¢&×2ç6WDÖ&v–ç2†G†ÆVgB’ÂG‡F÷’ÂG‡&–v‡B’ÂG†&÷GFöÒ’“°Ğ¢&WGW&â&×3°Ğ¢ĞĞ Ğ¢&—fFR–çBG†–çBfÇVR’°¢&WGW&â†–çB’‡fÇVR¢vWE&W6÷W&6W2‚’ævWDF—7Æ”ÖWG&–72‚’æFVç6—G’²ãVb“°¢Ğ ¢&—fFRf–æÂ6Æ726÷§•&ööÕf–WrW‡FVæG2f–Wr°¢&—fFRf–æÂ–çBW'ÆU–çBÒæWr–çB…–çBäåD•ôÄ”5ôdÄr“°¢&—fFRf–æÂ–çBw&VVå–çBÒæWr–çB…–çBäåD•ôÄ”5ôdÄr“°¢&—fFRf–æÂ–çB6ögE–çBÒæWr–çB…–çBäåD•ôÄ”5ôdÄr“°¢&—fFRf–æÂF‚6†FUF‚ÒæWrF‚‚“°¢&—fFRf–æÂF‚7W6†–öåF‚ÒæWrF‚‚“°¢&—fFRf–æÂF‚–7GW&UF‚ÒæWrF‚‚“° ¢6÷§•&ööÕf–Wr„6öçFW‡B6öçFW‡B’°¢7WW"†6öçFW‡B“°¢W'ÆU–çBç6WE7G–ÆR…–çBå7G–ÆRå5E$ô´R“°¢W'ÆU–çBç6WE7G&ö¶Uv–GF‚†Gƒ’“°¢W'ÆU–çBç6WE7G&ö¶T6…–çBä6å$õTäB“°¢W'ÆU–çBç6WE7G&ö¶T¦ö–â…–çBä¦ö–âå$õTäB“°¢W'ÆU–çBç6WD6öÆ÷"„ÔôDU$åõU%ÄR“° ¢w&VVå–çBç6WB‡W'ÆU–çB“°¢w&VVå–çBç6WD6öÆ÷"„ÔôDU$åôu$TTâ“° ¢6ögE–çBç6WE7G–ÆR…–çBå7G–ÆRäd”ÄÂ“°¢6ögE–çBç6WD6öÆ÷"†—4F&´ÖöFR‚’ò6öÆ÷"ç&v"ƒcÂSRÂs"’¢6öÆ÷"ç&v"ƒ#SÂ#CrÂ#C’“°¢6WD6öçFVçDFW67&—F–öâ‚-
=í-İò=í-İòÍ=}½­½Íİí’­í½íİ­í’"“°¢Ğ ¢÷fW'&–FP¢&÷FV7FVBfö–BöäG&r„6çf26çf2’°¢7WW"æöäG&r†6çf2“°¢fÆöBrÒvWEv–GF‚‚“°¢fÆöB‚ÒvWD†V–v‡B‚“°¢6çf2æG&u&÷VæE&V7B‡r¢ã–bÂ‚¢ãC6bÂr¢ãsvbÂ‚¢ãsfbÂGƒ‚’ÂGƒ‚’Â6ögE–çB“° ¢òòfÆö÷"æBÆ×à¢6çf2æG&tÆ–æR‡r¢ãFbÂ‚¢ãs†bÂr¢ã“FbÂ‚¢ãs†bÂW'ÆU–çB“°¢6çf2æG&tÆ–æR‡r¢ãbÂ‚¢ã3vbÂr¢ãbÂ‚¢ãs†bÂW'ÆU–çB“°¢6çf2æG&tÆ–æR‡r¢ãvbÂ‚¢ãs†bÂr¢ãVbÂ‚¢ãs†bÂW'ÆU–çB“°¢6†FUF‚ç&W6WB‚“°¢6†FUF‚æÖ÷fUFò‡r¢ãcVbÂ‚¢ã3vb“°¢6†FUF‚æÆ–æUFò‡r¢ãSVbÂ‚¢ã3vb“°¢6†FUF‚æÆ–æUFò‡r¢ãFbÂ‚¢ã#fb“°¢6†FUF‚æÆ–æUFò‡r¢ã†bÂ‚¢ã#fb“°¢6†FUF‚æ6Æ÷6R‚“°¢6çf2æG&uF‚‡6†FUF‚ÂW'ÆU–çB“° ¢òò6öfà¢6çf2æG&u&÷VæE&V7B‡r¢ã#bÂ‚¢ãCfbÂr¢ãs6bÂ‚¢ãs&bÂGƒb’ÂGƒb’ÂW'ÆU–çB“°¢6çf2æG&u&÷VæE&V7B‡r¢ãfbÂ‚¢ãSVbÂr¢ã#VbÂ‚¢ãsfbÂGƒ’ÂGƒ’ÂW'ÆU–çB“°¢6çf2æG&u&÷VæE&V7B‡r¢ãc–bÂ‚¢ãSVbÂr¢ãs†bÂ‚¢ãsfbÂGƒ’ÂGƒ’ÂW'ÆU–çB“°¢6çf2æG&tÆ–æR‡r¢ãCfbÂ‚¢ãCfbÂr¢ãCfbÂ‚¢ãcfbÂW'ÆU–çB“°¢6çf2æG&tÆ–æR‡r¢ã#6bÂ‚¢ãcvbÂr¢ãsbÂ‚¢ãcvbÂW'ÆU–çB“°¢6çf2æG&tÆ–æR‡r¢ã#FbÂ‚¢ãs&bÂr¢ã#6bÂ‚¢ãƒbÂW'ÆU–çB“°¢6çf2æG&tÆ–æR‡r¢ãsbÂ‚¢ãs&bÂr¢ãs&bÂ‚¢ãƒbÂW'ÆU–çB“° ¢òò7W6†–öâæBvÆÂ–7GW&Rà¢7W6†–öåF‚ç&W6WB‚“°¢7W6†–öåF‚æÖ÷fUFò‡r¢ã#VbÂ‚¢ãS&b“°¢7W6†–öåF‚æÆ–æUFò‡r¢ã3FbÂ‚¢ãSb“°¢7W6†–öåF‚æÆ–æUFò‡r¢ã3fbÂ‚¢ãc&b“°¢7W6†–öåF‚æÆ–æUFò‡r¢ã#vbÂ‚¢ãcFb“°¢7W6†–öåF‚æ6Æ÷6R‚“°¢6çf2æG&uF‚†7W6†–öåF‚ÂW'ÆU–çB“°¢6çf2æG&u&V7B‡r¢ãcbÂ‚¢ã#&bÂr¢ãsfbÂ‚¢ãCbÂW'ÆU–çB“°¢6çf2æG&u&V7B‡r¢ãc6bÂ‚¢ã#FbÂr¢ãsFbÂ‚¢ã3–bÂW'ÆU–çB“°¢–7GW&UF‚ç&W6WB‚“°¢–7GW&UF‚æÖ÷fUFò‡r¢ãcFbÂ‚¢ã3fb“°¢–7GW&UF‚æ7V&–5Fò‡r¢ãcvbÂ‚¢ã3bÂr¢ãsbÂ‚¢ã3†bÂr¢ãsFbÂ‚¢ã#–b“°¢6çf2æG&uF‚‡–7GW&UF‚ÂW'ÆU–çB“° ¢òòF&ÆRæB7V¶W"à¢6çf2æG&t÷fÂ‡r¢ã3bÂ‚¢ãsbÂr¢ãcFbÂ‚¢ãƒ&bÂW'ÆU–çB“°¢6çf2æG&tÆ–æR‡r¢ã3vbÂ‚¢ãs–bÂr¢ã3FbÂ‚¢ã“FbÂW'ÆU–çB“°¢6çf2æG&tÆ–æR‡r¢ãS†bÂ‚¢ãs–bÂr¢ãcbÂ‚¢ã“FbÂW'ÆU–çB“°¢6çf2æG&u&÷VæE&V7B‡r¢ãCFbÂ‚¢ãS–bÂr¢ãS6bÂ‚¢ãs6bÂGƒr’ÂGƒr’ÂW'ÆU–çB“°¢6çf2æG&t6—&6ÆR‡r¢ãCƒVbÂ‚¢ãc†bÂGƒ‚’ÂW'ÆU–çB“°¢6çf2æG&t6—&6ÆR‡r¢ãCƒVbÂ‚¢ãc#VbÂGƒ2’ÂW'ÆU–çB“°¢6çf2æG&uFW‡B‚.)š¢"Âr¢ãCSVbÂ‚¢ãSfbÂW'ÆU–çB“°¢6çf2æG&uFW‡B‚.)š¢"Âr¢ãSVbÂ‚¢ãS6bÂW'ÆU–çB“° ¢òòÆçBæB÷Bà¢6çf2æG&u&÷VæE&V7B‡r¢ãƒ&bÂ‚¢ãc–bÂr¢ã“bÂ‚¢ãƒ6bÂGƒB’ÂGƒB’Âw&VVå–çB“°¢6çf2æG&tÆ–æR‡r¢ãƒcVbÂ‚¢ãsbÂr¢ãƒcVbÂ‚¢ã3†bÂw&VVå–çB“°¢6çf2æG&t÷fÂ‡r¢ãs†bÂ‚¢ãC6bÂr¢ãƒcVbÂ‚¢ãSFbÂw&VVå–çB“°¢6çf2æG&t÷fÂ‡r¢ãƒcVbÂ‚¢ã3fbÂr¢ã“FbÂ‚¢ãSbÂw&VVå–çB“°¢6çf2æG&t÷fÂ‡r¢ãs–bÂ‚¢ãSFbÂr¢ãƒcVbÂ‚¢ãcVbÂw&VVå–çB“°¢6çf2æG&t÷fÂ‡r¢ãƒcVbÂ‚¢ãSbÂr¢ã“FbÂ‚¢ãc6bÂw&VVå–çB“°¢Ğ¢Ğ Ğ¢&—fFR7FF–27G&–ær‡VÖä'—FW2†Æöær'—FW2’°Ğ¢–b†'—FW2Â’°Ğ¢&WGW&â'Væ¶æ÷vâ#°Ğ¢ĞĞ¢–b†'—FW2Â#B’°Ğ¢&WGW&â'—FW2²""#°Ğ¢ĞĞ¢F÷V&ÆR¶"Ò'—FW2ò#Bã°Ğ¢–b†¶"Â#B’°Ğ¢&WGW&â7G&–æræf÷&ÖB„Æö6ÆRåU2Â"Rãb´""Â¶"“°Ğ¢ĞĞ¢F÷V&ÆRÖ"Ò¶"ò#Bã°Ğ¢&WGW&â7G&–æræf÷&ÖB„Æö6ÆRåU2Â"RãbÔ""ÂÖ"“°Ğ¢ĞĞ Ğ¢&—fFR7FF–27G&–ær6æ—F—¦Tf–ÆTæÖR…7G&–æræÖR’°Ğ¢–b†æÖRÓÒçVÆÂÇÂæÖRçG&–Ò‚’æ—4V×G’‚’’°Ğ¢&WGW&â&VF–õög&öÕö†÷7B#°Ğ¢ĞĞ¢&WGW&âæÖRç&WÆ6TÆÂ‚%µæ×¤Õ£Óİı	İ
ıåòÕÒ"Â%ò"“°Ğ¢ĞĞ§ĞĞ