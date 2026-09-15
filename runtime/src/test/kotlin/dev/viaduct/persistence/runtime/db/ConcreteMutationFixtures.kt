@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db

import viaduct.api.context.ExecutionContext
import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Type
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.GRT
import viaduct.api.types.Mutation
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import kotlin.reflect.KClass

class MutationRecord : NodeObject {
    object Reflection : Type<MutationRecord> by MutationFixtureType(MutationRecord::class)

    object Fields
}

class RecordPayload(
    val record: MutationRecord?,
) : CompositeOutput {
    object Reflection : Type<RecordPayload> by MutationFixtureType(RecordPayload::class)

    object Fields {
        val record = MutationFixtureField("record", Reflection, MutationRecord.Reflection)
    }

    class Builder(
        @Suppress("UNUSED_PARAMETER") ctx: ExecutionContext,
    ) {
        private var record: MutationRecord? = null

        fun record(value: MutationRecord?) = apply { record = value }

        fun build() = RecordPayload(record)
    }
}

class RecordsPayload(
    records: List<MutationRecord>,
) : CompositeOutput {
    val records: List<MutationRecord> = java.util.List.copyOf(records)

    object Reflection : Type<RecordsPayload> by MutationFixtureType(RecordsPayload::class)

    object Fields {
        val records = MutationFixtureField("records", Reflection, MutationRecord.Reflection)
    }

    class Builder(
        @Suppress("UNUSED_PARAMETER") ctx: ExecutionContext,
    ) {
        private var records: List<MutationRecord> = emptyList()

        fun records(value: List<MutationRecord>) = apply { records = java.util.List.copyOf(value) }

        fun build() = RecordsPayload(records)
    }
}

class MissingRecordPayload : CompositeOutput {
    object Reflection : Type<MissingRecordPayload> by MutationFixtureType(MissingRecordPayload::class)

    object Fields

    class Builder(
        @Suppress("UNUSED_PARAMETER") ctx: ExecutionContext,
    ) {
        fun build() = MissingRecordPayload()
    }
}

class AmbiguousRecordPayload : CompositeOutput {
    object Reflection : Type<AmbiguousRecordPayload> by MutationFixtureType(AmbiguousRecordPayload::class)

    object Fields {
        val record = MutationFixtureField("record", Reflection, MutationRecord.Reflection)
        val other = MutationFixtureField("other", Reflection, MutationRecord.Reflection)
    }

    class Builder(
        @Suppress("UNUSED_PARAMETER") ctx: ExecutionContext,
    ) {
        fun build() = AmbiguousRecordPayload()
    }
}

class MutationFixtureType<T : GRT>(
    override val kcls: KClass<T>,
) : Type<T> {
    override val name: String = requireNotNull(kcls.simpleName)
}

class MutationFixtureField<P : GRT, T : GRT>(
    override val name: String,
    override val containingType: Type<P>,
    override val type: Type<T>,
) : CompositeField<P, T>

class RecordMutationContext(
    delegate: MutationFieldExecutionContext<Query, Mutation, Arguments, RecordPayload>,
) : MutationFieldExecutionContext<Query, Mutation, Arguments, RecordPayload> by delegate

class RecordsMutationContext(
    delegate: MutationFieldExecutionContext<Query, Mutation, Arguments, RecordsPayload>,
) : MutationFieldExecutionContext<Query, Mutation, Arguments, RecordsPayload> by delegate

class MissingRecordMutationContext(
    delegate: MutationFieldExecutionContext<Query, Mutation, Arguments, MissingRecordPayload>,
) : MutationFieldExecutionContext<Query, Mutation, Arguments, MissingRecordPayload> by delegate

class AmbiguousRecordMutationContext(
    delegate: MutationFieldExecutionContext<Query, Mutation, Arguments, AmbiguousRecordPayload>,
) : MutationFieldExecutionContext<Query, Mutation, Arguments, AmbiguousRecordPayload> by delegate
