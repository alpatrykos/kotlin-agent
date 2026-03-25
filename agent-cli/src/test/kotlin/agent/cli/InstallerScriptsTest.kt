package agent.cli

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallerScriptsTest {
    @Test
    fun `install and uninstall scripts manage launcher links`() {
        val repoRoot = findRepoRoot()
        val tempRoot = Files.createTempDirectory("agent-install-test")
        val fakeDist = tempRoot.resolve("dist")
        val fakeLauncher = fakeDist.resolve("bin").resolve("agent")
        Files.createDirectories(fakeLauncher.parent)
        Files.writeString(
            fakeLauncher,
            "#!/usr/bin/env bash\necho agent-test\n",
            StandardCharsets.UTF_8,
        )
        Files.setPosixFilePermissions(fakeLauncher, PosixFilePermissions.fromString("rwxr-xr-x"))

        val binDir = tempRoot.resolve("bin")
        val installDir = tempRoot.resolve("install")

        val installResult = runScript(
            repoRoot.resolve("scripts").resolve("install-cli.sh"),
            listOf("--bin-dir", binDir.toString(), "--install-dir", installDir.toString()),
            repoRoot,
            mapOf(
                "AGENT_SKIP_BUILD" to "1",
                "AGENT_DIST_DIR" to fakeDist.toString(),
                "AGENT_VERSION" to "test-version",
                "PATH" to "/usr/bin:/bin",
            ),
        )

        assertEquals(0, installResult.exitCode)
        assertTrue(Files.isSymbolicLink(binDir.resolve("agent")))
        assertTrue(Files.isSymbolicLink(installDir.resolve("current")))
        assertTrue(Files.exists(installDir.resolve("test-version").resolve("bin").resolve("agent")))
        assertContains(installResult.output, "export PATH=\"${binDir}:")

        val secondInstall = runScript(
            repoRoot.resolve("scripts").resolve("install-cli.sh"),
            listOf("--bin-dir", binDir.toString(), "--install-dir", installDir.toString()),
            repoRoot,
            mapOf(
                "AGENT_SKIP_BUILD" to "1",
                "AGENT_DIST_DIR" to fakeDist.toString(),
                "AGENT_VERSION" to "test-version",
                "PATH" to "/usr/bin:/bin",
            ),
        )
        assertEquals(1, secondInstall.exitCode)
        assertContains(secondInstall.output, "Re-run with --force")

        val uninstallResult = runScript(
            repoRoot.resolve("scripts").resolve("uninstall-cli.sh"),
            listOf("--bin-dir", binDir.toString(), "--install-dir", installDir.toString()),
            repoRoot,
            mapOf("PATH" to "/usr/bin:/bin"),
        )

        assertEquals(0, uninstallResult.exitCode)
        assertFalse(Files.exists(binDir.resolve("agent")))
        assertFalse(Files.exists(installDir))
    }

    @Test
    fun `install and uninstall scripts default to local bin under home`() {
        val repoRoot = findRepoRoot()
        val tempRoot = Files.createTempDirectory("agent-install-defaults-test")
        val fakeDist = tempRoot.resolve("dist")
        val fakeLauncher = fakeDist.resolve("bin").resolve("agent")
        Files.createDirectories(fakeLauncher.parent)
        Files.writeString(
            fakeLauncher,
            "#!/usr/bin/env bash\necho agent-test\n",
            StandardCharsets.UTF_8,
        )
        Files.setPosixFilePermissions(fakeLauncher, PosixFilePermissions.fromString("rwxr-xr-x"))

        val homeDir = tempRoot.resolve("home")
        Files.createDirectories(homeDir)
        val expectedBinDir = homeDir.resolve(".local").resolve("bin")
        val expectedInstallDir = homeDir.resolve(".local").resolve("share").resolve("agent")

        val installResult = runScript(
            repoRoot.resolve("scripts").resolve("install-cli.sh"),
            emptyList(),
            repoRoot,
            mapOf(
                "HOME" to homeDir.toString(),
                "AGENT_SKIP_BUILD" to "1",
                "AGENT_DIST_DIR" to fakeDist.toString(),
                "AGENT_VERSION" to "test-version",
                "PATH" to "/usr/bin:/bin",
            ),
        )

        assertEquals(0, installResult.exitCode)
        assertTrue(Files.isSymbolicLink(expectedBinDir.resolve("agent")))
        assertTrue(Files.isSymbolicLink(expectedInstallDir.resolve("current")))
        assertTrue(Files.exists(expectedInstallDir.resolve("test-version").resolve("bin").resolve("agent")))
        assertContains(installResult.output, "launcher: ${expectedBinDir.resolve("agent")}")
        assertContains(installResult.output, "export PATH=\"${expectedBinDir}:")

        val uninstallResult = runScript(
            repoRoot.resolve("scripts").resolve("uninstall-cli.sh"),
            emptyList(),
            repoRoot,
            mapOf(
                "HOME" to homeDir.toString(),
                "PATH" to "/usr/bin:/bin",
            ),
        )

        assertEquals(0, uninstallResult.exitCode)
        assertFalse(Files.exists(expectedBinDir.resolve("agent")))
        assertFalse(Files.exists(expectedInstallDir))
    }

    private fun runScript(
        script: Path,
        args: List<String>,
        workingDirectory: Path,
        environment: Map<String, String>,
    ): ProcessResult {
        val process = ProcessBuilder(listOf("/bin/zsh", script.toString()) + args)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .apply {
                this.environment().putAll(environment)
            }
            .start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        check(finished) { "Script timed out: $script" }
        val output = process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
        return ProcessResult(process.exitValue(), output)
    }

    private fun findRepoRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (current.parent != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent
        }
        return current
    }
}

private data class ProcessResult(
    val exitCode: Int,
    val output: String,
)
