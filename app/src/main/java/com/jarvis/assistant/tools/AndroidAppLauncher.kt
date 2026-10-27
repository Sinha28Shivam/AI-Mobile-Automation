package com.jarvis.assistant.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Launches installed apps via their launcher intent. Package visibility is granted by the
 * `<queries>` MAIN/LAUNCHER entry in the manifest (no QUERY_ALL_PACKAGES).
 */
class AndroidAppLauncher(context: Context) : AppLauncher {

    private val appContext = context.applicationContext

    fun installedApps(): List<AppEntry> {
        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return infos
            .filter { it.activityInfo.packageName != appContext.packageName }
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
    }

    override suspend fun launch(appName: String): AppLaunchOutcome {
        val apps = withContext(Dispatchers.IO) { installedApps() }
        return when (val r = AppNameResolver.resolve(appName, apps)) {
            AppResolution.NotFound -> AppLaunchOutcome.NotFound
            is AppResolution.Ambiguous -> AppLaunchOutcome.Ambiguous(r.candidates.map { it.label }.distinct())
            is AppResolution.Match -> start(r.app)
        }
    }

    private fun start(app: AppEntry): AppLaunchOutcome {
        val intent = appContext.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return AppLaunchOutcome.Failed(app.label)
        return when (ActivityStarter.start(appContext, listOf(intent), app.label)) {
            StartOutcome.Started -> AppLaunchOutcome.Launched(app.label)
            StartOutcome.NotificationPosted -> AppLaunchOutcome.NeedsUserTap(app.label)
            StartOutcome.Failed -> AppLaunchOutcome.Failed(app.label)
        }
    }
}
