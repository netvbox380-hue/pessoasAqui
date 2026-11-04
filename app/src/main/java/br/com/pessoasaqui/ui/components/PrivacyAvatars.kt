package br.com.pessoasaqui.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import br.com.pessoasaqui.domain.model.NearbyPerson

/** Galeria vetorial original: animais escaláveis sem perda de definição, offline. */
object AnimalGallery {
    val animals = listOf(
        "gato" to "Gato", "cachorro" to "Cachorro",
        "raposa" to "Raposa", "coruja" to "Coruja",
        "panda" to "Panda", "coelho" to "Coelho",
        "leao" to "Leão", "urso" to "Urso"
    )
    fun isValid(id: String?) = id != null && animals.any { it.first == id }
}

/** Foto real jamais aparece se não houver vínculo MÚTUO confirmado. */
@Composable
fun PeerPrivacyAvatar(person: NearbyPerson, modifier: Modifier = Modifier) {
    val real = br.com.pessoasaqui.core.profile.AvatarVisibilityPolicy.permittedRealPhoto(
        person.isMutualConnection, person.mutualProfilePhotoBase64
    )
    AvatarVisual(person.animalAvatarId, real, modifier)
}

@Composable
fun AvatarVisual(animalId: String?, realPhotoBase64: String?, modifier: Modifier = Modifier) {
    val image = remember(realPhotoBase64) {
        if (realPhotoBase64.isNullOrBlank() || realPhotoBase64.length > 180_000) null
        else runCatching {
            val bytes = Base64.decode(realPhotoBase64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    if (image != null) {
        Image(
            bitmap = image, contentDescription = "Foto de perfil",
            contentScale = ContentScale.Crop, modifier = modifier.clip(CircleShape)
        )
    } else if (AnimalGallery.isValid(animalId)) {
        AnimalPortrait(animalId!!, modifier)
    } else {
        Box(modifier.clip(CircleShape).background(Color(0xFF273449)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Person, contentDescription = "Sem foto de perfil",
                tint = Color(0xFF9CC6D3), modifier = Modifier.fillMaxSize(0.59f))
        }
    }
}

@Composable
fun AnimalPortrait(id: String, modifier: Modifier = Modifier) {
    val palette = when(id) {
        "gato" -> Triple(0xFF243E62,0xFFFFD48B,0xFFFFE8BA)
        "cachorro" -> Triple(0xFF3B5B4D,0xFFD7AC77,0xFFF4D5A3)
        "raposa" -> Triple(0xFF3B467A,0xFFF99A52,0xFFFDD9A2)
        "coruja" -> Triple(0xFF405A72,0xFF99775D,0xFFE3BA85)
        "panda" -> Triple(0xFF50746E,0xFFEEF2EA,0xFFFFFFFF)
        "coelho" -> Triple(0xFF786A8F,0xFFECDBF0,0xFFFFF5F7)
        "leao" -> Triple(0xFF695233,0xFFF6BB54,0xFFFFD686)
        else -> Triple(0xFF5E7280,0xFFB68B6E,0xFFE0B99C)
    }
    Canvas(modifier.clip(CircleShape)) {
        val u = size.minDimension/200f
        val x0 = (size.width-200f*u)/2f
        val y0 = (size.height-200f*u)/2f
        fun pt(x:Float,y:Float)=Offset(x0+x*u,y0+y*u)
        fun circ(color:Color,x:Float,y:Float,r:Float) =
            drawCircle(color,r*u,pt(x,y))
        fun oval(color:Color,cx:Float,cy:Float,rx:Float,ry:Float) =
            drawOval(color,topLeft=pt(cx-rx,cy-ry),size=androidx.compose.ui.geometry.Size(2*rx*u,2*ry*u))
        fun polygon(color:Color,points:List<Pair<Float,Float>>) {
            val p=Path();points.forEachIndexed { index,(x,y) ->
                if(index==0)p.moveTo(x0+x*u,y0+y*u)else p.lineTo(x0+x*u,y0+y*u)
            };p.close();drawPath(p,color)
        }
        val back=Color(palette.first)
        val fur=Color(palette.second)
        val face=Color(palette.third)
        drawRect(Brush.radialGradient(listOf(back.copy(alpha=.72f),back),
            center=pt(78f,65f),radius=230*u))
        // Light/glow and background detail at full scalable vector resolution.
        circ(Color.White.copy(alpha=.08f),38f,34f,31f)
        circ(Color.White.copy(alpha=.055f),177f,157f,58f)
        if (id=="leao") circ(Color(0xFF7C4A28),100f,104f,88f)
        if (id=="gato" || id=="raposa") {
            polygon(fur,listOf(39f to 82f,39f to 15f,94f to 56f))
            polygon(fur,listOf(108f to 56f,161f to 15f,160f to 83f))
            polygon(Color(0xFFC77984),listOf(51f to 71f,49f to 34f,80f to 63f))
            polygon(Color(0xFFC77984),listOf(122f to 63f,151f to 34f,149f to 72f))
        } else if(id=="coelho") {
            oval(fur,64f,51f,18f,52f);oval(fur,136f,51f,18f,52f)
            oval(Color(0xFFD89CA9),64f,47f,10f,37f)
            oval(Color(0xFFD89CA9),136f,47f,10f,37f)
        } else if(id=="cachorro"){
            oval(Color(0xFF8D603C),36f,90f,27f,52f)
            oval(Color(0xFF8D603C),164f,90f,27f,52f)
        } else if(id=="panda"||id=="urso"){
            circ(fur,43f,53f,27f);circ(fur,157f,53f,27f)
            if(id=="panda"){circ(Color(0xFF242C30),43f,53f,17f);circ(Color(0xFF242C30),157f,53f,17f)}
        } else if(id=="coruja") {
            polygon(fur,listOf(36f to 89f,34f to 23f,88f to 64f))
            polygon(fur,listOf(112f to 64f,166f to 23f,164f to 89f))
        }
        oval(fur,100f,111f,77f,83f)
        oval(face,100f,126f,59f,63f)
        if(id=="panda"){
            oval(Color(0xFF272A32),68f,101f,24f,35f)
            oval(Color(0xFF272A32),132f,101f,24f,35f)
        }
        if(id=="coruja"){
            circ(Color(0xFFF6E5C8),66f,100f,33f)
            circ(Color(0xFFF6E5C8),134f,100f,33f)
        }
        circ(Color(0xFF201D22),69f,106f,if(id=="coruja")14f else 9f)
        circ(Color(0xFF201D22),131f,106f,if(id=="coruja")14f else 9f)
        circ(Color.White,72f,101f,4f)
        circ(Color.White,134f,101f,4f)
        if(id=="raposa"){
            polygon(Color(0xFFFCF1DF),listOf(57f to 135f,99f to 170f,145f to 134f,128f to 170f,71f to 170f))
        } else {
            oval(Color.White.copy(alpha=.48f),100f,147f,35f,22f)
        }
        if(id=="coruja") {
            polygon(Color(0xFFE8A43D),listOf(89f to 124f,111f to 124f,100f to 149f))
        } else {
            oval(Color(0xFF493833),100f,139f,15f,10f)
            val mouth=Path().apply {
                moveTo(x0+100*u,y0+146*u)
                quadraticTo(x0+95*u,y0+158*u,x0+85*u,y0+156*u)
                moveTo(x0+100*u,y0+146*u)
                quadraticTo(x0+105*u,y0+158*u,x0+115*u,y0+156*u)
            }
            drawPath(mouth,Color(0xFF58443C),style=Stroke(width=2.5f*u))
        }
        if(id=="gato"||id=="raposa"){
            for(side in listOf(-1f,1f)){
                val endX=100f + side*88f
                drawLine(Color.White.copy(alpha=.82f),pt(100f+side*12f,148f),pt(endX,137f),1.6f*u)
                drawLine(Color.White.copy(alpha=.82f),pt(100f+side*12f,155f),pt(endX,159f),1.6f*u)
            }
        }
        // The same original art remains sharp on any DPI/screen size.
    }
}
