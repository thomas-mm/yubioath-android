package com.yubico.yubioath.client

import com.yubico.yubioath.exc.PasswordRequiredException
import com.yubico.yubioath.protocol.*
import com.yubico.yubioath.transport.Backend
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.*

class OathClient(backend: Backend, private val keyManager: KeyManager) : Closeable {
    private val api: YkOathApi = YkOathApi(backend)
    val deviceInfo = api.deviceInfo

    init {
        if (api.isLocked()) {
            val secrets = keyManager.getSecrets(deviceInfo.id)

            if (secrets.isEmpty()) {
                throw PasswordRequiredException("Password is missing!", deviceInfo.id, true)
            }

            secrets.find {
                api.unlock(it)
            }?.apply {
                keyManager.setOnlySecret(deviceInfo.id, this)
            } ?: throw PasswordRequiredException("Password is incorrect!", deviceInfo.id, false)
        }
    }

    override fun close() = api.close()

    private fun ensureOwnership(credential: Credential) {
        if (!Arrays.equals(deviceInfo.id, credential.parentId)) {
            throw IllegalArgumentException("Credential parent ID doesn't match!")
        }
    }

    fun setPassword(pw: String?, remember:Boolean) {
        if (pw == null || pw.isEmpty()) {
            api.unsetLockCode()
            keyManager.storeSecret(deviceInfo.id, byteArrayOf(), true)
        } else {
            val secret = KeyManager.calculateSecret(pw, deviceInfo.id, false)
            api.setLockCode(secret)
            keyManager.storeSecret(deviceInfo.id, secret, remember)
        }
    }

    fun calculate(credential: Credential, timestamp: Long): Code {
        ensureOwnership(credential)

        val timeStep = (timestamp / 1000 / credential.period)
        val challenge = ByteBuffer.allocate(8).putLong(timeStep).array()

        val value = when (credential.issuer) {
            "Steam" -> formatSteam(api.calculate(credential.key, challenge, false))
            else -> formatTruncated(api.calculate(credential.key, challenge))
        }

        val (validFrom, validUntil) = when (credential.type) {
            OathType.TOTP -> Pair(timeStep * 1000 * credential.period, (timeStep + 1) * 1000 * credential.period)
            OathType.HOTP -> Pair(timestamp, Long.MAX_VALUE)
        }

        return Code(value, validFrom, validUntil)
    }

    fun refreshCodes(timestamp: Long, existing:MutableMap<Credential, Code?>):MutableMap<Credential, Code?> {
        // Default to 30 second period
        val timeStep = (timestamp / 1000 / 30)
        val challenge = ByteBuffer.allocate(8).putLong(timeStep).array()

        return api.calculateAll(challenge).map {
            val credential = Credential(deviceInfo.id, it.key, it.oathType, it.touch)
            val existingCode = existing[credential]
            val code: Code? = if (it.data.size > 1) {
                if (credential.period != 30 || credential.issuer == "Steam") {
                    //Recalculate needed for for periods != 30 or Steam credentials
                    if(existingCode != null && existingCode.validUntil > timestamp) existingCode else calculate(credential, timestamp)
                } else {
                    Code(formatTruncated(it.data), timeStep * 30 * 1000, (timeStep + 1) * 30 * 1000)
                }
            } else existingCode

            Pair(credential, code)
        }.toMap().toSortedMap(compareBy<Credential> { it.issuer }.thenBy { it.name })
    }

    fun delete(credential: Credential) {
        ensureOwnership(credential)
        api.deleteCode(credential.key)
    }

    fun addCredential(data: CredentialData): Credential {
        with(data) {
            if(oathType == OathType.TOTP && period != 30) {
                name = "$period/$name"
            }
            api.putCode(name, key, oathType, algorithm, digits, counter, touch)
            return Credential(deviceInfo.id, name, oathType, touch)
        }
    }

    companion object {
        private val STEAM_CHARS = "23456789BCDFGHJKMNPQRTVWXY"

        private fun formatTruncated(data: ByteArray): String {
            return with(ByteBuffer.wrap(data)) {
                val digits = get().toInt()
                int.toString().takeLast(digits).padStart(digits, '0')
            }
        }

        private fun formatSteam(data: ByteArray): String {
            val offs = 0xf and data[data.size - 1].toInt() + 1
            var code = 0x7fffffff and ByteBuffer.wrap(data.copyOfRange(offs, offs + 4)).int
            return StringBuilder().apply {
                for (i in 0..4) {
                    append(STEAM_CHARS[code % STEAM_CHARS.length])
                    code /= STEAM_CHARS.length
                }
            }.toString()
        }
    }
}