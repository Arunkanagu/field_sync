package dev.nura.app.field.sync.core.util

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Today's date in the device's time zone. Work dates are local calendar days. */
fun Clock.today(): LocalDate = Instant.ofEpochMilli(millis()).atZone(ZoneId.systemDefault()).toLocalDate()

private val dateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
private val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

fun formatTimestamp(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(dateTimeFormatter)

fun LocalDate.formatForDisplay(): String = format(dateFormatter)
