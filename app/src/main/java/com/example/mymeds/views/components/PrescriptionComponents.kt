package com.example.mymeds.views.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

object PrescriptionComponents {

    /**
     * Tarjeta de estado NFC (disponible, activado, leyendo) y último payload leído.
     */
    @Composable
    fun HeaderStatusCard(
        supported: Boolean,
        enabled: Boolean,
        reading: Boolean,
        status: String,
        lastPayload: String?
    ) {
        val bg = Color(0xFFB8C7EE)
        val muted = Color(0xFF6B7280)

        Surface(
            color = bg,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier
                    .padding(16.dp),
                    //.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📶", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(8.dp))
                    val title = when {
                        !supported -> "NFC No Disponible"
                        !enabled   -> "NFC Desactivado"
                        reading    -> "Leyendo NFC…"
                        else       -> "NFC Listo"
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = when {
                        !supported -> "Este dispositivo no soporta NFC"
                        !enabled   -> "Activa NFC en Ajustes para continuar"
                        status.isNotBlank() -> status
                        else -> "Acerque el tag para leer o elija una acción"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted
                )

                if (!lastPayload.isNullOrBlank()) {
                    Divider(color = Color.White.copy(alpha = 0.6f))
                    Text("Última prescripción leída:", style = MaterialTheme.typography.labelLarge)
                    Text(
                        lastPayload,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF1A2247)
                    )
                }
            }
        }
    }

    /**
     * Tarjeta de acción grande (NFC/Imagen/PDF).
     * Usa un slot [leading] para icono/emoji.
     */
    @Composable
    fun LargeActionCard(
        title: String,
        subtitle: String,
        enabled: Boolean,
        onClick: () -> Unit,
        leading: @Composable (() -> Unit)? = null
    ) {
        val bg = if (enabled) Color(0xFFB8C7EE) else Color(0xFFCBD5E1)
        val content = if (enabled) Color(0xFF1A2247) else Color(0xFF475569)

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = bg,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 84.dp)
                .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    leading?.invoke()
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = content)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = content.copy(alpha = 0.75f)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("›", color = content, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }

    /**
     * Caja de ayuda con bullets y nota al pie.
     */
    @Composable
    fun HelpBox(title: String, bullets: List<String>, footnote: String?) {
        Surface(
            color = Color(0xFFE8F2FF),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ℹ️")
                    Spacer(Modifier.width(8.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    bullets.forEach {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("✅")
                            Spacer(Modifier.width(8.dp))
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                footnote?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                }
            }
        }
    }
}
