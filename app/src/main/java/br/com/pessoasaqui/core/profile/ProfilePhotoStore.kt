package br.com.pessoasaqui.core.profile

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Foto real privada: AES-GCM com chave não exportável AndroidKeyStore.
 * Não usar LocalDiskCryptoManager: seu fallback legado salva em plaintext.
 */
class ProfilePhotoStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("pessoasaqui_profile_private_v1",Context.MODE_PRIVATE)
    private val keyName = "pessoasaqui_profile_photo_AES_2026"
    private fun secretKey(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        (store.getKey(keyName,null) as? SecretKey)?.let { return it }
        val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyName,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
    private fun secureKey(ownerId:String) = "photo_" + ownerId.replace(Regex("[^A-Za-z0-9]"),"").uppercase()

    fun savePhoto(ownerId:String, base64:String?): Boolean {
        val key=secureKey(ownerId)
        if(base64==null) {
            return preferences.edit().remove(key).commit()
        }
        if(base64.length>160000 || !Regex("^[A-Za-z0-9+/]+={0,2}$").matches(base64))return false
        val bytes=try{Base64.decode(base64,Base64.DEFAULT)}catch(_:Exception){return false}
        if(bytes.size>120000 || bytes.isEmpty())return false
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)?:return false
        if(bitmap.width<=0||bitmap.height<=0||bitmap.width>4096||bitmap.height>4096)return false
        return try{
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,secretKey())
            val encrypted=cipher.doFinal(bytes)
            val output=Base64.encodeToString(cipher.iv+encrypted,Base64.NO_WRAP)
            preferences.edit().putString(key,output).commit()
        }catch(_:Exception){false}
    }

    fun readPhoto(ownerId:String):String? {
        val raw=preferences.getString(secureKey(ownerId),null)?:return null
        return try{
            val bytes=Base64.decode(raw,Base64.NO_WRAP)
            if(bytes.size<29||bytes.size>120100)return null
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,secretKey(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            val decrypted=cipher.doFinal(bytes.copyOfRange(12,bytes.size))
            Base64.encodeToString(decrypted,Base64.NO_WRAP)
        }catch(_:Exception){null} // Falha criptográfica não habilita fallback plaintext.
    }

    /** Thumb para canal E2EE. Imagem HD original não sai do celular. */
    fun createCompressedPhoto(uri: Uri):String? {
        return try{
            val opts=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it,null,opts)
            }
            if(opts.outWidth<=0||opts.outHeight<=0)return null
            val maxDim=maxOf(opts.outWidth,opts.outHeight)
            var sample=1
            while(maxDim/sample>640)sample*=2
            val decode=BitmapFactory.Options().apply { inSampleSize=sample }
            val bitmap=context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it,null,decode)
            }?:return null
            val ratio=512f/maxOf(bitmap.width,bitmap.height)
            val resized=if(ratio<1f)android.graphics.Bitmap.createScaledBitmap(
                bitmap,(bitmap.width*ratio).toInt().coerceAtLeast(1),
                (bitmap.height*ratio).toInt().coerceAtLeast(1),true
            )else bitmap
            val stream=ByteArrayOutputStream()
            var quality=82
            do{
                stream.reset()
                resized.compress(android.graphics.Bitmap.CompressFormat.JPEG,quality,stream)
                quality-=12
            }while(stream.size()>110000&&quality>=45)
            stream.toByteArray().takeIf{it.isNotEmpty()&&it.size<=110000}
                ?.let{Base64.encodeToString(it,Base64.NO_WRAP)}
        }catch(_:Exception){null}
    }
}
