package dev.nura.app.field.sync.core.di

import javax.inject.Qualifier

/** CPU-bound work such as password hashing and, later, payload encryption. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher
