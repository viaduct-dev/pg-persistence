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
import viaduct.api.types.Union
import kotlin.reflect.KClass

interface AbstractSubject : Union {
    object Reflection : Type<AbstractSubject> by AbstractFixtureType(AbstractSubject::class)
}

interface AbstractActor : viaduct.api.types.Interface {
    object Reflection : Type<AbstractActor> by AbstractFixtureType(AbstractActor::class)

    object Fields
}

class AbstractPerson :
    NodeObject,
    AbstractSubject,
    AbstractActor {
    object Reflection : Type<AbstractPerson> by AbstractFixtureType(AbstractPerson::class)

    object Fields
}

class AbstractGroup :
    NodeObject,
    AbstractSubject,
    AbstractActor {
    object Reflection : Type<AbstractGroup> by AbstractFixtureType(AbstractGroup::class)

    object Fields
}

class AbstractActivity : NodeObject {
    object Reflection : Type<AbstractActivity> by AbstractFixtureType(AbstractActivity::class)

    object Fields {
        val subject = AbstractFixtureField("subject", Reflection, AbstractSubject.Reflection)
        val subjects = AbstractFixtureField("subjects", Reflection, AbstractSubject.Reflection)
    }
}

interface AbstractPayload : Union {
    object Reflection : Type<AbstractPayload> by AbstractFixtureType(AbstractPayload::class)
}

class SubjectPayload(
    val subject: AbstractSubject?,
) : AbstractPayload {
    object Reflection : Type<SubjectPayload> by AbstractFixtureType(SubjectPayload::class)

    object Fields {
        val subject = AbstractFixtureField("subject", Reflection, AbstractSubject.Reflection)
    }

    class Builder(
        @Suppress("UNUSED_PARAMETER") ctx: ExecutionContext,
    ) {
        private var subject: AbstractSubject? = null

        fun subject(value: AbstractSubject?) = apply { subject = value }

        fun build() = SubjectPayload(subject)
    }
}

class ActorPayload(
    val actor: AbstractActor?,
) : AbstractPayload {
    object Reflection : Type<ActorPayload> by AbstractFixtureType(ActorPayload::class)

    object Fields {
        val actor = AbstractFixtureField("actor", Reflection, AbstractActor.Reflection)
    }

    class Builder(
        @Suppress("UNUSED_PARAMETER") ctx: ExecutionContext,
    ) {
        private var actor: AbstractActor? = null

        fun actor(value: AbstractActor?) = apply { actor = value }

        fun build() = ActorPayload(actor)
    }
}

class BatchSubjectPayload(
    subjects: List<AbstractSubject>,
) : CompositeOutput {
    val subjects: List<AbstractSubject> = java.util.List.copyOf(subjects)

    object Reflection : Type<BatchSubjectPayload> by AbstractFixtureType(BatchSubjectPayload::class)

    object Fields {
        val subjects = AbstractFixtureField("subjects", Reflection, AbstractSubject.Reflection)
    }

    class Builder(
        @Suppress("UNUSED_PARAMETER") ctx: ExecutionContext,
    ) {
        private var subjects: List<AbstractSubject> = emptyList()

        fun subjects(value: List<AbstractSubject>) = apply { subjects = java.util.List.copyOf(value) }

        fun build() = BatchSubjectPayload(subjects)
    }
}

class AmbiguousSubjectPayload(
    val subject: AbstractSubject?,
    val other: AbstractSubject?,
) : CompositeOutput {
    object Reflection : Type<AmbiguousSubjectPayload> by AbstractFixtureType(AmbiguousSubjectPayload::class)

    object Fields {
        val subject = AbstractFixtureField("subject", Reflection, AbstractSubject.Reflection)
        val other = AbstractFixtureField("other", Reflection, AbstractSubject.Reflection)
    }

    class Builder(
        @Suppress("UNUSED_PARAMETER") ctx: ExecutionContext,
    ) {
        private var subject: AbstractSubject? = null
        private var other: AbstractSubject? = null

        fun subject(value: AbstractSubject?) = apply { subject = value }

        fun other(value: AbstractSubject?) = apply { other = value }

        fun build() = AmbiguousSubjectPayload(subject, other)
    }
}

class AbstractFixtureType<T : GRT>(
    override val kcls: KClass<T>,
) : Type<T> {
    override val name: String = requireNotNull(kcls.simpleName)
}

class AbstractFixtureField<P : GRT, T : GRT>(
    override val name: String,
    override val containingType: Type<P>,
    override val type: Type<T>,
) : CompositeField<P, T>

class AbstractMutationContext(
    delegate: MutationFieldExecutionContext<Query, Mutation, Arguments, AbstractPayload>,
) : MutationFieldExecutionContext<Query, Mutation, Arguments, AbstractPayload> by delegate

class AmbiguousMutationContext(
    delegate: MutationFieldExecutionContext<Query, Mutation, Arguments, AmbiguousSubjectPayload>,
) : MutationFieldExecutionContext<Query, Mutation, Arguments, AmbiguousSubjectPayload> by delegate
