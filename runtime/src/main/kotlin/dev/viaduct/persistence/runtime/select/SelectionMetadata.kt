@file:OptIn(viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.runtime.select

import viaduct.api.internal.internalType
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput

/** Persistence needs the internal root type when materializing typed results. */
internal val <T : CompositeOutput> SelectionSet<T>.type: Type<T>
    get() = internalType()
