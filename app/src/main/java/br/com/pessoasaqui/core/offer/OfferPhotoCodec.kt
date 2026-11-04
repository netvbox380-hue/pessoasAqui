package br.com.pessoasaqui.core.offer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Anúncio público: imagem JPEG limitada para não sobrecarregar armazenamento/HTTP.
 * Não compartilhar imagens reais de PERFIL por esta API.
 */
object OfferPhotoCodec {
    fun fromUri(context:Context,uri:Uri):String? {
        return try {
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it,null,bounds)
            }
            if(bounds.outWidth<=0||bounds.outHeight<=0)return null
            var factor=1
            while(maxOf(bounds.outWidth,bounds.outHeight)/factor>900)factor*=2
            val opt=BitmapFactory.Options().apply { inSampleSize=factor }
            val bitmap=context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it,null,opt)
            }?:return null
            val ratio=800f/maxOf(bitmap.width,bitmap.height)
            val reduced=if(ratio<1f)Bitmap.createScaledBitmap(
                bitmap,(bitmap.width*ratio).toInt().coerceAtLeast(1),
                (bitmap.height*ratio).toInt().coerceAtLeast(1),true
            )else bitmap
            val out=ByteArrayOutputStream()
            var quality=78
            do {
                out.reset()
                reduced.compress(Bitmap.CompressFormat.JPEG,quality,out)
                quality-=10
            } while(out.size()>105000&&quality>=38)
            out.toByteArray().takeIf{it.isNotEmpty()&&it.size<=105000}
                ?.let {Base64.encodeToString(it,Base64.NO_WRAP)}
        } catch(_:Exception){null}
    }
}
