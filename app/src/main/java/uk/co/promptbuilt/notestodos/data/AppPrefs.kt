package uk.co.promptbuilt.notestodos.data

import android.content.Context
import android.os.Build
import android.icu.util.LocaleData
import android.icu.util.ULocale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small synchronous settings the metered paths read on background threads, mirroring the
 * UserDefaults values on iOS: the preferred unit system and the daily automatic-naming budget.
 */
class AppPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    enum class Units(val key: String) { AUTOMATIC("auto"), METRIC("metric"), US("us") }

    var units: Units
        get() = Units.entries.firstOrNull { it.key == prefs.getString(KEY_UNITS, null) } ?: Units.AUTOMATIC
        set(value) {
            prefs.edit().putString(KEY_UNITS, value.key).apply()
        }

    /** What extraction converts to; Automatic follows the device region (the UK cooks in metric). */
    fun resolvedUnits(): String = when (units) {
        Units.METRIC -> "metric"
        Units.US -> "us"
        Units.AUTOMATIC -> if (usesUsCustomary()) "us" else "metric"
    }

    /** ICU's measurement system arrived in API 28; older phones fall back to the region code. */
    private fun usesUsCustomary(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            LocaleData.getMeasurementSystem(ULocale.getDefault()) == LocaleData.MeasurementSystem.US
        } else {
            Locale.getDefault().country in US_CUSTOMARY_REGIONS
        }

    /**
     * Automatic naming costs real money per call, so free accounts get 30 a day (the Worker's
     * TITLE_DAILY_LIMIT enforces the same cap). Accounts holding purchased credits are exempt.
     */
    fun autoNameAllowed(exempt: Boolean): Boolean {
        if (exempt) return true
        if (prefs.getString(KEY_NAME_DAY, null) != today()) return true
        return prefs.getInt(KEY_NAME_COUNT, 0) < AUTO_NAME_DAILY_LIMIT
    }

    fun countAutoName() {
        val day = today()
        val count = if (prefs.getString(KEY_NAME_DAY, null) == day) prefs.getInt(KEY_NAME_COUNT, 0) else 0
        prefs.edit().putString(KEY_NAME_DAY, day).putInt(KEY_NAME_COUNT, count + 1).apply()
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())

    companion object {
        const val AUTO_NAME_DAILY_LIMIT = 30
        private val US_CUSTOMARY_REGIONS = setOf("US", "LR", "MM")
        private const val KEY_UNITS = "units"
        private const val KEY_NAME_DAY = "auto_name_day"
        private const val KEY_NAME_COUNT = "auto_name_count"
    }
}
