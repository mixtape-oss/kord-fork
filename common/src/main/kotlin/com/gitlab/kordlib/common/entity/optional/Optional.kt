package com.gitlab.kordlib.common.entity.optional

import com.gitlab.kordlib.common.entity.optional.Optional.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Represents a value that encapsulates all [three possible states of a value in the Discord API](https://discord.com/developers/docs/reference#nullable-and-optional-resource-fields).
 * Specifically:
 *
 * * [Missing] - a field that was not present in the serialized entity
 * * [Null] - a field that was assigned null in the serialized entity
 * * [Value] - a field that was assigned a non-null value in the serialized entity.
 *
 * The base class is  (de)serializable with kotlinx.serialization and should be used as follows:
 *
 * * `Optional<T>` - a field that is only optional but not nullable.
 * * `Optional<T?>` - A field that is both optional and nullable.
 * * A field that is only nullable should be represented as `T?` instead.
 *
 * Trying to deserialize `null` as `Optional<T>` will result in a [SerializationException] being thrown.
 *
 * Note that kotlinx.serialization does **not** call serializers for values that are not
 * present in the serialized format. `Optional` fields should have a default value of `Optional.Missing`:
 *
 * ```kotlin
 * @Serializable
 * class DiscordUser(
 *     val id: Long,
 *     val username: String,
 *     val bot: Optional<Boolean?> = Optional.Missing()
 * )
 * ```
 */
@Serializable(with = OptionalSerializer::class)
sealed class Optional<out T> {

    /**
     * The value this optional wraps.
     * * Both [Missing] and [Null] will always return `null`.
     * * [Value] will always return a non-null value.
     */
    abstract val value: T?

    /**
     * Represents a field that was not present in the serialized entity.
     */
    class Missing<out T> private constructor() : Optional<T>() {

        /**
         * The value this optional wraps, always `null`.
         */
        override val value: T?
            get() = null

        override fun toString(): String = "Optional.Missing"

        override fun equals(other: Any?): Boolean {
            return other is Missing<*>
        }

        override fun hashCode(): Int = 0

        companion object {
            private val constantNull = Missing<Nothing>()

            operator fun <T : Any> invoke(): Missing<T> = constantNull
        }
    }

    /**
     * Represents a field that was assigned null in the serialized entity.
     */
    class Null<out T> private constructor() : Optional<T?>() {

        /**
         * The value this optional wraps, always `null`.
         */
        override val value: T?
            get() = null

        override fun toString(): String = "Optional.Null"

        override fun equals(other: Any?): Boolean {
            return other is Null<*>
        }

        override fun hashCode(): Int = 0

        companion object {
            private val constantNull = Null<Nothing>()

            operator fun <T : Any> invoke(): Null<T> = constantNull
        }
    }

    /**
     * Represents a field that was assigned a non-null value in the serialized entity.
     * Equality and hashcode is implemented through its [value].
     *
     * @param value the value this optional wraps.
     */
    class Value<T : Any>(override val value: T) : Optional<T>() {
        override fun toString(): String = "Optional.Something(content=$value)"

        /**
         * Destructures this optional to its [value].
         */
        operator fun component1(): T = value

        override fun equals(other: Any?): Boolean {
            val value = other as? Value<*> ?: return false
            return value.value == this.value
        }

        override fun hashCode(): Int = value.hashCode()
    }

    companion object {

        /**
         * Returns a [Missing] optional of type [T].
         */
        operator fun <T : Any> invoke(): Missing<T> = Missing()

        /**
         * Returns a [Value] optional of type [T] with the given [value].
         */
        operator fun <T : Any> invoke(value: T): Value<T> = Value(value)

        /**
         * Returns an [Optional] that is either [value] on a non-null [value], or [Null] on `null`.
         */
        @JvmName("invokeNullable")
        operator fun <T : Any> invoke(value: T?): Optional<T?> = when (value) {
            null -> Null()
            else -> Value(value)
        }
    }

    internal class OptionalSerializer<T>(private val contentSerializer: KSerializer<T>) : KSerializer<Optional<T>> {
        override val descriptor: SerialDescriptor = contentSerializer.descriptor

        @OptIn(ExperimentalSerializationApi::class)
        override fun deserialize(decoder: Decoder): Optional<T> {
            /**
             * let's clear up any inconsistencies, an Optional cannot be <T: Any> and be represented as nullable.
             */
            if (!descriptor.isNullable && !decoder.decodeNotNullMark()) {
                throw SerializationException("descriptor for ${descriptor.serialName} was not nullable but null mark was encountered")
            }

            /**
             * This is rather ugly; I can't figure out a way to convince the compiler that <T> isn't nullable,
             * we have personally proven above that the serializer cannot return null so we'll just act as if we
             * know what we're doing.
             */
            val optional: Optional<T?> = when {
                !decoder.decodeNotNullMark() -> {
                    decoder.decodeNull()
                    Null<Nothing>()
                }
                else -> Optional(decoder.decodeSerializableValue(contentSerializer))
            }

            @Suppress("UNCHECKED_CAST")
            return optional as Optional<T>
        }

        @OptIn(ExperimentalSerializationApi::class)
        override fun serialize(encoder: Encoder, value: Optional<T>) = when (value) {
            is Missing<*> -> Unit //ignore value
            is Null<*> -> encoder.encodeNull()
            is Value -> encoder.encodeSerializableValue(contentSerializer, value.value)
        }
    }
}

