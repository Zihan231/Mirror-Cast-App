package app.mirror.receiver

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Remembers the last server address (not secret). Pairing codes are never stored. */
@Singleton
class ServerStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("receiver", Context.MODE_PRIVATE)

    var lastServer: String?
        get() = prefs.getString(KEY, null)
        set(value) = prefs.edit().putString(KEY, value).apply()

    private companion object {
        const val KEY = "last_server"
    }
}
