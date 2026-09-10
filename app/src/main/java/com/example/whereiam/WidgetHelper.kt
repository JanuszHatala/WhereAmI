package com.example.whereiam

import android.content.Context
import java.text.DateFormat
import java.util.Date

object WidgetHelper {
    private const val PREFS_NAME = "where_i_am_prefs"
    private const val KEY_LANGUAGE = "display_language"
    private const val KEY_TIME = "time"
    private const val KEY_LAT = "lat"
    private const val KEY_LNG = "lng"
    private const val KEY_SPEED = "speed"

    fun getSavedLanguage(context: Context): DisplayLanguage {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val langStr = prefs.getString(KEY_LANGUAGE, DisplayLanguage.EN.name)
        return try {
            DisplayLanguage.valueOf(langStr ?: DisplayLanguage.EN.name)
        } catch (_: Exception) {
            DisplayLanguage.EN
        }
    }

    fun saveLanguage(context: Context, language: DisplayLanguage) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, language.name).commit()
        refreshWidgetUI(context)
    }

    fun saveLastCoordinates(context: Context, lat: Double, lng: Double) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat(KEY_LAT, lat.toFloat())
            .putFloat(KEY_LNG, lng.toFloat())
            .commit()
    }

    fun getLastCoordinates(context: Context): Pair<Double, Double>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LAT) || !prefs.contains(KEY_LNG)) return null
        return Pair(prefs.getFloat(KEY_LAT, 0f).toDouble(), prefs.getFloat(KEY_LNG, 0f).toDouble())
    }

    fun saveLastSpeed(context: Context, speed: Float?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (speed != null) {
            prefs.edit().putFloat(KEY_SPEED, speed).commit()
        } else {
            prefs.edit().remove(KEY_SPEED).commit()
        }
    }

    fun getLastSpeed(context: Context): Float? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SPEED)) return null
        return prefs.getFloat(KEY_SPEED, 0f)
    }

    fun saveMultiLanguageWidgetData(context: Context, data: MultiLanguagePlaceInfo) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentTime = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())

        prefs.edit()
            .putString("city_en", data.en.city)
            .putString("hierarchy_en", LocationManager.formatHierarchy(data.en))
            .putString("state_en", data.en.voivodeship)
            .putString("country_en", data.en.country)

            .putString("city_pl", data.pl.city)
            .putString("hierarchy_pl", LocationManager.formatHierarchy(data.pl))
            .putString("state_pl", data.pl.voivodeship)
            .putString("country_pl", data.pl.country)

            .putString("city_native", data.native.city)
            .putString("hierarchy_native", LocationManager.formatHierarchy(data.native))
            .putString("state_native", data.native.voivodeship)
            .putString("country_native", data.native.country)

            .putString(KEY_TIME, currentTime)
            .commit()

        refreshWidgetUI(context)
    }

    fun refreshWidgetUI(context: Context) {
        WhereIAmWidget.updateAllWidgets(context)
    }
}
