package com.finnl.gymaccess.protocol

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

object WatchUpdate {
    const val OFFER = "/gymaccess/v1/update/apk"
    const val STATUS = "/gymaccess/v1/update/status"
    const val CAPABILITY = "gym_access_updater_v1"
    const val MAX_BYTES = 64L * 1024 * 1024
    data class Apk(val version: Long, val name: String)
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun validateTransfer(context: Context, file: File, bytes: Long, sha: String, version: Long): Apk {
        require(file.length() == bytes && sha256(file) == sha) { "Update transfer was incomplete" }
        val info = inspect(context, file)
        require(info.version == version) { "Update version does not match" }
        return info
    }
    @Suppress("DEPRECATION")
    fun inspect(context: Context, file: File, newer: Boolean = true): Apk {
        require(file.length() in 1..MAX_BYTES) { "Invalid update size" }
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_CONFIGURATIONS
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Invalid update APK")
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        require(candidate.packageName == context.packageName) { "Update is for another app" }
        require(candidate.reqFeatures?.any { it.name == "android.hardware.type.watch" } == true) { "Update is not a watch app" }
        fun signatures(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners
            ?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }?.toSet()
        val expected = signatures(installed)
        require(!expected.isNullOrEmpty() && signatures(candidate) == expected) { "Update signature does not match" }
        require(!newer || candidate.longVersionCode > installed.longVersionCode) { "Watch is already up to date" }
        return Apk(candidate.longVersionCode, candidate.versionName ?: candidate.longVersionCode.toString())
    }
}
