package org.mozilla.javascript.decompiler

import org.mozilla.javascript.RhinoDecompilerProject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec


class ScriptEncryption(private val project: RhinoDecompilerProject) {

    companion object {
        var FIX_STRING_1 = "9a1132118990c3db"
        var FIX_STRING_2 = "seBmfdYSRu2ysWEl"

        fun encrypt(data: ByteArray, key: ByteArray): ByteArray {
            return ByteArray(data.size).apply {
                for (i in data.indices) {
                    this[i] = (data[i] + key[i % key.size]).toByte()
                }
            }
        }

        fun decrypt(encryptedData: ByteArray, key: ByteArray): ByteArray {
            return ByteArray(encryptedData.size).apply {
                for (i in encryptedData.indices) {
                    this[i] = (encryptedData[i] - key[i % key.size]).toByte()
                }
            }
        }
    }

    private var mInitVector = ""

    private fun getFingerprint(fixString: String): ByteArray {
        // 生成 MD5 哈希值用于初始化向量
        val combinedConfigInfo = project.packageName + project.versionName + project.main + project.versionCode
        val configHash = byteArrayToHexString(generateMD5Hash(combinedConfigInfo))
        // 构建指纹字符串
        val fingerprintBuilder = StringBuilder()
        fingerprintBuilder.append(project.build.build_id)
        fingerprintBuilder.append(project.name)
        val fingerprintHash = byteArrayToHexString(generateMD5Hash(fingerprintBuilder.toString()))
        // 提取初始化向量的前 16 位
        val initVector = fingerprintHash.substring(0, 16)
        this.mInitVector = initVector
        // 将配置哈希和初始化向量转换为字节数组
        val keyBytes = configHash.toByteArray(Charsets.UTF_8)
        val initVectorBytes = initVector.toByteArray(Charsets.UTF_8)
        // 固定的密钥数据
        val fixedKeyData = fixString.toByteArray(Charsets.UTF_8)
        // 设置 AES 加密
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(initVectorBytes))
        // 生成最终密钥
        return cipher.doFinal(fixedKeyData)
    }

    fun decrypt(encryptedData: ByteArray, offset: Int, length: Int): ByteArray? {
        try {
            val key = this.getFingerprint(FIX_STRING_1)
            val secretKey = SecretKeySpec(key, "AES")
            val initVectorBytes = this.mInitVector.toByteArray(Charsets.UTF_8)
            val ivParameterSpec = IvParameterSpec(initVectorBytes)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec)
            return decrypt(cipher.doFinal(encryptedData, offset, length - offset), key)
        } catch (_: Exception) {
            val key = this.getFingerprint(FIX_STRING_2)
            val secretKey = SecretKeySpec(key, "AES")
            val initVectorBytes = this.mInitVector.toByteArray(Charsets.UTF_8)
            val ivParameterSpec = IvParameterSpec(initVectorBytes)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec)
            return cipher.doFinal(encryptedData, offset, length - offset)
        }
    }

    // 将字节数组转换为十六进制字符串
    private fun byteArrayToHexString(byteArray: ByteArray): String {
        val hexStringBuilder = StringBuilder(32)
        for (byte in byteArray) {
            val hexString = Integer.toHexString(byte.toInt() and 255)
            if (hexString.length == 1) {
                hexStringBuilder.append('0')
            }
            hexStringBuilder.append(hexString)
        }
        return hexStringBuilder.toString()
    }

    // 生成输入字符串的 MD5 哈希值
    private fun generateMD5Hash(input: String): ByteArray {
        return try {
            val messageDigest = MessageDigest.getInstance("MD5")
            messageDigest.update(input.toByteArray())
            messageDigest.digest()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}
