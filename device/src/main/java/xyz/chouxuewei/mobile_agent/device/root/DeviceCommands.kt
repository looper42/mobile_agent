package xyz.chouxuewei.mobile_agent.device.root

import xyz.chouxuewei.mobile_agent.core.localizedText
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

internal object DeviceCommands {
    private const val COMMAND_TIMEOUT_SECONDS = 10L
    private const val TERMINATION_GRACE_MILLIS = 500L
    private const val MAX_OUTPUT_BYTES = 1_048_576
    private val permits = Semaphore(2, true)
    private val readers = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "device-command-output").apply { isDaemon = true }
    }
    private val commands = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "device-command-worker").apply { isDaemon = true }
    }
    private val timeouts = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "device-command-timeout").apply { isDaemon = true }
    }

    /** ProcessBuilder 使用独立参数，不把包名、文字或坐标拼成 shell 脚本。 */
    fun run(vararg arguments: String): String {
        check(permits.tryAcquire(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            localizedText("设备命令队列已满", "The device command queue is full.")
        }
        var process: Process? = null
        try {
            val running = ProcessBuilder(*arguments).redirectErrorStream(false).start()
            process = running
            val stdout = readers.submit<String> { running.inputStream.readTextBounded() }
            val stderr = readers.submit<String> { running.errorStream.readTextBounded() }
            if (!running.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                terminate(running)
                error(localizedText("设备命令超时：${arguments.first()}", "Device command timed out: ${arguments.first()}"))
            }
            val output = stdout.get(2, TimeUnit.SECONDS)
            val errorOutput = stderr.get(2, TimeUnit.SECONDS)
            val combined = listOf(output, errorOutput).filter(String::isNotBlank).joinToString("\n")
            check(running.exitValue() == 0 && !combined.contains("Error:") && !combined.contains("Exception")) {
                combined.take(1000).ifBlank { localizedText("设备命令失败：${running.exitValue()}", "Device command failed: ${running.exitValue()}") }
            }
            return combined
        } finally {
            process?.let(::terminate)
            permits.release()
        }
    }

    /** Streams binary screencap output without creating an unbounded byte array in the Root process. */
    fun capturePng(output: OutputStream) {
        commands.execute {
            output.use { destination ->
                if (!permits.tryAcquire(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return@use
                var process: Process? = null
                try {
                    val running = ProcessBuilder("/system/bin/screencap", "-p")
                        .redirectErrorStream(false)
                        .start()
                    process = running
                    val stderr = readers.submit<String> { running.errorStream.readTextBounded() }
                    val timeout = timeouts.schedule({
                        terminate(running)
                        runCatching { destination.close() }
                    }, COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    try {
                        running.inputStream.use { source ->
                            val buffer = ByteArray(16 * 1024)
                            var total = 0L
                            while (true) {
                                val count = source.read(buffer)
                                if (count < 0) break
                                total += count
                                check(total <= MAX_SCREENSHOT_STREAM_BYTES) {
                                    localizedText("主屏截图过大", "The main-screen screenshot is too large.")
                                }
                                destination.write(buffer, 0, count)
                            }
                        }
                        check(running.waitFor(2, TimeUnit.SECONDS) && running.exitValue() == 0) {
                            stderr.get(1, TimeUnit.SECONDS).take(1_000).ifBlank {
                                localizedText("主屏截图失败", "Main-screen screenshot failed.")
                            }
                        }
                    } finally {
                        timeout.cancel(false)
                    }
                } catch (_: Exception) {
                    // Closing the pipe communicates capture failure to the app-side decoder.
                } finally {
                    process?.let(::terminate)
                    permits.release()
                }
            }
        }
    }

    private fun terminate(process: Process) {
        if (!process.isAlive) return
        process.destroy()
        if (!process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    /** Continues draining after the capture limit so a noisy child cannot block on a full pipe. */
    private fun InputStream.readTextBounded(): String = use { input ->
        val output = java.io.ByteArrayOutputStream(minOf(MAX_OUTPUT_BYTES, 8_192))
        val buffer = ByteArray(8_192)
        var captured = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            val writable = minOf(count, MAX_OUTPUT_BYTES - captured).coerceAtLeast(0)
            if (writable > 0) {
                output.write(buffer, 0, writable)
                captured += writable
            }
        }
        output.toString(Charsets.UTF_8.name())
    }

    private const val MAX_SCREENSHOT_STREAM_BYTES = 32L * 1024 * 1024

    // 仅解析任务的显示归属，不返回其他应用的页面内容给界面。
    fun packageDisplays(dump: String, packageName: String): Set<Int> {
        check(Regex("(?m)^\\s*Display #0").containsMatchIn(dump)) { localizedText("无法识别任务显示归属，停止启动应用", "Could not determine the task display; app launch stopped.") }
        var display: Int? = null
        val result = mutableSetOf<Int>()
        val activity = Regex("\\b${Regex.escape(packageName)}/")
        for (line in dump.lineSequence()) {
            // Display 列表之后会再次汇总 Resumed Activity；该摘要没有 displayId，不能沿用上一个虚拟屏编号。
            if (line.trimStart().startsWith("Resumed activities in task display areas")) break
            Regex("^\\s*Display #(\\d+)").find(line)?.let { display = it.groupValues[1].toInt() }
            if (line.contains("ActivityRecord{") && activity.containsMatchIn(line)) {
                display?.let(result::add)
            }
        }
        return result
    }

    /** WindowManager 的逐显示段用于确认刚启动的可见窗口最终落在哪块屏。 */
    fun packageWindowDisplays(dump: String, packageName: String): Set<Int> {
        // RootService 在 HyperOS 上只会看到自己拥有的虚拟屏，因此这里只要求存在可识别的显示段。
        check(Regex("(?m)^\\s*Display: mDisplayId=\\d+\\b").containsMatchIn(dump)) {
            localizedText("无法识别窗口显示归属，停止启动应用", "Could not determine the window display; app launch stopped.")
        }
        var display: Int? = null
        val result = mutableSetOf<Int>()
        for (line in dump.lineSequence()) {
            Regex("^\\s*Display: mDisplayId=(\\d+)\\b").find(line)?.let {
                display = it.groupValues[1].toInt()
            }
            if (line.contains(packageName)) display?.let(result::add)
        }
        return result
    }

    fun browserDisplays(dump: String): Set<Int> = packageDisplays(dump, "com.android.browser")
}
