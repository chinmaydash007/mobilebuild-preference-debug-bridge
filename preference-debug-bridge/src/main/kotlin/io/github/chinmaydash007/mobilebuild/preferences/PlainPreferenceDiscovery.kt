package io.github.chinmaydash007.mobilebuild.preferences

import android.content.Context
import java.io.File
import java.io.IOException

internal object PlainPreferenceDiscovery {
    const val ENCRYPTED_MESSAGE =
        "Register this encrypted store with its app-specific configuration before inspecting it"

    private const val ANDROIDX_KEYSET_PREFIX = "__androidx_security_crypto_encrypted_prefs_"

    fun normalizedExclusions(fileName: String): Set<String> {
        require(fileName.isNotBlank()) { "Excluded preference filename must not be blank" }
        require('/' !in fileName && '\\' !in fileName) {
            "Excluded preference filename must not contain path separators"
        }
        return if (fileName.endsWith(".xml")) {
            setOf(fileName, fileName.removeSuffix(".xml"))
        } else {
            setOf(fileName)
        }
    }

    fun stores(context: Context, excludedFileNames: Set<String>): List<RegisteredPreferenceStore> {
        val dataDirectory = context.applicationInfo.dataDir ?: return emptyList()
        val root = File(dataDirectory, "shared_prefs")
        return fileNames(root, excludedFileNames).map { fileName ->
            if (fileName.contains("encrypted", ignoreCase = true)) {
                RegisteredPreferenceStore(
                    id = fileName,
                    label = fileName,
                    kind = "unregisteredEncrypted",
                    unavailableMessage = ENCRYPTED_MESSAGE,
                    open = { app ->
                        app.getSharedPreferences(fileName, Context.MODE_PRIVATE)
                    },
                )
            } else {
                RegisteredPreferenceStore(
                    id = fileName,
                    label = fileName,
                    kind = "plain",
                    validate = { values ->
                        if (values.keys.any { it.startsWith(ANDROIDX_KEYSET_PREFIX) }) {
                            PreferenceStoreRejection(
                                kind = "unregisteredEncrypted",
                                message = ENCRYPTED_MESSAGE,
                            )
                        } else {
                            null
                        }
                    },
                    open = { app ->
                        app.getSharedPreferences(fileName, Context.MODE_PRIVATE)
                    },
                )
            }
        }
    }

    fun fileNames(root: File, excludedFileNames: Set<String>): List<String> {
        val canonicalRoot = try {
            root.canonicalFile
        } catch (_: IOException) {
            return emptyList()
        }
        if (!canonicalRoot.isDirectory) return emptyList()

        return canonicalRoot.listFiles().orEmpty().mapNotNull { candidate ->
            if (!candidate.name.endsWith(".xml")) return@mapNotNull null
            val name = candidate.name.removeSuffix(".xml")
            if (name.isBlank() || name in excludedFileNames) return@mapNotNull null
            val canonicalCandidate = try {
                candidate.canonicalFile
            } catch (_: IOException) {
                return@mapNotNull null
            }
            val expected = File(canonicalRoot, candidate.name).absoluteFile
            if (
                canonicalCandidate != expected ||
                canonicalCandidate.parentFile != canonicalRoot ||
                !canonicalCandidate.isFile
            ) {
                return@mapNotNull null
            }
            name
        }.sorted()
    }
}
