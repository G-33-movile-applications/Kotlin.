package com.example.mymeds.views

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "PDFUploadActivity"
private const val MAX_PDFS = 3


class UploadPrescriptionPDFActivity : ComponentActivity() {
    private val vm: PdfOcrUploadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UploadPrescriptionPDFScreen(vm = vm, finish = { finish() })
        }
    }
}

data class MedicationInformation(
    val medicationId: String,
    val name: String,
    val medicationRef: String,
    val doseMg: Int,
    val frequencyHours: Int,
    val startDate: Date,
    val endDate: Date,
    val active: Boolean,
    val prescriptionId: String,
    val sourceFile: String = "" // Nombre del PDF de origen
)

data class PdfDocument(
    val uri: Uri,
    val fileName: String,
    val isProcessing: Boolean = false,
    val isProcessed: Boolean = false,
    val medicationCount: Int = 0,
    val extractedText: String? = null
)

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║                    IMPLEMENTACIÓN DE MULTITHREADING                       ║
 * ║              PROCESAMIENTO MÚLTIPLE DE PDFS (HASTA 3)                     ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 *
 * NUEVAS FUNCIONALIDADES:
 * - Carga de hasta 3 PDFs simultáneamente
 * - Procesamiento paralelo de múltiples documentos
 * - Detección de múltiples medicamentos por PDF
 * - Identificación del archivo fuente de cada medicamento
 * - Consolidación de medicamentos de múltiples prescripciones
 */
class PdfOcrUploadViewModel : ViewModel() {
    var pdfDocuments by mutableStateOf<List<PdfDocument>>(emptyList())
        private set
    var parsedMedications by mutableStateOf<List<MedicationInfo>>(emptyList())
        private set
    var processing by mutableStateOf(false)
        private set
    var uploading by mutableStateOf(false)
        private set
    var progressMessage by mutableStateOf("")
        private set
    var tempFiles by mutableStateOf<List<Uri>>(emptyList())
        private set

    private val firestore = FirebaseFirestore.getInstance()

    /**
     * Agrega un nuevo PDF a la lista (máximo 3)
     */
    fun addPdfDocument(uri: Uri, fileName: String): Boolean {
        if (pdfDocuments.size >= MAX_PDFS) {
            return false
        }

        pdfDocuments = pdfDocuments + PdfDocument(uri, fileName)
        return true
    }

    /**
     * Elimina un PDF de la lista
     */
    fun removePdfDocument(index: Int) {
        if (index in pdfDocuments.indices) {
            val fileName = pdfDocuments[index].fileName
            pdfDocuments = pdfDocuments.filterIndexed { i, _ -> i != index }
            // También elimina los medicamentos asociados a ese PDF
            parsedMedications = parsedMedications.filter { it.sourceFile != fileName }
        }
    }

    /**
     * Limpia todos los PDFs y medicamentos
     */
    fun clearAll() {
        pdfDocuments = emptyList()
        parsedMedications = emptyList()
        tempFiles.forEach { uri ->
            try {
                File(uri.path!!).delete()
            } catch (_: Exception) {}
        }
        tempFiles = emptyList()
    }

    /**
     * ═══════════════════════════════════════════════════════════════════════
     * FUNCIÓN PRINCIPAL: processAllPdfs
     * ═══════════════════════════════════════════════════════════════════════
     *
     * PROCESAMIENTO PARALELO DE MÚLTIPLES PDFs
     * - Cada PDF se procesa en su propia corrutina async
     * - Los medicamentos de todos los PDFs se consolidan
     * - Se mantiene la trazabilidad del archivo fuente
     */
    fun processAllPdfs(context: Activity, onError: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "╔═══════════════════════════════════════════════════════╗")
                Log.d(TAG, "║  PROCESANDO ${pdfDocuments.size} PDFs EN PARALELO     ║")
                Log.d(TAG, "╚═══════════════════════════════════════════════════════╝")

                withContext(Dispatchers.Main) {
                    processing = true
                    progressMessage = "Procesando ${pdfDocuments.size} documento(s)..."
                }

                // PROCESAMIENTO PARALELO DE TODOS LOS PDFs
                val pdfProcessingJobs = pdfDocuments.mapIndexed { index, pdfDoc ->
                    async(Dispatchers.IO) {
                        Log.d(TAG, "🔄 [Async-IO] Procesando PDF ${index + 1}: ${pdfDoc.fileName}")

                        // Marca como procesando
                        withContext(Dispatchers.Main) {
                            pdfDocuments = pdfDocuments.toMutableList().apply {
                                this[index] = this[index].copy(isProcessing = true)
                            }
                        }

                        // Procesa el PDF
                        val result = processSinglePdf(context, pdfDoc, index + 1)

                        // Marca como procesado
                        withContext(Dispatchers.Main) {
                            pdfDocuments = pdfDocuments.toMutableList().apply {
                                this[index] = this[index].copy(
                                    isProcessing = false,
                                    isProcessed = true,
                                    medicationCount = result.medications.size,
                                    extractedText = result.extractedText
                                )
                            }
                        }

                        Log.d(TAG, "✅ PDF ${index + 1} completado: ${result.medications.size} medicamento(s)")
                        result
                    }
                }

                // Espera a que todos los PDFs terminen de procesarse
                Log.d(TAG, "⏳ Esperando a ${pdfProcessingJobs.size} trabajos de procesamiento...")
                val allResults = pdfProcessingJobs.awaitAll()

