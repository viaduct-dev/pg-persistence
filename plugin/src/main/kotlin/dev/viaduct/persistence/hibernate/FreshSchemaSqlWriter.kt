package dev.viaduct.persistence.hibernate

import org.hibernate.boot.Metadata
import org.hibernate.tool.schema.spi.DelayedDropRegistryNotAvailableImpl
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator
import java.io.File
import java.nio.file.Files

/** Creates table DDL from the same metadata used by snapshots and migration diffs. */
internal object FreshSchemaSqlWriter {
    fun write(
        metadata: Metadata,
        destination: File,
    ) {
        Files.createDirectories(destination.parentFile.toPath())
        check(!destination.exists() || destination.delete()) { "Could not replace fresh schema SQL" }
        SchemaManagementToolCoordinator.process(
            metadata,
            metadata.database.serviceRegistry,
            mapOf(
                "jakarta.persistence.schema-generation.scripts.action" to "create",
                "jakarta.persistence.schema-generation.scripts.create-target" to destination.absolutePath,
                "hibernate.hbm2ddl.delimiter" to ";",
                "hibernate.hbm2ddl.halt_on_error" to true,
            ),
            DelayedDropRegistryNotAvailableImpl.INSTANCE,
        )
    }
}
