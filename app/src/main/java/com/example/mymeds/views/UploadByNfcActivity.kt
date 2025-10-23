package com.example.mymeds.views

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.Toast
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
import java.util.concurrent.atomic.AtomicReference

class UploadByNfcActivity : ComponentActivity() {

    private val vm: NfcViewModel by viewModels()
    private var nfcAdapter: NfcAdapter? = null
    private val pendingTag = AtomicReference<Tag?>(null)

    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        vm.onTagDiscovered(tag)
        pendingTag.set(tag) // Guarda el tag para operaciones posteriores
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        vm.init(nfcAdapter)

        setContent {
            UploadByNfcScreen(
                vm = vm,
                onRead = { enableReading(true) },
                onStopRead = { enableReading(false) },
                onWrite = { json: String ->
                    val tag = pendingTag.get()
                    if (tag == null) {
                        Toast.makeText(this, "Acerque un tag para escribir", Toast.LENGTH_SHORT).show()
                    } else {
                        vm.writeToTag(tag, json) { _, msg ->
                            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
                        }
                    }
                },
                onWipe = {
                    val tag = pendingTag.get()
                    if (tag == null) {
                        Toast.makeText(this, "Acerque un tag para limpiar", Toast.LENGTH_SHORT).show()
                    } else {
                        vm.wipeTag(tag) { _, msg ->
                            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
                        }
                    }
                },
                onBack = { finish() }
            )
        }
    }

    private fun enableReading(enable: Boolean) {
        if (enable) {
            nfcAdapter?.enableReaderMode(
                this,
                readerCallback,
                NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_NFC_F or
                        NfcAdapter.FLAG_READER_NFC_V or
                        NfcAdapter.FLAG_READER_NFC_BARCODE,
                Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 50) }
            )
            vm.startReading()
        } else {
            nfcAdapter?.disableReaderMode(this)
            vm.stopReading()
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
                .fillMaxSize()
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

