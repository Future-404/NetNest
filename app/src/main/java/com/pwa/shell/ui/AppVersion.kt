package com.pwa.shell.ui

import android.content.Context

internal fun getAppVersionName(context: Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "1.0.0"
