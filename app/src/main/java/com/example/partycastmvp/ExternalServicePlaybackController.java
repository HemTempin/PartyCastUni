package com.example.partycastmvp;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

public final class ExternalServicePlaybackController {
    public interface Listener {
        void onLog(String message);
        void onError(String message);
    }

    private static final String PACKAGE_YANDEX_MUSIC = "ru.yandex.music";
    private static final String PACKAGE_VK_MUSIC = "com.uma.musicvk";
    private static final String PACKAGE_VK_MAIN = "com.vkontakte.android";

    private final Context context;
    private final AudioManager audioManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;

    public ExternalServicePlaybackController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.listener = listener;
    }

    public void openLinkAndTryPlay(String url, int service) {
        try {
            boolean opened;
            String serviceName = OnlineLinkHelper.detectServiceName(url);

            if (service == OnlineLinkHelper.SERVICE_YANDEX_MUSIC) {
                opened = tryOpenWithPackage(url, PACKAGE_YANDEX_MUSIC, "Яндекс Музыка");
                if (!opened) {
                    opened = tryOpenGeneric(url, serviceName);
                }
                listener.onLog("Яндекс Музыка: ссылка открыта как внешний сервис. Для надёжного добавления в очередь включите настройку YMQueueController.");
            } else if (service == OnlineLinkHelper.SERVICE_VK_MUSIC) {
                opened = tryOpenWithPackage(url, PACKAGE_VK_MUSIC, "VK Музыка");
                if (!opened) {
                    opened = tryOpenWithPackage(url, PACKAGE_VK_MAIN, "VK");
                }
                if (!opened) {
                    opened = tryOpenGeneric(url, serviceName);
                }
                listener.onLog("VK Музыка: если приложение не начнёт трек само, подтвердите запуск вручную.");
            } else {
                opened = tryOpenGeneric(url, serviceName);
            }

            if (!opened) {
                listener.onError("Не найдено приложение или браузер, способные открыть ссылку");
                return;
            }

            listener.onLog(serviceName + ": ссылка открыта. Отправляю несколько MEDIA_PLAY-команд с задержкой.");
            schedulePlayKeyAttempts();
        } catch (Exception e) {
            listener.onError("Не удалось открыть ссылку: " + e.getMessage());
        }
    }

    private boolean tryOpenWithPackage(String url, String packageName, String label) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setPackage(packageName);
            context.startActivity(intent);
            listener.onLog(label + ": открыл ссылку через package " + packageName);
            return true;
        } catch (Exception e) {
            listener.onLog(label + ": не удалось открыть через package " + packageName + " — " + e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean tryOpenGeneric(String url, String serviceName) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            listener.onLog(serviceName + ": открыл ссылку через системный обработчик Android");
            return true;
        } catch (Exception e) {
            listener.onLog(serviceName + ": системный обработчик не открыл ссылку — " + e.getClass().getSimpleName());
            return false;
        }
    }

    private void schedulePlayKeyAttempts() {
        handler.postDelayed(this::dispatchPlayKey, 1200);
        handler.postDelayed(this::dispatchPlayKey, 2600);
        handler.postDelayed(this::dispatchPlayKey, 4200);
    }

    private void dispatchPlayKey() {
        if (audioManager == null) {
            listener.onLog("AudioManager недоступен: MEDIA_PLAY не отправлен");
            return;
        }

        long now = android.os.SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0);
        KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0);
        audioManager.dispatchMediaKeyEvent(down);
        audioManager.dispatchMediaKeyEvent(up);
        listener.onLog("MEDIA_PLAY отправлен активному медиаплееру Android.");
    }
}
