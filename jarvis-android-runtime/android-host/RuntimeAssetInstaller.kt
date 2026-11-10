package com.jarvis.android.host

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Copies bundled ARM64 executables and libraries from APK assets to app-private storage. */
object RuntimeAssetInstaller {
    private const val ASSET_ROOT = "jarvis-runtime"

    fun install(context: Context): File {
        val target = File(context.filesDir, ASSET_ROOT)
        copyTree(context, ASSET_ROOT, target)
        verifyChecksums(target)
        val bin = File(target, "bin")
        val lib = File(target, "lib")
        require(File(bin, "dispatch").isFile && File(bin, "dmcp").isFile &&
            File(bin, "contextor").isFile && File(bin, "llama-server").isFile) {
            "JARVIS Android runtime binaries are missing from APK assets"
        }
        require(File(lib, "libllama.so").isFile && File(lib, "libggml.so").isFile) {
            "JARVIS local LLM shared libraries are missing from APK assets"
        }
        bin.listFiles()?.forEach { it.setExecutable(true, true) }
        return target
    }

    private fun verifyChecksums(root: File) {
        val checksumFile = File(root, "SHA256SUMS.txt")
        require(checksumFile.isFile) { "JARVIS runtime checksum manifest is missing" }
        checksumFile.readLines().forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            require(parts.size == 2) { "Invalid runtime checksum entry: $line" }
            val target = File(root, parts[1])
            require(target.isFile) { "Runtime asset missing: ${parts[1]}" }
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(target).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual.equals(parts[0], ignoreCase = true)) { "Runtime SHA-256 mismatch: ${parts[1]}" }
        }
    }

    private fun copyTree(context: Context, assetPath: String, destination: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isNotEmpty()) {
            destination.mkdirs()
            children.forEach { child -> copyTree(context, "$assetPath/$child", File(destination, child)) }
        } else {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}
