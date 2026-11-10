package com.jarvis.android.host

import android.content.Context
import java.io.File

/** Copies bundled ARM64 executables and libraries from APK assets to app-private storage. */
object RuntimeAssetInstaller {
    private const val ASSET_ROOT = "jarvis-runtime"

    fun install(context: Context): File {
        val target = File(context.filesDir, ASSET_ROOT)
        copyTree(context, ASSET_ROOT, target)
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
