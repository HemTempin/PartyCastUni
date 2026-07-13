package com.example.partycastmvp;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.SocketFactory;

public final class HostDiscovery {
    public interface Listener {
        void onLog(String message);
        void onHostFound(String hostIp, int tcpPort, String label);
    }

    public static final String SERVICE_TYPE = "_partycast._tcp.";
    private static final int PING_TIMEOUT_MS = 900;
    private static final int SUBNET_SCAN_SECONDS = 8;

    private final Context appContext;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private NsdManager.DiscoveryListener discoveryListener;
    private WifiManager.MulticastLock multicastLock;
    private volatile boolean nsdRegistered = false;
    private volatile boolean nsdDiscoveryRunning = false;
    private final Object resolveLock = new Object();
    private boolean resolvingService = false;

    public HostDiscovery(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    public void startResponder(int tcpPort, String label) {
        startNsdResponder(tcpPort, label);
    }

    public void stopResponder() {
        stopNsdResponder();
        stopNsdDiscovery();
        releaseMulticastLock();
    }

    private void startNsdResponder(int tcpPort, String label) {
        if (nsdRegistered) {
            postLog("NSD/mDNS публикация хоста уже включена");
            return;
        }

        acquireMulticastLock();
        nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            postLog("NSD/mDNS недоступен на этом устройстве");
            return;
        }

        String safeLabel = label == null || label.trim().isEmpty() ? "PartyCast Host" : label.trim();
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(safeLabel);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(tcpPort);
        try {
            serviceInfo.setAttribute("app", "PartyCast");
            serviceInfo.setAttribute("version", String.valueOf(PartyProtocol.VERSION));
        } catch (Exception ignored) {
        }

        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                nsdRegistered = true;
                postLog("NSD/mDNS: хост опубликован как " + serviceInfo.getServiceName());
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                nsdRegistered = false;
                postLog("NSD/mDNS: не удалось опубликовать хост, код " + errorCode);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                nsdRegistered = false;
                postLog("NSD/mDNS: публикация хоста остановлена");
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                postLog("NSD/mDNS: ошибка остановки публикации, код " + errorCode);
            }
        };

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
        } catch (Exception e) {
            nsdRegistered = false;
            postLog("NSD/mDNS: ошибка запуска публикации: " + e.getMessage());
        }
    }

    private void stopNsdResponder() {
        if (nsdManager != null && registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener);
            } catch (Exception ignored) {
            }
        }
        registrationListener = null;
        nsdRegistered = false;
    }

    public void discoverOnce(String roomCode) {
        discoverByNsd(roomCode);
    }

    public void discoverByNsd(String roomCode) {
        if (nsdDiscoveryRunning) {
            postLog("NSD/mDNS поиск уже идёт");
            return;
        }

        acquireMulticastLock();
        nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            postLog("NSD/mDNS недоступен на этом устройстве");
            return;
        }

        nsdDiscoveryRunning = true;
        postLog("NSD/mDNS: ищу PartyCast-хост в общей Wi‑Fi сети...");

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
                postLog("NSD/mDNS: поиск запущен");
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (!SERVICE_TYPE.equals(serviceInfo.getServiceType())) {
                    return;
                }
                postLog("NSD/mDNS: найден сервис " + serviceInfo.getServiceName() + ", проверяю PING...");
                resolveService(serviceInfo, roomCode);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                postLog("NSD/mDNS: сервис потерян: " + serviceInfo.getServiceName());
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                nsdDiscoveryRunning = false;
                postLog("NSD/mDNS: поиск остановлен");
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                nsdDiscoveryRunning = false;
                postLog("NSD/mDNS: не удалось запустить поиск, код " + errorCode);
                stopNsdDiscovery();
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                nsdDiscoveryRunning = false;
                postLog("NSD/mDNS: ошибка остановки поиска, код " + errorCode);
            }
        };

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) {
            nsdDiscoveryRunning = false;
            postLog("NSD/mDNS: ошибка поиска: " + e.getMessage());
            return;
        }

        mainHandler.postDelayed(() -> {
            if (nsdDiscoveryRunning) {
                stopNsdDiscovery();
                postLog("NSD/mDNS: поиск завершён по таймеру. Если хост не найден, попробуйте скан подсети.");
            }
        }, 9000);
    }

    private void resolveService(NsdServiceInfo serviceInfo, String roomCode) {
        synchronized (resolveLock) {
            if (resolvingService) {
                return;
            }
            resolvingService = true;
        }

        NsdManager.ResolveListener resolveListener = new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                synchronized (resolveLock) {
                    resolvingService = false;
                }
                postLog("NSD/mDNS: не удалось получить IP сервиса, код " + errorCode);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                synchronized (resolveLock) {
                    resolvingService = false;
                }
                InetAddress host = serviceInfo.getHost();
                int port = serviceInfo.getPort() > 0 ? serviceInfo.getPort() : PartyProtocol.DEFAULT_PORT;
                if (host == null) {
                    postLog("NSD/mDNS: сервис найден, но IP пустой");
                    return;
                }

                String ip = host.getHostAddress();
                verifyHost(ip, port, roomCode, "NSD/mDNS: ");
            }
        };

        try {
            nsdManager.resolveService(serviceInfo, resolveListener);
        } catch (Exception e) {
            synchronized (resolveLock) {
                resolvingService = false;
            }
            postLog("NSD/mDNS: ошибка resolveService: " + e.getMessage());
        }
    }

    private void stopNsdDiscovery() {
        if (nsdManager != null && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception ignored) {
            }
        }
        discoveryListener = null;
        nsdDiscoveryRunning = false;
    }

    public void scanLocalSubnet(String roomCode) {
        new Thread(() -> runSubnetScan(roomCode), "partycast-subnet-scan").start();
    }

    private void runSubnetScan(String roomCode) {
        String localIp = NetworkUtils.getWifiIpv4Address(appContext);
        String prefix = NetworkUtils.getWifiPrefix24(appContext);
        if (localIp == null || prefix == null) {
            postLog("Скан подсети невозможен: Wi‑Fi IP не найден. Подключитесь к общей Wi‑Fi сети или Hotspot хоста.");
            return;
        }

        postLog("Сканирую подсеть " + prefix + "1–254 через Wi‑Fi. Локальный IP: " + localIp);
        SocketFactory socketFactory = NetworkUtils.getWifiSocketFactory(appContext);
        ExecutorService executor = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(254);
        AtomicBoolean found = new AtomicBoolean(false);
        Set<String> tried = new HashSet<>();

        for (int i = 1; i <= 254; i++) {
            String candidate = prefix + i;
            if (candidate.equals(localIp) || !tried.add(candidate)) {
                latch.countDown();
                continue;
            }

            executor.execute(() -> {
                try {
                    if (!found.get()) {
                        PartyProtocol.PingResult result = PartyProtocol.pingHost(
                                candidate,
                                PartyProtocol.DEFAULT_PORT,
                                roomCode,
                                socketFactory,
                                PING_TIMEOUT_MS
                        );
                        if (found.compareAndSet(false, true)) {
                            mainHandler.post(() -> listener.onHostFound(result.host, result.port, "Subnet scan: " + result.label));
                            postLog("Скан подсети: хост найден " + result.host + ":" + result.port);
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(SUBNET_SCAN_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        executor.shutdownNow();

        if (!found.get()) {
            postLog("Скан подсети завершён: хост не найден. Проверьте код комнаты, Wi‑Fi/Hotspot и запущен ли хост.");
        }
    }

    public void connectToHotspotHost(String roomCode) {
        new Thread(() -> runHotspotConnect(roomCode), "partycast-hotspot-connect").start();
    }

    private void runHotspotConnect(String roomCode) {
        List<String> candidates = NetworkUtils.getHotspotHostCandidates(appContext);
        SocketFactory socketFactory = NetworkUtils.getWifiSocketFactory(appContext);
        postLog("Hotspot mode: проверяю IP хоста-точки доступа: " + candidates);

        for (String candidate : candidates) {
            try {
                PartyProtocol.PingResult result = PartyProtocol.pingHost(
                        candidate,
                        PartyProtocol.DEFAULT_PORT,
                        roomCode,
                        socketFactory,
                        1200
                );
                mainHandler.post(() -> listener.onHostFound(result.host, result.port, "Hotspot: " + result.label));
                postLog("Hotspot mode: хост найден " + result.host + ":" + result.port);
                return;
            } catch (Exception ignored) {
            }
        }

        postLog("Hotspot mode: быстрый поиск не нашёл хост. Запускаю скан подсети как запасной вариант...");
        runSubnetScan(roomCode);
    }

    private void verifyHost(String ip, int port, String roomCode, String prefix) {
        new Thread(() -> {
            try {
                PartyProtocol.PingResult result = PartyProtocol.pingHost(
                        ip,
                        port,
                        roomCode,
                        NetworkUtils.getWifiSocketFactory(appContext),
                        1600
                );
                mainHandler.post(() -> listener.onHostFound(result.host, result.port, result.label));
                postLog(prefix + "хост подтверждён PING: " + result.host + ":" + result.port);
            } catch (Exception e) {
                postLog(prefix + "сервис найден, но PING не прошёл: " + e.getMessage());
            }
        }, "partycast-nsd-ping").start();
    }

    private void acquireMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                return;
            }
            WifiManager wifiManager = (WifiManager) appContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                multicastLock = wifiManager.createMulticastLock("partycast-mdns");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Exception ignored) {
        }
        multicastLock = null;
    }

    private void postLog(String message) {
        mainHandler.post(() -> listener.onLog(message));
    }
}
