package com.ansardeo.bnb.demo.exec

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Virtual (paper) trading was removed: no package, no class, no service, no database dependency may come back. */
class NoVirtualTradingTest {
    private fun root() = listOf("src/main", "app/src/main").map { File(it) }.first { it.isDirectory }

    @Test fun thePaperPackageAndItsClassesAreGone() {
        val files = root().walkTopDown().filter { it.isFile }.toList()
        assertTrue(files.none { it.path.replace('\\', '/').contains("/paper/") })
        val banned = listOf("PaperEngine", "PaperMonitorService", "PaperDatabase", "com.ansardeo.bnb.paper", "TradingStage", "StageStore", "TradingModeStore", "TradingControlStore", "androidx.room")
        for (f in files.filter { it.extension == "kt" || it.name == "AndroidManifest.xml" }) {
            val t = f.readText(); banned.forEach { assertFalse("${f.name} still mentions $it", t.contains(it)) }
        }
    }

    @Test fun noBackgroundServiceIsDeclared() { assertFalse(File(root(), "AndroidManifest.xml").readText().contains("<service")) }

    @Test fun roomIsNoLongerADependency() {
        val g = listOf("build.gradle", "app/build.gradle").map { File(it) }.first { it.isFile }.readText()
        assertFalse(g.contains("room")); assertFalse(g.contains("kapt"))
    }
}
