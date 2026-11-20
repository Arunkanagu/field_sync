package dev.nura.app.field.sync.core.serialization

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * Gson used for the transfer protocol and for conflict snapshots. DTOs use only primitives,
 * strings and lists, so no custom adapters are needed; dates travel as epoch days/millis.
 */
object GsonProvider {
    fun create(): Gson = GsonBuilder()
        .disableHtmlEscaping()
        .disableJdkUnsafe()
        .create()
}
