package com.lexken.lexkenai.core.model

/**
 * A file the user picked, as the domain sees it: no `Uri`, no `ContentResolver`. The UI layer
 * reads the bytes before calling a use case, so rules and repositories stay testable on the JVM.
 */
class FileInput(
    val name: String,
    val mimeType: String?,
    val bytes: ByteArray,
) {
    val sizeBytes: Long get() = bytes.size.toLong()

    override fun toString(): String = "FileInput($name, $mimeType, $sizeBytes bytes)"
}
