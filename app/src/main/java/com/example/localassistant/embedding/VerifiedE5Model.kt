package com.example.localassistant.embedding

import java.io.File
import java.security.MessageDigest

/** The first integration intentionally accepts only the audited local positional repair. */
internal object VerifiedE5Model {
    const val SIZE = 132_440_544L
    const val SHA256 = "bdb785b2ca8532ff3097f93ac3e4e1d560a122cfba377bf559895aebfd9be6d9"

    fun verify(file: File, checkCancelled: () -> Unit) {
        require(file.isFile && file.length() == SIZE) { "Нужен проверенный файл multilingual-e5-small Q8_0 posfix" }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= SIZE) { "Файл embedding-модели изменился во время проверки" }
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        require(total == SIZE && hash == SHA256) { "Контрольная сумма embedding-модели не совпадает" }
        checkCancelled()
    }
}
