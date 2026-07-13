package ru.example.ymqueue

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

class YandexAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var scenarioRunning = false

    // При повторном добавлении Я.Музыка часто переиспользует старый Activity/Fragment и
    // несколько сотен миллисекунд отдаёт в Accessibility дерево ещё не до конца
    // перестроенной страницы. Если начать кликать в этот момент, координата строки
    // уезжает на следующий трек. Поэтому перед первым кликом ждём короткую
    // "тишину" по accessibility-событиям. После первого клика этот gate отключается,
    // чтобы не мешать открытию bottom-sheet меню.
    private var lastYandexUiEventAt: Long = 0L
    private var scenarioStartedAt: Long = 0L
    private var initialYandexUiSettled: Boolean = false

    /**
     * Для album/track ссылок нельзя нажимать меню шапки альбома: в коде Я.Музыки это
     * приводит к AddPlayablesAsNextCommand со списком playables всего альбома.
     * Поэтому в albumSafeMode мы нажимаем AddNext только после попытки открыть меню строки трека.
     */
    private var albumSafeRowMenuGestureSent = false
    private var albumTargetRowTapSent = false
    private var albumTargetRowDescription: String = ""

    sealed class PendingAction {
        data object None : PendingAction()
        data class AddCurrentTrackToQueue(
            val createdAt: Long = System.currentTimeMillis(),
            val returnToApp: Boolean = true,
            val expectedTrackId: String? = null,
            val openedUrl: String? = null,
            val preferTrackRowMenu: Boolean = false,
            val expectedTrackTitle: String? = null,
            val expectedArtists: List<String> = emptyList()
        ) : PendingAction()
    }

    companion object {
        const val YANDEX_MUSIC_PACKAGE = "ru.yandex.music"
        private const val EXTRA_AUTOMATION_STATUS = "com.example.partycastmvp.EXTRA_AUTOMATION_STATUS"

        @Volatile
        var pendingAction: PendingAction = PendingAction.None

        @Volatile
        var lastHierarchyDump: String = ""
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg != YANDEX_MUSIC_PACKAGE) return

        lastYandexUiEventAt = System.currentTimeMillis()

        val action = pendingAction
        if (action is PendingAction.AddCurrentTrackToQueue && !scenarioRunning) {
            scenarioRunning = true
            scenarioStartedAt = lastYandexUiEventAt
            initialYandexUiSettled = false
            albumSafeRowMenuGestureSent = false
            albumTargetRowTapSent = false
            albumTargetRowDescription = ""
            handler.removeCallbacksAndMessages(null)
            // Даём Я.Музыке закончить открытие ссылки/анимацию.
            handler.postDelayed({
                attemptAddToQueue(
                    attempt = 0,
                    returnToApp = action.returnToApp,
                    expectedTrackId = action.expectedTrackId,
                    preferTrackRowMenu = action.preferTrackRowMenu,
                    expectedTrackTitle = action.expectedTrackTitle,
                    expectedArtists = action.expectedArtists
                )
            }, if (action.preferTrackRowMenu) 1700 else 1200)
        }
    }

    override fun onInterrupt() = Unit

    private fun attemptAddToQueue(
        attempt: Int,
        returnToApp: Boolean,
        expectedTrackId: String?,
        preferTrackRowMenu: Boolean,
        expectedTrackTitle: String? = null,
        expectedArtists: List<String> = emptyList()
    ) {
        val root = rootInActiveWindow
        if (root == null) {
            retryOrFail(attempt, returnToApp, expectedTrackId, preferTrackRowMenu, expectedTrackTitle, expectedArtists, "Не вижу окно Яндекс Музыки")
            return
        }

        if (!initialYandexUiSettled && shouldWaitForInitialYandexUiSettle(preferTrackRowMenu)) {
            handler.postDelayed({ attemptAddToQueue(attempt, returnToApp, expectedTrackId, preferTrackRowMenu, expectedTrackTitle, expectedArtists) }, 300)
            return
        }
        initialYandexUiSettled = true

        updateHierarchyDump(root)

        // Для обычных ссылок оставляем рабочую логику fixed5.
        // Для album/track ссылок НЕ нажимаем AddNext до попытки открыть меню строки трека,
        // чтобы не применить команду к меню альбома.
        val canClickAddNextNow = !preferTrackRowMenu || albumSafeRowMenuGestureSent || albumTargetRowTapSent
        if (canClickAddNextNow && clickAddNextMenuItem(root)) {
            val idSuffix = expectedTrackId?.let { " (trackId=$it)" }.orEmpty()
            finishScenario(success = true, returnToApp = returnToApp, message = "Нажал пункт добавления трека следующим$idSuffix")
            return
        }

        // Критичная правка: раньше сервис видел scrollable album page как меню и сразу
        // прокручивал альбом в самый низ, попадая в рекомендации. Теперь прокрутка разрешена
        // только для обычного режима и только если экран действительно похож на bottom-sheet меню.
        val rowMenuAlreadyOpened = albumSafeRowMenuGestureSent || albumTargetRowTapSent
        if ((!preferTrackRowMenu || rowMenuAlreadyOpened) && looksLikeContextMenuOpen(root) && scrollLikelyMenu(root)) {
            handler.postDelayed({ attemptAddToQueue(attempt + 1, returnToApp, expectedTrackId, preferTrackRowMenu, expectedTrackTitle, expectedArtists) }, 450)
            return
        }

        val menuOpened = if (preferTrackRowMenu) {
            openAlbumTrackRowMenuAttempt(root, attempt, expectedTrackTitle, expectedArtists)
        } else {
            openGenericTrackMenuAttempt(root, attempt)
        }

        if (menuOpened) {
            if (preferTrackRowMenu) albumSafeRowMenuGestureSent = true
            handler.postDelayed({ attemptAddToQueue(attempt + 1, returnToApp, expectedTrackId, preferTrackRowMenu, expectedTrackTitle, expectedArtists) }, 800)
        } else {
            retryOrFail(attempt, returnToApp, expectedTrackId, preferTrackRowMenu, expectedTrackTitle, expectedArtists, "Не удалось открыть меню трека")
        }
    }

    private fun retryOrFail(
        attempt: Int,
        returnToApp: Boolean,
        expectedTrackId: String?,
        preferTrackRowMenu: Boolean,
        expectedTrackTitle: String? = null,
        expectedArtists: List<String> = emptyList(),
        reason: String
    ) {
        val maxAttempts = if (preferTrackRowMenu) 2 else 8
        val shouldStopAfterSingleAlbumRowTap = preferTrackRowMenu && albumTargetRowTapSent
        if (attempt < maxAttempts && !shouldStopAfterSingleAlbumRowTap) {
            handler.postDelayed({ attemptAddToQueue(attempt + 1, returnToApp, expectedTrackId, preferTrackRowMenu, expectedTrackTitle, expectedArtists) }, 650)
        } else {
            val safetyNote = if (preferTrackRowMenu) {
                val targetInfo = if (albumTargetRowDescription.isNotBlank()) "\nПоследняя выбранная строка: $albumTargetRowDescription" else ""
                val apiInfo = expectedTrackTitle?.let { title ->
                    val artists = expectedArtists.joinToString(", ").ifBlank { "без артиста" }
                    "\nИскали по API: $title — $artists"
                }.orEmpty()
                "\n\nБезопасный album/track режим остановился: приложение ищет строку по title из API и не перебирает соседние строки. Artist-фильтр сейчас отключён.$apiInfo$targetInfo"
            } else ""
            val diagnostic = if (lastHierarchyDump.isBlank()) {
                reason + safetyNote
            } else {
                "$reason$safetyNote\n\nПоследние видимые элементы Я.Музыки:\n$lastHierarchyDump"
            }
            finishScenario(success = false, returnToApp = returnToApp, message = diagnostic)
        }
    }

    private fun openGenericTrackMenuAttempt(root: AccessibilityNodeInfo, attempt: Int): Boolean = when (attempt) {
        0 -> clickTrackContextMenuBySemantics(root)
        1 -> clickTrackContextMenuByScore(root)
        2 -> tapTopRightOverflow()
        3 -> tapUpperRightOverflow()
        4 -> tapMiddleRightOverflow()
        5 -> longPressLikelyTrackArea()
        6 -> tapLowerRightOverflow()
        else -> false
    }

    private fun openAlbumTrackRowMenuAttempt(
        root: AccessibilityNodeInfo,
        attempt: Int,
        expectedTrackTitle: String?,
        expectedArtists: List<String>
    ): Boolean = when (attempt) {
        /*
         * Album/track режим больше не кликает по "строке" как по широкому контейнеру.
         * В fixed11 именно это сдвигало координату вниз и открывало меню следующего трека.
         * Теперь сначала ищем явную маленькую кнопку справа на той же Y-координате, что и
         * текст нужной строки после fragment_container_view. Если кнопка не видна
         * Accessibility API, делаем один fallback-tap строго на высоте текста этой строки.
         */
        0 -> clickAlbumTargetOverflowByExplicitButton(root, expectedTrackTitle, expectedArtists)
        1 -> if (!albumTargetRowTapSent) tapAlbumTargetOverflowByExactTextLine(root, expectedTrackTitle, expectedArtists) else false
        else -> false
    }

    private fun finishScenario(success: Boolean, returnToApp: Boolean, message: String) {
        pendingAction = PendingAction.None
        scenarioRunning = false
        scenarioStartedAt = 0L
        initialYandexUiSettled = false
        albumSafeRowMenuGestureSent = false
        albumTargetRowTapSent = false
        albumTargetRowDescription = ""
        handler.removeCallbacksAndMessages(null)

        if (success) {
            // Закрываем bottom-sheet/контекстное меню, не останавливая воспроизведение.
            handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 450)
        }

        if (returnToApp) {
            handler.postDelayed({ openOwnApp(message) }, if (success) 950 else 250)
        }
    }

    private fun openOwnApp(message: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val intent = (launchIntent ?: Intent()).apply {
            if (launchIntent == null) {
                setClassName(packageName, "$packageName.MainActivity")
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_AUTOMATION_STATUS, message)
        }
        startActivity(intent)
    }

    private fun shouldWaitForInitialYandexUiSettle(preferTrackRowMenu: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val quietMs = if (preferTrackRowMenu) 900L else 550L
        val minWaitMs = if (preferTrackRowMenu) 1700L else 1000L
        val maxWaitMs = if (preferTrackRowMenu) 5200L else 3200L

        val age = now - scenarioStartedAt
        if (age >= maxWaitMs) return false
        if (age < minWaitMs) return true

        return now - lastYandexUiEventAt < quietMs
    }

    /**
     * Внутри Я.Музыки мы видели команды AddPlayablesAsNextCommand / YnisonEditQueueCommand.AddNext.
     * В UI это обычно называется не технически AddNext, а "Слушать следующим", "Играть следующим",
     * "Добавить в очередь" и похожими фразами.
     */
    private fun clickAddNextMenuItem(root: AccessibilityNodeInfo): Boolean {
        val all = mutableListOf<AccessibilityNodeInfo>()
        collect(root, all)

        val scored = all.mapNotNull { node ->
            if (!node.isVisibleToUser) return@mapNotNull null
            val label = labelWithChildren(node).normalizeYm()
            if (label.isBlank()) return@mapNotNull null
            val clickable = if (node.isClickable) node else clickableParent(node)
            if (clickable == null) return@mapNotNull null

            /*
             * Важно: в Я.Музыке "Добавить в очередь" на твоей версии уводит трек
             * в конец очереди. Нам нужна именно внутренняя логика AddNext / PlayNext,
             * которую в UI называют "Слушать следующим", "Играть следующим" и т.п.
             * Поэтому больше не кликаем generic "Добавить в очередь", если в тексте
             * нет явного признака "следующим/далее/next".
             */
            var score = 0
            var hasNextIntent = false

            fun next(points: Int) {
                score += points
                hasNextIntent = true
            }

            if (label.contains("слушатьследующим")) next(180)
            if (label.contains("игратьследующим")) next(180)
            if (label.contains("воспроизвестиследующим")) next(180)
            if (label.contains("добавитьследующим")) next(170)
            if (label.contains("следующимвочереди")) next(170)
            if (label.contains("следующаявочередь")) next(160)
            if (label.contains("послетекущего")) next(150)
            if (label.contains("слушатьдалее")) next(145)
            if (label.contains("игратьдалее")) next(145)
            if (label.contains("следующим")) next(95)

            if (label.contains("playnext")) next(180)
            if (label.contains("addnext")) next(170)
            if (label.contains("addasnext")) next(170)
            if (label.contains("listennext")) next(160)
            if (label.contains("nextinqueue")) next(155)

            // Эти пункты похожи по смыслу, но на практике добавляют в конец очереди.
            if (label.contains("добавитьвочередь") && !hasNextIntent) score -= 220
            if (label.contains("вочередь") && !hasNextIntent) score -= 180
            if (label.contains("addtoqueue") && !hasNextIntent) score -= 220
            if (label.contains("queue") && !hasNextIntent) score -= 140
            if (label.contains("playlast") || label.contains("addlast") || label.contains("слушатьпоследним")) score -= 260

            if (label.contains("перейтикочереди") || label.contains("открытьочередь")) score -= 180
            if (label.contains("поделиться") || label.contains("share")) score -= 120
            if (label.contains("плейлист") || label.contains("playlist")) score -= 60
            if (label.contains("альбом") || label.contains("album")) score -= 40
            if (label.contains("исполнитель") || label.contains("artist")) score -= 40
            if (label.contains("скачать") || label.contains("download")) score -= 40

            if (hasNextIntent && score > 0) Candidate(clickable, boundsOf(clickable), score, label) else null
        }.sortedByDescending { it.score }

        val target = scored.firstOrNull() ?: return false
        return target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun looksLikeContextMenuOpen(root: AccessibilityNodeInfo): Boolean {
        val all = mutableListOf<AccessibilityNodeInfo>()
        collect(root, all)
        val h = resources.displayMetrics.heightPixels
        var menuSignals = 0
        all.forEach { node ->
            if (!node.isVisibleToUser) return@forEach
            val r = boundsOf(node)
            if (r.centerY() < h * 0.35) return@forEach
            val label = labelWithChildren(node).normalizeYm()
            if (label.contains("слушатьследующим") || label.contains("игратьследующим") || label.contains("добавитьвочередь")) menuSignals += 3
            if (label.contains("добавитьвплейлист") || label.contains("поделиться") || label.contains("перейтикисполнителю")) menuSignals += 1
            if (label.contains("текстпесни") || label.contains("скачать") || label.contains("радиопотреку")) menuSignals += 1
        }
        return menuSignals >= 3
    }

    private fun scrollLikelyMenu(root: AccessibilityNodeInfo): Boolean {
        val all = mutableListOf<AccessibilityNodeInfo>()
        collect(root, all)
        val screenHeight = resources.displayMetrics.heightPixels
        val scrollable = all.firstOrNull { node ->
            if (!node.isVisibleToUser || !node.isScrollable) return@firstOrNull false
            val r = boundsOf(node)
            // Только bottom-sheet/меню, не основная страница альбома.
            r.top > screenHeight * 0.25 && r.bottom > screenHeight * 0.70
        } ?: return false
        return scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }


    /**
     * Album/track стабильный режим: ищем конкретный title, полученный через API,
     * а не первую/вторую строку после fragment_container_view. Если есть anchor
     * fragment_container_view, рассматриваем только элементы после него, чтобы не взять
     * заголовок/мини-плеер выше списка.
     */
    private fun findAlbumTargetTextByApiMetadata(
        root: AccessibilityNodeInfo,
        expectedTrackTitle: String?,
        expectedArtists: List<String>
    ): Candidate? {
        val title = expectedTrackTitle?.trim().orEmpty()
        val normalizedTitle = title.normalizeYm().stripExplicitTrackMarkers()
        if (normalizedTitle.isBlank()) return null

        val normalizedArtists = expectedArtists
            .map { it.normalizeYm() }
            .filter { it.isNotBlank() }

        val all = mutableListOf<AccessibilityNodeInfo>()
        collect(root, all)

        val anchorIndex = all.indexOfFirst { node ->
            val ownId = node.viewIdResourceName.orEmpty().normalizeYm()
            val ownLabel = labelOf(node).normalizeYm()
            ownId.contains("fragmentcontainerview") || ownLabel.contains("fragmentcontainerview")
        }

        fun buildCandidates(requireAfterAnchor: Boolean): List<Candidate> = all.mapIndexedNotNull { index, node ->
            if (!node.isVisibleToUser) return@mapIndexedNotNull null
            if (requireAfterAnchor && anchorIndex >= 0 && index <= anchorIndex) return@mapIndexedNotNull null

            val raw = listOf(
                node.text?.toString().orEmpty(),
                node.contentDescription?.toString().orEmpty()
            ).filter { it.isNotBlank() }.joinToString(" ").trim()
            if (raw.isBlank()) return@mapIndexedNotNull null

            val normalized = raw.normalizeYm()
            if (isDefinitelyNotAlbumTargetTrack(normalized)) return@mapIndexedNotNull null

            // У треков с explicit-меткой Я.Музыка добавляет в Accessibility рядом с title
            // описание значка: "ненормативная лексика", "восклицательный знак" и т.п.
            // Из-за этого raw label уже не равен title, хотя визуально это тот же трек.
            // Для сравнения с API-title убираем только такие служебные explicit-маркеры.
            val normalizedForMatch = normalized.stripExplicitTrackMarkers()
            val exactTitle = normalizedForMatch == normalizedTitle
            val containsTitle = !exactTitle &&
                normalizedForMatch.contains(normalizedTitle) &&
                normalizedForMatch.length <= normalizedTitle.length + 72
            val titleContainsNode = !exactTitle &&
                normalizedTitle.contains(normalizedForMatch) &&
                normalizedForMatch.length >= 4
            if (!exactTitle && !containsTitle && !titleContainsNode) return@mapIndexedNotNull null

            val r = boundsOf(node)
            if (!isPotentialAlbumTrackTextBounds(r)) return@mapIndexedNotNull null
            if (node.childCount > 0 && r.height() > dp(64)) return@mapIndexedNotNull null

            val row = findLikelyRowContainer(node) ?: node
            val rowLabelRaw = labelWithChildren(row)
            val rowContext = (rowLabelRaw + " " + ancestorLabels(node, 5)).normalizeYm()

            // В одиночных релизах Я.Музыка часто показывает строку "Моя волна по <title>".
            // Внутри неё может быть отдельный дочерний TextView только с названием трека,
            // поэтому простое совпадение raw == title ошибочно выбирало именно эту полоску.
            if (isUnsafeAlbumTitleContext(rowContext)) return@mapIndexedNotNull null

            val artistScore = nearbyArtistScore(all, r, normalizedArtists)
            val rowHasDuration = rowLabelRaw.contains(Regex("""\b\d{1,2}:\d{2}\b"""))
            var score = 0
            if (exactTitle) score += 280 else score += 165
            if (requireAfterAnchor && anchorIndex >= 0) score += 70
            score += artistScore
            if (rowHasDuration) score += 45
            if (node.childCount == 0) score += 20

            // Предпочитаем реальные title TextView слева/по центру, а не меню/служебный элемент справа.
            if (r.left < resources.displayMetrics.widthPixels * 0.68f) score += 35

            val h = resources.displayMetrics.heightPixels
            // По твоим наблюдениям нужный трек в альбомах обычно оказывается в верхней части экрана.
            // Это не жёсткое правило, но хороший скоринг, чтобы не уйти в рекомендации/мини-плеер ниже.
            when (r.centerY()) {
                in (h * 0.16f).toInt()..(h * 0.58f).toInt() -> score += 35
                in (h * 0.58f).toInt()..(h * 0.72f).toInt() -> score += 5
                else -> score -= 30
            }

            Candidate(
                node,
                r,
                score,
                "API title='$title', artistFilter=${normalizedArtists.isNotEmpty()}, artists='${expectedArtists.joinToString(", ")}', node#$index '$raw' [$r], row='${rowLabelRaw.take(120)}'"
            )
        }.sortedByDescending { it.score }

        val anchored = buildCandidates(requireAfterAnchor = true)
        val bestAnchored = anchored.firstOrNull { it.score >= 210 }
        if (bestAnchored != null) return bestAnchored

        // Fallback нужен для экранов, где fragment_container_view не попадает в актуальное
        // Accessibility-дерево или приходит не перед списком. Всё равно ищем конкретный title
        // и отсекаем "Моя волна"/рекомендации по контексту строки.
        return buildCandidates(requireAfterAnchor = false).firstOrNull { it.score >= 230 }
    }

    private fun nearbyArtistScore(
        all: List<AccessibilityNodeInfo>,
        titleBounds: Rect,
        normalizedArtists: List<String>
    ): Int {
        if (normalizedArtists.isEmpty()) return 0
        val titleCenterY = titleBounds.centerY()
        var best = 0
        all.forEach { node ->
            if (!node.isVisibleToUser) return@forEach
            val raw = listOf(
                node.text?.toString().orEmpty(),
                node.contentDescription?.toString().orEmpty()
            ).filter { it.isNotBlank() }.joinToString(" ")
            val normalized = raw.normalizeYm()
            if (normalized.isBlank()) return@forEach
            val matched = normalizedArtists.any { artist -> normalized.contains(artist) || artist.contains(normalized) }
            if (!matched) return@forEach
            val r = boundsOf(node)
            val dy = abs(r.centerY() - titleCenterY)
            if (dy <= dp(54)) best = maxOf(best, 90)
            else if (dy <= dp(96)) best = maxOf(best, 45)
        }
        return best
    }


    private fun isPotentialAlbumTrackTextBounds(r: Rect): Boolean {
        val dm = resources.displayMetrics
        if (r.isEmpty) return false
        if (r.centerY() < dm.heightPixels * 0.12f) return false
        if (r.centerY() > dm.heightPixels * 0.74f) return false
        if (r.height() !in dp(8)..dp(72)) return false
        if (r.width() < dm.widthPixels * 0.05f) return false
        // Названия треков в списке обычно слева/по центру. Правая зона занята кнопками.
        if (r.left > dm.widthPixels * 0.72f) return false
        return true
    }

    private fun isUnsafeAlbumTitleContext(context: String): Boolean {
        if (context.isBlank()) return true
        val bad = listOf(
            "мояволна", "волнапотреку", "радиопотреку", "запуститьволну", "слушатьволну",
            "рекоменда", "recommend", "похож", "вамможетпонрав",
            "поделиться", "share", "поиск", "search", "очередь", "queue",
            "добавлен", "добавлено", "added", "перемешать", "shuffle", "повтор", "repeat"
        )
        return bad.any { context.contains(it) }
    }

    private fun ancestorLabels(node: AccessibilityNodeInfo, maxDepth: Int): String {
        val out = StringBuilder()
        var cur = node.parent
        var depth = 0
        while (cur != null && depth < maxDepth) {
            // Только собственные label/id ancestor-узлов. Не берём labelWithChildren(),
            // иначе в контекст одного трека могут попасть соседние строки, например
            // "Моя волна по ..." или рекомендации из того же списка.
            val label = labelOf(cur!!)
            if (label.isNotBlank()) out.append(' ').append(label)
            cur = cur!!.parent
            depth++
        }
        return out.toString()
    }

    /**
     * Находит именно текстовую строку нужного трека после fragment_container_view.
     * Важно: возвращаем НЕ широкий row-container, а tight bounds самого текста.
     * В fixed11 использовался parent row/container, из-за этого Y-координата часто
     * попадала на следующую строку.
     */
    private fun findAlbumTargetTextAfterFragment(root: AccessibilityNodeInfo): Candidate? {
        val all = mutableListOf<AccessibilityNodeInfo>()
        collect(root, all)

        val anchorIndex = all.indexOfFirst { node ->
            val ownId = node.viewIdResourceName.orEmpty().normalizeYm()
            val ownLabel = labelOf(node).normalizeYm()
            ownId.contains("fragmentcontainerview") || ownLabel.contains("fragmentcontainerview")
        }
        if (anchorIndex < 0) return null

        val dm = resources.displayMetrics
        for (i in (anchorIndex + 1) until all.size) {
            val node = all[i]
            if (!node.isVisibleToUser) continue

            // Для выбора целевой строки используем только text/contentDescription.
            // viewId тут опасен: он может идти раньше настоящего текста трека.
            val raw = listOf(
                node.text?.toString().orEmpty(),
                node.contentDescription?.toString().orEmpty()
            ).filter { it.isNotBlank() }.joinToString(" ").trim()
            if (raw.isBlank()) continue

            val normalized = raw.normalizeYm()
            if (isDefinitelyNotAlbumTargetTrack(normalized)) continue
            if (normalized.matches(Regex("^[0-9]{1,2}[0-9]{2}$"))) continue

            val r = boundsOf(node)
            if (!isPotentialTrackTextBounds(r)) continue

            // Если это parent с кучей детей и огромной высотой, пропускаем: нам нужен
            // текстовый элемент, а не контейнер списка/карточки.
            if (node.childCount > 0 && r.height() > dp(64)) continue

            return Candidate(node, r, 100000 - (i - anchorIndex), "#$i ${raw.take(120)} [$r]")
        }
        return null
    }

    /**
     * Безопасный первый шаг: если у Я.Музыки в дереве есть явная кнопка overflow/menu
     * на той же высоте, что и текст нужного трека, жмём её через ACTION_CLICK.
     */
    private fun clickAlbumTargetOverflowByExplicitButton(
        root: AccessibilityNodeInfo,
        expectedTrackTitle: String?,
        expectedArtists: List<String>
    ): Boolean {
        if (albumTargetRowTapSent) return false
        val target = if (!expectedTrackTitle.isNullOrBlank()) {
            findAlbumTargetTextByApiMetadata(root, expectedTrackTitle, expectedArtists) ?: return false
        } else {
            findAlbumTargetTextAfterFragment(root) ?: return false
        }
        val all = mutableListOf<AccessibilityNodeInfo>()
        collect(root, all)
        val targetY = target.bounds.centerY()

        val candidates = all.mapNotNull { node ->
            if (!node.isVisibleToUser) return@mapNotNull null
            val clickable = if (node.isClickable) node else clickableParent(node)
            if (clickable == null) return@mapNotNull null
            val r = boundsOf(clickable)
            if (!isRightSideButtonNearTargetText(r, targetY)) return@mapNotNull null

            val combined = (
                labelOf(node) + " " + labelOf(clickable) + " " +
                    node.viewIdResourceName.orEmpty() + " " + clickable.viewIdResourceName.orEmpty() + " " +
                    node.className?.toString().orEmpty() + " " + clickable.className?.toString().orEmpty()
                ).normalizeYm()

            val className = (node.className?.toString().orEmpty() + " " + clickable.className?.toString().orEmpty()).lowercase()
            val hasExplicitMenu = hasOverflowSignal(combined)
            val imageLike = className.contains("imagebutton") || className.contains("imageview")
            val yDistance = abs(r.centerY() - targetY)
            if (!hasExplicitMenu && !imageLike) return@mapNotNull null
            // Без явного contentDescription/id меню разрешаем только почти идеально
            // совпадающую по высоте правую кнопку. Это защищает от выбора overflow
            // следующего трека, что проявлялось со второй попытки.
            if (!hasExplicitMenu && yDistance > dp(16)) return@mapNotNull null

            var score = 200
            if (hasExplicitMenu) score += 120
            if (imageLike) score += 35
            score += (80 - yDistance / dp(1)).coerceAtLeast(0)
            score += if (r.centerX() > resources.displayMetrics.widthPixels * 0.82f) 40 else 0
            Candidate(clickable, r, score, "target=${target.label} | button=$combined [$r]")
        }
            .distinctBy { "${it.bounds.left}:${it.bounds.top}:${it.bounds.right}:${it.bounds.bottom}" }
            .sortedByDescending { it.score }

        val best = candidates.firstOrNull() ?: return false
        albumTargetRowTapSent = true
        albumTargetRowDescription = best.label
        return best.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * Fallback без перебора соседних строк: один tap строго на Y текста нужного трека.
     * Не используем centerY родительского row-container, потому что он в fixed11 попадал
     * на следующую композицию.
     */
    private fun tapAlbumTargetOverflowByExactTextLine(
        root: AccessibilityNodeInfo,
        expectedTrackTitle: String?,
        expectedArtists: List<String>
    ): Boolean {
        if (albumTargetRowTapSent) return false
        val target = if (!expectedTrackTitle.isNullOrBlank()) {
            findAlbumTargetTextByApiMetadata(root, expectedTrackTitle, expectedArtists) ?: return false
        } else {
            findAlbumTargetTextAfterFragment(root) ?: return false
        }
        val dm = resources.displayMetrics
        val x = (dm.widthPixels - dp(34)).coerceIn((dm.widthPixels * 0.72f).toInt(), dm.widthPixels - dp(14))
        // Берём не нижнюю часть строки и не parent-row, а верхнюю половину самого
        // текстового node. Это снижает риск попасть в touch-зону следующей строки
        // после того, как layout Я.Музыки пересобрался на повторной попытке.
        val rawY = target.bounds.top + (target.bounds.height() * 0.42f).toInt()
        val y = rawY.coerceIn(dp(88), (dm.heightPixels * 0.76f).toInt())
        albumTargetRowTapSent = true
        albumTargetRowDescription = "${target.label} | exactY=$y rawY=$rawY"
        return tap(x, y)
    }

    private fun isPotentialTrackTextBounds(r: Rect): Boolean {
        val dm = resources.displayMetrics
        if (r.isEmpty) return false
        if (r.centerY() < dm.heightPixels * 0.18f) return false
        if (r.centerY() > dm.heightPixels * 0.80f) return false
        if (r.height() !in dp(8)..dp(72)) return false
        if (r.width() < dm.widthPixels * 0.06f) return false
        return true
    }

    private fun isRightSideButtonNearTargetText(r: Rect, targetY: Int): Boolean {
        val dm = resources.displayMetrics
        if (r.isEmpty) return false
        val width = r.width()
        val height = r.height()
        if (width !in dp(14)..dp(120)) return false
        if (height !in dp(14)..dp(120)) return false
        if (r.centerX() < dm.widthPixels * 0.62f) return false
        // Узкое окно по Y критично: при повторном открытии альбома соседние строки
        // тоже могут попадать в дерево как ImageButton без текста. Широкий допуск
        // выбирал кнопку следующей строки.
        if (abs(r.centerY() - targetY) > dp(24)) return false
        return true
    }

    private fun isDefinitelyNotAlbumTargetTrack(label: String): Boolean {
        if (label.isBlank()) return true
        val badParts = listOf(
            "fragmentcontainerview",
            "нравится", "ненравится", "пауза", "pause", "воспроизвести", "воспроизведение",
            "назад", "back", "закрыть", "close", "поиск", "search", "поделиться", "share",
            "рекоменда", "recommend", "похож", "вамможетпонрав", "другиерелизы", "смотрите",
            "радиопотреку", "радио", "волна", "wave",
            "очередь", "queue", "добавлен", "добавлено", "added", "перемешать", "shuffle", "повтор", "repeat",
            "скачатьальбом", "downloadalbum", "добавитьальбом", "albumoptions",
            "исполнитель", "artist", "альбом", "album", "плейлист", "playlist"
        )
        return badParts.any { label.contains(it) }
    }

    private fun clickAlbumExplicitTrackOverflow(root: AccessibilityNodeInfo, strict: Boolean): Boolean {
        val all = mutableListOf<AccessibilityNodeInfo>()
        collect(root, all)

        val candidates = all.mapNotNull { node ->
            if (!node.isVisibleToUser) return@mapNotNull null

            val nodeBounds = boundsOf(node)
            val clickable = if (node.isClickable) node else clickableParent(node)
            if (clickable == null) return@mapNotNull null
            val clickBounds = boundsOf(clickable)

            // Главное отличие от старого алгоритма: не кликаем широкую строку трека.
            // Только маленький правый элемент, похожий на overflow/menu button.
            if (!isRightSideSmallButton(clickBounds)) return@mapNotNull null
            if (!isRightSideSmallButton(nodeBounds) && strict) return@mapNotNull null

            val ownLabel = labelOf(node).normalizeYm()
            val ownId = node.viewIdResourceName.orEmpty().normalizeYm()
            val clickableLabel = labelOf(clickable).normalizeYm()
            val clickableId = clickable.viewIdResourceName.orEmpty().normalizeYm()
            val combined = "$ownLabel $ownId $clickableLabel $clickableId"
            val row = findLikelyRowContainer(clickable) ?: clickable
            val rowRaw = labelWithChildren(row)
            val rowLabel = rowRaw.normalizeYm()
            val hasDuration = rowRaw.contains(Regex("""\b\d{1,2}:\d{2}\b"""))

            val explicitMenuSignal = hasOverflowSignal(combined)
            if (strict && !explicitMenuSignal) return@mapNotNull null

            var score = 0
            if (explicitMenuSignal) score += 140
            if (hasDuration) score += 55
            if (node.className?.toString().orEmpty().lowercase().contains("imagebutton")) score += 35
            if (node.className?.toString().orEmpty().lowercase().contains("imageview")) score += 15
            if (clickable === node) score += 20
            if (row.isSelected || row.isFocused || row.isAccessibilityFocused || node.isSelected || node.isFocused || node.isAccessibilityFocused) score += 75
            score += albumRowPositionScore(clickBounds)
            score -= unsafeAlbumRowPenalty(clickBounds, rowLabel, hasDuration)
            score -= excludedOverflowScore(rowLabel)

            // Без явного сигнала меню нельзя нажимать даже если позиция похожа: это и запускало соседние треки.
            if (!explicitMenuSignal && score < 130) return@mapNotNull null

            val threshold = if (strict) 160 else 120
            if (score >= threshold) Candidate(clickable, clickBounds, score, combined.ifBlank { rowRaw.take(80) }) else null
        }
            .distinctBy { "${it.bounds.left}:${it.bounds.top}:${it.bounds.right}:${it.bounds.bottom}" }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { abs(it.bounds.centerY() - preferredAlbumRowY()) })

        val target = candidates.firstOrNull() ?: return false
        return target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun clickAlbumExplicitTrackOverflowNearCurrentRow(root: AccessibilityNodeInfo): Boolean {
        val all = mutableListOf<AccessibilityNodeInfo>()
        collect(root, all)

        val currentRows = all.mapNotNull { node ->
            if (!node.isVisibleToUser) return@mapNotNull null
            if (!node.isSelected && !node.isFocused && !node.isAccessibilityFocused) return@mapNotNull null
            val row = findLikelyRowContainer(node) ?: node
            val r = boundsOf(row)
            if (!isAlbumTrackRowBand(r)) return@mapNotNull null
            r
        }
        val currentRow = currentRows.minByOrNull { abs(it.centerY() - preferredAlbumRowY()) } ?: return false

        val candidates = all.mapNotNull { node ->
            if (!node.isVisibleToUser) return@mapNotNull null
            val clickable = if (node.isClickable) node else clickableParent(node)
            if (clickable == null) return@mapNotNull null
            val r = boundsOf(clickable)
            if (!isRightSideSmallButton(r)) return@mapNotNull null
            if (abs(r.centerY() - currentRow.centerY()) > dp(42)) return@mapNotNull null

            val label = (labelOf(node) + " " + labelOf(clickable)).normalizeYm()
            val id = (node.viewIdResourceName.orEmpty() + " " + clickable.viewIdResourceName.orEmpty()).normalizeYm()
            val combined = "$label $id"
            if (!hasOverflowSignal(combined)) return@mapNotNull null

            var score = 180
            score += (50 - abs(r.centerY() - currentRow.centerY()) / dp(2)).coerceAtLeast(0)
            score += albumRowPositionScore(r)
            Candidate(clickable, r, score, combined)
        }
            .distinctBy { "${it.bounds.left}:${it.bounds.top}:${it.bounds.right}:${it.bounds.bottom}" }
            .sortedByDescending { it.score }

        val target = candidates.firstOrNull() ?: return false
        return target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun hasOverflowSignal(text: String): Boolean {
        val t = text.normalizeYm()
        return t.contains("еще") || t.contains("ещё") || t.contains("more") ||
            t.contains("overflow") || t.contains("menu") || t.contains("options") ||
            t.contains("действия") || t.contains("другиедействия") ||
            t.contains("открытьменю") || t.contains("контекстноеменю")
    }

    private fun isRightSideSmallButton(r: Rect): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val width = r.width()
        val height = r.height()
        val small = width in dp(16)..dp(112) && height in dp(16)..dp(112)
        val right = r.centerX() > w * 0.64
        val safeVertical = r.centerY() in (h * 0.24).toInt()..(h * 0.68).toInt()
        return small && right && safeVertical
    }

    private fun isAlbumTrackRowBand(r: Rect): Boolean {
        val h = resources.displayMetrics.heightPixels
        return r.centerY() in (h * 0.24).toInt()..(h * 0.68).toInt() && r.height() in dp(32)..dp(128)
    }

    private fun clickAlbumTrackRowMenuByNodes(root: AccessibilityNodeInfo, strict: Boolean): Boolean {
        val all = mutableListOf<AccessibilityNodeInfo>()
        collect(root, all)

        val candidates = all.mapNotNull { node ->
            if (!node.isVisibleToUser) return@mapNotNull null
            val clickable = if (node.isClickable) node else clickableParent(node)
            if (clickable == null) return@mapNotNull null

            val r = boundsOf(clickable)
            if (!isSafeAlbumRowRightSide(r)) return@mapNotNull null

            val label = labelOf(node).normalizeYm()
            val id = node.viewIdResourceName.orEmpty().normalizeYm()
            val className = node.className?.toString().orEmpty().lowercase()
            val row = findLikelyRowContainer(clickable)
            val rowRaw = labelWithChildren(row ?: clickable)
            val rowLabel = rowRaw.normalizeYm()
            val hasDuration = rowRaw.contains(Regex("\\b\\d{1,2}:\\d{2}\\b"))

            var score = albumRowPositionScore(r)
            if (id.contains("more") || id.contains("overflow") || id.contains("menu")) score += 90
            if (label.contains("еще") || label.contains("ещё") || label.contains("more") || label.contains("меню") || label.contains("options")) score += 80
            if (className.contains("imagebutton") || className.contains("imageview")) score += 10
            if (hasDuration) score += 55
            if (node.isSelected || node.isFocused || node.isAccessibilityFocused || (row?.isSelected == true) || (row?.isFocused == true)) score += 65
            if (r.width() <= dp(96) && r.height() <= dp(96)) score += 15

            score -= unsafeAlbumRowPenalty(r, rowLabel, hasDuration)
            score -= excludedOverflowScore(label)

            val threshold = if (strict) 85 else 55
            if (score >= threshold) Candidate(clickable, r, score, label.ifBlank { id.ifBlank { rowLabel.take(60) } }) else null
        }
            .distinctBy { "${it.bounds.left}:${it.bounds.top}:${it.bounds.right}:${it.bounds.bottom}" }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { abs(it.bounds.centerY() - preferredAlbumRowY()) })

        val target = candidates.firstOrNull() ?: return false
        return target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun clickSelectedOrFocusedRowMenu(root: AccessibilityNodeInfo): Boolean {
        val all = mutableListOf<AccessibilityNodeInfo>()
        collect(root, all)

        val rows = all.mapNotNull { node ->
            if (!node.isVisibleToUser) return@mapNotNull null
            if (!node.isSelected && !node.isFocused && !node.isAccessibilityFocused) return@mapNotNull null
            val row = findLikelyRowContainer(node) ?: node
            val r = boundsOf(row)
            val h = resources.displayMetrics.heightPixels
            if (r.centerY() < h * 0.24 || r.centerY() > h * 0.66) return@mapNotNull null
            r
        }

        val best = rows.minByOrNull { abs(it.centerY() - preferredAlbumRowY()) } ?: return false
        return tap(resources.displayMetrics.widthPixels - dp(32), best.centerY())
    }

    private fun isSafeAlbumRowRightSide(r: Rect): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val width = r.width()
        val height = r.height()
        val iconOrCellSized = width in dp(16)..dp(180) && height in dp(16)..dp(120)
        val rightSide = r.centerX() > w * 0.60
        val notHeader = r.centerY() > h * 0.24
        val notRecommendations = r.centerY() < h * 0.68
        return iconOrCellSized && rightSide && notHeader && notRecommendations
    }

    private fun albumRowPositionScore(r: Rect): Int {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        var score = 0
        if (r.centerX() > w * 0.72) score += 35
        if (r.centerX() > w * 0.84) score += 35
        if (r.centerY() in (h * 0.30).toInt()..(h * 0.64).toInt()) score += 40
        score += (60 - (abs(r.centerY() - preferredAlbumRowY()) / dp(8))).coerceAtLeast(0)
        return score
    }

    private fun unsafeAlbumRowPenalty(r: Rect, label: String, hasDuration: Boolean): Int {
        val h = resources.displayMetrics.heightPixels
        var penalty = 0
        if (r.centerY() < h * 0.24) penalty += 200
        if (r.centerY() > h * 0.68) penalty += 220
        if (!hasDuration && (label.contains("альбом") || label.contains("album"))) penalty += 140
        if (label.contains("рекоменда") || label.contains("recommend") || label.contains("похож") || label.contains("вамможетпонрав")) penalty += 260
        if (label.contains("плейлист") || label.contains("playlist")) penalty += 120
        if (label.contains("исполнитель") || label.contains("artist")) penalty += 90
        if (label.contains("перемешать") || label.contains("shuffle")) penalty += 120
        if (label.contains("скачатьальбом") || label.contains("downloadalbum")) penalty += 120
        if (label.contains("обложка") || label.contains("cover")) penalty += 80
        return penalty
    }

    private fun preferredAlbumRowY(): Int = (resources.displayMetrics.heightPixels * 0.48f).toInt()

    private fun tapAlbumTrackRowRightMenu(centerBias: Float): Boolean {
        val dm = resources.displayMetrics
        val y = (dm.heightPixels * centerBias).toInt().coerceIn((dm.heightPixels * 0.26f).toInt(), (dm.heightPixels * 0.64f).toInt())
        return tap(dm.widthPixels - dp(32), y)
    }

    private fun longPressAlbumTrackRow(centerBias: Float): Boolean {
        val dm = resources.displayMetrics
        val y = (dm.heightPixels * centerBias).toInt().coerceIn((dm.heightPixels * 0.28f).toInt(), (dm.heightPixels * 0.62f).toInt())
        return gesture((dm.widthPixels * 0.42f).toInt(), y, durationMs = 700)
    }

    private fun clickTrackContextMenuBySemantics(root: AccessibilityNodeInfo): Boolean {
        val semanticWords = listOf(
            "ещё", "еще", "more", "moreoptions", "options",
            "действия", "другиедействия", "меню", "открытьменю",
            "контекстноеменю", "contextmenu", "overflow"
        )

        val all = mutableListOf<AccessibilityNodeInfo>()
        collect(root, all)

        val candidates = all.mapNotNull { node ->
            if (!node.isVisibleToUser) return@mapNotNull null
            val clickable = if (node.isClickable) node else clickableParent(node)
            if (clickable == null) return@mapNotNull null
            val label = labelOf(node).normalizeYm()
            val id = node.viewIdResourceName.orEmpty().normalizeYm()
            val className = node.className?.toString().orEmpty().lowercase()
            val r = boundsOf(clickable)
            var score = 0

            if (semanticWords.any { label.contains(it) }) score += 120
            if (semanticWords.any { id.contains(it) }) score += 110
            if (id.contains("more") || id.contains("overflow") || id.contains("menu")) score += 90
            if (className.contains("imagebutton") || className.contains("imageview")) score += 10
            score += overflowPositionScore(r)
            score -= excludedOverflowScore(label)

            if (score >= 90) Candidate(clickable, r, score, label.ifBlank { id }) else null
        }.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.bounds.top }.thenByDescending { it.bounds.right })

        val target = candidates.firstOrNull() ?: return false
        return target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun clickTrackContextMenuByScore(root: AccessibilityNodeInfo): Boolean {
        val all = mutableListOf<AccessibilityNodeInfo>()
        collect(root, all)

        val candidates = all.mapNotNull { node ->
            if (!node.isVisibleToUser) return@mapNotNull null
            val clickable = if (node.isClickable) node else clickableParent(node)
            if (clickable == null || clickable !== node && !node.isFocusable) return@mapNotNull null
            val r = boundsOf(clickable)
            val label = labelOf(clickable).normalizeYm()
            val id = clickable.viewIdResourceName.orEmpty().normalizeYm()

            val width = r.width()
            val height = r.height()
            val iconSized = width in dp(24)..dp(96) && height in dp(24)..dp(96)
            if (!iconSized) return@mapNotNull null

            var score = overflowPositionScore(r)
            if (id.contains("menu") || id.contains("more") || id.contains("overflow")) score += 65
            score -= excludedOverflowScore(label)

            if (score > 45) Candidate(clickable, r, score, label.ifBlank { id }) else null
        }.distinctBy { "${it.bounds.left}:${it.bounds.top}:${it.bounds.right}:${it.bounds.bottom}" }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.bounds.top }.thenByDescending { it.bounds.right })

        val target = candidates.firstOrNull() ?: return false
        return target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun overflowPositionScore(r: Rect): Int {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        var score = 0
        if (r.centerX() > w * 0.72) score += 45
        if (r.centerX() > w * 0.86) score += 30
        if (r.centerY() in dp(44)..(h * 0.42).toInt()) score += 40
        if (r.centerY() in (h * 0.42).toInt()..(h * 0.72).toInt()) score += 15
        if (r.centerY() > h * 0.80) score -= 40
        if (abs(r.width() - r.height()) < dp(24)) score += 10
        return score
    }

    private fun excludedOverflowScore(label: String): Int {
        val bad = listOf(
            "назад", "back", "play", "pause", "пауза", "воспроиз", "стоп",
            "like", "лайк", "нравится", "share", "поделиться", "search", "поиск",
            "close", "закрыть", "shuffle", "перемеш", "repeat", "повтор"
        )
        return if (bad.any { label.contains(it) }) 100 else 0
    }

    private fun tapTopRightOverflow(): Boolean {
        val dm = resources.displayMetrics
        return tap(dm.widthPixels - dp(28), dp(64))
    }

    private fun tapUpperRightOverflow(): Boolean {
        val dm = resources.displayMetrics
        return tap(dm.widthPixels - dp(28), dp(112))
    }

    private fun tapMiddleRightOverflow(): Boolean {
        val dm = resources.displayMetrics
        return tap(dm.widthPixels - dp(28), (dm.heightPixels * 0.34f).toInt())
    }

    private fun tapLowerRightOverflow(): Boolean {
        val dm = resources.displayMetrics
        return tap(dm.widthPixels - dp(28), (dm.heightPixels * 0.58f).toInt())
    }

    private fun longPressLikelyTrackArea(): Boolean {
        val dm = resources.displayMetrics
        return gesture(dm.widthPixels / 2, (dm.heightPixels * 0.42f).toInt(), durationMs = 650)
    }

    private fun tap(x: Int, y: Int): Boolean = gesture(x, y, durationMs = 80)

    private fun gesture(x: Int, y: Int, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun clickableParent(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = node?.parent
        repeat(8) {
            if (cur == null) return null
            if (cur!!.isClickable) return cur
            cur = cur!!.parent
        }
        return null
    }

    private fun findLikelyRowContainer(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = node
        var cur = node.parent
        repeat(5) {
            if (cur == null) return best
            val r = boundsOf(cur!!)
            val h = r.height()
            val w = r.width()
            if (h in dp(36)..dp(112) && w > resources.displayMetrics.widthPixels * 0.45) {
                best = cur
            }
            cur = cur!!.parent
        }
        return best
    }

    private fun labelOf(node: AccessibilityNodeInfo): String {
        val parts = listOf(
            node.text?.toString().orEmpty(),
            node.contentDescription?.toString().orEmpty(),
            node.viewIdResourceName.orEmpty()
        )
        return parts.filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun labelWithChildren(node: AccessibilityNodeInfo): String {
        val out = StringBuilder(labelOf(node))
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 3) return
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                val label = labelOf(child)
                if (label.isNotBlank()) out.append(' ').append(label)
                walk(child, depth + 1)
            }
        }
        walk(node, 0)
        return out.toString()
    }

    private fun String.normalizeYm(): String = lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^a-zа-я0-9]+"), "")

    private fun String.stripExplicitTrackMarkers(): String {
        var out = this
        val markers = listOf(
            "трексодержитненормативнуюлексику",
            "содержитненормативнуюлексику",
            "ненормативнаялексика",
            "ненормативнуюлексику",
            "ненормативнойлексикой",
            "матерныеслова",
            "восклицательныйзнаквкружочке",
            "восклицательныйзнак",
            "значоквосклицательный",
            "parentaladvisory",
            "explicitlyrics",
            "explicitcontent",
            "explicit"
        )
        markers.forEach { marker -> out = out.replace(marker, "") }
        return out
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }

    private fun updateHierarchyDump(root: AccessibilityNodeInfo) {
        val all = mutableListOf<AccessibilityNodeInfo>()
        collect(root, all)
        lastHierarchyDump = all.asSequence()
            .mapIndexedNotNull { index, node ->
                if (!node.isVisibleToUser) return@mapIndexedNotNull null
                val rawLabel = labelOf(node).trim()
                if (rawLabel.isBlank()) return@mapIndexedNotNull null
                val r = boundsOf(node)
                val clickable = if (node.isClickable) " clickable" else ""
                val selected = when {
                    node.isSelected -> " selected"
                    node.isFocused -> " focused"
                    node.isAccessibilityFocused -> " accessibilityFocused"
                    else -> ""
                }
                "#$index ${rawLabel.take(90)} [$r]$clickable$selected"
            }
            .distinct()
            .take(80)
            .joinToString("\n")
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        out += node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, out) }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class Candidate(
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
        val score: Int,
        val label: String
    )
}
