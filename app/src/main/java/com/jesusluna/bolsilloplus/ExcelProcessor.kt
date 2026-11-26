package com.jesusluna.bolsilloplus

import android.content.Context
import android.net.Uri
import com.opencsv.CSVParserBuilder
import com.opencsv.CSVReaderBuilder
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

/**
 * Procesador universal de extractos bancarios (.xlsx / .xls / .csv).
 *
 * Diseño:
 *  1. Detección de cabecera por scoring de familias de keywords (no por nombre exacto).
 *  2. Mapeo de columnas por sinónimos, con prioridades para fecha.
 *  3. Parseo tolerante: fecha numérica Excel, varios formatos string; importe con paréntesis,
 *     miles/decimal español/anglosajón, EUR/€.
 *  4. CSV: autodetección de delimitador y encoding.
 *  5. Best-effort: las filas que no parsean se descartan sin reventar; se loguea motivo.
 *
 * Las funciones de companion (detectHeaderRow, buildColumnMapping, parseAmount, parseDate,
 * stableId) son internas y testables sin Context.
 */
class ExcelProcessor(private val context: Context) {

    // ─── Data classes ─────────────────────────────────────────────────────────

    data class ColumnMapping(
        val fechaIdx: Int,
        val conceptoIdx: Int,
        val importeIdx: Int?,   // null cuando se usa cargo + abono
        val cargoIdx: Int?,
        val abonoIdx: Int?,
        val saldoIdx: Int?
    )

    // ─── Public entry point ───────────────────────────────────────────────────

    fun processExcel(uri: Uri): ExcelProcessResult {
        return try {
            val mimeType = context.contentResolver.getType(uri)
            val fileName = getFileName(uri)
            when {
                fileName?.endsWith(".csv", ignoreCase = true) == true ||
                        mimeType == "text/csv" ||
                        mimeType == "text/comma-separated-values" -> processCSV(uri)

                fileName?.endsWith(".xlsx", ignoreCase = true) == true ||
                        fileName?.endsWith(".xls", ignoreCase = true) == true ||
                        mimeType?.contains("spreadsheet") == true ||
                        mimeType?.contains("excel") == true -> processXLSX(uri)

                else -> ExcelProcessResult(
                    success = false,
                    errorMessage = "Formato no soportado. Usa .xlsx, .xls o .csv"
                )
            }
        } catch (e: Exception) {
            ExcelProcessResult(success = false, errorMessage = "Error al procesar archivo: ${e.message}")
        }
    }

    // ─── XLSX ─────────────────────────────────────────────────────────────────

