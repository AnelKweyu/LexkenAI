package com.lexken.lexkenai.core.result

/**
 * Typed result used across every layer. Repositories never throw for
 * expected failures — they return [Result.Failure] with a typed [Error].
 */
sealed interface Result<out D, out E : Error> {
    data class Success<out D>(val data: D) : Result<D, Nothing>
    data class Failure<out E : Error>(val error: E) : Result<Nothing, E>
}

/** Marker for every error type that can travel inside a [Result]. */
interface Error

/** Result with no meaningful payload (e.g. delete, sign-out). */
typealias EmptyResult<E> = Result<Unit, E>

inline fun <T, E : Error, R> Result<T, E>.map(transform: (T) -> R): Result<R, E> = when (this) {
    is Result.Success -> Result.Success(transform(data))
    is Result.Failure -> this
}

inline fun <T, E : Error, R : Error> Result<T, E>.mapError(transform: (E) -> R): Result<T, R> = when (this) {
    is Result.Success -> this
    is Result.Failure -> Result.Failure(transform(error))
}

inline fun <T, E : Error> Result<T, E>.onSuccess(action: (T) -> Unit): Result<T, E> {
    if (this is Result.Success) action(data)
    return this
}

inline fun <T, E : Error> Result<T, E>.onFailure(action: (E) -> Unit): Result<T, E> {
    if (this is Result.Failure) action(error)
    return this
}

fun <T, E : Error> Result<T, E>.asEmptyResult(): EmptyResult<E> = map { }

fun <T, E : Error> Result<T, E>.getOrNull(): T? = (this as? Result.Success)?.data

fun <T, E : Error> Result<T, E>.errorOrNull(): E? = (this as? Result.Failure)?.error

val <T, E : Error> Result<T, E>.isSuccess: Boolean get() = this is Result.Success