                // Consolida todos los medicamentos
                val allMedications = allResults.flatMap { it.medications }

                withContext(Dispatchers.Main) {
                    parsedMedications = allMedications
                    processing = false
                    progressMessage = "Completado: ${allMedications.size} medicamento(s) encontrado(s)"
                }

                Log.d(TAG, "╔═══════════════════════════════════════════════════════╗")
                Log.d(TAG, "║  TODOS LOS PDFs PROCESADOS EXITOSAMENTE               ║")
                Log.d(TAG, "║  Total de medicamentos: ${allMedications.size}        ║")
                Log.d(TAG, "╚═══════════════════════════════════════════════════════╝")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error durante el procesamiento", e)

                withContext(Dispatchers.Main) {
                    processing = false
                    progressMessage = "Error"
                }

                onError(e.message ?: "Falló el procesamiento")
            }
        }
    }

    /**
     * ═══════════════════════════════════════════════════════════════════════
     * FUNCIÓN: processSinglePdf
     * ═══════════════════════════════════════════════════════════════════════
     *
     * Procesa un solo PDF con OCR y extrae múltiples medicamentos
     */
    private suspend fun processSinglePdf(
        context: Activity,
        pdfDoc: PdfDocument,
        pdfNumber: Int
    ): PdfProcessingResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "📄 [IO] Procesando PDF: ${pdfDoc.fileName}")

        withContext(Dispatchers.Main) {
            progressMessage = "PDF $pdfNumber: Preparando archivo..."
        }

        // Copia archivo temporal
        val tempFile = copyToTempFile(context, pdfDoc.uri, "rx_${System.currentTimeMillis()}_$pdfNumber.pdf")
        tempFiles = tempFiles + Uri.fromFile(tempFile)

        withContext(Dispatchers.Main) {
            progressMessage = "PDF $pdfNumber: Abriendo documento..."
        }

        // Procesa PDF con OCR
        val extractedText = withContext(Dispatchers.IO) {
            val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            // PROCESAMIENTO PARALELO DE PÁGINAS
            val pageResults = (0 until renderer.pageCount).map { pageIndex ->
                async(Dispatchers.IO) {
                    withContext(Dispatchers.Main) {
                        progressMessage = "PDF $pdfNumber: Página ${pageIndex + 1}/${renderer.pageCount}"
                    }

                    renderer.openPage(pageIndex).use { page ->
                        val bmp = Bitmap.createBitmap(
                            page.width,
                            page.height,
                            Bitmap.Config.ARGB_8888
                        )
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        launch(Dispatchers.IO) {
                            Log.d(TAG, "🔍 [Launch-IO] OCR PDF $pdfNumber página $pageIndex")
                        }.join()

                        val result = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
                        result.text
                    }
                }
            }

            val allTexts = pageResults.awaitAll()
            renderer.close()
            pfd.close()

            allTexts.joinToString("\n").ifBlank { null }
        }

        // Parsea medicamentos con Dispatcher.Default
        val medications = if (extractedText != null) {
            withContext(Dispatchers.Main) {
                progressMessage = "PDF $pdfNumber: Analizando medicamentos..."
            }

            Log.d(TAG, "🧮 [Default] Iniciando parseo de medicamentos para PDF $pdfNumber")
            Log.d(TAG, "📊 Texto recibido para parseo: ${extractedText.length} caracteres")

            withContext(Dispatchers.Default) {
                Log.d(TAG, "🧮 [Default] Parseando PDF $pdfNumber en thread: ${Thread.currentThread().name}")
                val result = parseMedicationsFromText(extractedText, pdfDoc.fileName)
                Log.d(TAG, "✅ [Default] Parseo completado: ${result.size} medicamento(s) encontrado(s)")
                result
            }
        } else {
            Log.e(TAG, "❌ extractedText es NULL para PDF $pdfNumber - No se puede parsear")
            emptyList()
        }

        Log.d(TAG, "🏁 PDF $pdfNumber procesamiento completo:")
        Log.d(TAG, "   - Texto extraído: ${extractedText != null}")
        Log.d(TAG, "   - Medicamentos detectados: ${medications.size}")

        PdfProcessingResult(
            medications = medications,
            extractedText = extractedText
        )
    }

    /**
     * ═══════════════════════════════════════════════════════════════════════
     * FUNCIÓN: parseMedicationsFromText
     * ═══════════════════════════════════════════════════════════════════════
     *
     * EJECUTADA EN: Dispatchers.Default (CPU-intensivo)
     * Detecta MÚLTIPLES medicamentos en un mismo texto
     */
    private suspend fun parseMedicationsFromText(
        text: String,
        sourceFile: String
    ): List<MedicationInfo> {
        try {
            Log.d(TAG, "🔍 [Default] Buscando múltiples medicamentos en: $sourceFile")
            Log.d(TAG, "📄 Texto completo a analizar:\n$text")

            val medications = mutableListOf<MedicationInfo>()
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

            // Busca ID de prescripción global
            var globalPrescriptionId = "RX-${(1000..9999).random()}"
            for (line in lines) {
                val rxPattern = """RX[- ]?\d+""".toRegex(RegexOption.IGNORE_CASE)
                val match = rxPattern.find(line)
                if (match != null) {
                    globalPrescriptionId = match.value.uppercase()
                    Log.d(TAG, "  📋 ID Prescripción encontrado: $globalPrescriptionId")
                    break
                }
            }

            // ESTRATEGIA 1: Buscar formato con "Medicamento:" explícito
            var i = 0
            var medicamentosEncontrados = 0

            while (i < lines.size) {
                val line = lines[i]
                val lineLower = line.lowercase()

                if (lineLower.startsWith("medicamento:") ||
                    lineLower.trim() == "medicamento:") {

                    Log.d(TAG, "  🎯 Línea 'Medicamento:' encontrada en índice $i: $line")

                    val medBlock = extractMedicationBlock(lines, i)
                    if (medBlock != null) {
                        medications.add(
                            medBlock.copy(
                                prescriptionId = globalPrescriptionId,
                                sourceFile = sourceFile
                            )
                        )
                        medicamentosEncontrados++
                        i = medBlock.lastLineIndex
                        Log.d(TAG, "  ✅ Medicamento #$medicamentosEncontrados añadido: ${medBlock.name}")
                    } else {
                        Log.w(TAG, "  ⚠️ No se pudo extraer información del bloque")
                    }
                }
                i++
            }

            Log.d(TAG, "📊 Estrategia 1 completada: $medicamentosEncontrados medicamento(s) encontrado(s)")

            // ESTRATEGIA 2: Formato alternativo (sin palabra "Medicamento:")
            // Busca líneas que contengan nombre de medicamento + dosis directamente
            if (medications.isEmpty()) {
                Log.d(TAG, "🔄 Intentando Estrategia 2: Formato alternativo...")

                i = 0
                val foundMeds = mutableSetOf<String>() // Para evitar duplicados

                while (i < lines.size) {
                    val line = lines[i]
                    val lineLower = line.lowercase()

                    // Lista de palabras que NO son medicamentos
                    val irrelevantWords = listOf(
                        "cantidad", "duración", "dosis", "instrucciones",
                        "fecha", "paciente", "diagnóstico", "médico",
                        "registro", "tableta", "tabletas", "firma",
                        "sello", "tratante", "clínica", "hospital"
                    )

                    // Busca patrones como "Losartán 50mg" o "Medicamento: Losartán 50mg"
                    val medWithDosePattern = """([A-Za-záéíóúÁÉÍÓÚñÑ]{4,})\s+(\d+)\s*mg""".toRegex(RegexOption.IGNORE_CASE)
                    val match = medWithDosePattern.find(line)

                    if (match != null) {
                        val medName = match.groupValues[1].trim()
                        val dose = match.groupValues[2].toIntOrNull() ?: 0
                        val fullName = "$medName ${dose}mg"

                        // Verifica que no sea una palabra irrelevante
                        val isIrrelevant = irrelevantWords.any {
                            medName.lowercase().contains(it) || lineLower.startsWith(it)
                        }

                        // Verifica que la línea no sea solo "Cantidad:" o similar
                        val isLabelOnly = lineLower.startsWith("cantidad:") ||
                                lineLower.startsWith("duración:") ||
                                lineLower.startsWith("dosis:")

                        if (!isIrrelevant && !isLabelOnly && dose > 0 && fullName !in foundMeds) {
                            foundMeds.add(fullName)
                            Log.d(TAG, "  🎯 Posible medicamento encontrado: $fullName")

                            // Busca información adicional cerca de esta línea
                            val medInfo = extractMedicationFromAlternateFormat(lines, i, medName, dose)
                            medications.add(
                                medInfo.copy(
                                    prescriptionId = globalPrescriptionId,
                                    sourceFile = sourceFile
                                )
                            )
                            Log.d(TAG, "  ✅ Medicamento añadido: ${medInfo.name}")
                        } else if (isIrrelevant || isLabelOnly) {
                            Log.d(TAG, "  ⏭️ Ignorando palabra irrelevante: $medName en línea: $line")
                        }
                    }
                    i++
                }

                Log.d(TAG, "📊 Estrategia 2 completada: ${foundMeds.size} medicamento(s) encontrado(s)")
            }

            // ESTRATEGIA 3: Si aún no hay medicamentos, busca solo patrones de nombre
            if (medications.isEmpty()) {
                Log.d(TAG, "🔄 Intentando Estrategia 3: Búsqueda de patrones simples...")

                val medPattern = """([A-Za-záéíóúÁÉÍÓÚñÑ]{4,})\s+(\d+)\s*mg""".toRegex(RegexOption.IGNORE_CASE)
                val matches = medPattern.findAll(text)

                val uniqueMeds = mutableSetOf<String>()
                for (match in matches) {
                    val medName = match.groupValues[1].trim()
                    val dose = match.groupValues[2].toIntOrNull() ?: 0

                    val key = "${medName}_${dose}"
                    val irrelevantWords = listOf("cantidad", "duración", "dosis", "instrucciones", "fecha", "paciente", "diagnóstico", "médico", "registro", "tableta", "tabletas")

                    if (key !in uniqueMeds &&
                        !irrelevantWords.contains(medName.lowercase()) &&
                        medName.length > 3 &&
                        dose > 0) {
                        uniqueMeds.add(key)
                        medications.add(
                            createDefaultMedication(
                                name = "$medName ${dose}mg",
                                doseMg = dose,
                                prescriptionId = globalPrescriptionId,
                                sourceFile = sourceFile
                            )
                        )
                        Log.d(TAG, "  ✅ Encontrado: $medName ${dose}mg (patrón simple)")
                    }
                }
            }

            // Si no encontró nada, agrega placeholder
            if (medications.isEmpty()) {
                medications.add(
                    createDefaultMedication(
                        name = "Medicamento no identificado",
                        prescriptionId = globalPrescriptionId,
                        sourceFile = sourceFile
                    )
                )
                Log.w(TAG, "  ⚠️ No se encontraron medicamentos en $sourceFile")
            }

            Log.d(TAG, "📊 Total detectado en $sourceFile: ${medications.size} medicamento(s)")
            return medications

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error en parseo", e)
            return listOf(
                createDefaultMedication(
                    name = "Error al procesar",
                    prescriptionId = "RX-ERROR",
                    sourceFile = sourceFile
                )
            )
        }
    }

    /**
     * Extrae información de medicamento en formato alternativo
     * (cuando no hay línea "Medicamento:" explícita)
     */
    private fun extractMedicationFromAlternateFormat(
        lines: List<String>,
        startIndex: Int,
        medName: String,
        doseMg: Int
    ): MedicationInfo {
        var frequencyHours = 24
        var durationDays = 30

        Log.d(TAG, "    🔍 Extrayendo info adicional para: $medName")

        // Busca en las siguientes 10 líneas
        val endIndex = minOf(startIndex + 10, lines.size)
        for (i in (startIndex + 1) until endIndex) {
            val line = lines[i].lowercase()

            // Busca frecuencia
            val freqPattern = """cada\s+(\d+)\s+hora""".toRegex(RegexOption.IGNORE_CASE)
            val freqMatch = freqPattern.find(line)
            if (freqMatch != null) {
                frequencyHours = freqMatch.groupValues[1].toIntOrNull() ?: 24
                Log.d(TAG, "      ⏰ Frecuencia: cada $frequencyHours horas")
            }

            // Busca duración
            val durPattern = """(\d+)\s*día""".toRegex(RegexOption.IGNORE_CASE)
            val durMatch = durPattern.find(line)
            if (durMatch != null && line.contains("duración")) {
                durationDays = durMatch.groupValues[1].toIntOrNull() ?: 30
                Log.d(TAG, "      📅 Duración: $durationDays días")
            }

            // Busca cantidad para calcular duración
            if (line.contains("cantidad:")) {
                val qtyPattern = """(\d+)\s*tableta""".toRegex(RegexOption.IGNORE_CASE)
                val qtyMatch = qtyPattern.find(line)
                if (qtyMatch != null && durationDays == 30) {
                    val quantity = qtyMatch.groupValues[1].toIntOrNull() ?: 30
                    val dosesPerDay = 24 / frequencyHours
                    durationDays = if (dosesPerDay > 0) quantity / dosesPerDay else 30
                    Log.d(TAG, "      📦 Cantidad: $quantity → Duración: $durationDays días")
                }
            }
        }

        val startDate = Date()
        val endDate = Calendar.getInstance().apply {
            time = startDate
            add(Calendar.DAY_OF_YEAR, durationDays)
        }.time

        return MedicationInfo(
            medicationId = "med_${System.currentTimeMillis()}_${(1000..9999).random()}",
            name = "$medName ${doseMg}mg",
            medicationRef = "/medicamentosGlobales/med_${medName.hashCode().toString().replace("-", "")}",
            doseMg = doseMg,
            frequencyHours = frequencyHours,
            startDate = startDate,
            endDate = endDate,
            active = true,
            prescriptionId = "",
            sourceFile = ""
        )
    }

    /**
     * ═══════════════════════════════════════════════════════════════════════
     * FUNCIÓN: saveAllMedications (ACTUALIZADA)
     * ═══════════════════════════════════════════════════════════════════════
     */
    fun saveAllMedicationsGroupedAsPrescription(
        userId: String,
        onDone: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (userId.isBlank()) throw IllegalStateException("Usuario no autenticado.")
                if (parsedMedications.isEmpty()) throw IllegalStateException("No hay medicamentos para guardar.")

                withContext(Dispatchers.Main) {
                    uploading = true
                    progressMessage = "Creando prescripciones..."
                }

                // Agrupar por archivo fuente (cada archivo = 1 prescripción)
                val groups: Map<String, List<MedicationInfo>> =
                    parsedMedications.groupBy { it.sourceFile.ifBlank { "Desconocido" } }

                val userDoc = firestore.collection("usuarios").document(userId)
                val prescRoot = userDoc.collection("prescripcionesUsuario")

                var totalMeds = 0
                var createdPrescriptions = 0

                // Guardar cada grupo como una prescripción
                for ((sourceFile, meds) in groups) {
                    withContext(Dispatchers.Main) {
                        progressMessage = "Creando prescripción de $sourceFile..."
                    }

                    // Doc de prescripción (metadatos del archivo/proceso)
                    val prescData = hashMapOf(
                        "fileName" to sourceFile,
                        "uploadedAt" to Date(),
                        "status" to "pendiente", // o "en_proceso"/"aprobado" según tu flujo
                        "totalItems" to meds.size,
                        "fromOCR" to true,
                        "notes" to "",
                    )

                    // Creamos la prescripción y obtenemos su id
                    val prescRef = prescRoot.add(prescData).await()
                    createdPrescriptions++

                    // Subcolección: medicamentosPrescripcion
                    val medsCol = prescRef.collection("medicamentosPrescripcion")

                    // Guardado paralelo de medicamentos del grupo
                    val jobs = meds.mapIndexed { idx, med ->
                        async(Dispatchers.IO) {
                            withContext(Dispatchers.Main) {
                                progressMessage = "Guardando ${idx + 1}/${meds.size} en $sourceFile..."
                            }

                            val doc = hashMapOf(
                                "medicationId" to med.medicationId,
                                "name" to med.name,
                                "medicationRef" to med.medicationRef,
                                "doseMg" to med.doseMg,
                                "frequencyHours" to med.frequencyHours,
                                "startDate" to med.startDate,
                                "endDate" to med.endDate,
                                "active" to med.active,
                                "prescriptionId" to (med.prescriptionId.ifBlank { prescRef.id }),
                                "sourceFile" to med.sourceFile,
                                "createdAt" to Date()
                            )
                            medsCol.add(doc).await()
                        }
                    }
                    jobs.awaitAll()
                    totalMeds += meds.size
                }

                withContext(Dispatchers.Main) {
                    uploading = false
                    progressMessage = "Guardado completo"
                }
                onDone(true, "✅ $createdPrescriptions prescripción(es), $totalMeds medicamento(s) guardado(s)")

                // Limpieza de temporales
                withContext(Dispatchers.IO) {
                    tempFiles.forEach { uri -> runCatching { File(uri.path!!).delete() } }
                    tempFiles = emptyList()
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error guardando prescripciones agrupadas", e)
                withContext(Dispatchers.Main) {
                    uploading = false
                    progressMessage = "Error al guardar"
                }
                onDone(false, "❌ Error: ${e.message}")
            }
        }
    }


    private fun copyToTempFile(context: Activity, uri: Uri, name: String): File {
        val out = File(context.cacheDir, name)
        context.contentResolver.openInputStream(uri).use { input ->
            FileOutputStream(out).use { output ->
                input!!.copyTo(output)
            }
        }
        return out
    }

    private fun extractMedicationBlock(lines: List<String>, startIndex: Int): MedicationBlockResult? {
        try {
            var medName = ""
            var doseMg = 0
            var frequencyHours = 24
            var durationDays = 30
            var currentIndex = startIndex

            Log.d(TAG, "🔍 Extrayendo bloque desde línea $startIndex: ${lines[startIndex]}")

            // EXTRAE EL NOMBRE DEL MEDICAMENTO (línea con "Medicamento:")
            val currentLine = lines[startIndex]
            val medLine = currentLine.replace("Medicamento:", "", ignoreCase = true).trim()

            if (medLine.isNotEmpty()) {
                // El nombre está en la misma línea después de "Medicamento:"
                medName = medLine
                Log.d(TAG, "  📌 Nombre encontrado en misma línea: $medName")

                // Extrae dosis del nombre si está presente (ej: "Amoxicilina 1000mg")
                val doseMatch = """(\d+)\s*mg""".toRegex(RegexOption.IGNORE_CASE).find(medLine)
                if (doseMatch != null) {
                    doseMg = doseMatch.groupValues[1].toIntOrNull() ?: 0
                    Log.d(TAG, "  💊 Dosis encontrada en nombre: $doseMg mg")
                }
            } else if (startIndex + 1 < lines.size) {
                // El nombre está en la siguiente línea
                currentIndex++
                medName = lines[currentIndex].trim()
                Log.d(TAG, "  📌 Nombre encontrado en línea siguiente: $medName")

                val doseMatch = """(\d+)\s*mg""".toRegex(RegexOption.IGNORE_CASE).find(medName)
                if (doseMatch != null) {
                    doseMg = doseMatch.groupValues[1].toIntOrNull() ?: 0
                    Log.d(TAG, "  💊 Dosis encontrada: $doseMg mg")
                }
            }

            // VALIDA QUE EL NOMBRE NO SEA UNA PALABRA IRRELEVANTE
            val irrelevantWords = listOf("cantidad", "duración", "dosis", "instrucciones", "fecha", "paciente", "diagnóstico", "médico", "registro", "tableta", "tabletas")
            if (irrelevantWords.contains(medName.lowercase().substringBefore(":").trim())) {
                Log.w(TAG, "  ⚠️ Nombre detectado es palabra irrelevante: $medName")
                return null
            }

            // BUSCA INFORMACIÓN ADICIONAL EN LAS SIGUIENTES LÍNEAS
            val endSearchIndex = minOf(currentIndex + 15, lines.size)
            Log.d(TAG, "  🔎 Buscando info adicional desde línea ${currentIndex + 1} hasta $endSearchIndex")

            for (i in (currentIndex + 1) until endSearchIndex) {
                val line = lines[i]
                val lineLower = line.lowercase()

                Log.d(TAG, "    Línea $i: $line")

                // Si encuentra otro "Medicamento:", termina el bloque
                if (lineLower.startsWith("medicamento:")) {
                    currentIndex = i - 1
                    Log.d(TAG, "  ⏹️ Fin de bloque en línea $i (nuevo medicamento detectado)")
                    break
                }

                // EXTRAE DOSIS desde línea "Dosis:"
                if (lineLower.contains("dosis:")) {
                    Log.d(TAG, "    📌 Línea contiene 'Dosis:'")
                    // Busca "cada X horas" en la misma línea
                    val freqPattern = """cada\s+(\d+)\s+hora""".toRegex(RegexOption.IGNORE_CASE)
                    val freqMatch = freqPattern.find(line)
                    if (freqMatch != null) {
                        frequencyHours = freqMatch.groupValues[1].toIntOrNull() ?: 24
                        Log.d(TAG, "    ⏰ Frecuencia encontrada en Dosis: cada $frequencyHours horas")
                    }
                }

                // EXTRAE FRECUENCIA (puede estar en línea separada o en Dosis)
                if (frequencyHours == 24) {
                    val freqPattern = """cada\s+(\d+)\s+hora""".toRegex(RegexOption.IGNORE_CASE)
                    val freqMatch = freqPattern.find(line)
                    if (freqMatch != null) {
                        frequencyHours = freqMatch.groupValues[1].toIntOrNull() ?: 24
                        Log.d(TAG, "    ⏰ Frecuencia: cada $frequencyHours horas")
                    }
                }

                // EXTRAE DURACIÓN desde línea "Duración:"
                if (lineLower.contains("duración:")) {
                    Log.d(TAG, "    📌 Línea contiene 'Duración:'")
                    val durPattern = """(\d+)\s*día""".toRegex(RegexOption.IGNORE_CASE)
                    val durMatch = durPattern.find(line)
                    if (durMatch != null) {
                        durationDays = durMatch.groupValues[1].toIntOrNull() ?: 30
                        Log.d(TAG, "    📅 Duración: $durationDays días")
                    }
                }

                // EXTRAE CANTIDAD (para calcular duración si no está explícita)
                if (lineLower.contains("cantidad:")) {
                    Log.d(TAG, "    📌 Línea contiene 'Cantidad:'")
                    val qtyPattern = """(\d+)\s*tableta""".toRegex(RegexOption.IGNORE_CASE)
                    val qtyMatch = qtyPattern.find(line)
                    if (qtyMatch != null) {
                        val quantity = qtyMatch.groupValues[1].toIntOrNull() ?: 30
                        // Solo usa cantidad si no hay duración explícita
                        if (durationDays == 30) {
                            val dosesPerDay = 24 / frequencyHours
                            durationDays = if (dosesPerDay > 0) quantity / dosesPerDay else 30
                            Log.d(TAG, "    📦 Cantidad: $quantity tabletas → Duración calculada: $durationDays días")
                        } else {
                            Log.d(TAG, "    📦 Cantidad: $quantity tabletas (duración ya definida: $durationDays días)")
                        }
                    }
                }

                currentIndex = i
            }

            Log.d(TAG, "  🏁 Análisis completado. Última línea procesada: $currentIndex")

            // VALIDACIONES FINALES
            if (medName.isEmpty() || medName.length < 3) {
                Log.w(TAG, "  ⚠️ Nombre de medicamento inválido o muy corto")
                return null
            }

            // Si no encontró dosis en el texto, intenta del nombre del medicamento
            if (doseMg == 0 && medName.isNotEmpty()) {
                val doseMatch = """(\d+)\s*mg""".toRegex(RegexOption.IGNORE_CASE).find(medName)
                if (doseMatch != null) {
                    doseMg = doseMatch.groupValues[1].toIntOrNull() ?: 0
                }
            }

            val startDate = Date()
            val calendar = Calendar.getInstance().apply {
                time = startDate
                add(Calendar.DAY_OF_YEAR, durationDays)
            }

            Log.d(TAG, "  ✅ Bloque completado: $medName | ${doseMg}mg | cada ${frequencyHours}h | $durationDays días")

            return MedicationBlockResult(
                name = medName,
                doseMg = doseMg,
                frequencyHours = frequencyHours,
                startDate = startDate,
                endDate = calendar.time,
                lastLineIndex = currentIndex
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error extrayendo bloque", e)
            return null
        }
    }

    private fun createDefaultMedication(
        name: String,
        doseMg: Int = 0,
        prescriptionId: String,
        sourceFile: String
    ): MedicationInfo {
        val startDate = Date()
        val endDate = Calendar.getInstance().apply {
            time = startDate
            add(Calendar.DAY_OF_YEAR, 30)
        }.time

        return MedicationInfo(
            medicationId = "med_${System.currentTimeMillis()}_${(1000..9999).random()}",
            name = name,
            medicationRef = "/medicamentosGlobales/med_${name.hashCode().toString().replace("-", "")}",
            doseMg = doseMg,
            frequencyHours = 24,
            startDate = startDate,
            endDate = endDate,
            active = true,
            prescriptionId = prescriptionId,
            sourceFile = sourceFile
        )
    }

    fun updateMedication(index: Int, updated: MedicationInfo) {
        val mutableList = parsedMedications.toMutableList()
        if (index in mutableList.indices) {
            mutableList[index] = updated
            parsedMedications = mutableList
        }
    }

    fun addNewMedication() {
        val newMed = createDefaultMedication(
            name = "Nuevo medicamento",
            prescriptionId = parsedMedications.firstOrNull()?.prescriptionId ?: "RX-${(1000..9999).random()}",
            sourceFile = "Manual"
        )
        parsedMedications = parsedMedications + newMed
    }

    fun removeMedication(index: Int) {
        parsedMedications = parsedMedications.filterIndexed { i, _ -> i != index }
    }

    private data class MedicationBlockResult(
        val name: String,
        val doseMg: Int,
        val frequencyHours: Int,
        val startDate: Date,
        val endDate: Date,
        val lastLineIndex: Int
    ) {
        fun copy(
            prescriptionId: String,
            sourceFile: String
        ): MedicationInfo {
            return MedicationInfo(
                medicationId = "med_${System.currentTimeMillis()}_${(1000..9999).random()}",
                name = name,
                medicationRef = "/medicamentosGlobales/med_${name.hashCode().toString().replace("-", "")}",
                doseMg = doseMg,
                frequencyHours = frequencyHours,
                startDate = startDate,
                endDate = endDate,
                active = true,
                prescriptionId = prescriptionId,
                sourceFile = sourceFile
            )
        }
    }

    private data class PdfProcessingResult(
        val medications: List<MedicationInfo>,
        val extractedText: String?
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadPrescriptionPDFScreen(vm: PdfOcrUploadViewModel, finish: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val scroll = rememberScrollState()

    val pickPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && activity != null) {
            val fileName = runCatching {
                val cursor = ctx.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null, null, null
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    } else "Documento_${System.currentTimeMillis()}.pdf"
                }
            }.getOrDefault("Documento_${System.currentTimeMillis()}.pdf") as String

            val added = vm.addPdfDocument(uri, fileName)
            if (!added) {
                Toast.makeText(ctx, "⚠️ Máximo $MAX_PDFS PDFs permitidos", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cargar Prescripciones", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(scroll),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))

            // SECCIÓN: SELECTOR DE PDFs
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "📄 Documentos PDF (${vm.pdfDocuments.size}/$MAX_PDFS)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Row {
                            if (vm.pdfDocuments.isNotEmpty() && !vm.processing) {
                                IconButton(
                                    onClick = { vm.clearAll() },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(Icons.Filled.Delete, "Limpiar todo")
                                }
                            }

                            if (vm.pdfDocuments.size < MAX_PDFS) {
                                IconButton(onClick = { pickPdf.launch("application/pdf") }) {
                                    Icon(Icons.Filled.Add, "Agregar PDF")
                                }
                            }
                        }
                    }

                    if (vm.pdfDocuments.isEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .border(
                                    2.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                    RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Filled.Description,
                                    null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Toca + para agregar PDFs",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.height(8.dp))
                        vm.pdfDocuments.forEachIndexed { index, pdfDoc ->
                            PdfDocumentCard(
                                pdfDoc = pdfDoc,
                                onRemove = { vm.removePdfDocument(index) }
                            )
                            if (index < vm.pdfDocuments.size - 1) {
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }

            // BOTÓN PROCESAR
            if (vm.pdfDocuments.isNotEmpty() && !vm.processing) {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (activity != null) {
                            vm.processAllPdfs(activity) { errorMsg ->
                                Toast.makeText(ctx, errorMsg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Procesar ${vm.pdfDocuments.size} Documento(s)")
                }
            }

            Spacer(Modifier.height(16.dp))

            // INDICADOR DE PROCESAMIENTO
            if (vm.processing) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                "Procesando documentos...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (vm.progressMessage.isNotEmpty()) {
                                Text(
                                    vm.progressMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // RESUMEN DE PDFs PROCESADOS
            if (vm.pdfDocuments.any { it.isProcessed }) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "📊 Resumen de Procesamiento",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        vm.pdfDocuments.filter { it.isProcessed }.forEach { pdfDoc ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    pdfDoc.fileName,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "${pdfDoc.medicationCount} med(s)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // LISTA DE MEDICAMENTOS DETECTADOS
            if (vm.parsedMedications.isNotEmpty() && !vm.processing) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "💊 Medicamentos Detectados (${vm.parsedMedications.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    IconButton(onClick = { vm.addNewMedication() }) {
                        Icon(Icons.Filled.Add, "Agregar medicamento")
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Agrupa medicamentos por archivo fuente
                val groupedMeds = vm.parsedMedications.groupBy { it.sourceFile }

                groupedMeds.forEach { (sourceFile, meds) ->
                    Text(
                        "📄 $sourceFile",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    meds.forEachIndexed { _, med ->
                        val globalIndex = vm.parsedMedications.indexOf(med)
                        MedicationEditCard(
                            medication = med,
                            onUpdate = { updated -> vm.updateMedication(globalIndex, updated) },
                            onDelete = { vm.removeMedication(globalIndex) }
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }

            // TEXTOS EXTRAÍDOS (COLAPSABLES)
            if (vm.pdfDocuments.any { it.extractedText != null }) {
                vm.pdfDocuments.filter { it.extractedText != null }.forEach { pdfDoc ->
                    var showText by remember { mutableStateOf(false) }

                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        onClick = { showText = !showText }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "📝 Texto Extraído",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        pdfDoc.fileName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                                Icon(
                                    if (showText) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = null
                                )
                            }

                            if (showText) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                Text(
                                    pdfDoc.extractedText!!,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(8.dp))
            }

            // BOTÓN GUARDAR TODOS
            if (vm.parsedMedications.isNotEmpty() && !vm.processing) {
                Button(
                    enabled = !vm.uploading,
                    onClick = {
                        val userId = FirebaseAuth.getInstance().currentUser?.uid

                        if (userId.isNullOrBlank()) {
                            Toast.makeText(ctx, "❌ Usuario no autenticado", Toast.LENGTH_LONG).show()
                            return@Button
                        }

                        if (activity != null) {
                            // ⬇️⬇️⬇️  AHORA GUARDAMOS AGRUPADO POR PRESCRIPCIÓN  ⬇️⬇️⬇️
                            vm.saveAllMedicationsGroupedAsPrescription(userId) { ok, msg ->
                                activity.runOnUiThread {
                                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                                    if (ok) finish()
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (vm.uploading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                            if (vm.progressMessage.isNotEmpty()) {
                                Spacer(Modifier.width(8.dp))
                                Text(vm.progressMessage)
                            }
                        }
                    } else {
                        Icon(Icons.Filled.Save, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Guardar ${vm.parsedMedications.size} Medicamento(s)")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PdfDocumentCard(
    pdfDoc: PdfDocument,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                pdfDoc.isProcessing -> MaterialTheme.colorScheme.secondaryContainer
                pdfDoc.isProcessed -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Description,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = when {
                    pdfDoc.isProcessing -> MaterialTheme.colorScheme.onSecondaryContainer
                    pdfDoc.isProcessed -> MaterialTheme.colorScheme.onTertiaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    pdfDoc.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                when {
                    pdfDoc.isProcessing -> {
                        Text(
                            "Procesando...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                    }
                    pdfDoc.isProcessed -> {
                        Text(
                            "✅ ${pdfDoc.medicationCount} medicamento(s) detectado(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                        )
                    }
                    else -> {
                        Text(
                            "Listo para procesar",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            if (!pdfDoc.isProcessing) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Close,
                        "Eliminar",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MedicationEditCard(
    medication: MedicationInfo,
    onUpdate: (MedicationInfo) -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf(medication.name) }
    var dose by remember { mutableStateOf(medication.doseMg.toString()) }
    var frequency by remember { mutableStateOf(medication.frequencyHours.toString()) }
    var prescriptionId by remember { mutableStateOf(medication.prescriptionId) }

    // Calcula duración en días
    val durationDays = remember(medication.startDate, medication.endDate) {
        val diffInMillis = medication.endDate.time - medication.startDate.time
        (diffInMillis / (1000 * 60 * 60 * 24)).toInt()
    }
    var duration by remember { mutableStateOf(durationDays.toString()) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        name.ifBlank { "Nuevo medicamento" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (medication.sourceFile.isNotEmpty()) {
                        Text(
                            "Origen: ${medication.sourceFile}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                        )
                    }
                }

                Row {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.Edit,
                            contentDescription = if (expanded) "Contraer" else "Editar"
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, "Eliminar", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // NOMBRE DEL MEDICAMENTO
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre del medicamento") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.MedicalServices, null) }
                )

                Spacer(Modifier.height(8.dp))

                // DOSIS Y FRECUENCIA
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = dose,
                        onValueChange = { if (it.all { c -> c.isDigit() }) dose = it },
                        label = { Text("Dosis (mg)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        leadingIcon = { Text("💊", style = MaterialTheme.typography.titleMedium) }
                    )

                    Spacer(Modifier.width(8.dp))

                    OutlinedTextField(
                        value = frequency,
                        onValueChange = { if (it.all { c -> c.isDigit() }) frequency = it },
                        label = { Text("Cada (horas)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        leadingIcon = { Text("⏰", style = MaterialTheme.typography.titleMedium) }
                    )
                }

                Spacer(Modifier.height(8.dp))

                // DURACIÓN
                OutlinedTextField(
                    value = duration,
                    onValueChange = { if (it.all { c -> c.isDigit() }) duration = it },
                    label = { Text("Duración (días)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Text("📅", style = MaterialTheme.typography.titleMedium) }
                )

                Spacer(Modifier.height(8.dp))

                // ID PRESCRIPCIÓN
                OutlinedTextField(
                    value = prescriptionId,
                    onValueChange = { prescriptionId = it },
                    label = { Text("ID Prescripción") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Text("📋", style = MaterialTheme.typography.titleMedium) }
                )

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        val durationInt = duration.toIntOrNull() ?: durationDays
                        val newEndDate = Calendar.getInstance().apply {
                            time = medication.startDate
                            add(Calendar.DAY_OF_YEAR, durationInt)
                        }.time

                        val updated = medication.copy(
                            name = name.ifBlank { "Medicamento sin nombre" },
                            doseMg = dose.toIntOrNull() ?: 0,
                            frequencyHours = frequency.toIntOrNull() ?: 24,
                            prescriptionId = prescriptionId,
                            endDate = newEndDate
                        )
                        onUpdate(updated)
                        expanded = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Check, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Aplicar cambios")
                }
            } else {
                Spacer(Modifier.height(8.dp))

                // VISTA RESUMIDA CON TODOS LOS CAMPOS
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "💊 Dosis:",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(100.dp)
                        )
                        Text(
                            "${medication.doseMg} mg",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "⏰ Frecuencia:",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(100.dp)
                        )
                        Text(
                            "Cada ${medication.frequencyHours} horas",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "📅 Duración:",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(100.dp)
                        )
                        Text(
                            "$durationDays días",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "📋 Prescripción:",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(100.dp)
                        )
                        Text(
                            medication.prescriptionId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}
