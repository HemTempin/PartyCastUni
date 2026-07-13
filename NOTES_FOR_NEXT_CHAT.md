# PartyCast MVP v5.1 notes

Текущая версия: `0.5.1-link-clear-external-progress`.

Что добавлено после v5:

1. После успешной отправки онлайн-ссылки клиент очищает `linkInput`.
2. `ExternalServicePlaybackController` теперь для Яндекс Музыки пробует package `ru.yandex.music`, для VK — `com.uma.musicvk`, затем `com.vkontakte.android`, затем общий `ACTION_VIEW`.
3. После открытия внешней ссылки отправляются три MEDIA_PLAY попытки: примерно через 1.2, 2.6 и 4.2 секунды.
4. Добавлен UI для локально играющего файла: название, время `current / duration`, progress bar.
5. `HostPlayer.Listener` получил `onLocalNowPlayingChanged(...)`.

Ограничение по Яндекс/VK не снято: это всё ещё external fallback. Настоящего API для постановки трека в очередь приложения сервиса нет, поэтому очередь держит PartyCast.
