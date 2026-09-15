package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.model.PersistenceModel
import org.w3c.dom.Document

/** Renders a normalized [HbmMappingDocument] using the native Hibernate XML template. */
internal class HbmXmlWriter {
    fun document(model: PersistenceModel): Document = document(PersistenceModelToHbmMapper.map(model))

    internal fun document(mapping: HbmMappingDocument): Document =
        StringTemplateXmlRenderer.document(
            templateResource = TEMPLATE_RESOURCE,
            model = mapping,
        )

    private companion object {
        private const val TEMPLATE_RESOURCE =
            "/dev/viaduct/persistence/hibernate/viaduct-persistence.hbm.stg"
    }
}
