package dev.viaduct.persistence.gradle

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtUserType

/** Reads declarations before resolver-base codegen; compiling those declarations would form a cycle. */
internal object NodeResolverSourceDiscovery {
    private const val RESOLVER = "viaduct.api.resolver.Resolver"

    fun discover(sources: Map<String, String>): Map<String, Boolean> {
        val disposable = Disposer.newDisposable()
        try {
            val environment =
                KotlinCoreEnvironment.createForProduction(
                    disposable,
                    CompilerConfiguration(),
                    EnvironmentConfigFiles.JVM_CONFIG_FILES,
                )
            val factory = KtPsiFactory(environment.project)
            val files = sources.map { (path, source) -> factory.createFile(path.substringAfterLast('/'), source) }
            val classes = files.flatMap { PsiTreeUtil.collectElementsOfType(it, KtClass::class.java) }
            val byName = classes.mapNotNull { klass -> klass.fqName?.asString()?.let { it to klass } }.toMap()

            val result = mutableMapOf<String, Boolean>()
            for (klass in classes.filter(::isResolver)) {
                val name = nodeBase(klass, byName) ?: continue
                require(name !in result) { "Multiple @Resolver classes resolve node $name" }
                result[name] = batching(klass, byName)
            }
            return result
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun isResolver(klass: KtClass): Boolean =
        !klass.hasModifier(KtTokens.ABSTRACT_KEYWORD) &&
            klass.annotationEntries.any { annotation ->
                annotation.typeReference?.text?.let { RESOLVER in names(klass.containingKtFile, it) } == true
            }

    private fun nodeBase(
        klass: KtClass,
        byName: Map<String, KtClass>,
        visited: Set<KtClass> = emptySet(),
    ): String? {
        if (klass in visited) return null
        return superNames(klass).firstNotNullOfOrNull { name ->
            when {
                ".resolverbases.NodeResolvers." in name -> name.substringAfterLast('.')
                name in byName -> nodeBase(byName.getValue(name), byName, visited + klass)
                else -> null
            }
        }
    }

    private fun batching(
        klass: KtClass,
        byName: Map<String, KtClass>,
        visited: Set<KtClass> = emptySet(),
    ): Boolean {
        if (klass in visited) return false
        val overridesBatch =
            klass.declarations.any {
                it.name == "batchResolve" && it.hasModifier(KtTokens.OVERRIDE_KEYWORD)
            }
        return overridesBatch ||
            superNames(klass).mapNotNull(byName::get).any { batching(it, byName, visited + klass) }
    }

    private fun superNames(klass: KtClass): List<String> =
        klass.superTypeListEntries.flatMap { entry ->
            val type = entry.typeReference?.typeElement as? KtUserType
            type?.let { names(klass.containingKtFile, typeName(it)) }.orEmpty()
        }

    private fun typeName(type: KtUserType): String {
        val qualifier = type.qualifier?.let(::typeName)
        return listOfNotNull(qualifier, type.referencedName).joinToString(".")
    }

    private fun names(
        file: KtFile,
        name: String,
    ): List<String> {
        val first = name.substringBefore('.')
        val suffix = name.removePrefix(first)
        val imported =
            file.importDirectives
                .firstOrNull {
                    !it.isAllUnder && (it.aliasName ?: it.importedFqName?.shortName()?.asString()) == first
                }?.importedFqName
                ?.asString()
        if (imported != null) return listOf(imported + suffix)
        return listOf(name, "${file.packageFqName.asString()}.$name") +
            file.importDirectives.filter { it.isAllUnder }.map { "${it.importedFqName?.asString()}.$name" }
    }
}