    private fun processXLSX(uri: Uri): ExcelProcessResult {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return ExcelProcessResult(success = false, errorMessage = "No se pudo abrir el archivo")

            val workbook = WorkbookFactory.create(inputStream)
            val sheet = workbook.getSheetAt(0)

            // Convertir todas las filas a List<String> para el pipeline unificado
            val rawRows: List<List<String>> = (0..sheet.lastRowNum).map { ri ->
                val row = sheet.getRow(ri) ?: return@map emptyList()
                (0 until row.lastCellNum).map { ci -> row.getCell(ci)?.toString()?.trim() ?: "" }
            }
            val poiRows = (0..sheet.lastRowNum).map { sheet.getRow(it) }

            val headerIndex = detectHeaderRow(rawRows)
                ?: return ExcelProcessResult(
                    success = false,
                    errorMessage = "No se encontró la fila de cabecera. El archivo debe tener " +
                            "columnas de Fecha, Concepto/Descripción e Importe."
                )

            val mapping = buildColumnMapping(rawRows[headerIndex])
                ?: return ExcelProcessResult(
                    success = false,
                    errorMessage = "No se encontraron las columnas mínimas (Fecha + Concepto + Importe). " +
                            "Cabecera detectada: ${rawRows[headerIndex].filter { it.isNotBlank() }}"
                )

            log("XLSX header fila $headerIndex: ${rawRows[headerIndex].filter { it.isNotBlank() }}")
            log("Mapping: $mapping")

            val transactions = mutableListOf<Transaction>()
            var discarded = 0

            for (ri in (headerIndex + 1)..sheet.lastRowNum) {
                val raw = rawRows.getOrNull(ri) ?: continue
                if (raw.all { it.isBlank() }) continue
                val poi = poiRows.getOrNull(ri)
                val tx = parseXLSXRow(raw, poi, mapping, ri)
                if (tx != null) transactions.add(tx) else discarded++
            }

            workbook.close()
            inputStream.close()

            log("XLSX importadas: ${transactions.size}, descartadas: $discarded")
            logSampleRows(transactions)

            ExcelProcessResult(success = true, transactions = transactions, newTransactions = transactions.size)
        } catch (e: Exception) {
            ExcelProcessResult(success = false, errorMessage = "Error al leer Excel: ${e.message}")
        }
    }

    // ─── CSV ──────────────────────────────────────────────────────────────────

    private fun processCSV(uri: Uri): ExcelProcessResult {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return ExcelProcessResult(success = false, errorMessage = "No se pudo abrir el archivo")

            val rawBytes = inputStream.readBytes()
            inputStream.close()

            // 1) Detectar charset: UTF-8 (con BOM) o ISO-8859-1.
            //    toString(charset) nunca lanza excepción; U+FFFD indica bytes inválidos en UTF-8.
            val detectedCharset = detectCharset(rawBytes)
            val decodedText = rawBytes.toString(detectedCharset).trimStart('\uFEFF')

            // 2) Detectar delimitador con el texto ya correctamente decodificado
            val firstNonEmpty = decodedText.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
            val delimiter = detectDelimiter(firstNonEmpty)

            // 3) Crear el reader con el charset detectado (no siempre UTF-8)
            val csvParser = CSVParserBuilder().withSeparator(delimiter).build()
            val reader = CSVReaderBuilder(InputStreamReader(rawBytes.inputStream(), detectedCharset))
                .withCSVParser(csvParser)
                .build()
            val allCsvRows = reader.readAll()
            reader.close()

            val rawRows: List<List<String>> = allCsvRows.map { arr -> arr.map { it.trim() } }

            if (rawRows.isEmpty()) {
                return ExcelProcessResult(success = false, errorMessage = "El archivo CSV está vacío")
            }

            val headerIndex = detectHeaderRow(rawRows)
                ?: return ExcelProcessResult(
                    success = false,
                    errorMessage = "No se encontró la fila de cabecera en el CSV. " +
                            "¿Delimitador correcto? (detectado: '$delimiter')"
                )

            val mapping = buildColumnMapping(rawRows[headerIndex])
                ?: return ExcelProcessResult(
                    success = false,
                    errorMessage = "No se encontraron las columnas mínimas. " +
                            "Cabecera: ${rawRows[headerIndex].filter { it.isNotEmpty() }}"
                )

            log("CSV delimiter='$delimiter' header fila $headerIndex: ${rawRows[headerIndex].filter { it.isNotBlank() }}")
            log("Mapping: $mapping")

            val transactions = mutableListOf<Transaction>()
            var discarded = 0

            for (ri in (headerIndex + 1) until rawRows.size) {
                val row = rawRows[ri]
                if (row.all { it.isBlank() }) continue
                val tx = parseCSVRow(row, mapping, ri)
                if (tx != null) transactions.add(tx) else discarded++
            }

            log("CSV importadas: ${transactions.size}, descartadas: $discarded")
            logSampleRows(transactions)

            ExcelProcessResult(success = true, transactions = transactions, newTransactions = transactions.size)
        } catch (e: Exception) {
            ExcelProcessResult(success = false, errorMessage = "Error al leer CSV: ${e.message}")
        }
    }

    // ─── Row parsing XLSX ─────────────────────────────────────────────────────

    private fun parseXLSXRow(
        raw: List<String>,
        poi: org.apache.poi.ss.usermodel.Row?,
        mapping: ColumnMapping,
        rowIndex: Int
    ): Transaction? {
        return try {
            // Fecha: prefiere celda POI numérica/fecha sobre string
            val date = run {
                val cell = poi?.getCell(mapping.fechaIdx)
                when {
                    cell != null && cell.cellType == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell) ->
                        cell.dateCellValue
                    cell != null && cell.cellType == CellType.NUMERIC ->
                        DateUtil.getJavaDate(cell.numericCellValue)
                    else -> parseDate(raw.getOrElse(mapping.fechaIdx) { "" })
                }
            } ?: run {
                log("Fila $rowIndex descartada: fecha no parseable '${raw.getOrElse(mapping.fechaIdx) { "" }}'")
                return null
            }

            // Concepto
            val concept = raw.getOrElse(mapping.conceptoIdx) { "" }.trim()
            if (concept.isBlank()) {
                log("Fila $rowIndex descartada: concepto vacío")
                return null
            }

            // Importe
            val amount = if (mapping.importeIdx != null) {
                val cell = poi?.getCell(mapping.importeIdx)
                when {
                    cell != null && cell.cellType == CellType.NUMERIC -> cell.numericCellValue
                    else -> parseAmount(raw.getOrElse(mapping.importeIdx) { "" })
                }
            } else {
                resolveDebitCredit(
                    raw.getOrElse(mapping.cargoIdx ?: -1) { "" },
                    raw.getOrElse(mapping.abonoIdx ?: -1) { "" }
                )
            } ?: run {
                log("Fila $rowIndex descartada: importe no parseable '${raw.getOrElse(mapping.importeIdx ?: -1) { "" }}'")
                return null
            }

            // Saldo (opcional)
            val balance = mapping.saldoIdx?.let { idx ->
                val cell = poi?.getCell(idx)
                when {
                    cell != null && cell.cellType == CellType.NUMERIC -> cell.numericCellValue
                    else -> parseAmount(raw.getOrElse(idx) { "" }) ?: 0.0
                }
            } ?: 0.0

            buildTransaction(date, concept, amount, balance, "Excel")
        } catch (e: Exception) {
            log("Fila $rowIndex excepción: ${e.message}")
            null
        }
    }

    // ─── Row parsing CSV ──────────────────────────────────────────────────────

    private fun parseCSVRow(
        row: List<String>,
        mapping: ColumnMapping,
        rowIndex: Int
    ): Transaction? {
        return try {
            val date = parseDate(row.getOrElse(mapping.fechaIdx) { "" })
                ?: run {
                    log("CSV fila $rowIndex descartada: fecha '${row.getOrElse(mapping.fechaIdx) { "" }}'")
                    return null
                }

            val concept = row.getOrElse(mapping.conceptoIdx) { "" }.trim()
            if (concept.isBlank()) {
                log("CSV fila $rowIndex descartada: concepto vacío")
                return null
            }

            val amount = if (mapping.importeIdx != null) {
                parseAmount(row.getOrElse(mapping.importeIdx) { "" })
            } else {
                resolveDebitCredit(
                    row.getOrElse(mapping.cargoIdx ?: -1) { "" },
                    row.getOrElse(mapping.abonoIdx ?: -1) { "" }
                )
            } ?: run {
                log("CSV fila $rowIndex descartada: importe no parseable")
                return null
            }

            val balance = mapping.saldoIdx?.let { parseAmount(row.getOrElse(it) { "" }) ?: 0.0 } ?: 0.0

            buildTransaction(date, concept, amount, balance, "CSV")
        } catch (e: Exception) {
            log("CSV fila $rowIndex excepción: ${e.message}")
            null
        }
    }

    // ─── Helpers privados ─────────────────────────────────────────────────────

    private fun buildTransaction(
        date: Date, concept: String, amount: Double, balance: Double, source: String
    ) = Transaction(
        id = stableId(date, concept, amount),
        date = date,
        amount = amount,
        concept = concept,
        category = "Sin categoría",
        balance = balance,
        type = if (amount < 0) TransactionType.EXPENSE else TransactionType.INCOME,
        originalText = "$source: $concept"
    )

    private fun resolveDebitCredit(cargoStr: String, abonoStr: String): Double? =
        Companion.resolveDebitCredit(cargoStr, abonoStr)

    private fun log(msg: String) = android.util.Log.d(TAG, msg)

    private fun logSampleRows(txs: List<Transaction>) {
        txs.take(3).forEachIndexed { i, tx ->
            log("Muestra[$i]: fecha=${tx.date} concepto='${tx.concept}' importe=${tx.amount}")
        }
    }

    private fun getFileName(uri: Uri): String? {
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) it.getString(idx) else null
                } else null
            }
        } catch (_: Exception) { null }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COMPANION — lógica pura, testable sin Context
    // ═══════════════════════════════════════════════════════════════════════════

    companion object {

        private const val TAG = "ExcelImport"
        const val MAX_SCAN_ROWS = 60

        // ── Familias para scoring de fila de cabecera ──────────────────────────
        private val FAMILIA_FECHA = listOf(
            "fecha", "date", "booking", "value date", "fecha valor",
            "fecha operacion", "fecha operacion"
        )
        private val FAMILIA_CONCEPTO = listOf(
            "concepto", "descripcion", "description", "detalle", "details",
            "narrative", "movimiento", "beneficiario",
            // Sinónimos adicionales para cobertura universal de bancos.
            // NOTA: "referencia" excluida intencionalmente — es demasiado genérica y aparece
            // en filas de metadatos bancarios (ej. "Fecha | Referencia | Importe") sin que
            // haya una columna de descripción real, lo que provoca falsos positivos.
            "texto", "concept", "observaciones", "glosa", "asunto", "narracion"
        )
        private val FAMILIA_IMPORTE = listOf(
            "importe", "amount", "cantidad", "cargo", "abono", "debit", "credit"
        )

        // ── Listas de prioridad para fecha (usadas en buildColumnMapping) ──────
        // P3 (mejor): columna explícitamente de operación
        private val FECHA_OP_KW = listOf(
            "fecha operacion", "booking date", "booking", "operation date", "f.operacion"
        )
        // P2: fecha genérica (sin calificador) — probablemente operación
        private val FECHA_GENERIC_KW = listOf("fecha", "date")
        // P1 (peor): fecha valor / liquidación
        private val FECHA_VALOR_KW = listOf("fecha valor", "value date", "valor")

        // ── Listas para mapeo de concepto, importe, saldo ─────────────────────
        private val CONCEPTO_KW = listOf(
            "concepto", "descripcion", "description", "detalle", "details",
            "narrative", "movimiento", "beneficiario",
            // Sinónimos adicionales (sin "referencia": demasiado ambigua para mapeo)
            "texto", "concept", "observaciones", "glosa", "asunto", "narracion"
        )
        private val IMPORTE_SINGLE_KW = listOf("importe", "amount", "cantidad", "monto")
        private val CARGO_KW  = listOf("cargo", "debit", "debito", "salida")
        private val ABONO_KW  = listOf("abono", "credit", "credito", "entrada", "haber")
        private val SALDO_KW  = listOf("saldo", "balance", "disponible")

        // ─── Normalización ────────────────────────────────────────────────────

        /** Lowercase sin acentos para comparar cabeceras robustamente. */
        fun normalize(s: String): String = s.trim().lowercase()
            .replace('á', 'a').replace('é', 'e').replace('í', 'i')
            .replace('ó', 'o').replace('ú', 'u').replace('ü', 'u').replace('ñ', 'n')

        // ─── Detección de cabecera ────────────────────────────────────────────

        /**
         * Escanea las primeras MAX_SCAN_ROWS filas y devuelve el índice de la que
         * tiene mayor score de familias de keywords (≥2 familias = cabecera válida).
         */
        fun detectHeaderRow(rows: List<List<String>>): Int? {
            val limit = minOf(rows.size, MAX_SCAN_ROWS)
            var bestIndex: Int? = null
            var bestScore = 0
            for (i in 0 until limit) {
                val cells = rows[i].map { normalize(it) }
                val score = scoreHeaderFamilies(cells)
                // Requiere ≥2 familias Y que concepto esté presente.
                // Evita falsos positivos como "Fecha + Saldo" en metadatos bancarios.
                val hasConcepto = cells.any { c -> FAMILIA_CONCEPTO.any { c.contains(it) } }
                if (score >= 2 && hasConcepto && score > bestScore) {
                    bestScore = score; bestIndex = i
                }
            }
            return if (bestScore >= 2) bestIndex else null
        }

        private fun scoreHeaderFamilies(cells: List<String>): Int {
            var score = 0
            if (cells.any { c -> FAMILIA_FECHA.any { c.contains(it) } }) score++
            if (cells.any { c -> FAMILIA_CONCEPTO.any { c.contains(it) } }) score++
            if (cells.any { c -> FAMILIA_IMPORTE.any { c.contains(it) } }) score++
            return score
        }

        // ─── Mapeo de columnas ────────────────────────────────────────────────

        /**
         * Construye el mapping de columnas a partir de la fila de cabecera.
         *
         * Fecha: prioridad P3 (operacion) > P2 (genérica) > P1 (valor).
         * Importe: columna única ó par cargo/abono.
         * Retorna null si faltan los mínimos: fecha + concepto + (importe ó cargo/abono).
         */
        fun buildColumnMapping(rawHeaders: List<String>): ColumnMapping? {
            val h = rawHeaders.map { normalize(it) }

            // Fecha: iteramos todos y guardamos el de mayor prioridad
            var fechaIdx: Int? = null
            var fechaPrio = 0
            h.forEachIndexed { i, hdr ->
                // Orden: VALOR antes que GENERIC para que "fecha valor" no coincida con "fecha" genérico
                val p = when {
                    FECHA_OP_KW.any { hdr.contains(it) }    -> 3
                    FECHA_VALOR_KW.any { hdr.contains(it) } -> 1
                    FECHA_GENERIC_KW.any { hdr.contains(it) } -> 2
                    else -> 0
                }
                if (p > fechaPrio) { fechaPrio = p; fechaIdx = i }
            }

            // Concepto: primer match
            val conceptoIdx = h.indexOfFirst { hdr -> CONCEPTO_KW.any { hdr.contains(it) } }
                .takeIf { it >= 0 }

            // Importe único
            val importeIdx = h.indexOfFirst { hdr -> IMPORTE_SINGLE_KW.any { hdr.contains(it) } }
                .takeIf { it >= 0 }

            // Cargo y abono (columnas separadas)
            val cargoIdx = h.indexOfFirst { hdr -> CARGO_KW.any { hdr.contains(it) } }
                .takeIf { it >= 0 }
            val abonoIdx = h.indexOfFirst { hdr -> ABONO_KW.any { hdr.contains(it) } }
                .takeIf { it >= 0 }

            // Saldo (opcional)
            val saldoIdx = h.indexOfFirst { hdr -> SALDO_KW.any { hdr.contains(it) } }
                .takeIf { it >= 0 }

            // Validar mínimos
            val hasImporte = importeIdx != null || cargoIdx != null || abonoIdx != null
            if (fechaIdx == null || conceptoIdx == null || !hasImporte) return null

            return ColumnMapping(
                fechaIdx = fechaIdx!!,
                conceptoIdx = conceptoIdx,
                importeIdx = importeIdx,
                cargoIdx = cargoIdx,
                abonoIdx = abonoIdx,
                saldoIdx = saldoIdx
            )
        }

        // ─── Autodetección de delimitador CSV ────────────────────────────────

        /** Elige el delimitador más frecuente entre [; \t | ,] en la primera línea. */
        fun detectDelimiter(line: String): Char {
            val candidates = listOf(';', '\t', '|', ',')
            return candidates.maxByOrNull { c -> line.count { it == c } } ?: ','
        }

        // ─── Parseo de importe ────────────────────────────────────────────────

        /**
         * Parsea importes bancarios en varios formatos:
         *  - Español:     "1.234,56 €" | "-1.234,56" | "0,00"
         *  - Anglosajón:  "1,234.56"   | "1000.5"
         *  - Entero ES:   "1.000"      (punto = miles, sin decimal)
         *  - Con EUR/€/$: "99,99EUR"   → 99.99
         *  - Paréntesis:  "(123,45)"   → -123.45  (convención contable)
         */
        internal fun parseAmount(amountStr: String): Double? {
            return try {
                var cleaned = amountStr
                    .replace("€", "").replace("EUR", "")
                    .replace("$", "").replace("USD", "")
                    .replace("\u00A0", "")  // espacio no separable
                    .replace(" ", "")
                    .trim()

                // Paréntesis → negativo: (123,45) → -123,45
                if (cleaned.startsWith("(") && cleaned.endsWith(")")) {
                    cleaned = "-" + cleaned.substring(1, cleaned.length - 1)
                }

                if (cleaned.isEmpty() || cleaned == "-" || cleaned == "+") return null

                val normalized = when {
                    ',' in cleaned && '.' in cleaned -> {
                        // Determinar separadores por posición del último de cada uno
                        val lastComma = cleaned.lastIndexOf(',')
                        val lastDot   = cleaned.lastIndexOf('.')
                        if (lastComma > lastDot)
                        // "1.234,56" — coma es decimal (formato español)
                            cleaned.replace(".", "").replace(",", ".")
                        else
                        // "1,234.56" — punto es decimal (formato anglosajón)
                            cleaned.replace(",", "")
                    }
                    ',' in cleaned -> {
                        // Solo coma: distinguir miles anglosajón ("1,000" → 1000)
                        //            de decimal español ("45,99" → 45.99)
                        // Heurística: si encaja ^\d{1,3}(,\d{3})+$ → coma = miles (quitar comas).
                        //             si encaja ^\d+,\d{1,2}$        → coma = decimal (coma → punto).
                        //             en cualquier otro caso          → tratar como decimal.
                        val withoutSign = cleaned.removePrefix("-").removePrefix("+")
                        when {
                            Regex("""^\d{1,3}(,\d{3})+$""").matches(withoutSign) ->
                                cleaned.replace(",", "")          // "1,000" → "1000"
                            Regex("""^\d+,\d{1,2}$""").matches(withoutSign) ->
                                cleaned.replace(",", ".")         // "45,99" → "45.99"
                            else ->
                                cleaned.replace(",", ".")         // fallback: tratar coma como decimal
                        }
                    }
                    '.' in cleaned -> {
                        // Solo punto: miles ("1.000") o decimal ("1000.5")
                        val withoutSign = cleaned.removePrefix("-").removePrefix("+")
                        if (Regex("""^\d{1,3}(\.\d{3})+$""").matches(withoutSign))
                            cleaned.replace(".", "")   // miles: "1.000" → "1000"
                        else
                            cleaned                    // decimal: "1000.5"
                    }
                    else -> cleaned
                }

                normalized.toDoubleOrNull()
            } catch (_: Exception) { null }
        }

        // ─── Parseo de fecha ──────────────────────────────────────────────────

        /**
         * Intenta parsear la cadena de fecha en los formatos más habituales
         * de extractos bancarios españoles y europeos.
         */
        internal fun parseDate(dateStr: String): Date? {
            if (dateStr.isBlank()) return null

            // Algunos bancos añaden hora: "30/09/2025 | 12:44:11" → quedarse con la parte de fecha
            val datePart = dateStr.substringBefore("|").substringBefore("T").trim()

            val formats = listOf(
                "dd/MM/yyyy",
                "d/M/yyyy",
                "dd/MM/yy",
                "d/M/yy",
                "yyyy-MM-dd",
                "dd-MM-yyyy",
                "d-M-yyyy",
                "dd.MM.yyyy",
                "yyyy/MM/dd",
                "yyyyMMdd",
                "MM/dd/yyyy",
                "dd MMM yyyy",
                "dd/MM/yyyy HH:mm:ss",
                "dd/MM/yyyy HH:mm"
            )
            for (pattern in formats) {
                try {
                    val locale = if (pattern.contains("MMM")) Locale("es", "ES") else Locale.getDefault()
                    val sdf = SimpleDateFormat(pattern, locale)
                    sdf.isLenient = false
                    val result = sdf.parse(datePart)
                    if (result != null) return result
                } catch (_: Exception) { }
            }
            // Fallback con locale inglés para "MMM"
            try {
                val sdf = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
                sdf.isLenient = false
                return sdf.parse(datePart)
            } catch (_: Exception) { }
            return null
        }

        // ─── Cargo / Abono ───────────────────────────────────────────────────

        /**
         * Convierte columnas separadas de cargo (salida) y abono (entrada) en un importe neto.
         *
         * Convención: ambas columnas almacenan el valor en positivo (el banco ya las separa
         * en columnas distintas). El resultado es abono − cargo (negativo si hay más gasto).
         *
         * Casos:
         *  - Solo cargo (abono vacío): −cargo → negativo.
         *  - Solo abono (cargo vacío): +abono → positivo.
         *  - Ambos presentes (devolución parcial, etc.): abono − cargo.
         *  - Ambos vacíos: null (fila sin importe → se descarta).
         */
        /**
         * Convierte columnas separadas de cargo (salida) y abono (entrada) en un importe neto.
         *
         * Robustez "cualquier banco":
         *  - Parsea ambos valores con parseAmount() → pueden venir positivos o negativos.
         *  - Trabaja con magnitudes (abs) para normalizar la convención del banco.
         *  - Solo cargo  → resultado negativo  (-abs(cargo))
         *  - Solo abono  → resultado positivo  (+abs(abono))
         *  - Ambos       → neto = abs(abono) - abs(cargo)
         *  - Ambos vacíos / sin parsear → null (fila se descarta)
         *
         * Por qué abs():
         *  "cargoParsed < 0" (banco ya pone signo) y "cargoParsed > 0" (banco solo magnitud)
         *  producen el mismo resultado: -abs() garantiza negativo en cualquier caso.
         */
        internal fun resolveDebitCredit(cargoStr: String, abonoStr: String): Double? {
            if (cargoStr.isBlank() && abonoStr.isBlank()) return null
            val cargoParsed = if (cargoStr.isBlank()) null else parseAmount(cargoStr)
            val abonoParsed = if (abonoStr.isBlank()) null else parseAmount(abonoStr)
            return when {
                cargoParsed != null && abonoParsed != null ->
                    kotlin.math.abs(abonoParsed) - kotlin.math.abs(cargoParsed)
                cargoParsed != null -> -kotlin.math.abs(cargoParsed)
                abonoParsed != null ->  kotlin.math.abs(abonoParsed)
                else -> null  // ambos fallaron al parsear
            }
        }

        // ─── Detección de charset CSV ─────────────────────────────────────────

        /**
         * Detecta si [rawBytes] es UTF-8 válido (sin caracteres de reemplazo U+FFFD).
         * Retorna Charsets.UTF_8 o Charsets.ISO_8859_1.
         * Se usa para garantizar que el CSVReader lee con el mismo charset que se detectó.
         */
        internal fun detectCharset(rawBytes: ByteArray): java.nio.charset.Charset {
            val utf8 = rawBytes.toString(Charsets.UTF_8)
            return if ('\uFFFD' !in utf8) Charsets.UTF_8 else Charsets.ISO_8859_1
        }

        // ─── ID estable ───────────────────────────────────────────────────────

        /**
         * ID determinista basado en (fecha, concepto, importe, occurrenceIndex).
         * Evita colisiones por System.currentTimeMillis() en bucles rápidos.
         */
        internal fun stableId(
            date: Date, concept: String, amount: Double, occurrenceIndex: Int = 0
        ): Long {
            var h = 17L
            h = 31L * h + date.time
            h = 31L * h + concept.trim().lowercase().hashCode()
            h = 31L * h + amount.toBits()
            if (occurrenceIndex > 0) h = 31L * h + occurrenceIndex.toLong()
            return if (h == 0L) 1L else h
        }
    }
}
