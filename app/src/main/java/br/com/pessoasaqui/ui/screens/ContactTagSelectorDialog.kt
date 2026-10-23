package br.com.pessoasaqui.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import br.com.pessoasaqui.domain.model.ContactTag
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.ui.theme.*

private val TAG_PALETTE = listOf(
    "#00D4FF", // Radar Cyan
    "#00E676", // Emerald Green
    "#FFB300", // Amber Gold
    "#B388FF", // Purple
    "#FF5252", // Coral Red
    "#2979FF"  // Vibrant Blue
)

@Composable
fun ContactTagSelectorDialog(
    person: NearbyPerson,
    allTags: List<ContactTag>,
    assignedTagIds: Set<String>,
    onToggleTag: (tagId: String) -> Unit,
    onCreateTag: (name: String, colorHex: String) -> Unit,
    onDeleteTag: (tagId: String) -> Unit,
    onDismiss: () -> Unit
) {
    var newTagName by remember { mutableStateOf("") }
    var selectedColorHex by remember { mutableStateOf(TAG_PALETTE.first()) }
    var showCreateSection by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .heightIn(max = 620.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Cabeçalho
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = RadarCyan.copy(alpha = 0.15f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Label, contentDescription = null, tint = RadarCyan, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Etiquetas do Contato", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                            Text(person.alias, style = MaterialTheme.typography.bodySmall.copy(color = RadarCyan), maxLines = 1)
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = DarkCardElevated)
                Spacer(modifier = Modifier.height(12.dp))

                // Lista de Etiquetas Existentes
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (allTags.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkCard,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("Nenhuma etiqueta criada ainda.", color = TextSecondary, fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Crie etiquetas para organizar seus contatos e transmissões.", color = TextSecondary.copy(alpha = 0.7f), fontSize = 11.sp)
                            }
                        }
                    } else {
                        allTags.forEach { tag ->
                            val isChecked = assignedTagIds.contains(tag.id)
                            val tagColor = try { Color(android.graphics.Color.parseColor(tag.colorHex)) } catch (_: Exception) { RadarCyan }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isChecked) tagColor.copy(alpha = 0.15f) else DarkCard,
                                border = BorderStroke(1.dp, if (isChecked) tagColor.copy(alpha = 0.6f) else DarkCardElevated),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleTag(tag.id) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { onToggleTag(tag.id) },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = tagColor,
                                            uncheckedColor = TextSecondary
                                        ),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(tagColor)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = tag.name,
                                        fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isChecked) TextPrimary else TextSecondary,
                                        fontSize = 14.sp,
                                        modifier = Modifier.weight(1f)
                                    )

                                    // Botão de excluir etiqueta
                                    IconButton(
                                        onClick = { onDeleteTag(tag.id) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.DeleteOutline,
                                            contentDescription = "Excluir",
                                            tint = TextSecondary.copy(alpha = 0.5f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Seção de Criação de Nova Etiqueta
                    if (!showCreateSection) {
                        OutlinedButton(
                            onClick = { showCreateSection = true },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = RadarCyan, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Criar Nova Etiqueta", color = RadarCyan, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = DarkCard,
                            border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Nome da Etiqueta", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = newTagName,
                                    onValueChange = { newTagName = it },
                                    placeholder = { Text("Ex: Usuários de 30$, VIP, Amigos...", fontSize = 12.sp) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(8.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = RadarCyan,
                                        unfocusedBorderColor = DarkCardElevated,
                                        focusedContainerColor = DarkSurface,
                                        unfocusedContainerColor = DarkSurface
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(10.dp))
                                Text("Cor da Etiqueta", color = TextSecondary, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TAG_PALETTE.forEach { hex ->
                                        val color = Color(android.graphics.Color.parseColor(hex))
                                        val isSelected = selectedColorHex == hex
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .background(color)
                                                .clickable { selectedColorHex = hex }
                                                .then(
                                                    if (isSelected) Modifier.background(Color.White.copy(alpha = 0.2f))
                                                    else Modifier
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isSelected) {
                                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        onClick = {
                                            showCreateSection = false
                                            newTagName = ""
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Cancelar", color = TextSecondary)
                                    }
                                    Button(
                                        onClick = {
                                            if (newTagName.isNotBlank()) {
                                                onCreateTag(newTagName.trim(), selectedColorHex)
                                                newTagName = ""
                                                showCreateSection = false
                                            }
                                        },
                                        enabled = newTagName.isNotBlank(),
                                        colors = ButtonDefaults.buttonColors(containerColor = RadarCyan),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Salvar", color = Color.Black, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Concluir", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
