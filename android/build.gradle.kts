plugins {
    id("com.android.application") version "8.11.1" apply false
    id("com.android.library") version "8.11.1" apply false
    id("androidx.room") version "2.8.4" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" apply false
}

val sourceExtensions = setOf("kt", "kts", "xml")

tasks.register<Exec>("verifyTermuxRunner") {
    group = "verification"
    description = "Runs Linux acceptance tests for the Termux process supervisor."
    onlyIf { System.getProperty("os.name").lowercase().contains("linux") }
    commandLine("python3", "extensions/termux/src/test/runner_test.py")
}

tasks.register("verifyFormatting") {
    group = "verification"
    description = "Checks native source files for tabs and trailing whitespace."

    doLast {
        val violations = fileTree(rootDir) {
            include("**/*.kt", "**/*.kts", "**/*.xml")
            exclude("**/build/**", "**/.gradle/**")
        }.files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                when {
                    line.contains('\t') ->
                        "${file.relativeTo(rootDir)}:${index + 1}: tab character"
                    line.trimEnd() != line ->
                        "${file.relativeTo(rootDir)}:${index + 1}: trailing whitespace"
                    else -> null
                }
            }
        }
        check(violations.isEmpty()) {
            violations.joinToString(
                prefix = "Formatting violations:\n",
                separator = "\n",
            )
        }
        val shellFilesWithCarriageReturns = fileTree(rootDir) {
            include("**/src/main/assets/**/*.sh")
            exclude("**/build/**", "**/.gradle/**")
        }.files.filter { file -> file.readBytes().any { it == 13.toByte() } }
        check(shellFilesWithCarriageReturns.isEmpty()) {
            "Bundled shell scripts must use LF line endings: " +
                shellFilesWithCarriageReturns.joinToString { it.relativeTo(rootDir).toString() }
        }
    }
}

tasks.register("verifyArchitecture") {
    group = "verification"
    description = "Checks native dependency direction and UI boundary rules."

    doLast {
        val violations = mutableListOf<String>()
        val sourceRoot = file("app/src/main/java")

        fileTree(sourceRoot) {
            include("**/core/**/*.kt")
        }.files.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (line.startsWith("import ") && ".feature." in line) {
                    violations +=
                        "${file.relativeTo(rootDir)}:${index + 1}: core imports feature"
                }
            }
        }

        fileTree(sourceRoot) {
            include("**/*.kt")
        }.files.forEach { file ->
            val content = file.readText()
            if ("@Composable" in content) {
                content.lineSequence().forEachIndexed { index, line ->
                    if (
                        line.startsWith("import ") &&
                        (
                            "androidx.room" in line ||
                                ".database." in line ||
                                ".network." in line
                        )
                    ) {
                        violations +=
                            "${file.relativeTo(rootDir)}:${index + 1}: " +
                            "Composable source imports persistence or network code"
                    }
                }
            }
        }

        check(violations.isEmpty()) {
            violations.joinToString(
                prefix = "Architecture violations:\n",
                separator = "\n",
            )
        }
    }
}

tasks.register("verifyNative") {
    group = "verification"
    description = "Runs deterministic native formatting, lint, tests, and debug assembly."
    dependsOn(
        "verifyTermuxRunner",
        "verifyArchitecture",
        "verifyFormatting",
        ":app:lintDebug",
        ":app:testDebugUnitTest",
        ":app:assembleDebug",
        ":extension-api:lintDebug",
        ":extension-api:testDebugUnitTest",
        ":extension-api:assembleDebug",
        ":extension-ui:lintDebug",
        ":extension-ui:assembleDebug",
        ":extensions:mijia:lintDebug",
        ":extensions:mijia:testDebugUnitTest",
        ":extensions:mijia:assembleDebug",
        ":extensions:termux:lintDebug",
        ":extensions:termux:testDebugUnitTest",
        ":extensions:termux:assembleDebug",
    )
}

tasks.register("verifyRelease") {
    group = "verification"
    description = "Runs native checks and assembles the release APK."
    dependsOn(
        "verifyArchitecture",
        "verifyFormatting",
        ":app:lintRelease",
        ":app:testDebugUnitTest",
        ":app:assembleRelease",
        ":extension-api:lintRelease",
        ":extension-api:testDebugUnitTest",
        ":extension-api:assembleRelease",
        ":extension-ui:lintRelease",
        ":extension-ui:assembleRelease",
        ":extensions:mijia:lintRelease",
        ":extensions:mijia:testDebugUnitTest",
        ":extensions:mijia:assembleRelease",
        ":extensions:termux:lintRelease",
        ":extensions:termux:testDebugUnitTest",
        ":extensions:termux:assembleRelease",
    )
}
