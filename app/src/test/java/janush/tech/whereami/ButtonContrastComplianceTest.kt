package janush.tech.whereami

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Automated regression test verifying that all interactive buttons and controls
 * strictly adhere to the High-Contrast Interactive Buttons Invariant (AGENTS.md Rule 10,
 * docs/DESIGN_SYSTEM.md Section 6).
 */
class ButtonContrastComplianceTest {

    @Test
    fun testNoButtonsUseLowContrastGrayText() {
        val srcDir = File("src/main/java")
        val altSrcDir = File("app/src/main/java")
        val targetDir = if (srcDir.exists()) srcDir else altSrcDir

        assertTrue("Source directory must exist: ${targetDir.absolutePath}", targetDir.exists())

        val violations = mutableListOf<String>()

        val buttonRegex = Regex("""\b(Button|TextButton|OutlinedButton)\s*\(""")

        targetDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val lines = file.readLines()
            for (i in lines.indices) {
                val line = lines[i]
                if (buttonRegex.containsMatchIn(line)) {
                    var openBraces = 0
                    var foundOpen = false
                    val buttonLines = mutableListOf<String>()

                    for (j in i until minOf(lines.size, i + 60)) {
                        val currentLine = lines[j]
                        buttonLines.add(currentLine)
                        openBraces += currentLine.count { it == '{' } - currentLine.count { it == '}' }
                        if (currentLine.contains('{')) {
                            foundOpen = true
                        }
                        if (foundOpen && openBraces <= 0) {
                            break
                        }
                    }

                    val block = buttonLines.joinToString("\n")
                    // Scan block for prohibited gray text colors on button labels
                    val hasGrayText = block.contains("color = Color.Gray") ||
                            block.contains("color = Color.LightGray") ||
                            block.contains("color = ComposeColor.Gray") ||
                            block.contains("color = ComposeColor.LightGray") ||
                            block.contains("0xFF94A3B8") ||
                            block.contains("0xFFCBD5E1")

                    if (hasGrayText) {
                        violations.add("${file.name}:${i + 1} -> Button block contains prohibited gray text color")
                    }
                }
            }
        }

        assertTrue(
            "Found ${violations.size} button contrast violations! Buttons must use crisp white text:\n" +
                    violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun testTripDetailDialogPauseButtonsUseWhiteText() {
        val mainActivityFile = File("src/main/java/janush/tech/whereami/MainActivity.kt").let {
            if (it.exists()) it else File("app/src/main/java/janush/tech/whereami/MainActivity.kt")
        }
        assertTrue("MainActivity.kt must exist", mainActivityFile.exists())

        val content = mainActivityFile.readText()

        // Verify Split, Merge, Remove buttons in TripDetailDialog have Color.White
        assertTrue(
            "Split button in TripDetailDialog must have color = Color.White",
            content.contains("Text(\"✂️ Split\", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)")
        )
        assertTrue(
            "Merge button in TripDetailDialog must have color = Color.White",
            content.contains("Text(\"🔗 Merge\", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)")
        )
        assertTrue(
            "Remove button in TripDetailDialog must have color = Color.White",
            content.contains("Text(\"🗑️ Remove\", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)")
        )
    }
}
