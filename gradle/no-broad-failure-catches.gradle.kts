// Source policy only: third-party dependencies and compiler-generated cleanup are outside this check.
val verifyNoBroadFailureCatches by tasks.registering {
    group = "verification"
    description = "Rejects fatal-error catches and Kotlin helpers that catch every Throwable."
    val sources = fileTree(rootDir) {
        include("**/*.kt", "**/*.kts", "**/*.java", "**/*.groovy")
        exclude("**/build/**", "**/.gradle/**", "**/.git/**", "**/node_modules/**", "**/.worktrees/**", "**/.local/**")
    }
    inputs.files(sources)
    doLast {
        val catches = Regex("""\bcatch\s*\(([^)]*)\)""")
        val fatalType = Regex("""\b(?:Throwable|Error)\b""")
        val broadHelper = Regex("""\b(?:runCatching|mapCatching|recoverCatching|catchThrowable)\b""")
        val violations = sources.files.sorted().flatMap { source ->
            val text = source.readText()
            // Remove comments and literals before inspecting tokens, retaining line boundaries.
            val tripleQuote = "\"".repeat(3)
            val trivia = Regex("""$tripleQuote[\s\S]*?$tripleQuote|"(?:\\.|[^"\\])*"|//[^\n]*|/\*[\s\S]*?\*/""")
            val code = trivia.replace(text) { match -> match.value.map { if (it == '\n') '\n' else ' ' }.joinToString("") }
            val matches = catches.findAll(code).filter { fatalType.containsMatchIn(it.groupValues[1]) } +
                broadHelper.findAll(code)
            matches.map { match ->
                val line = code.take(match.range.first).count { it == '\n' } + 1
                "${source.relativeTo(rootDir)}:$line: ${match.value.trim()}"
            }.toList()
        }
        check(violations.isEmpty()) { "Broad failure catches are forbidden:\n" + violations.joinToString("\n") }
    }
}

tasks.matching { it.name == "check" || it.name == "build" || it.name == "test" }.configureEach {
    dependsOn(verifyNoBroadFailureCatches)
}
subprojects {
    tasks.matching { it.name == "check" || it.name == "build" || it.name == "test" }.configureEach {
        dependsOn(rootProject.tasks.named("verifyNoBroadFailureCatches"))
    }
}
