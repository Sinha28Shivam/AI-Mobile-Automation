package com.jarvis.assistant.service

import android.content.Context
import android.util.Log
import com.jarvis.assistant.commands.CommandRouter
import com.jarvis.assistant.data.LocalHistoryRepository
import com.jarvis.assistant.data.PreferencesRepository
import com.jarvis.assistant.tools.AndroidAppLauncher
import com.jarvis.assistant.tools.AndroidDeviceInfoTool
import com.jarvis.assistant.tools.AndroidNotificationReader
import com.jarvis.assistant.tools.AndroidReminderTool
import com.jarvis.assistant.tools.AndroidSettingsNavigationTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Adapts [CommandRouter] to the pipeline's [CommandHandler]. Records the exchange to
 * [LocalHistoryRepository] only when the user has opted in to history.
 */
class RouterCommandHandler(
    private val router: CommandRouter,
    private val historyEnabled: suspend () -> Boolean,
    private val history: LocalHistoryRepository?,
) : CommandHandler {

    override suspend fun handle(text: String, confidence: Float): HandlerResult {
        val result = router.route(text, confidence)
        history?.let { repo ->
            try {
                repo.record(historyEnabled(), text, result.spokenText)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "History record failed: ${e.javaClass.simpleName}")
            }
        }
        return HandlerResult(result.spokenText, result.stopRequested)
    }

    companion object {
        private const val TAG = "RouterCommandHandler"

        fun create(context: Context): RouterCommandHandler {
            val app = context.applicationContext
            val preferences = PreferencesRepository(app)
            val router = CommandRouter(
                appLauncher = AndroidAppLauncher(app),
                deviceInfo = AndroidDeviceInfoTool(app),
                settings = AndroidSettingsNavigationTool(app),
                notifications = AndroidNotificationReader(app),
                reminders = AndroidReminderTool(app),
            )
            return RouterCommandHandler(
                router = router,
                historyEnabled = { preferences.settings.first().historyEnabled },
                history = LocalHistoryRepository.get(app),
            )
        }
    }
}
