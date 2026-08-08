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
import android.widget.Toast
import kotlin.math.abs

class YandexAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var scenarioRunning = false

    // –ü—Ä–∏ –ø–æ–≤—Ç–æ—Ä–Ω–æ–º –¥–æ–±–∞–≤–ª–µ–Ω–∏–∏ –Ø.–ú—É–∑—ã–∫–∞ —á–∞—Å—Ç–æ –ø–µ—Ä–µ–∏—Å–ø–æ–ª—å–∑—É–µ—Ç —Å—Ç–∞—Ä—ã–π Activity/Fragment –∏
    // –Ω–µ—Å–∫–æ–ª—å–∫–æ —Å–æ—Ç–µ–Ω –º–∏–ª–ª–∏—Å–µ–∫—É–Ω–¥ –æ—Ç–¥–∞—ë—Ç –≤ Accessibility –¥–µ—Ä–µ–≤–æ –µ—â—ë –Ω–µ –¥–æ –∫–æ–Ω—Ü–∞
    // –ø–µ—Ä–µ—Å—Ç—Ä–æ–µ–Ω–Ω–æ–π —Å—Ç—Ä–∞–Ω–∏—Ü—ã. –ï—Å–ª–∏ –Ω–∞—á–∞—Ç—å –∫–ª–∏–∫–∞—Ç—å –≤ —ç—Ç–æ—Ç –º–æ–º–µ–Ω—Ç, –∫–æ–æ—Ä–¥–∏–Ω–∞—Ç–∞ —Å—Ç—Ä–æ–∫–∏
    // —É–µ–∑–∂–∞–µ—Ç –Ω–∞ —Å–ª–µ–¥—É—é—â–∏–π —Ç—Ä–µ–∫. –ü–æ—ç—Ç–æ–º—É –ø–µ—Ä–µ–¥ –ø–µ—Ä–≤—ã–º –∫–ª–∏–∫–æ–º –∂–¥—ë–º –∫–æ—Ä–æ—Ç–∫—É—é
    // "—Ç–∏—à–∏–Ω—É" –ø–æ accessibility-—Å–æ–±—ã—Ç–∏—è–º. –ü–æ—Å–ª–µ –ø–µ—Ä–≤–æ–≥–æ –∫–ª–∏–∫–∞ —ç—Ç–æ—Ç gate –æ—Ç–∫–ª—é—á–∞–µ—Ç—Å—è,
    // —á—Ç–æ–±—ã –Ω–µ –º–µ—à–∞—Ç—å –æ—Ç–∫—Ä—ã—Ç–∏—é bottom-sheet –º–µ–Ω—é.
    private var lastYandexUiEventAt: Long = 0L
    private var scenarioStartedAt: Long = 0L
    private var initialYandexUiSettled: Boolean = false
    private var readinessSignature: String = ""
    private var readinessStableChecks: Int = 0
    private var scenarioStepScheduled: Boolean = false
    private var scenarioStepAt: Long = 0L
    private var scheduledAttempt: Int = 0

    private val scenarioStep = Runnable {
        scenarioStepScheduled = false
        scenarioStepAt = 0L
        val action = pendingAction as? PendingAction.AddCurrentTrackToQueue ?: return@Runnable
        if (!scenarioRunning) return@Runnable
        attemptAddToQueue(
            attempt = scheduledAttempt,
            returnToApp = action.returnToApp,
            expectedTrackId = action.expectedTrackId,
            preferTrackRowMenu = action.preferTrackRowMenu,
            expectedTrackTitle = action.expectedTrackTitle,
            expectedArtists = action.expectedArtists
        )
    }

    /**
     * –î–ª—è album/track —Å—Å—ã–ª–æ–∫ –Ω–µ–ª—å–∑—è –Ω–∞–∂–∏–º–∞—Ç—å –º–µ–Ω—é —à–∞–ø–∫–∏ –∞–ª—å–±–æ–º–∞: –≤ –∫–æ–¥–µ –Ø.–ú—É–∑—ã–∫–∏ —ç—Ç–æ
     * –ø—Ä–∏–≤–æ–¥–∏—Ç –∫ AddPlayablesAsNextCommand —Å–æ —Å–ø–∏—Å–∫–æ–º playables –≤—Å–µ–≥–æ –∞–ª—å–±–æ–º–∞.
     * –ü–æ—ç—Ç–æ–º—É –≤ albumSafeMode –º—ã –Ω–∞–∂–∏–º–∞–µ–º AddNext —Ç–æ–ª—å–∫–æ –ø–æ—Å–ª–µ –ø–æ–ø—ã—Ç–∫–∏ –æ—Ç–∫—Ä—ã—Ç—å –º–µ–Ω—é —Å—Ç—Ä–æ–∫–∏ —Ç—Ä–µ–∫–∞.
     */
    private var albumSafeRowMenuGestureSent = false
    private var albumTargetRowTapSent = false
    private var albumTargetRowDescription: String = ""
    private var miniPlayerExpanded = false
    private var lastExternalPackage: String = ""
    private var lastExternalPackageAt: Long = 0L
    private var automationReturnPackage: String = ""

    sealed class PendingAction {
        data object None : PendingAction()
        data class AddCurrentTrackToQueue(
            val createdAt: Long = System.currentTimeMillis(),
            val returnToApp: Boolean = false,
            val expectedTrackId: String? = null,
            val openedUrl: String? = null,
            val preferTrackRowMenu: Boolean = false,
            val expectedTrackTitle: String? = null,
            val expectedArtists: List<String> = emptyList(),
            val fullRetryCount: Int = 0
        ) : PendingAction()
        data class Diagnostic(
            val createdAt: Long = System.currentTimeMillis()
        ) : PendingAction()
    }

    companion object {
        const val YANDEX_MUSIC_PACKAGE = "ru.yandex.music"
        private const val EXTRA_AUTOMATION_STATUS = "com.example.partycastmvp.EXTRA_AUTOMATION_STATUS"
        private const val PAGE_READY_POLL_MS = 350L
        private const val PAGE_READY_TIMEOUT_MS = 25_000L
        private const val REQUIRED_STABLE_READY_CHECKS = 2

        @Volatile
        private var activeService: YandexAccessibilityService? = null

        @Volatile
        var pendingAction: PendingAction = PendingAction.None

        @Volatile
        var lastHierarchyDump: String = ""

        @Volatile
        var lastDiagnosticReport: String = "–ü—Ä–æ–≤–µ—Ä–∫–∞ –µ—â—ë –Ω–µ –∑–∞–ø—É—Å–∫–∞–ª–∞—Å—å"

        @Volatile
        private var automationCompletion: ((Boolean, String) -> Unit)? = null

        fun setAutomationCompletionCallback(callback: ((Boolean, String) -> Unit)?) {
            automationCompletion = callback
        }

        fun kickPendingAction() {
            activeService?.kickPendingActionFromApp()
        }

        fun captureReturnTarget() {
            activeService?.captureReturnTargetBeforeAutomation()
        }

        fun isScenarioRunning(): Boolean = activeService?.scenarioRunning == true

        fun isServiceConnected(): Boolean = activeService != null

        fun requestDiagnostic(): Boolean {
            val service = activeService ?: return false
            if (service.scenarioRunning) return false
            pendingAction = PendingAction.Diagnostic()
            service.kickPendingActionFromApp()
            return true
        }

        fun connectionStatus(): String {
            val service = activeService
                ?: return "–°–µ—Ä–≤–∏—Å Accessibility –Ω–µ –ø–æ–¥–∫–ª—é—á—ë–Ω –∫ PartyCast"
            val eventAge = if (service.lastYandexUiEventAt > 0L) {
                "${(System.currentTimeMillis() - service.lastYandexUiEventAt).coerceAtLeast(0L) / 1000} —Å–µ–∫. –Ω–∞–∑–∞–¥"
            } else {
                "–µ—â—ë –Ω–µ –ø–æ–ª—É—á–µ–Ω–æ"
            }
            val rootPackage = service.rootInActiveWindow?.packageName?.toString().orEmpty().ifBlank { "–Ω–µ—Ç –∞–∫—Ç–∏–≤–Ω–æ–≥–æ –æ–∫–Ω–∞" }
            return buildString {
                append("–°–µ—Ä–≤–∏—Å –ø–æ–¥–∫–ª—é—á—ë–Ω: –¥–∞")
                append("\n–°—Ü–µ–Ω–∞—Ä–∏–π –≤—ã–ø–æ–ª–Ω—è–µ—Ç—Å—è: ").append(if (service.scenarioRunning) "–¥–∞" else "–Ω–µ—Ç")
                append("\n–ü–æ—Å–ª–µ–¥–Ω–µ–µ —Å–æ–±—ã—Ç–∏–µ –Ø–Ω–¥–µ–∫—Å –ú—É–∑—ã–∫–∏: ").append(eventAge)
                append("\n–ê–∫—Ç–∏–≤–Ω–æ–µ –æ–∫–Ω–æ: ").append(rootPackage)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        kickPendingActionFromApp()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg != YANDEX_MUSIC_PACKAGE) {
            rememberExternalPackage(pkg, event?.eventType ?: 0)
            return
        }

        lastYandexUiEventAt = System.currentTimeMillis()

        startPendingScenarioIfNeeded()
        if (scenarioRunning && !initialYandexUiSettled) {
            scheduleAttempt(scheduledAttempt, 180L, onlyIfEarlier = true)
        }
    }

    override fun onInterrupt() = Unit

    private fun rememberExternalPackage(pkg: String, eventType: Int) {
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return
        if (!isReturnCandidate(pkg)) return
        lastExternalPackage = pkg
        lastExternalPackageAt = System.currentTimeMillis()
    }

    private fun isReturnCandidate(pkg: String): Boolean {
        if (pkg.isBlank() || pkg == YANDEX_MUSIC_PACKAGE || pkg == packageName) return false
        if (pkg.startsWith("com.android.systemui") ||
            pkg.contains("launcher", ignoreCase = true) ||
            pkg.contains("permissioncontroller", ignoreCase = true) ||
            pkg.contains("inputmethod", ignoreCase = true)
        ) return false
        return packageManager.getLaunchIntentForPackage(pkg) != null
    }

    private fun captureReturnTargetBeforeAutomation() {
        val activePackage = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (isReturnCandidate(activePackage)) {
            lastExternalPackage = activePackage
            lastExternalPackageAt = System.currentTimeMillis()
        }
        if (lastExternalPackage.isNotBlank() &&
            System.currentTimeMillis() - lastExternalPackageAt <= 30 * 60 * 1000L
        ) {
            automationReturnPackage = lastExternalPackage
        }
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun kickPendingActionFromApp() {
        handler.post { startPendingScenarioIfNeeded() }
    }

    private fun startPendingScenarioIfNeeded() {
        val action = pendingAction
        if (scenarioRunning) return

        if (action is PendingAction.Diagnostic) {
            scenarioRunning = true
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ runDiagnosticAttempt(action, 0) }, 1400)
            return
        }

        if (action !is PendingAction.AddCurrentTrackToQueue) return

        scenarioRunning = true
        scenarioStartedAt = System.currentTimeMillis()
        resetAutomationCycleState()
        handler.removeCallbacksAndMessages(null)
        // –î–∞—ë–º –Ø.–ú—É–∑—ã–∫–µ –∑–∞–∫–æ–Ω—á–∏—Ç—å –æ—Ç–∫—Ä—ã—Ç–∏–µ —Å—Å—ã–ª–∫–∏/–∞–Ω–∏–º–∞—Ü–∏—é. –°—Ü–µ–Ω–∞—Ä–∏–π –∑–∞–ø—É—Å–∫–∞–µ—Ç—Å—è –∫–∞–∫
        // –æ—Ç Accessibility-—Å–æ–±—ã—Ç–∏—è, —Ç–∞–∫ –∏ –æ—Ç —è–≤–Ω–æ–≥–æ —Å–∏–≥–Ω–∞–ª–∞ PartyCast.
        scheduleAttempt(0, 250L)
    }

    private fun runDiagnosticAttempt(action: PendingAction.Diagnostic, attempt: Int) {
        val root = rootInActiveWindow
        val rootPackage = root?.packageName?.toString().orEmpty()
        if ((root == null || rootPackage != YANDEX_MUSIC_PACKAGE) && attempt < 10) {
            handler.postDelayed({ runDiagnosticAttempt(action, attempt + 1) }, 450)
            return
        }

        val report = if (root == null || rootPackage != YANDEX_MUSIC_PACKAGE) {
            buildString {
                append("–†–ï–ó–£–õ–¨–¢–ê–¢: –æ—à–∏–±–∫–∞\n")
                append("–°–µ—Ä–≤–∏—Å –ø–æ–¥–∫–ª—é—á—ë–Ω, –Ω–æ –æ–∫–Ω–æ –Ø–Ω–¥–µ–∫—Å –ú—É–∑—ã–∫–∏ –Ω–µ –æ–±–Ω–∞—Ä—É–∂–µ–Ω–æ.")
                append("\n–¢–µ–∫—É—â–µ–µ –æ–∫–Ω–æ: ").append(rootPackage.ifBlank { "–Ω–µ –æ–ø—Ä–µ–¥–µ–ª–µ–Ω–æ" })
                append("\n–°–æ–±—ã—Ç–∏–µ –Ø–Ω–¥–µ–∫—Å –ú—É–∑—ã–∫–∏ –ø–æ—Å–ª–µ –∑–∞–ø—É—Å–∫–∞ —Ç–µ—Å—Ç–∞: ")
                append(if (lastYandexUiEventAt >= action.createdAt) "–ø–æ–ª—É—á–µ–Ω–æ" else "–Ω–µ –ø–æ–ª—É—á–µ–Ω–æ")
            }
        } else {
            updateHierarchyDump(root)
            val all = mutableListOf<AccessibilityNodeInfo>()
            collect(root, all)
            val visible = all.count { it.isVisibleToUser }
            val labeled = all.count { it.isVisibleToUser && labelOf(it).isNotBlank() }
            val clickable = all.count { it.isVisibleToUser && it.isClickable }
            buildString {
                append("–†–ï–ó–£–õ–¨–¢–ê–¢: Accessibility —Ä–∞–±–æ—Ç–∞–µ—Ç\n")
                append("–°–æ–±—ã—Ç–∏–µ –Ø–Ω–¥–µ–∫—Å –ú—É–∑—ã–∫–∏ –ø–æ—Å–ª–µ –∑–∞–ø—É—Å–∫–∞ —Ç–µ—Å—Ç–∞: ")
                append(if (lastYandexUiEventAt >= action.createdAt) "–ø–æ–ª—É—á–µ–Ω–æ" else "–Ω–µ –ø–æ–ª—É—á–µ–Ω–æ, –∏—Å–ø–æ–ª—å–∑–æ–≤–∞–Ω –ø—Ä—è–º–æ–π –∑–∞–ø—É—Å–∫")
                append("\n–ü–∞–∫–µ—Ç –∞–∫—Ç–∏–≤–Ω–æ–≥–æ –æ–∫–Ω–∞: ").append(rootPackage)
                append("\n–≠–ª–µ–º–µ–Ω—Ç–æ–≤ –≤ –¥–µ—Ä–µ–≤–µ: ").append(all.size)
                append("; –≤–∏–¥–∏–º—ã—Ö: ").append(visible)
                append("; —Å —Ç–µ–∫—Å—Ç–æ–º/–æ–ø–∏—Å–∞–Ω–∏–µ–º: ").append(labeled)
                append("; –∫–ª–∏–∫–∞–±–µ–ª—å–Ω—ã—Ö: ").append(clickable)
                if (lastHierarchyDump.isNotBlank()) {
                    append("\n\n–í–ò–î–ò–ú–´–ï –≠–õ–ï–ú–ï–ù–¢–´:\n").append(lastHierarchyDump)
                }
            }
        }

        lastDiagnosticReport = report
        pendingAction = PendingAction.None
        scenarioRunning = false
        handler.removeCallbacksAndMessages(null)
        openOwnApp("Accessibility-–ø—Ä–æ–≤–µ—Ä–∫–∞:\n$report")
    }

    private fun attemptAddToQueue(
        attempt: Int,
        returnToApp: Boolean,
        expectedTrackId: String?,
        preferTrackRowMenu: Boolean,
        expectedTrackTitle: String? = null,
        expectedArtists: List<String> = emptyList()
    ) {
        val elapsed = System.currentTimeMillis() - scenarioStartedAt
        if (!initialYandexUiSettled && elapsed >= PAGE_READY_TIMEOUT_MS) {
            retryWholeScenarioOrFail(
                "–Ø–Ω–¥–µ–∫—Å –ú—É–∑—ã–∫–∞ –Ω–µ –ø–æ–∫–∞–∑–∞–ª–∞ —Å—Ç—Ä–∞–Ω–∏—Ü—É –æ–∂–∏–¥–∞–µ–º–æ–≥–æ —Ç—Ä–µ–∫–∞ –∑–∞ ${PAGE_READY_TIMEOUT_MS / 1000} —Å–µ–∫—É–Ω–¥"
            )
            return
        }

        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != YANDEX_MUSIC_PACKAGE) {
            scheduleAttempt(attempt, PAGE_READY_POLL_MS)
            return
        }

        if (!initialYandexUiSettled) {
            val signature = expectedTrackReadinessSignature(
                root,
                preferTrackRowMenu,
                expectedTrackTitle,
                expectedArtists
            )
            if (signature == null) {
                readinessSignature = ""
                readinessStableChecks = 0
                updateHierarchyDump(root)
                scheduleAttempt(attempt, PAGE_READY_POLL_MS)
                return
            }

            if (signature == readinessSignature) {
                readinessStableChecks++
            } else {
                readinessSignature = signature
                readinessStableChecks = 1
            }
            if (readinessStableChecks < REQUIRED_STABLE_READY_CHECKS) {
                scheduleAttempt(attempt, PAGE_READY_POLL_MS)
                return
            }
            initialYandexUiSettled = true
        }

        updateHierarchyDump(root)

        // Deep link –º–æ–∂–µ—Ç –æ—Å—Ç–∞–≤–∏—Ç—å –Ø–Ω–¥–µ–∫—Å –ú—É–∑—ã–∫—É –Ω–∞ –≥–ª–∞–≤–Ω–æ–º —ç–∫—Ä–∞–Ω–µ, –ø–æ–∫–∞–∑–∞–≤ –Ω—É–∂–Ω—ã–π
        // —Ç—Ä–µ–∫ —Ç–æ–ª—å–∫–æ –≤ –º–∏–Ω–∏-–ø–ª–µ–µ—Ä–µ. –í —ç—Ç–æ–º —Å–ª—É—á–∞–µ —Å–Ω–∞—á–∞–ª–∞ —Ä–∞—Å–∫—Ä—ã–≤–∞–µ–º –º–∏–Ω–∏-–ø–ª–µ–µ—Ä.
        if (!miniPlayerExpanded && expandExpectedTrackMiniPlayer(root, expectedTrackTitle)) {
            miniPlayerExpanded = true
            scheduleAttempt(0, 900L)
            return
        }

        // –î–ª—è –æ–±—ã—á–Ω—ã—Ö —Å—Å—ã–ª–æ–∫ –æ—Å—Ç–∞–≤–ª—è–µ–º —Ä–∞–±–æ—á—É—é –ª–æ–≥–∏–∫—É fixed5.
        // –î–ª—è album/track —Å—Å—ã–ª–æ–∫ –ù–ï –Ω–∞–∂–∏–º–∞–µ–º AddNext –¥–æ –ø–æ–ø—ã—Ç–∫–∏ –æ—Ç–∫—Ä—ã—Ç—å –º–µ–Ω—é —Å—Ç—Ä–æ–∫–∏ —Ç—Ä–µ–∫–∞,
        // —á—Ç–æ–±—ã –Ω–µ –ø—Ä–∏–º–µ–Ω–∏—Ç—å –∫–æ–º–∞–Ω–¥—É –∫ –º–µ–Ω—é –∞–ª—å–±–æ–º–∞.
        val albumRowMode = preferT◊Ω∫Í⁄$z{-ÆÈ‹j◊ùR"í«¬∆&V¬Ê6ˆÁFñÁ2Ç-Õ]›‚"í«¬∆&V¬Ê6ˆÁFñÁ2Ç&˜FñˆÁ2"íí66˜&R≥“É –¢ñbÜ6∆74Ê÷RÊ6ˆÁFñÁ2Ç&ñ÷vV'WGFˆ‚"í«¬6∆74Ê÷RÊ6ˆÁFñÁ2Ç&ñ÷vWfñWr"íí66˜&R≥“ –¢ñbÜÜ4GW&Fñˆ‚í66˜&R≥“SP–¢ñbÜÊˆFRÊó56V∆V7FVB«¬ÊˆFRÊó4fˆ7W6VB«¬ÊˆFRÊó466W76ñ&ñ∆óGîfˆ7W6VB«¬á&˜sÚÊó56V∆V7FVB”“G'VRí«¬á&˜sÚÊó4fˆ7W6VB”“G'VRíí66˜&R≥“cP–¢ñbá"ÁvñGFÇÇí√“GÉìbíbb"ÊÜVñváBÇí√“GÉìbíí66˜&R≥“P–†–¢66˜&R”“VÁ6fT∆'V’&˜uVÊ«Gíá"¬&˜t∆&V¬¬Ü4GW&Fñˆ‚ê–¢66˜&R”“WÜ6«VFVD˜fW&f∆˜u66˜&RÜ∆&V¬ê–†–¢f¬Fá&W6Üˆ∆B“ñbá7G&ñ7BíÉRV«6RSP–¢ñbá66˜&R„“Fá&W6Üˆ∆Bí6ÊFñFFRÜ6∆ñ6∂&∆R¬"¬66˜&R¬∆&V¬Êñd&∆Ê≤≤ñBÊñd&∆Ê≤≤&˜t∆&V¬ÁF∂RÉcí““íV«6RÁV∆¿–¢––¢ÊFó7FñÊ7D'í≤"G∂óBÊ&˜VÊG2Ê∆VgG”¢G∂óBÊ&˜VÊG2ÁF˜”¢G∂óBÊ&˜VÊG2Á&ñváG”¢G∂óBÊ&˜VÊG2Ê&˜GFˆ◊“"––¢Á6˜'FVEvóFÇÜ6ˆ◊&T'îFW66VÊFñÊsƒ6ÊFñFFS‚≤óBÁ66˜&R“ÁFÜV‰'í≤'2ÜóBÊ&˜VÊG2Ê6VÁFW%íÇí“&VfW'&VD∆'V’&˜uíÇíí“ê–†–¢f¬F&vWB“6ÊFñFFW2Êfó'7D˜$ÁV∆¬ÇíÛ¢&WGW&‚f«6P–¢&WGW&‚F&vWBÊÊˆFRÁW&f˜&‘7Fñˆ‚Ñ66W76ñ&ñ∆óGîÊˆFTñÊfÚ‰5DîÙÂÙ4ƒî4≤ê–¢––†–¢&ófFRgV‚6∆ñ6µ6V∆V7FVD˜$fˆ7W6VE&˜t÷VÁRá&ˆ˜C¢66W76ñ&ñ∆óGîÊˆFTñÊfÚì¢&ˆˆ∆V‚∞–¢f¬∆¬“◊WF&∆T∆ó7Dˆcƒ66W76ñ&ñ∆óGîÊˆFTñÊfÛ‚Çê–¢6ˆ∆∆V7Bá&ˆ˜B¬∆¬ê–†–¢f¬&˜w2“∆¬Ê÷Ê˜DÁV∆¬≤ÊˆFR”‡–¢ñbÇÊˆFRÊó5fó6ñ&∆UFıW6W"í&WGW&‰÷Ê˜DÁV∆¬ÁV∆¿–¢ñbÇÊˆFRÊó56V∆V7FVBbbÊˆFRÊó4fˆ7W6VBbbÊˆFRÊó466W76ñ&ñ∆óGîfˆ7W6VBí&WGW&‰÷Ê˜DÁV∆¬ÁV∆¿–¢f¬&˜r“fñÊD∆ñ∂V«ï&˜t6ˆÁFñÊW"ÜÊˆFRíÛ¢ÊˆFP–¢f¬"“&˜VÊG4ˆbá&˜rê–¢f¬Ç“&W6˜W&6W2ÊFó7∆î÷WG&ñ72ÊÜVñváEóÜV«0–¢ñbá"Ê6VÁFW%íÇí¬Ç¢„#B«¬"Ê6VÁFW%íÇí‚Ç¢„cbí&WGW&‰÷Ê˜DÁV∆¬ÁV∆¿–¢ –¢––†–¢f¬&W7B“&˜w2Ê÷ñ‰'î˜$ÁV∆¬≤'2ÜóBÊ6VÁFW%íÇí“&VfW'&VD∆'V’&˜uíÇíí“Û¢&WGW&‚f«6P–¢&WGW&‚Fá&W6˜W&6W2ÊFó7∆î÷WG&ñ72ÁvñGFÖóÜV«2“GÉ3"í¬&W7BÊ6VÁFW%íÇíê–¢––†–¢&ófFRgV‚ó56fT∆'V’&˜u&ñváE6ñFRá#¢&V7Bì¢&ˆˆ∆V‚∞–¢f¬F““&W6˜W&6W2ÊFó7∆î÷WG&ñ70–¢f¬r“F“ÁvñGFÖóÜV«0–¢f¬Ç“F“ÊÜVñváEóÜV«0–¢f¬vñGFÇ“"ÁvñGFÇÇê–¢f¬ÜVñváB“"ÊÜVñváBÇê–¢f¬ñ6ˆ‰˜$6V∆≈6ó¶VB“vñGFÇñ‚GÉbí‚ÊGÉÉíbbÜVñváBñ‚GÉbí‚ÊGÉ#ê–¢f¬&ñváE6ñFR“"Ê6VÁFW%ÇÇí‚r¢„c –¢f¬Ê˜DÜVFW"“"Ê6VÁFW%íÇí‚Ç¢„#@–¢f¬Ê˜E&V6ˆ÷÷VÊFFñˆÁ2“"Ê6VÁFW%íÇí¬Ç¢„cÄ–¢&WGW&‚ñ6ˆ‰˜$6V∆≈6ó¶VBbb&ñváE6ñFRbbÊ˜DÜVFW"bbÊ˜E&V6ˆ÷÷VÊFFñˆÁ0–¢––†–¢&ófFRgV‚∆'V’&˜u˜6óFñˆÂ66˜&Rá#¢&V7Bì¢ñÁB∞–¢f¬F““&W6˜W&6W2ÊFó7∆î÷WG&ñ70–¢f¬r“F“ÁvñGFÖóÜV«0–¢f¬Ç“F“ÊÜVñváEóÜV«0–¢f"66˜&R“ –¢ñbá"Ê6VÁFW%ÇÇí‚r¢„s"í66˜&R≥“3P–¢ñbá"Ê6VÁFW%ÇÇí‚r¢„ÉBí66˜&R≥“3P–¢ñbá"Ê6VÁFW%íÇíñ‚ÜÇ¢„3íÁFÙñÁBÇí‚‚ÜÇ¢„cBíÁFÙñÁBÇíí66˜&R≥“C –¢66˜&R≥“Éc“Ü'2á"Ê6VÁFW%íÇí“&VfW'&VD∆'V’&˜uíÇííÚGÉÇíííÊ6ˆW&6TD∆V7BÉê–¢&WGW&‚66˜&P–¢––†–¢&ófFRgV‚VÁ6fT∆'V’&˜uVÊ«Gíá#¢&V7B¬∆&V√¢7G&ñÊr¬Ü4GW&Fñˆ„¢&ˆˆ∆V‚ì¢ñÁB∞–¢f¬Ç“&W6˜W&6W2ÊFó7∆î÷WG&ñ72ÊÜVñváEóÜV«0–¢f"VÊ«Gí“ –¢ñbá"Ê6VÁFW%íÇí¬Ç¢„#BíVÊ«Gí≥“# –¢ñbá"Ê6VÁFW%íÇí‚Ç¢„cÇíVÊ«Gí≥“## –¢ñbÇÜ4GW&Fñˆ‚bbÜ∆&V¬Ê6ˆÁFñÁ2Ç-ΩÕÌ¬"í«¬∆&V¬Ê6ˆÁFñÁ2Ç&∆'V“"íííVÊ«Gí≥“C –¢ñbÜ∆&V¬Ê6ˆÁFñÁ2Ç-]≠ÌÕ]›M"í«¬∆&V¬Ê6ˆÁFñÁ2Ç'&V6ˆ÷÷VÊB"í«¬∆&V¬Ê6ˆÁFñÁ2Ç-˝Ì]Ìb"í«¬∆&V¬Ê6ˆÁFñÁ2Ç--ÕÕÌm]-˝Ì›""ííVÊ«Gí≥“#c –¢ñbÜ∆&V¬Ê6ˆÁFñÁ2Ç-˝Ω]ùΩç""í«¬∆&V¬Ê6ˆÁFñÁ2Ç'∆ñ∆ó7B"ííVÊ«Gí≥“# –¢ñbÜ∆&V¬Ê6ˆÁFñÁ2Ç-ç˝ÌΩ›ç-]Ω¬"í«¬∆&V¬Ê6ˆÁFñÁ2Ç&'Fó7B"ííVÊ«Gí≥“ì –¢ñbÜ∆&V¬Ê6ˆÁFñÁ2Ç-˝]]Õ]ç-¬"í«¬∆&V¬Ê6ˆÁFñÁ2Ç'6áVff∆R"ííVÊ«Gí≥“# –¢ñbÜ∆&V¬Ê6ˆÁFñÁ2Ç-≠}-ÕΩÕÌ¬"í«¬∆&V¬Ê6ˆÁFñÁ2Ç&F˜vÊ∆ˆF∆'V“"ííVÊ«Gí≥“# –¢ñbÜ∆&V¬Ê6ˆÁFñÁ2Ç-ÌΩÌm≠"í«¬∆&V¬Ê6ˆÁFñÁ2Ç&6˜fW""ííVÊ«Gí≥“É –¢&WGW&‚VÊ«Gê–¢––†–¢&ófFRgV‚&VfW'&VD∆'V’&˜uíÇì¢ñÁB“á&W6˜W&6W2ÊFó7∆î÷WG&ñ72ÊÜVñváEóÜV«2¢„CÜbíÁFÙñÁBÇê–†–¢&ófFRgV‚F∆'V’G&6µ&˜u&ñváD÷VÁRÜ6VÁFW$&ñ3¢f∆ˆBì¢&ˆˆ∆V‚∞–¢f¬F““&W6˜W&6W2ÊFó7∆î÷WG&ñ70–¢f¬í“ÜF“ÊÜVñváEóÜV«2¢6VÁFW$&ñ2íÁFÙñÁBÇíÊ6ˆW&6Tñ‚ÇÜF“ÊÜVñváEóÜV«2¢„#fbíÁFÙñÁBÇí¬ÜF“ÊÜVñváEóÜV«2¢„cFbíÁFÙñÁBÇíê–¢&WGW&‚FÜF“ÁvñGFÖóÜV«2“GÉ3"í¬íê–¢––†–¢&ófFRgV‚∆ˆÊu&W74∆'V’G&6µ&˜rÜ6VÁFW$&ñ3¢f∆ˆBì¢&ˆˆ∆V‚∞–¢f¬F““&W6˜W&6W2ÊFó7∆î÷WG&ñ70–¢f¬í“ÜF“ÊÜVñváEóÜV«2¢6VÁFW$&ñ2íÁFÙñÁBÇíÊ6ˆW&6Tñ‚ÇÜF“ÊÜVñváEóÜV«2¢„#ÜbíÁFÙñÁBÇí¬ÜF“ÊÜVñváEóÜV«2¢„c&bíÁFÙñÁBÇíê–¢&WGW&‚vW7GW&RÇÜF“ÁvñGFÖóÜV«2¢„C&bíÁFÙñÁBÇí¬í¬GW&Fñˆ‰◊2“sê–¢––†–¢&ófFRgV‚6∆ñ6µG&6¥6ˆÁFWáD÷VÁT'ï6V÷ÁFñ72á&ˆ˜C¢66W76ñ&ñ∆óGîÊˆFTñÊfÚì¢&ˆˆ∆V‚∞–¢f¬6V÷ÁFñ5v˜&G2“∆ó7DˆbÄ–¢-]ù"¬-]ùR"¬&÷˜&R"¬&÷˜&V˜FñˆÁ2"¬&˜FñˆÁ2"¿–¢-M]ù--çÚ"¬-M==ç]M]ù--çÚ"¬-Õ]›‚"¬-Ì-≠Ω-ÕÕ]›‚"¿–¢-≠Ì›-]≠-›Ì]Õ]›‚"¬&6ˆÁFWáF÷VÁR"¬&˜fW&f∆˜r –¢ê–†–¢f¬∆¬“◊WF&∆T∆ó7Dˆcƒ66W76ñ&ñ∆óGîÊˆFTñÊfÛ‚Çê–¢6ˆ∆∆V7Bá&ˆ˜B¬∆¬ê–†–¢f¬6ÊFñFFW2“∆¬Ê÷Ê˜DÁV∆¬≤ÊˆFR”‡–¢ñbÇÊˆFRÊó5fó6ñ&∆UFıW6W"í&WGW&‰÷Ê˜DÁV∆¬ÁV∆¿–¢f¬6∆ñ6∂&∆R“ñbÜÊˆFRÊó46∆ñ6∂&∆RíÊˆFRV«6R6∆ñ6∂&∆U&VÁBÜÊˆFRê–¢ñbÜ6∆ñ6∂&∆R”“ÁV∆¬í&WGW&‰÷Ê˜DÁV∆¬ÁV∆¿–¢f¬∆&V¬“∆&VƒˆbÜÊˆFRíÊÊ˜&÷∆ó¶Uñ“Çê–¢f¬ñB“ÊˆFRÁfñWtñE&W6˜W&6TÊ÷RÊ˜$V◊GíÇíÊÊ˜&÷∆ó¶Uñ“Çê–¢f¬6∆74Ê÷R“ÊˆFRÊ6∆74Ê÷SÚÁFı7G&ñÊrÇíÊ˜$V◊GíÇíÊ∆˜vW&66RÇê–¢f¬"“&˜VÊG4ˆbÜ6∆ñ6∂&∆Rê–¢f"66˜&R“ –†–¢ñbá6V÷ÁFñ5v˜&G2ÊÁí≤∆&V¬Ê6ˆÁFñÁ2ÜóBí“í66˜&R≥“# –¢ñbá6V÷ÁFñ5v˜&G2ÊÁí≤ñBÊ6ˆÁFñÁ2ÜóBí“í66˜&R≥“ –¢ñbÜñBÊ6ˆÁFñÁ2Ç&÷˜&R"í«¬ñBÊ6ˆÁFñÁ2Ç&˜fW&f∆˜r"í«¬ñBÊ6ˆÁFñÁ2Ç&÷VÁR"íí66˜&R≥“ì –¢ñbÜ6∆74Ê÷RÊ6ˆÁFñÁ2Ç&ñ÷vV'WGFˆ‚"í«¬6∆74Ê÷RÊ6ˆÁFñÁ2Ç&ñ÷vWfñWr"íí66˜&R≥“ –¢66˜&R≥“˜fW&f∆˜u˜6óFñˆÂ66˜&Rá"ê–¢66˜&R”“WÜ6«VFVD˜fW&f∆˜u66˜&RÜ∆&V¬ê–†–¢ñbá66˜&R„“ìí6ÊFñFFRÜ6∆ñ6∂&∆R¬"¬66˜&R¬∆&V¬Êñd&∆Ê≤≤ñB“íV«6RÁV∆¿–¢“Á6˜'FVEvóFÇÜ6ˆ◊&T'îFW66VÊFñÊsƒ6ÊFñFFS‚≤óBÁ66˜&R“ÁFÜV‰'í≤óBÊ&˜VÊG2ÁF˜“ÁFÜV‰'îFW66VÊFñÊr≤óBÊ&˜VÊG2Á&ñváB“ê–†–¢f¬F&vWB“6ÊFñFFW2Êfó'7D˜$ÁV∆¬ÇíÛ¢&WGW&‚f«6P–¢&WGW&‚F&vWBÊÊˆFRÁW&f˜&‘7Fñˆ‚Ñ66W76ñ&ñ∆óGîÊˆFTñÊfÚ‰5DîÙÂÙ4ƒî4≤ê–¢––†–¢&ófFRgV‚6∆ñ6µG&6¥6ˆÁFWáD÷VÁT'ï66˜&Rá&ˆ˜C¢66W76ñ&ñ∆óGîÊˆFTñÊfÚì¢&ˆˆ∆V‚∞–¢f¬∆¬“◊WF&∆T∆ó7Dˆcƒ66W76ñ&ñ∆óGîÊˆFTñÊfÛ‚Çê–¢6ˆ∆∆V7Bá&ˆ˜B¬∆¬ê–†–¢f¬6ÊFñFFW2“∆¬Ê÷Ê˜DÁV∆¬≤ÊˆFR”‡–¢ñbÇÊˆFRÊó5fó6ñ&∆UFıW6W"í&WGW&‰÷Ê˜DÁV∆¬ÁV∆¿–¢f¬6∆ñ6∂&∆R“ñbÜÊˆFRÊó46∆ñ6∂&∆RíÊˆFRV«6R6∆ñ6∂&∆U&VÁBÜÊˆFRê–¢ñbÜ6∆ñ6∂&∆R”“ÁV∆¬«¬6∆ñ6∂&∆R”“ÊˆFRbbÊˆFRÊó4fˆ7W6&∆Rí&WGW&‰÷Ê˜DÁV∆¬ÁV∆¿–¢f¬"“&˜VÊG4ˆbÜ6∆ñ6∂&∆Rê–¢f¬∆&V¬“∆&VƒˆbÜ6∆ñ6∂&∆RíÊÊ˜&÷∆ó¶Uñ“Çê–¢f¬ñB“6∆ñ6∂&∆RÁfñWtñE&W6˜W&6TÊ÷RÊ˜$V◊GíÇíÊÊ˜&÷∆ó¶Uñ“Çê–†–¢f¬vñGFÇ“"ÁvñGFÇÇê–¢f¬ÜVñváB“"ÊÜVñváBÇê–¢f¬ñ6ˆÂ6ó¶VB“vñGFÇñ‚GÉ#Bí‚ÊGÉìbíbbÜVñváBñ‚GÉ#Bí‚ÊGÉìbê–¢ñbÇñ6ˆÂ6ó¶VBí&WGW&‰÷Ê˜DÁV∆¬ÁV∆¿–†–¢f"66˜&R“˜fW&f∆˜u˜6óFñˆÂ66˜&Rá"ê–¢ñbÜñBÊ6ˆÁFñÁ2Ç&÷VÁR"í«¬ñBÊ6ˆÁFñÁ2Ç&÷˜&R"í«¬ñBÊ6ˆÁFñÁ2Ç&˜fW&f∆˜r"íí66˜&R≥“cP–¢66˜&R”“WÜ6«VFVD˜fW&f∆˜u66˜&RÜ∆&V¬ê–†–¢ñbá66˜&R‚CRí6ÊFñFFRÜ6∆ñ6∂&∆R¬"¬66˜&R¬∆&V¬Êñd&∆Ê≤≤ñB“íV«6RÁV∆¿–¢“ÊFó7FñÊ7D'í≤"G∂óBÊ&˜VÊG2Ê∆VgG”¢G∂óBÊ&˜VÊG2ÁF˜”¢G∂óBÊ&˜VÊG2Á&ñváG”¢G∂óBÊ&˜VÊG2Ê&˜GFˆ◊“"––¢Á6˜'FVEvóFÇÜ6ˆ◊&T'îFW66VÊFñÊsƒ6ÊFñFFS‚≤óBÁ66˜&R“ÁFÜV‰'í≤óBÊ&˜VÊG2ÁF˜“ÁFÜV‰'îFW66VÊFñÊr≤óBÊ&˜VÊG2Á&ñváB“ê–†–¢f¬F&vWB“6ÊFñFFW2Êfó'7D˜$ÁV∆¬ÇíÛ¢&WGW&‚f«6P–¢&WGW&‚F&vWBÊÊˆFRÁW&f˜&‘7Fñˆ‚Ñ66W76ñ&ñ∆óGîÊˆFTñÊfÚ‰5DîÙÂÙ4ƒî4≤ê–¢––†–¢&ófFRgV‚˜fW&f∆˜u˜6óFñˆÂ66˜&Rá#¢&V7Bì¢ñÁB∞–¢f¬r“&W6˜W&6W2ÊFó7∆î÷WG&ñ72ÁvñGFÖóÜV«0–¢f¬Ç“&W6˜W&6W2ÊFó7∆î÷WG&ñ72ÊÜVñváEóÜV«0–¢f"66˜&R“ –¢ñbá"Ê6VÁFW%ÇÇí‚r¢„s"í66˜&R≥“CP–¢ñbá"Ê6VÁFW%ÇÇí‚r¢„Ébí66˜&R≥“3 –¢ñbá"Ê6VÁFW%íÇíñ‚GÉCBí‚‚ÜÇ¢„C"íÁFÙñÁBÇíí66˜&R≥“C –¢ñbá"Ê6VÁFW%íÇíñ‚ÜÇ¢„C"íÁFÙñÁBÇí‚‚ÜÇ¢„s"íÁFÙñÁBÇíí66˜&R≥“P–¢ñbá"Ê6VÁFW%íÇí‚Ç¢„Éí66˜&R”“C –¢ñbÜ'2á"ÁvñGFÇÇí“"ÊÜVñváBÇíí¬GÉ#Bíí66˜&R≥“ –¢&WGW&‚66˜&P–¢––†–¢&ófFRgV‚WÜ6«VFVD˜fW&f∆˜u66˜&RÜ∆&V√¢7G&ñÊrì¢ñÁB∞–¢f¬&B“∆ó7DˆbÄ–¢-›}B"¬&&6≤"¬'∆í"¬'W6R"¬-˝=}"¬--Ì˝Ìçr"¬--ÌÚ"¿–¢&∆ñ∂R"¬-Ωù¢"¬-›-ç-Ú"¬'6Ü&R"¬-˝ÌM]Ωç-ÕÚ"¬'6V&6Ç"¬-˝Ìç¢"¿–¢&6∆˜6R"¬-}≠Ω-¬"¬'6áVff∆R"¬-˝]]Õ]Ç"¬'&WVB"¬-˝Ì--Ì –¢ê–¢&WGW&‚ñbÜ&BÊÁí≤∆&V¬Ê6ˆÁFñÁ2ÜóBí“íV«6R –¢––†–¢&ófFRgV‚FF˜&ñváD˜fW&f∆˜rÇì¢&ˆˆ∆V‚∞–¢f¬F““&W6˜W&6W2ÊFó7∆î÷WG&ñ70–¢&WGW&‚FÜF“ÁvñGFÖóÜV«2“GÉ#Çí¬GÉcBíê–¢––†–¢&ófFRgV‚FWW%&ñváD˜fW&f∆˜rÇì¢&ˆˆ∆V‚∞–¢f¬F““&W6˜W&6W2ÊFó7∆î÷WG&ñ70–¢&WGW&‚FÜF“ÁvñGFÖóÜV«2“GÉ#Çí¬GÉ"íê–¢––†–¢&ófFRgV‚F÷ñFF∆U&ñváD˜fW&f∆˜rÇì¢&ˆˆ∆V‚∞–¢f¬F““&W6˜W&6W2ÊFó7∆î÷WG&ñ70–¢&WGW&‚FÜF“ÁvñGFÖóÜV«2“GÉ#Çí¬ÜF“ÊÜVñváEóÜV«2¢„3FbíÁFÙñÁBÇíê–¢––†–¢&ófFRgV‚F∆˜vW%&ñváD˜fW&f∆˜rÇì¢&ˆˆ∆V‚∞–¢f¬F““&W6˜W&6W2ÊFó7∆î÷WG&ñ70–¢&WGW&‚FÜF“ÁvñGFÖóÜV«2“GÉ#Çí¬ÜF“ÊÜVñváEóÜV«2¢„SÜbíÁFÙñÁBÇíê–¢––†–¢&ófFRgV‚∆ˆÊu&W74∆ñ∂V«ïG&6¥&VÇì¢&ˆˆ∆V‚∞–¢f¬F““&W6˜W&6W2ÊFó7∆î÷WG&ñ70–¢&WGW&‚vW7GW&RÜF“ÁvñGFÖóÜV«2Ú"¬ÜF“ÊÜVñváEóÜV«2¢„C&bíÁFÙñÁBÇí¬GW&Fñˆ‰◊2“cSê–¢––†–¢&ófFRgV‚FáÉ¢ñÁB¬ì¢ñÁBì¢&ˆˆ∆V‚“vW7GW&RáÇ¬í¬GW&Fñˆ‰◊2“Éê–†–¢&ófFRgV‚vW7GW&RáÉ¢ñÁB¬ì¢ñÁB¬GW&Fñˆ‰◊3¢∆ˆÊrì¢&ˆˆ∆V‚∞–¢f¬FÇ“FÇÇíÊ«í≤÷˜fUFÚáÇÁFÙf∆ˆBÇí¬íÁFÙf∆ˆBÇíí––¢f¬vW7GW&R“vW7GW&TFW67&óFñˆ‚‰'Vñ∆FW"Çê–¢ÊFE7G&ˆ∂RÑvW7GW&TFW67&óFñˆ‚Â7G&ˆ∂TFW67&óFñˆ‚áFÇ¬¬GW&Fñˆ‰◊2íê–¢Ê'Vñ∆BÇê–¢&WGW&‚Fó7F6ÑvW7GW&RÜvW7GW&R¬ÁV∆¬¬ÁV∆¬ê–¢––†–¢&ófFRgV‚6∆ñ6∂&∆U&VÁBÜÊˆFS¢66W76ñ&ñ∆óGîÊˆFTñÊfÛÚì¢66W76ñ&ñ∆óGîÊˆFTñÊfÛÚ∞–¢f"7W"“ÊˆFSÚÁ&VÁ@–¢&WVBÉÇí∞–¢ñbÜ7W"”“ÁV∆¬í&WGW&‚ÁV∆¿–¢ñbÜ7W"Êó46∆ñ6∂&∆Rí&WGW&‚7W –¢7W"“7W"Á&VÁ@–¢––¢&WGW&‚ÁV∆¿–¢––†–¢&ófFRgV‚fñÊD∆ñ∂V«ï&˜t6ˆÁFñÊW"ÜÊˆFS¢66W76ñ&ñ∆óGîÊˆFTñÊfÚì¢66W76ñ&ñ∆óGîÊˆFTñÊfÛÚ∞–¢f"&W7C¢66W76ñ&ñ∆óGîÊˆFTñÊfÛÚ“ÊˆFP–¢f"7W"“ÊˆFRÁ&VÁ@–¢&WVBÉRí∞–¢ñbÜ7W"”“ÁV∆¬í&WGW&‚&W7@–¢f¬"“&˜VÊG4ˆbÜ7W"ê–¢f¬Ç“"ÊÜVñváBÇê–¢f¬r“"ÁvñGFÇÇê–¢ñbÜÇñ‚GÉ3bí‚ÊGÉ"íbbr‚&W6˜W&6W2ÊFó7∆î÷WG&ñ72ÁvñGFÖóÜV«2¢„CRí∞–¢&W7B“7W –¢––¢7W"“7W"Á&VÁ@–¢––¢&WGW&‚&W7@–¢––†–¢&ófFRgV‚∆&VƒˆbÜÊˆFS¢66W76ñ&ñ∆óGîÊˆFTñÊfÚì¢7G&ñÊr∞–¢f¬'G2“∆ó7DˆbÄ–¢ÊˆFRÁFWáCÚÁFı7G&ñÊrÇíÊ˜$V◊GíÇí¿–¢ÊˆFRÊ6ˆÁFVÁDFW67&óFñˆ„ÚÁFı7G&ñÊrÇíÊ˜$V◊GíÇí¿–¢ÊˆFRÁfñWtñE&W6˜W&6TÊ÷RÊ˜$V◊GíÇê–¢ê–¢&WGW&‚'G2Êfñ«FW"≤óBÊó4Ê˜D&∆Ê≤Çí“Ê¶ˆñÂFı7G&ñÊrÇ""ê–¢––†–¢&ófFRgV‚∆&V≈vóFÑ6Üñ∆G&V‚ÜÊˆFS¢66W76ñ&ñ∆óGîÊˆFTñÊfÚì¢7G&ñÊr∞–¢f¬˜WB“7G&ñÊt'Vñ∆FW"Ü∆&VƒˆbÜÊˆFRíê–¢gV‚v∆≤Ü„¢66W76ñ&ñ∆óGîÊˆFTñÊfÚ¬FWFÉ¢ñÁBí∞–¢ñbÜFWFÇ‚2í&WGW&‡–¢f˜"Üíñ‚VÁFñ¬‚Ê6Üñ∆D6˜VÁBí∞–¢f¬6Üñ∆B“‚ÊvWD6Üñ∆BÜííÛ¢6ˆÁFñÁVP–¢f¬∆&V¬“∆&VƒˆbÜ6Üñ∆Bê–¢ñbÜ∆&V¬Êó4Ê˜D&∆Ê≤Çíí˜WBÊVÊBÇrríÊVÊBÜ∆&V¬ê–¢v∆≤Ü6Üñ∆B¬FWFÇ≤ê–¢––¢––¢v∆≤ÜÊˆFR¬ê–¢&WGW&‚˜WBÁFı7G&ñÊrÇê–¢––†–¢&ófFRgV‚7G&ñÊrÊÊ˜&÷∆ó¶Uñ“Çì¢7G&ñÊr“∆˜vW&66RÇê–¢Á&W∆6RÇ}r¬}Rrê–¢Á&W∆6RÖ&VvWÇÇ%µÊ◊≠›Û”ï“≤"í¬""ê–†–¢&ófFRgV‚7G&ñÊrÁ7G&óWá∆ñ6óEG&6¥÷&∂W'2Çì¢7G&ñÊr∞–¢f"˜WB“FÜó0–¢f¬÷&∂W'2“∆ó7DˆbÄ–¢--]≠ÌM]mç-›]›ÌÕ-ç-›=ÌΩ]≠ç≠2"¿–¢-ÌM]mç-›]›ÌÕ-ç-›=ÌΩ]≠ç≠2"¿–¢-›]›ÌÕ-ç-›˝Ω]≠ç≠"¿–¢-›]›ÌÕ-ç-›=ÌΩ]≠ç≠2"¿–¢-›]›ÌÕ-ç-›ÌùΩ]≠ç≠Ìí"¿–¢-Õ-]›Ω]ΩÌ-"¿–¢--Ì≠Ωçm-]ΩÕ›Ωù}›≠-≠=mÌ}≠R"¿–¢--Ì≠Ωçm-]ΩÕ›Ωù}›¢"¿–¢-}›}Ì≠-Ì≠Ωçm-]ΩÕ›Ωí"¿–¢'&VÁF∆Gfó6˜'í"¿–¢&Wá∆ñ6óF«ó&ñ72"¿–¢&Wá∆ñ6óF6ˆÁFVÁB"¿–¢&Wá∆ñ6óB –¢ê–¢÷&∂W'2Êf˜$V6Ç≤÷&∂W"”‚˜WB“˜WBÁ&W∆6RÜ÷&∂W"¬""í––¢&WGW&‚˜W@–¢––†–¢&ófFRgV‚&˜VÊG4ˆbÜÊˆFS¢66W76ñ&ñ∆óGîÊˆFTñÊfÚì¢&V7B“&V7BÇíÊ«6Ú≤ÊˆFRÊvWD&˜VÊG4ñÂ67&VV‚ÜóBí––†–¢&ófFRgV‚WFFTÜñW&&6áîGV◊á&ˆ˜C¢66W76ñ&ñ∆óGîÊˆFTñÊfÚí∞–¢f¬∆¬“◊WF&∆T∆ó7Dˆcƒ66W76ñ&ñ∆óGîÊˆFTñÊfÛ‚Çê–¢6ˆ∆∆V7Bá&ˆ˜B¬∆¬ê–¢∆7DÜñW&&6áîGV◊“∆¬Ê56WVVÊ6RÇê–¢Ê÷ñÊFWÜVDÊ˜DÁV∆¬≤ñÊFWÇ¬ÊˆFR”‡–¢ñbÇÊˆFRÊó5fó6ñ&∆UFıW6W"í&WGW&‰÷ñÊFWÜVDÊ˜DÁV∆¬ÁV∆¿–¢f¬&t∆&V¬“∆&VƒˆbÜÊˆFRíÁG&ñ“Çê–¢ñbá&t∆&V¬Êó4&∆Ê≤Çíí&WGW&‰÷ñÊFWÜVDÊ˜DÁV∆¬ÁV∆¿–¢f¬"“&˜VÊG4ˆbÜÊˆFRê–¢f¬6∆ñ6∂&∆R“ñbÜÊˆFRÊó46∆ñ6∂&∆Rí"6∆ñ6∂&∆R"V«6R" –¢f¬6V∆V7FVB“vÜV‚∞–¢ÊˆFRÊó56V∆V7FVB”‚"6V∆V7FVB –¢ÊˆFRÊó4fˆ7W6VB”‚"fˆ7W6VB –¢ÊˆFRÊó466W76ñ&ñ∆óGîfˆ7W6VB”‚"66W76ñ&ñ∆óGîfˆ7W6VB –¢V«6R”‚" –¢––¢"2FñÊFWÇG∑&t∆&V¬ÁF∂RÉìó“≤G%“F6∆ñ6∂&∆RG6V∆V7FVB –¢––¢ÊFó7FñÊ7BÇê–¢ÁF∂RÉÉê–¢Ê¶ˆñÂFı7G&ñÊrÇ%∆‚"ê–¢––†–¢&ófFRgV‚6ˆ∆∆V7BÜÊˆFS¢66W76ñ&ñ∆óGîÊˆFTñÊfÚ¬˜WC¢◊WF&∆T∆ó7Cƒ66W76ñ&ñ∆óGîÊˆFTñÊfÛ‚í∞–¢˜WB≥“ÊˆFP–¢f˜"Üíñ‚VÁFñ¬ÊˆFRÊ6Üñ∆D6˜VÁBí∞–¢ÊˆFRÊvWD6Üñ∆BÜíìÚÊ∆WB≤6ˆ∆∆V7BÜóB¬˜WBí––¢––¢––†–¢&ófFRgV‚Gáf«VS¢ñÁBì¢ñÁB“áf«VR¢&W6˜W&6W2ÊFó7∆î÷WG&ñ72ÊFVÁ6óGííÁFÙñÁBÇê–†–¢&ófFRFF6∆726ÊFñFFRÄ–¢f¬ÊˆFS¢66W76ñ&ñ∆óGîÊˆFTñÊfÚ¿–¢f¬&˜VÊG3¢&V7B¿–¢f¬66˜&S¢ñÁB¿–¢f¬∆&V√¢7G&ñÊp–¢ê–ß––