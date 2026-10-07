package org.johnfegan.plextouch.ui

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * User-facing text that is resolved against resources only where it is shown, so the use cases, the data layer and their
 * JVM tests never need an Android `Context`. An argument may itself be a [UiText] (a nested label such as "Loading music"
 * inside "%1$s failed: %2$s"); it is resolved first. Server-provided text (album titles, speaker names) goes in as a plain
 * argument or as [Raw].
 */
@Immutable
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText
    /** Text that is not ours to translate: a server's name, an album title, or a system exception's own message. */
    data class Raw(val value: String) : UiText
}

/** `uiText(R.string.x, a, b)`: the common case, a string resource with positional arguments. */
fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

/** `uiPlural(R.plurals.x, count)`; the count is the first format argument unless others are given. */
fun uiPlural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
    UiText.Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())

fun UiText.asString(context: Context): String = when (this) {
    is UiText.Raw -> value
    is UiText.Res -> context.getString(id, *args.map { if (it is UiText) it.asString(context) else it }.toTypedArray())
    is UiText.Plural -> context.resources.getQuantityString(id, count, *args.map { if (it is UiText) it.asString(context) else it }.toTypedArray())
}

@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Raw -> value
    is UiText.Res -> stringResource(id, *args.map { if (it is UiText) it.asString() else it }.toTypedArray())
    is UiText.Plural -> pluralStringResource(id, count, *args.map { if (it is UiText) it.asString() else it }.toTypedArray())
}

/**
 * An exception whose message is meant for the user. Data and use-case code throws these (through [requireText] and
 * [checkText]) instead of English messages; the use-case boundary and the ViewModel read [text] with [userText].
 */
interface UserFacing { val text: UiText }

/** A refused input or precondition; still an [IllegalArgumentException] so existing `require`-style handling is unchanged. */
class InvalidInputException(override val text: UiText) : IllegalArgumentException(text.toString()), UserFacing

/** A state the user must fix or retry; still an [IllegalStateException] like the `check` it replaces. */
class InvalidStateException(override val text: UiText) : IllegalStateException(text.toString()), UserFacing

@OptIn(ExperimentalContracts::class)
inline fun requireText(value: Boolean, text: () -> UiText) {
    contract { returns() implies value }
    if (!value) throw InvalidInputException(text())
}

@OptIn(ExperimentalContracts::class)
inline fun checkText(value: Boolean, text: () -> UiText) {
    contract { returns() implies value }
    if (!value) throw InvalidStateException(text())
}

/**
 * What to show for a failure: our own text when the exception carries it, otherwise the platform's message (for example a
 * socket error, which we cannot translate), otherwise [fallback].
 */
fun Throwable.userText(fallback: UiText): UiText =
    (this as? UserFacing)?.text ?: message?.takeIf { it.isNotBlank() }?.let(UiText::Raw) ?: fallback
