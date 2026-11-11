package com.printscript.fakes

import com.printscript.domain.errors.StorageFileNotFoundException
import com.printscript.domain.errors.StorageUnavailableException
import com.printscript.repository.FileRepository

/** Asset Service en memoria. Con [unavailable] en true simula el servicio caido. */
class InMemoryFileRepository(vararg entries: Pair<String, String>) : FileRepository {
    val files = mutableMapOf(*entries)
    val writes = mutableListOf<String>()
    @Volatile var unavailable = false

    override fun get(key: String): String {
        if (unavailable) throw StorageUnavailableException("Asset Service caido", null)
        return files[key] ?: throw StorageFileNotFoundException(key)
    }

    override fun put(key: String, content: String) {
        if (unavailable) throw StorageUnavailableException("Asset Service caido", null)
        writes.add(key)
        files[key] = content
    }
}
