package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import kotlinx.serialization.json.JsonObject
import viaduct.api.select.SelectionSet

/** The same semantic-nullability policy is applied before single or batch hydration. */
internal class DbRowValidator(
    private val typeReflection: GeneratedTypeReflection,
) {
    fun validate(
        data: JsonObject,
        errors: List<UpstreamGraphqlError>,
        selections: SelectionSet<*>,
        responseKey: String,
    ): List<UpstreamGraphqlError> =
        SemanticNotNullValidator(SemanticNotNullCoordinates.load(selections.type.kcls.java.classLoader)).validate(
            SemanticValidationRequest(
                data = data,
                errors = errors,
                document = selections.toFragment().document,
                rootType = selections.type.name,
                rootResponseKey = responseKey,
                schema = typeReflection.translationSchema(selections.type),
            ),
        )
}
