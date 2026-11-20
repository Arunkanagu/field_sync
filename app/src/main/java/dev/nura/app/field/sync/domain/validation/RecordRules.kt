package dev.nura.app.field.sync.domain.validation

import dev.nura.app.field.sync.domain.model.AttendanceStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Field rules shared by local edits and records received from another device.
 * Each `*Error` function returns a message, or null if the value is valid.
 */
object EmployeeRules {
    const val MAX_NAME = 80
    const val MAX_ADDRESS = 200
    const val MAX_WAGE_MINOR = 100_000_000L

    private val codePattern = Regex("[A-Z0-9][A-Z0-9_-]{0,19}")
    private val mobilePattern = Regex("\\+?[0-9]{7,15}")

    fun normalizeCode(raw: String) = raw.trim().uppercase(Locale.ROOT)

    fun codeError(code: String): String? =
        if (codePattern.matches(code)) null else "Employee code must be 1–20 letters, digits, '-' or '_'."

    fun nameError(name: String): String? = when {
        name.isBlank() -> "Name is required."
        name.length > MAX_NAME -> "Name must be at most $MAX_NAME characters."
        else -> null
    }

    fun mobileError(mobile: String?): String? =
        if (mobile == null || mobilePattern.matches(mobile)) null else "Mobile must be 7–15 digits."

    fun addressError(address: String?): String? =
        if (address == null || address.length <= MAX_ADDRESS) null else "Address must be at most $MAX_ADDRESS characters."

    fun wageError(wageMinor: Long?): String? =
        if (wageMinor == null || wageMinor in 0..MAX_WAGE_MINOR) null else "Daily wage is out of range."

    fun normalizeMobile(raw: String): String? = raw.filterNot { it == ' ' || it == '-' }.ifEmpty { null }
}

object AttendanceRules {
    const val MINUTES_PER_DAY = 24 * 60
    const val MAX_TEXT = 200

    fun error(
        status: AttendanceStatus,
        inTime: Int?,
        outTime: Int?,
        overtimeMinutes: Int,
        absentReason: String?,
        notes: String?,
    ): String? = when {
        inTime != null && inTime !in 0 until MINUTES_PER_DAY -> "In time is invalid."
        outTime != null && outTime !in 0 until MINUTES_PER_DAY -> "Out time is invalid."
        inTime != null && outTime != null && outTime < inTime -> "Out time must be after in time."
        overtimeMinutes !in 0..MINUTES_PER_DAY -> "Overtime must be between 0 and $MINUTES_PER_DAY minutes."
        status == AttendanceStatus.ABSENT && (inTime != null || outTime != null) -> "An absent entry cannot have in/out times."
        status != AttendanceStatus.ABSENT && absentReason != null -> "Absence reason applies only to absent entries."
        (absentReason?.length ?: 0) > MAX_TEXT || (notes?.length ?: 0) > MAX_TEXT ->
            "Text fields must be at most $MAX_TEXT characters."
        else -> null
    }
}

object JobRules {
    const val MAX_TITLE = 100
    const val MAX_DESCRIPTION = 1000
    const val MAX_NOTE = 300

    fun error(title: String, description: String, assignedDate: LocalDate, dueDate: LocalDate?): String? = when {
        title.isBlank() -> "Title is required."
        title.length > MAX_TITLE -> "Title must be at most $MAX_TITLE characters."
        description.length > MAX_DESCRIPTION -> "Description must be at most $MAX_DESCRIPTION characters."
        dueDate != null && dueDate < assignedDate -> "Due date cannot be before the assigned date."
        else -> null
    }

    fun noteError(note: String?): String? =
        if ((note?.length ?: 0) <= MAX_NOTE) null else "Note must be at most $MAX_NOTE characters."
}

object InputParsing {

    /** Parses `HH:mm` (or `H:mm`) into minutes after midnight. Blank → null. */
    fun parseTime(raw: String): Int? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val parts = value.split(":")
        val hours = parts.getOrNull(0)?.toIntOrNull()
        val minutes = parts.getOrNull(1)?.toIntOrNull()
        require(parts.size == 2 && hours != null && minutes != null && hours in 0..23 && minutes in 0..59) {
            "Enter times as HH:mm, for example 09:30."
        }
        return hours * 60 + minutes
    }

    fun formatTime(minutes: Int?): String =
        minutes?.let { String.format(Locale.ROOT, "%02d:%02d", it / 60, it % 60) }.orEmpty()

    /** Parses a non-negative amount with up to two decimals into minor units. Blank → null. */
    fun parseMoney(raw: String): Long? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val amount = value.toBigDecimalOrNull()
        require(amount != null && amount.signum() >= 0 && amount.scale() <= 2) { "Enter a valid amount, for example 650 or 650.50." }
        return amount.movePointRight(2).longValueExact()
    }

    fun formatMoney(minor: Long?): String =
        minor?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty()

    fun parseDate(raw: String, field: String): LocalDate? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        return try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException("$field must be a date like 2026-01-31.")
        }
    }

    fun parseNonNegativeInt(raw: String, field: String): Int {
        val value = raw.trim()
        if (value.isEmpty()) return 0
        val parsed = value.toIntOrNull()
        require(parsed != null && parsed >= 0) { "$field must be a whole number." }
        return parsed
    }

    fun optionalText(raw: String): String? = raw.trim().ifEmpty { null }
}
