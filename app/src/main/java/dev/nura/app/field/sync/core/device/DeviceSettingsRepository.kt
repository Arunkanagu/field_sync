package dev.nura.app.field.sync.core.device

import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.nura.app.field.sync.domain.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device identity and settings. The device ID is a random UUID generated once per install;
 * it identifies this device in sync metadata but is not proof of identity on its own.
 */
@Singleton
class DeviceSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    private val preferences: Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    val deviceId: Flow<String?> = preferences.map { it[KEY_DEVICE_ID] }

    val deviceName: Flow<String> = preferences.map { it[KEY_DEVICE_NAME] ?: defaultDeviceName() }

    /** The role this device was set up for on first run (Admin device or Manager device). */
    val deviceRole: Flow<Role?> = preferences.map { prefs ->
        prefs[KEY_DEVICE_ROLE]?.let { name -> Role.entries.firstOrNull { it.name == name } }
    }

    suspend fun getOrCreateDeviceId(): String {
        val prefs = dataStore.edit {
            if (it[KEY_DEVICE_ID] == null) it[KEY_DEVICE_ID] = UUID.randomUUID().toString()
        }
        return checkNotNull(prefs[KEY_DEVICE_ID])
    }

    suspend fun setDeviceName(name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Device name cannot be empty." }
        require(trimmed.length <= MAX_DEVICE_NAME_LENGTH) {
            "Device name must be at most $MAX_DEVICE_NAME_LENGTH characters."
        }
        dataStore.edit { it[KEY_DEVICE_NAME] = trimmed }
    }

    suspend fun setDeviceRole(role: Role) {
        dataStore.edit { it[KEY_DEVICE_ROLE] = role.name }
    }

    private fun defaultDeviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    companion object {
        const val MAX_DEVICE_NAME_LENGTH = 40
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
        private val KEY_DEVICE_ROLE = stringPreferencesKey("device_role")
    }
}
