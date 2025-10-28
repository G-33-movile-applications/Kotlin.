package com.example.mymeds.views

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.widget.Toast
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.mymeds.viewModels.NfcViewModel
import com.example.mymeds.views.components.PrescriptionComponents.HeaderStatusCard
import com.example.mymeds.views.components.PrescriptionComponents.LargeActionCard
import com.example.mymeds.views.components.PrescriptionComponents.HelpBox

class UploadByNfcActivity : ComponentActivity() {

    private val vm: NfcViewModel by viewModels()
    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        vm.init(nfcAdapter)

        setContent {
            UploadByNfcScreen(
                vm = vm,
                onRead = { vm.startReading() },
                onStopRead = { vm.stopReading() },
                onWrite = { json: String -> vm.prepareToWrite(json) },
                onWipe = { vm.prepareToWipe() },
                onBack = { finish() }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        enableForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        disableForegroundDispatch()
    }

    /**
     * Función del sistema de Android para usar NFC mientras la app anda corriendo
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TAG_DISCOVERED == intent.action) {

            Log.d("UploadByNfcActivity", "Foreground Dispatch discovered a tag.")

            val tag = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }

            // Tag nuevo
            tag?.let {
                vm.onTagDiscovered(it)
            }
        }
    }

    /**
     * Prioridad a la actividad para leer los NFC
     */
    private fun enableForegroundDispatch() {
        if (nfcAdapter == null) {
            Log.e("UploadByNfcActivity", "NFC Adapter not available.")
            return
        }
        try {
            val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)

            // Para aceptar todos los NDEF
            val ndefFilter = arrayOf(IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply { addDataType("*/*") })

            // En dado caso de querer especificar los tech nfc
            val techLists = arrayOf(arrayOf(Ndef::class.java.name))

            // Foreground dispatch activo
            nfcAdapter?.enableForegroundDispatch(this, pendingIntent, ndefFilter, techLists)
            Log.d("UploadByNfcActivity", "Foreground Dispatch Enabled")
        } catch (e: Exception) {
            Log.e("UploadByNfcActivity", "Error enabling foreground dispatch", e)
        }
    }

    private fun disableForegroundDispatch() {
        try {
            nfcAdapter?.disableForegroundDispatch(this)
            Log.d("UploadByNfcActivity", "Foreground Dispatch Disabled")
        } catch (e: Exception) {
            Log.e("UploadByNfcActivity", "Error disabling foreground dispatch", e)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadByNfcScreen(
    vm: NfcViewModel,
    onRead: () -> Unit,
    onStopRead: () -> Unit,
    onWrite: (String) -> Unit,
    onWipe: () -> Unit,
    onBack: () -> Unit
) {
    val ui by vm.ui.collectAsState()
    val scroll = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cargar Prescripción por NFC", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HeaderStatusCard(
                supported = ui.supported,
                enabled = ui.enabled,
                reading = ui.reading,
                status = ui.status,
                lastPayload = ui.lastPayload
            )

            Text("Acciones NFC", style = MaterialTheme.typography.titleMedium)

            LargeActionCard(
                title = "Leer Prescripción desde NFC",
                subtitle = "Escanea un tag NFC para cargar una prescripción",
                enabled = ui.supported && ui.enabled && !ui.reading,
                onClick = onRead,
                leading = { Text("📖", style = MaterialTheme.typography.titleLarge) }
            )

            LargeActionCard(
                title = "Escribir Prescripción en NFC",
                subtitle = "Guarda una prescripción en un tag NFC",
                enabled = ui.supported && ui.enabled,
                onClick = { onWrite(buildPrescriptionJsonFromState()) },
                leading = { Text("✍️", style = MaterialTheme.typography.titleLarge) }
            )

            LargeActionCard(
                title = "Limpiar Tag NFC",
                subtitle = "Borra todo el contenido de un tag NFC",
                enabled = ui.supported && ui.enabled,
                onClick = onWipe,
                leading = { Text("🧹", style = MaterialTheme.typography.titleLarge) }
            )

            if (ui.reading) {
                Button(onClick = onStopRead, modifier = Modifier.fillMaxWidth()) {
                    Text("Detener lectura")
                }
            }

            Spacer(Modifier.weight(1f))

            HelpBox(
                title = "Cómo usar NFC",
                bullets = listOf(
                    "Activa NFC en tu dispositivo",
                    "Acerque el teléfono al tag NFC",
                    "Espere la confirmación de lectura o escritura"
                ),
                footnote = null
            )
        }
    }
}

/* ==== Ejemplo de JSON de prescripción ==== */
private fun buildPrescriptionJsonFromState(): String = """
{
  "rxId": "RX-${System.currentTimeMillis()}",
  "patient": "P12345",
  "meds": [{"drug":"Amoxicillin 500mg","dose":"1 cap","freq":"8h","days":7}],
  "issuedAt": "${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(java.util.Date())}",
  "signed": false
}
""".trimIndent()
