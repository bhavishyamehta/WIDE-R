package com.david.adbtest.data.executor

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.PrintStream
import java.util.concurrent.TimeUnit

class AdbExecutor(private val context: Context) {

    private val adbPath = "${context.applicationInfo.nativeLibraryDir}/libadb.so"
    val outputBufferFile: File = File.createTempFile("buffer", ".txt").also { it.deleteOnExit() }

    // Shell process for running commands (like LADB)
    private var shellProcess: Process? = null

    suspend fun executeCommand(args: List<String>): String = withContext(Dispatchers.IO) {
        try {
            val command = mutableListOf(adbPath).apply { addAll(args) }
            val process = ProcessBuilder(command)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .apply {
                    environment().apply {
                        put("HOME", context.filesDir.path)
                        put("TMPDIR", context.cacheDir.path)
                    }
                }
                .start()

            process.waitFor()
            BufferedReader(process.inputStream.reader()).use { it.readText() }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /**
     * Execute command with timeout (LADB style - for wait-for-device)
     */
    suspend fun executeCommandWithTimeout(args: List<String>, timeoutSeconds: Long): String = withContext(Dispatchers.IO) {
        try {
            val command = mutableListOf(adbPath).apply { addAll(args) }
            val process = ProcessBuilder(command)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .apply {
                    environment().apply {
                        put("HOME", context.filesDir.path)
                        put("TMPDIR", context.cacheDir.path)
                    }
                }
                .start()

            // Wait with timeout (LADB uses 1 minute)
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            if (!completed) {
                process.destroyForcibly()
                return@withContext "timeout"
            }

            // Read output
            val output = BufferedReader(process.inputStream.reader()).use { it.readText() }
            output.ifEmpty { "completed" }
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    suspend fun pairDevice(port: String, pairingCode: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val command = mutableListOf(adbPath, "pair", "localhost:$port")
            val process = ProcessBuilder(command)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .apply {
                    environment().apply {
                        put("HOME", context.filesDir.path)
                        put("TMPDIR", context.cacheDir.path)
                    }
                }
                .start()

            Thread.sleep(5000)
            PrintStream(process.outputStream).apply {
                println(pairingCode)
                flush()
            }

            process.waitFor(10, TimeUnit.SECONDS)
            process.destroyForcibly().waitFor()

            val killProcess = ProcessBuilder(adbPath, "kill-server")
                .directory(context.filesDir)
                .start()
            killProcess.waitFor(3, TimeUnit.SECONDS)
            killProcess.destroyForcibly()

            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getConnectedDevices(): List<String> = withContext(Dispatchers.IO) {
        try {
            val output = executeCommand(listOf("devices"))
            output.lines()
                .filter { it.isNotBlank() && !it.contains("List of devices attached") }
                .filter { it.contains("device") && !it.contains("offline") }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Start ADB shell and grant permission automatically (LADB style)
     * This is called after first successful connection
     */
    suspend fun startShellAndGrantPermission(deviceSerial: String?): Boolean = withContext(Dispatchers.IO) {
        try {
            debug("Starting ADB shell to grant permission...")

            // Build shell command (with device selection if multiple devices)
            val argList = if (deviceSerial != null) {
                listOf(adbPath, "-s", deviceSerial, "shell")
            } else {
                listOf(adbPath, "shell")
            }

            // Start shell process with output redirect
            shellProcess = ProcessBuilder(argList)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .redirectOutput(outputBufferFile)
                .apply {
                    environment().apply {
                        put("HOME", context.filesDir.path)
                        put("TMPDIR", context.cacheDir.path)
                    }
                }
                .start()

            Thread.sleep(2000) // Wait for shell to be ready

            // Grant WRITE_SECURE_SETTINGS permission (LADB style)
            sendToShellProcess("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS &> /dev/null")
            Thread.sleep(1000)

            // Set alias for adb command
            sendToShellProcess("alias adb=\"$adbPath\"")
            Thread.sleep(500)

            // Confirmation message
            sendToShellProcess("echo 'Permission granted successfully!'")
            Thread.sleep(500)

            debug("Permission grant command executed")
            true
        } catch (e: Exception) {
            debug("Failed to grant permission: ${e.message}")
            false
        }
    }

    /**
     * Send commands to shell process (LADB style)
     */
    private fun sendToShellProcess(msg: String) {
        try {
            if (shellProcess == null || shellProcess?.outputStream == null) {
                debug("Shell process not available")
                return
            }
            PrintStream(shellProcess!!.outputStream!!).apply {
                println(msg)
                flush()
            }
        } catch (e: Exception) {
            debug("Failed to send command to shell: ${e.message}")
        }
    }

    /**
     * Close shell process
     */
    fun closeShellProcess() {
        try {
            shellProcess?.destroy()
            shellProcess = null
        } catch (e: Exception) {
            debug("Failed to close shell: ${e.message}")
        }
    }

    fun debug(msg: String) {
        synchronized(outputBufferFile) {
            Log.d("ADB_DEBUG", msg)
            if (outputBufferFile.exists())
                outputBufferFile.appendText("* $msg\n")
        }
    }
}