package online.hatala.whereami

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static CI guard enforcing AGENTS.md Rule 4 & GEMINI.md Rule 15:
 * production code must stay worldwide and data-driven without hardcoded regions,
 * city/shop workarounds, regional fallback coordinates, or silent country defaults.
 */
class GlobalUniversalityInvariantTest {

    private val exemptAndroidAutoFiles = setOf(
        "AutoMediaService.kt",
        "StreetAbbreviator.kt"
    )

    private fun resolveRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(4) {
            if (dir != null && File(dir, "settings.gradle.kts").exists()) return dir!!
            dir = dir?.parentFile
        }
        error("Could not locate repository root from ${System.getProperty("user.dir")}")
    }

    @Test
    fun noStaticRegionalAssetsOrIndexExist() {
        val root = resolveRepoRoot()
        assertFalse(
            "Static PL border region asset must stay removed",
            File(root, "app/src/main/assets/outdoor_regions_pl_border.json").exists()
        )
        assertFalse(
            "Static OfflineOutdoorRegionIndex must stay removed",
            File(root, "app/src/main/java/online/hatala/whereami/OfflineOutdoorRegionIndex.kt").exists()
        )
    }

    @Test
    fun noHardcodedRegionsShopListsCoordsOrSilentCountryDefaults() {
        val root = resolveRepoRoot()
        val prodFiles = mutableListOf<File>()
        val javaMain = File(root, "app/src/main/java")
        val resMain = File(root, "app/src/main/res")
        val serverPublic = File(root, "server/public")
        assertTrue(javaMain.isDirectory)

        javaMain.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach {
            if (it.name !in exemptAndroidAutoFiles) prodFiles.add(it)
        }
        resMain.walkTopDown().filter { it.isFile && it.extension == "xml" }.forEach {
            prodFiles.add(it)
        }
        if (serverPublic.isDirectory) {
            serverPublic.walkTopDown().filter { it.isFile && it.extension in setOf("html", "js") }.forEach {
                prodFiles.add(it)
            }
        }

        val forbiddenLiterals = listOf(
            "Beskidy",
            "beskid_maly",
            "bielsko-biała",
            "porąbka",
            "\"Likwidacja\"",
            "\"Serwis\"",
            "\"likwidacja\"",
            "\"serwis\"",
            "\"koło\"",
            "52.0693",
            "19.4803",
            "49.822",
            "19.044",
            "SIDE_STREET_NAMES",
            "isCommemorativeStreet",
            "endsWith(\"ska\"",
            "endsWith('ska')"
        )
        val silentCountryFallbackRegex = Regex("""(?:\?:|(?<![=!<>])=)\s*"(?:pl|PL)"|\bcountryCode\.isNullOrBlank\(\)\s*\|\|""")
        val magicSpiderfyFallbackRegex = Regex("""\?:\s*(49\.85|19\.28)\b""")

        val violations = mutableListOf<String>()
        for (file in prodFiles) {
            val relPath = root.toPath().relativize(file.toPath()).toString()
            val lines = file.readLines()
            lines.forEachIndexed { idx, line ->
                for (literal in forbiddenLiterals) {
                    if (line.contains(literal)) {
                        violations.add("$relPath:${idx + 1}: forbidden literal '$literal' -> ${line.trim()}")
                    }
                }
                if (silentCountryFallbackRegex.containsMatchIn(line)) {
                    violations.add("$relPath:${idx + 1}: silent PL country fallback -> ${line.trim()}")
                }
                if (magicSpiderfyFallbackRegex.containsMatchIn(line)) {
                    violations.add("$relPath:${idx + 1}: hardcoded fallback coordinate -> ${line.trim()}")
                }
            }
        }

        assertTrue(
            "Global universality violations found:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}
