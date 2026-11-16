package com.marketsboard

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scans the repo's text files for rule violations (design rules in CLAUDE.md). */
class RepoHygieneTest {
    private val skipDirs = setOf("build", ".gradle", ".git", ".kotlin", ".idea", "node_modules")
    private val textExt = setOf("kt", "kts", "md", "xml", "toml", "yml", "yaml", "properties", "bat", "txt", "gitignore")

    private fun root(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        return requireNotNull(d) { "repo root not found" }
    }

    private fun textFiles(): List<File> = root().walkTopDown()
        .onEnter { it.name !in skipDirs }
        .filter { it.isFile && (it.extension in textExt || it.name == ".gitignore") }
        .toList()

    private fun rel(f: File) = f.relativeTo(root()).invariantSeparatorsPath

    @Test fun scanFindsFiles() {
        assertTrue("scan found too few files", textFiles().size > 20)
    }

    @Test fun noEmDashesAnywhere() {
        val emDash = Char(0x2014).toString()
        val bad = textFiles().filter { it.readText().contains(emDash) }.map(::rel)
        assertTrue("em-dash found in $bad", bad.isEmpty())
    }

    /** Palette tokens live in Theme.kt only; everything else uses Dash.* (audit finding 9). */
    @Test fun noColorLiteralsOutsideTheme() {
        val pattern = Regex("Color\\(\\s*0x")
        val bad = textFiles().filter { it.extension == "kt" && it.name != "Theme.kt" && pattern.containsMatchIn(it.readText()) }.map(::rel)
        assertTrue("raw hex colour literals outside Theme.kt: $bad", bad.isEmpty())
    }

    /**
     * Green and red mean price change only (plus the one "Live" dot), so they appear in Theme.kt and Components.kt:
     * the changeColor function (two lines) and liveDotColor (one line).
     */
    @Test fun greenAndRedAreOnlyUsedForPriceChange() {
        val pattern = Regex("Dash\\.(green|red)\\b")
        val users = textFiles().filter { it.extension == "kt" && "/src/main/" in rel(it) }
            .filter { it.name != "Theme.kt" && pattern.containsMatchIn(it.readText()) }
        assertTrue("unexpected green/red users: ${users.map(::rel)}", users.map { it.name } == listOf("Components.kt"))
        val lines = users.single().readLines().filter { pattern.containsMatchIn(it) }
        assertTrue("only changeColor and liveDotColor may use green/red: $lines", lines.size == 3)
        assertTrue("liveDotColor must be the only non-change use of green", lines.count { "liveDotColor" in it } == 1)
    }

    @Test fun noModelNamesOutsideThePlaybook() {
        val pattern = Regex("Sonnet|Opus|Haiku|Fable", RegexOption.IGNORE_CASE)
        val bad = textFiles().filter { rel(it) != "docs/PLAYBOOK.md" && it.name != "RepoHygieneTest.kt" && pattern.containsMatchIn(it.readText()) }.map(::rel)
        assertTrue("model name found in $bad", bad.isEmpty())
    }

    @Test fun sharedCodeHasNoAndroidImports() {
        val pattern = Regex("^import (android\\.|androidx\\.(lifecycle|activity))", RegexOption.MULTILINE)
        val bad = textFiles().filter { "shared/src/main" in rel(it) && it.extension == "kt" && pattern.containsMatchIn(it.readText()) }.map(::rel)
        assertTrue("android imports in shared code: $bad", bad.isEmpty())
    }
}
