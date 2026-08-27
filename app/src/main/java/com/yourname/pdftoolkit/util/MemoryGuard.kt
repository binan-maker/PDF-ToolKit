package com.yourname.pdftoolkit.util

import java.io.IOException

object MemoryGuard {
    private const val MIN_FREE_MB = 40L

    fun checkMemory(operationName: String) {
        val runtime = Runtime.getRuntime()
        val freeMb = (runtime.maxMemory() - runtime.totalMemory()
                + runtime.freeMemory()) / (1024 * 1024)
        if (freeMb < MIN_FREE_MB) {
            // Operations return Result.failure from their exception handlers.
            // Do not throw an Error here: Errors bypass those handlers and crash
            // the activity before the user can see a recoverable message.
            throw IllegalStateException(
                "Insufficient memory for $operationName: ${freeMb}MB available"
            )
        }
    }

    fun freeMemoryMb(): Long {
        val rt = Runtime.getRuntime()
        return (rt.maxMemory() - rt.totalMemory() + rt.freeMemory()) / (1024 * 1024)
    }
}
