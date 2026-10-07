package com.example

import com.example.common.SampleContext
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.engine.api.spi.CheckerExecutorFactory

/** Runtime authorization complements schema visibility, including node lookups. */
class AdminCheckerExecutorFactory : CheckerExecutorFactory {
    override fun checkerExecutorForField(
        schema: EngineSchema,
        typeName: String,
        fieldName: String,
    ): CheckerExecutor? = schema.schema.getObjectType(typeName)?.getFieldDefinition(fieldName)
        ?.takeIf { it.hasAppliedDirective("requiresAdmin") }?.let { AdminChecker }

    override fun checkerExecutorForType(schema: EngineSchema, typeName: String): CheckerExecutor? =
        schema.schema.getObjectType(typeName)
            ?.takeIf { it.hasAppliedDirective("requiresAdmin") }?.let { AdminChecker }
}

private object AdminChecker : CheckerExecutor {
    override suspend fun execute(
        arguments: Map<String, Any?>,
        objectDataMap: Map<String, EngineObjectData.Sync>,
        context: EngineExecutionContext,
        checkerType: CheckerExecutor.CheckerType,
    ): CheckerResult =
        if ((context.requestContext as? SampleContext)?.principal?.admin == true) CheckerResult.Success
        else AdminAccessDenied
}

private object AdminAccessDenied : CheckerResult.Error {
    override val error: Exception get() = IllegalAccessException("Administrator access required")
    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true
    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}
