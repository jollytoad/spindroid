package com.sendspin.sender.capture

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * The apps the finder finds on the device.
 *
 * The probe asks the package manager which apps carry a media browser
 * service, the badge the audio apps wear for Android Auto and the Wear
 * player. One row per service names the app, and the row holds no
 * session: it answers the finder, and the session list stays the boss of
 * what plays.
 */
class InstalledMediaApps(
    private val context: Context,
) : SessionApps.MediaAppFinder {

    override fun installedMediaApps(): List<SessionApps.SessionApp> {
        val packageManager = context.packageManager
        // The action of MediaBrowserService.SERVICE_INTERFACE, spelled as
        // the constant's value, because the constant lives in androidx.
        // media, which the app does not carry.
        val services = packageManager.queryIntentServices(
            Intent("android.media.browse.MediaBrowserService"),
            0,
        )
        return services.mapNotNull { service ->
            val packageName = service.serviceInfo?.packageName ?: return@mapNotNull null
            if (packageName == context.packageName) return@mapNotNull null
            val uid = try {
                packageManager.getPackageUid(packageName, 0)
            } catch (exception: PackageManager.NameNotFoundException) {
                0
            }
            if (uid <= 0) return@mapNotNull null
            SessionApps.SessionApp(
                packageName = packageName,
                label = service.loadLabel(packageManager).toString(),
                uid = uid,
                playingNow = false,
                nowPlaying = null,
                canPlay = false,
                canPause = false,
                holdsSession = false,
            )
        }
    }
}
