package com.luma.remote

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.ConfigurationCompat

/** Application contexts do not inherit AppCompat's locale override on Android 12 and earlier. */
internal fun Context.localizedResources():Resources {
    val locales=AppCompatDelegate.getApplicationLocales()
    return if(locales.isEmpty)resources else createConfigurationContext(Configuration(resources.configuration).apply {
        ConfigurationCompat.setLocales(this,locales)
    }).resources
}
