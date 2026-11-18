package app.gubbins.spindroid.discovery

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * The name that the phone shows to the servers it talks to.
 *
 * The user may rename the phone in the system settings, so the name is
 * read where it is set. The model stands in when no name is set, so
 * two phones still tell apart on a network of unrenamed devices.
 */
object DeviceName {
    fun of(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?: Build.MODEL
}
