package com.pyserver.v2

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Executes commands only in the user's separately installed official Termux environment. */
data class TermuxCommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val internalError: String? = null
)

object TermuxCommandBridge {
    const val TERMUX_PACKAGE = "com.termux"
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val SERVICE_CLASS = "com.termux.app.RunCommandService"
    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val KEY_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val KEY_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val KEY_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val KEY_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val KEY_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    private const val KEY_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    private const val KEY_RESULT_BUNDLE = "result"
    private const val KEY_STDOUT = "stdout"
    private const val KEY_STDERR = "stderr"
    private const val KEY_EXIT_CODE = "exitCode"
    private const val KEY_ERROR = "err"
    private const val KEY_ERROR_MESSAGE = "errmsg"
    private const val EXTRA_REQUEST_ID = "pyserver.termux.request_id"

    private val callbacks = ConcurrentHashMap<String, (TermuxCommandResult) -> Unit>()

    fun isInstalled(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                TERMUX_PACKAGE,
                android.content.pm.PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        }
        true
    } catch (_: Exception) {
        false
    }

    fun hasRunCommandPermission(context: Context): Boolean =
        context.checkSelfPermission(RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun openTermux(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    /**
     * Runs one executable and argv without concatenating them into a shell command.
     * A PendingIntent returns Termux's stdout, stderr and exit status.
     */
    fun execute(
        context: Context,
        executable: String,
        arguments: List<String>,
        workDir: String = TERMUX_HOME,
        background: Boolean = true,
        timeoutSeconds: Long = 180
    ): Result<TermuxCommandResult> {
        if (!isInstalled(context)) {
            return Result.failure(IllegalStateException("Install the official Termux app first"))
        }
        if (!hasRunCommandPermission(context)) {
            return Result.failure(SecurityException("Grant PyServer the Termux 'Run commands in Termux environment' permission"))
        }
        if (executable.isBlank() || executable.any { it == '\n' || it == '\u0000' }) {
            return Result.failure(IllegalArgumentException("Invalid Termux executable path"))
        }
        if (arguments.any { it.indexOf('\u0000') >= 0 }) {
            return Result.failure(IllegalArgumentException("Invalid command argument"))
        }

        val id = UUID.randomUUID().toString()
        val latch = CountDownLatch(1)
        val response = AtomicReference<TermuxCommandResult?>()
        callbacks[id] = { result -> response.set(result); latch.countDown() }
        val callbackIntent = Intent(context, TermuxResultReceiver::class.java).apply {
            putExtra(EXTRA_REQUEST_ID, id)
        }
        val pendingFlags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE
        val resultPendingIntent = try {
            PendingIntent.getBroadcast(context, id.hashCode(), callbackIntent, pendingFlags)
        } catch (e: Exception) {
            callbacks.remove(id)
            return Result.failure(e)
        }

        val request = Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, SERVICE_CLASS)
            putExtra(KEY_COMMAND_PATH, executable)
            putExtra(KEY_ARGUMENTS, arguments.toTypedArray())
            putExtra(KEY_WORKDIR, workDir)
            putExtra(KEY_BACKGROUND, background)
            putExtra(KEY_COMMAND_LABEL, "PyServer command")
            putExtra(KEY_PENDING_INTENT, resultPendingIntent)
        }

        try {
            context.startService(request)
        } catch (e: Exception) {
            callbacks.remove(id)
            resultPendingIntent.cancel()
            return Result.failure(e)
        }

        return try {
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                callbacks.remove(id)
                resultPendingIntent.cancel()
                Result.failure(IllegalStateException("Termux command timed out after $timeoutSeconds seconds"))
            } else {
                val value = response.get()
                    ?: return Result.failure(IllegalStateException("Termux returned no command result"))
                if (value.internalError != null) Result.failure(IllegalStateException(value.internalError))
                else Result.success(value)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            callbacks.remove(id)
            resultPendingIntent.cancel()
            Result.failure(e)
        } finally {
            callbacks.remove(id)
        }
    }

    internal fun deliver(requestId: String?, result: TermuxCommandResult) {
        if (requestId == null) return
        callbacks.remove(requestId)?.invoke(result)
    }

    fun runShell(context: Context, command: String, timeoutSeconds: Long = 180): Result<TermuxCommandResult> =
        execute(
            context = context,
            executable = "$TERMUX_PREFIX/bin/bash",
            arguments = listOf("-lc", command),
            background = true,
            timeoutSeconds = timeoutSeconds
        )
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra("pyserver.termux.request_id")
        val result: Bundle? = intent.getBundleExtra("result")
        if (result == null) {
            TermuxCommandBridge.deliver(
                requestId,
                TermuxCommandResult("", "Missing Termux result bundle", -1, "Termux did not return a result bundle")
            )
            return
        }
        val errorCode = result.getInt("err", -1)
        val errorMessage = result.getString("errmsg")
        val internalError = if (errorCode != -1) {
            errorMessage?.takeIf { it.isNotBlank() } ?: "Termux rejected the command (error $errorCode)"
        } else null
        TermuxCommandBridge.deliver(
            requestId,
            TermuxCommandResult(
                stdout = result.getString("stdout", ""),
                stderr = result.getString("stderr", ""),
                exitCode = result.getInt("exitCode", -1),
                internalError = internalError
            )
        )
    }
}
