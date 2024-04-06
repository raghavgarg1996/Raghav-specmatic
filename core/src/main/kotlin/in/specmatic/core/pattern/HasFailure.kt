package `in`.specmatic.core.pattern

import `in`.specmatic.core.Result

class HasFailure<T>(val failure: Result.Failure, val message: String = "") : ReturnValue<T>, ReturnFailure {
    override fun <U> withDefault(default: U, fn: (T) -> U): U {
        return default
    }

    override fun <U> ifValue(fn: (T) -> U): ReturnValue<U> {
        return HasFailure<U>(failure)
    }

    override fun <U> sequenceOf(fn: (T) -> Sequence<ReturnValue<U>>): Sequence<ReturnValue<U>> {
        return sequenceOf(cast())
    }

    override fun update(fn: (T) -> T): ReturnValue<T> {
        return this
    }

    override fun <U> combineWith(valueResult: ReturnValue<U>, fn: (T, U) -> T): ReturnValue<T> {
        return cast<T>()
    }

    override fun <U> cast(): ReturnValue<U> {
        return HasFailure<U>(failure, message)
    }

    override val value: T
        get() = throw ContractException(failure.toFailureReport())

    fun toFailure(): Result.Failure {
        return Result.Failure(message, failure)
    }

    override fun addDetails(errorMessage: String, breadCrumb: String): ReturnValue<T> {
        return HasFailure<T>(Result.Failure(errorMessage, this.toFailure(), breadCrumb))
    }

    override fun <U> realise(hasValue: (T) -> U, orFailure: (HasFailure<T>) -> U, orException: (HasException<T>) -> U): U {
        return orFailure(this)
    }
}
