package com.jarvis.assistant.tools

/** User choices that limit which notifications JARVIS may keep in memory. */
data class NotificationPrivacySettings(
    val blockSensitiveApps: Boolean = true,
    val excludedPackages: Set<String> = emptySet(),
)

/**
 * Pure decision logic for the notification listener. Notification content is never
 * logged or persisted; this only decides whether it may be held in memory at all.
 */
object NotificationPrivacy {

    /** Messaging, SMS, email, payment and banking apps commonly showing private previews or OTPs. */
    val KNOWN_SENSITIVE_PACKAGES: Set<String> = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
        "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging",
        "com.android.messaging", "com.facebook.orca", "com.instagram.android", "com.discord",
        "com.snapchat.android", "com.viber.voip", "jp.naver.line.android", "com.Slack",
        "com.microsoft.teams", "com.google.android.gm", "com.microsoft.office.outlook",
        "com.google.android.apps.nbu.paisa.user", "net.one97.paytm", "com.phonepe.app",
        "in.org.npci.upiapp", "com.paypal.android.p2pmobile", "com.google.android.apps.walletnfcrel",
        "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy",
    )

    private val SENSITIVE_PACKAGE_HINTS = listOf("bank", "upi", "wallet", "pay", "authenticator", "otp", "sms", "messag")

    /** Categories (Notification.CATEGORY_*) whose content is private by nature. */
    private val SENSITIVE_CATEGORIES = setOf("msg", "email", "call")

    private val OTP_PATTERN = Regex(
        "\\b(otp|one[- ]time|verification code|security code|passcode|login code|auth(entication)? code|pin)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val CODE_PATTERN = Regex("\\b\\d{4,8}\\b")

    /** Valid Android package name; used to validate user-entered exclusions. */
    private val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun isValidPackageName(name: String): Boolean = name.length <= 255 && PACKAGE_PATTERN.matches(name)

    /** Parses a comma/whitespace separated list, dropping invalid entries. */
    fun parsePackageList(text: String): Set<String> =
        text.split(',', ' ', '\n', ';', '\t').map { it.trim() }.filter { isValidPackageName(it) }.toSet()

    fun isSensitiveApp(packageName: String, category: String?, defaultSmsPackage: String? = null): Boolean {
        if (packageName in KNOWN_SENSITIVE_PACKAGES) return true
        if (defaultSmsPackage != null && packageName == defaultSmsPackage) return true
        if (category != null && category in SENSITIVE_CATEGORIES) return true
        val lower = packageName.lowercase()
        return SENSITIVE_PACKAGE_HINTS.any { lower.contains(it) }
    }

    fun looksLikeOtp(title: String?, text: String?): Boolean {
        val joined = listOfNotNull(title, text).joinToString(" ")
        return OTP_PATTERN.containsMatchIn(joined) && CODE_PATTERN.containsMatchIn(joined)
    }

    /** True if a notification may be kept (in memory only) for "read my latest notification". */
    fun mayRetain(
        settings: NotificationPrivacySettings,
        packageName: String,
        category: String?,
        title: String?,
        text: String?,
        defaultSmsPackage: String? = null,
    ): Boolean {
        if (packageName in settings.excludedPackages) return false
        if (settings.blockSensitiveApps) {
            if (isSensitiveApp(packageName, category, defaultSmsPackage)) return false
            if (looksLikeOtp(title, text)) return false
        }
        return true
    }
}

/**
 * In-memory ring of recent notifications: at most [CAPACITY] entries, each dropped after
 * [TTL_MS]. Never persisted or logged. Time is passed in (elapsed-realtime millis) for tests.
 */
internal object NotificationStore {
    const val CAPACITY = 5
    const val TTL_MS = 10 * 60 * 1000L

    private class Item(val key: String, val packageName: String, val summary: NotificationSummary, val postedAt: Long)

    private val items = ArrayDeque<Item>()

    @Synchronized
    fun add(key: String, summary: NotificationSummary, nowMillis: Long, packageName: String = "") {
        prune(nowMillis)
        items.removeAll { it.key == key }
        items.addLast(Item(key, packageName, summary, nowMillis))
        while (items.size > CAPACITY) items.removeFirst()
    }

    @Synchronized
    fun remove(key: String) {
        items.removeAll { it.key == key }
    }

    /** Drops everything from [packageName] (e.g. after the user excludes that app). */
    @Synchronized
    fun removePackage(packageName: String) {
        items.removeAll { it.packageName == packageName }
    }

    /** Re-applies [keep] to retained entries (e.g. after privacy settings change). */
    @Synchronized
    fun retainOnly(keep: (packageName: String, summary: NotificationSummary) -> Boolean) {
        items.removeAll { !keep(it.packageName, it.summary) }
    }

    @Synchronized
    fun latest(nowMillis: Long): NotificationSummary? {
        prune(nowMillis)
        return items.lastOrNull()?.summary
    }

    @Synchronized
    fun size(nowMillis: Long): Int {
        prune(nowMillis)
        return items.size
    }

    /** Non-empty title/text fragments currently held, used to keep them out of history. */
    @Synchronized
    fun sensitiveFragments(): List<String> =
        items.flatMap { listOfNotNull(it.summary.title, it.summary.text) }.filter { it.isNotBlank() }

    @Synchronized
    fun clear() = items.clear()

    private fun prune(nowMillis: Long) {
        items.removeAll { nowMillis - it.postedAt > TTL_MS || nowMillis < it.postedAt }
    }
}
