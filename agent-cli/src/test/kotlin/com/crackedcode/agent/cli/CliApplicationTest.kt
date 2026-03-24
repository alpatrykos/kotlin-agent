package com.crackedcode.agent.cli

import com.crackedcode.agent.core.AgentEngine
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CliApplicationTest {
    @Test
    fun `non interactive repl is rejected`() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("ccode-app-test")
        val io = FakeTerminalIO(isInteractive = false)
        var engineFactoryCalled = false
        val app = CliApplication(
            io = io,
            workspaceResolver = WorkspaceResolver(workspaceRoot),
            engineFactory = AgentEngineFactory {
                engineFactoryCalled = true
                error("engine should not be created for non-interactive repl")
            },
        )

        val exitCode = app.run(emptyList())

        assertEquals(1, exitCode)
        assertContains(io.output, "interactive terminal")
        assertEquals(false, engineFactoryCalled)
    }
}
