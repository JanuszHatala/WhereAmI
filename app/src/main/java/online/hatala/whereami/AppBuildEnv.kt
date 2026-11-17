package online.hatala.whereami

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build

/**
 * Environment helper distinguishing store distributions from local debug/ADB
 * or sideloaded installations without requiring generated BuildConfig flags.
 */
object AppBuildEnv {
    private const val STORE_INSTALLER = "com.android.vending"

    fun isLocalDevInstall(context: Context): Boolean {
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) return true
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()
        return isLocalDevInstall(installerPackageName = installer, isDebuggable = false)
    }

    internal fun isLocalDevInstall(installerPackageName: String?, isDebuggable: Boolean): Boolean {
        return isDebuggable || installerPackageName != STORE_INSTALLER
    }
}
