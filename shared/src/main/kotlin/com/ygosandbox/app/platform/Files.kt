package com.ygosandbox.app.platform

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Puts [tmp] in place of [target], replacing it. File.renameTo can't replace an existing file on
 * Windows (it just returns false), so every save after the first was silently lost there.
 */
fun replaceFile(tmp: File, target: File) {
    try {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: AtomicMoveNotSupportedException) {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
