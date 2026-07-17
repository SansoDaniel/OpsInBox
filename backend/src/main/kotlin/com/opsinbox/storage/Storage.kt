package com.opsinbox.storage

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Astrazione dello storage allegati. Per l'MVP salviamo su disco locale;
 * l'implementazione S3/MinIO potrà sostituire questa senza toccare la pipeline.
 */
interface StorageService {
    fun put(bytes: ByteArray, filename: String): String
    fun get(key: String): ByteArray
}

class LocalDiskStorage(rootDir: String) : StorageService {
    private val root: Path = Path.of(rootDir).toAbsolutePath()

    init {
        Files.createDirectories(root)
    }

    override fun put(bytes: ByteArray, filename: String): String {
        val safeName = filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val key = "${UUID.randomUUID()}_$safeName"
        Files.write(root.resolve(key), bytes)
        return key
    }

    override fun get(key: String): ByteArray = Files.readAllBytes(root.resolve(key))
}
