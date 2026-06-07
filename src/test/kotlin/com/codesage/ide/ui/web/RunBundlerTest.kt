package com.codesage.ide.ui.web

import org.junit.jupiter.api.Test

class RunBundlerTest {
    @Test
    fun writeBundle() {
        val outPath = "/tmp/codesage_bundle.js"
        val bytes = JsBundler.writeBundleForTest(outPath)
        println("Wrote $outPath: $bytes bytes")
    }
}
