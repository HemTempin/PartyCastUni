package com.example.partycastmvp;

import android.net.Uri;

import java.util.Locale;

public final class OnlineLinkHelper {
    private OnlineLinkHelper() {}

    public static final int SERVICE_UNKNOWN = 0;
    public static final int SERVICE_YANDEX_MUSIC = 2;
    public static final int SERVICE_VK_MUSIC = 3;
    public static final int SERVICE_SPOTIFY = 4;
    public static final int SERVICE_SOUNDCLOUD = 5;

    public static String buildDisplayTitle(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "Онлайн-ссылка";
        }
        return detectServiceName(url) + ": " + compactUrl(url);
    }

    public static String detectServiceName(String url) {
        int service = detectService(url);
        if (service == SERVICE_YANDEX_MUSIC) {
            return "Яндекс Музыка";
        }
        if (service == SERVICE_VK_MUSIC) {
            return "VK Музыка";
        }
        if (service == SERVICE_SPOTIFY) {
            return "Spotify";
        }
        if (service == SERVICE_SOUNDCLOUD) {
            return "SoundCloud";
        }
        return "Ссылка";
    }

    public static int detectService(String url) {
        String host = "";
        try {
            Uri uri = Uri.parse(url);
            if (uri.getHost() != null) {
                host = uri.getHost().toLowerCase(Locale.ROOT);
            }
        } catch (Exception ignored) {
        }
        if (host.contains("music.yandex") || host.contains("music.yandex.")) {
            return SERVICE_YANDEX_MUSIC;
        }
        if (host.contains("vk.com") || host.contains("vkontakte") || host.contains("vk.ru")) {
            return SERVICE_VK_MUSIC;
        }
        if (host.contains("spotify.com")) {
            return SERVICE_SPOTIFY;
        }
        if (host.contains("soundcloud.com")) {
            return SERVICE_SOUNDCLOUD;
        }
        return SERVICE_UNKNOWN;
    }

    public static String compactUrl(String url) {
        String value = url == null ? "" : url.trim();
        return value.length() <= 70 ? value : value.substring(0, 67) + "...";
    }

    public static boolean looksLikeHttpUrl(String url) {
        if (url == null) {
            return false;
        }
        String value = url.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("http://") || value.startsWith("https://");
    }
}
