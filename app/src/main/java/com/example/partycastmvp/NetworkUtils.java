package com.example.partycastmvp;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.os.Build;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.net.SocketFactory;

public final class NetworkUtils {
    private NetworkUtils() {}

    public static Network getWifiNetwork(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return null;
        }

        Network[] networks = cm.getAllNetworks();
        for (Network network : networks) {
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return network;
            }
        }
        return null;
    }

    public static SocketFactory getWifiSocketFactory(Context context) {
        Network wifiNetwork = getWifiNetwork(context);
        if (wifiNetwork == null) {
            return null;
        }
        return wifiNetwork.getSocketFactory();
    }

    public static boolean clearProcessNetworkBinding(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm != null && cm.bindProcessToNetwork(null);
    }

    public static String getWifiIpv4Address(Context context) {
        LinkProperties properties = getWifiLinkProperties(context);
        if (properties == null) {
            return null;
        }

        for (LinkAddress linkAddress : properties.getLinkAddresses()) {
            InetAddress address = linkAddress.getAddress();
            if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                String value = address.getHostAddress();
                if (value != null && !value.startsWith("169.254.")) {
                    return value;
                }
            }
        }
        return null;
    }

    public static String getWifiPrefix24(Context context) {
        String ip = getWifiIpv4Address(context);
        if (ip == null) {
            return null;
        }
        int lastDot = ip.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        return ip.substring(0, lastDot + 1);
    }

    public static String getWifiGatewayIpv4Address(Context context) {
        LinkProperties properties = getWifiLinkProperties(context);
        if (properties == null) {
            return null;
        }

        for (RouteInfo route : properties.getRoutes()) {
            InetAddress gateway = route.getGateway();
            if (gateway instanceof Inet4Address && !gateway.isLoopbackAddress()) {
                String value = gateway.getHostAddress();
                if (value != null && !value.startsWith("0.0.0.0")) {
                    return value;
                }
            }
        }
        return null;
    }

    public static List<String> getHotspotHostCandidates(Context context) {
        Set<String> candidates = new LinkedHashSet<>();
        String gateway = getWifiGatewayIpv4Address(context);
        if (gateway != null && !gateway.trim().isEmpty()) {
            candidates.add(gateway.trim());
        }

        String prefix = getWifiPrefix24(context);
        if (prefix != null) {
            candidates.add(prefix + "1");
        }

        candidates.add("192.168.43.1");
        candidates.add("192.168.49.1");
        candidates.add("172.20.10.1");
        candidates.add("192.168.1.1");
        candidates.add("192.168.0.1");
        candidates.add("10.0.0.1");

        return new ArrayList<>(candidates);
    }

    public static Socket openWifiSocket(Context context, String host, int port, int timeoutMs) throws Exception {
        SocketFactory factory = getWifiSocketFactory(context);
        Socket socket = factory != null ? factory.createSocket() : new Socket();
        socket.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
        socket.setTcpNoDelay(true);
        return socket;
    }

    public static String describeNetwork(Context context) {
        String wifiIp = getWifiIpv4Address(context);
        String gateway = getWifiGatewayIpv4Address(context);
        Network wifiNetwork = getWifiNetwork(context);
        SocketFactory wifiFactory = getWifiSocketFactory(context);
        boolean processUnbound = clearProcessNetworkBinding(context);

        StringBuilder sb = new StringBuilder();
        sb.append("Wi‑Fi IP: ").append(wifiIp == null ? "не найден" : wifiIp);
        sb.append("\nGateway: ").append(gateway == null ? "не найден" : gateway);
        sb.append("\nWi‑Fi Network object: ").append(wifiNetwork == null ? "не найден" : "найден");
        sb.append("\nWi‑Fi SocketFactory: ").append(wifiFactory == null ? "недоступен" : "доступен");
        sb.append("\nProcess binding: ").append(processUnbound ? "сброшен" : "не удалось сбросить");
        sb.append("\n\nРежим локальной комнаты:");
        sb.append("\n- PartyCast ищет хост и передаёт файлы по текущей Wi‑Fi/Hotspot сети.");
        sb.append("\n- Если авто-поиск не нашёл хост, введите IP хоста вручную или включите режим точки доступа на телефоне-хосте.");
        sb.append("\n- Для стабильной вечеринки держите хост и участников в одной Wi‑Fi сети.");
        return sb.toString();
    }

    private static LinkProperties getWifiLinkProperties(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return null;
        }

        Network wifiNetwork = getWifiNetwork(context);
        if (wifiNetwork == null) {
            return null;
        }
        return cm.getLinkProperties(wifiNetwork);
    }
}
