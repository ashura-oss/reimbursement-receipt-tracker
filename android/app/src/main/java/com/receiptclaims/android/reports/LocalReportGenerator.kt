package com.receiptclaims.android.reports

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.receiptclaims.android.data.local.ExpenseAccounts
import com.receiptclaims.android.data.local.ClaimDetails
import com.receiptclaims.android.data.local.NormalizedPoint
import com.receiptclaims.android.data.local.ReceiptDetails
import com.receiptclaims.android.data.local.ReportDetailsCodec
import com.receiptclaims.android.data.local.SignatureStroke
import com.receiptclaims.android.data.local.StaffProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

data class ReportReceipt(
    val key: String,
    val amount: String,
    val currency: String,
    val exchangeRate: String,
    val sgdEquivalent: String,
    val date: String,
    val category: String,
    val description: String?,
    val note: String?,
    val imagePath: String,
    val name: String? = null,
    val expenseAccountId: String? = null,
    val details: ReceiptDetails = ReceiptDetails(),
)

data class ReportClaim(
    val id: Long,
    val title: String,
    val startDate: String?,
    val endDate: String?,
    val claimantName: String,
    val receipts: List<ReportReceipt>,
    val details: ClaimDetails = ClaimDetails(),
    val staffProfile: StaffProfile = StaffProfile(),
)

data class GeneratedReportFile(val type: String, val name: String, val mimeType: String, val file: File)

internal fun fitReportSignature(width: Float, height: Float): Pair<Float, Float> {
    require(width >= 0f && height >= 0f)
    val fittedHeight = minOf(height, width / 5f)
    return fittedHeight * 5f to fittedHeight
}

/** Creates all claim exports on-device and publishes them as one complete generation. */
class LocalReportGenerator @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun generate(claim: ReportClaim): List<GeneratedReportFile> = withContext(Dispatchers.IO) {
        require(claim.id != 0L) { "Claim id must be nonzero" }
        require(claim.receipts.size <= MAX_RECEIPTS) { "A report can contain at most $MAX_RECEIPTS receipts" }
        val orderedClaim = claim.copy(
            receipts = sortReceipts(claim.receipts),
            staffProfile = claim.details.staffProfile ?: claim.staffProfile,
        )
        // Validate every source image before creating any report files.
        orderedClaim.receipts.forEach { receipt ->
            require(numeric(receipt.amount) != null) { "Receipt ${receipt.key} has an invalid amount" }
            require(numeric(receipt.exchangeRate) != null) { "Receipt ${receipt.key} has an invalid exchange rate" }
            require(numeric(receipt.sgdEquivalent) != null) { "Receipt ${receipt.key} has an invalid SGD equivalent" }
            require(excelDate(receipt.date) != null) { "Receipt ${receipt.key} has an invalid date" }
        }
        var imageBytes = 0L
        val images = orderedClaim.receipts.map { receipt ->
            val info = validateImage(receipt)
            imageBytes += info.file.length()
            require(imageBytes <= MAX_TOTAL_IMAGE_BYTES) { "Receipt images exceed the 50 MiB report limit" }
            info
        }
        val reportRoot = File(context.filesDir, "reports/${claim.id}").apply { mkdirs() }
        val staging = File(reportRoot, ".${UUID.randomUUID()}.tmp").apply { mkdirs() }
        try {
            val workbook = File(staging, "expenses.xlsx")
            writeWorkbook(workbook, orderedClaim)
            writeDocx(File(staging, "receipt-images.docx"), orderedClaim, images)
            writePdf(File(staging, "combined.pdf"), orderedClaim, images, workbook)
            val finalDir = File(reportRoot, "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}")
            check(staging.renameTo(finalDir)) { "Could not publish completed report files" }
            listOf(
                GeneratedReportFile("XLSX", "expenses.xlsx", XLSX_MIME, File(finalDir, "expenses.xlsx")),
                GeneratedReportFile("DOCX", "receipt-images.docx", DOCX_MIME, File(finalDir, "receipt-images.docx")),
                GeneratedReportFile("PDF", "combined.pdf", PDF_MIME, File(finalDir, "combined.pdf")),
            )
        } catch (failure: Throwable) {
            staging.deleteRecursively()
            throw failure
        }
    }

    private data class ImageInfo(val file: File, val width: Int, val height: Int, val sample: Int)
    private data class PdfCellStyle(
        val fontSize: Float = 9f,
        val bold: Boolean = false,
        val fill: Int? = null,
        val horizontal: String = "left",
        val borders: Set<String> = emptySet(),
    )
    private data class PdfCellData(val text: String, val style: PdfCellStyle)
    private data class PdfRowData(val height: Float, val cells: Map<Int, PdfCellData>)
    private data class PdfSheetData(
        val rows: Map<Int, PdfRowData>,
        val columnWidths: List<Float>,
        val merges: Map<Int, Map<Int, Int>>,
        val covered: Set<Pair<Int, Int>>,
        val defaultRowHeight: Float,
    )

    private fun validateImage(receipt: ReportReceipt): ImageInfo {
        val file = File(receipt.imagePath)
        require(file.isFile && file.length() in 1..MAX_IMAGE_BYTES) { "Receipt ${receipt.key} has a missing or oversized image" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        require(options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= MAX_PIXELS) {
            "Receipt ${receipt.key} has an unsupported image size"
        }
        var sample = 1
        while (options.outWidth / sample > MAX_DECODE_EDGE || options.outHeight / sample > MAX_DECODE_EDGE) sample *= 2
        return ImageInfo(file, options.outWidth, options.outHeight, sample)
    }

    private fun decode(info: ImageInfo): Bitmap = BitmapFactory.decodeFile(info.file.absolutePath, BitmapFactory.Options().apply {
        inSampleSize = info.sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }) ?: error("Could not decode receipt image ${info.file.name}")

    private fun writeWorkbook(target: File, claim: ReportClaim) {
        val template = context.assets.open("reports/sanitizedtemplate.xlsx")
        val bytes = template.use { it.readBytes() }
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        // The original template uses non-sequential worksheet ids (sheet2 and sheet9).
        val claimSheetPath = "xl/worksheets/sheet2.xml"
        val sheetBytes = entries[claimSheetPath] ?: error("Bundled workbook is missing its claim sheet")
        val doc = parseXml(sheetBytes)
        stripCompatibilityMetadata(doc)
        val sheetData = doc.getElementsByTagNameNS(MAIN_NS, "sheetData").item(0) as Element
        val dataRows = claim.receipts.size.coerceAtLeast(1)
        val extras = (dataRows - 29).coerceAtLeast(0)
        if (extras > 0) expandTemplateRows(doc, sheetData, extras)
        entries["xl/workbook.xml"]?.let { entries["xl/workbook.xml"] = shiftWorkbookRanges(it, extras) }
        findRow(sheetData, 20)?.let { header ->
            setCell(doc, header, "K20", "Amount Excluding GST", null)
            setCell(doc, header, "L20", "GST Included", null)
            setCell(doc, header, "M20", "Currency", null)
            setCell(doc, header, "S20", "Classification", null)
        }
        // Remove any residual sample values in the template's receipt grid.
        for (r in 21 until 21 + dataRows) {
            val row = findRow(sheetData, r) ?: continue
            for (col in 1..19) setCell(doc, row, "${columnName(col)}$r", null, null)
        }
        claim.receipts.forEachIndexed { index, receipt ->
            val rowNum = 21 + index
            val row = findRow(sheetData, rowNum) ?: error("Workbook is missing receipt row $rowNum")
            val account = ExpenseAccounts.find(receipt.expenseAccountId)
            val gst = receipt.details.gstAmount?.trim()?.takeIf(String::isNotEmpty)?.let(::decimal)
            val gross = decimal(receipt.amount)
            require(gst == null || (gst.signum() >= 0 && gst <= gross)) { "Receipt ${receipt.key} has GST outside its gross amount" }
            val net = if (gst == null) gross else gross.subtract(gst)
            val profile = claim.details.staffProfile ?: claim.staffProfile
            setCell(doc, row, "A$rowNum", (index + 1).toString(), null)
            setCell(doc, row, "B$rowNum", excelDate(receipt.date), "numeric")
            setCell(doc, row, "C$rowNum", account?.code, "numeric")
            setCell(doc, row, "D$rowNum", receipt.details.travelStart, null)
            setCell(doc, row, "E$rowNum", receipt.details.travelEnd, null)
            setCell(doc, row, "F$rowNum", receipt.details.fromLocation, null)
            setCell(doc, row, "G$rowNum", receipt.details.toLocation, null)
            setCell(doc, row, "H$rowNum", receiptLabel(receipt), null)
            setCell(doc, row, "I$rowNum", receipt.details.purpose, null)
            setCell(doc, row, "J$rowNum", receipt.details.eventName, null)
            setCell(doc, row, "K$rowNum", net.stripTrailingZeros().toPlainString(), "numeric")
            setCell(doc, row, "L$rowNum", gst?.stripTrailingZeros()?.toPlainString(), "numeric")
            setCell(doc, row, "M$rowNum", receipt.currency, null)
            setCell(doc, row, "N$rowNum", numeric(receipt.exchangeRate), "numeric")
            setCell(doc, row, "O$rowNum", numeric(receipt.sgdEquivalent), "numeric")
            setCell(doc, row, "P$rowNum", (receipt.details.businessUnit ?: profile.businessUnit).trim().takeIf(String::isNotEmpty), null)
            setCell(doc, row, "Q$rowNum", (receipt.details.costCentre1 ?: profile.costCentre1).trim().takeIf(String::isNotEmpty), null)
            setCell(doc, row, "R$rowNum", (receipt.details.departmentCode ?: profile.departmentCode).trim().takeIf(String::isNotEmpty), null)
            setCell(doc, row, "S$rowNum", receipt.category, null)
            setRowHeight(row, receipt, existingRowHeight = row.getAttribute("ht").toDoubleOrNull() ?: 18.0)
        }
        writeExpenseAccountSummary(doc, sheetData, claim, lastData = 20 + claim.receipts.size.coerceAtLeast(1))
        // Formula totals only use SGD equivalents. Original currency amounts stay unsummed.
        val lastData = 20 + claim.receipts.size.coerceAtLeast(1)
        val totalRowNum = 50 + extras
        findRow(sheetData, totalRowNum)?.let { total ->
            setFormula(doc, total, "O$totalRowNum", "SUM(O21:O$lastData)", sumSgd(claim.receipts).stripTrailingZeros().toPlainString())
            val gstProvided = claim.receipts.any { !it.details.gstAmount.isNullOrBlank() }
            val currencies = claim.receipts.map { it.currency.trim().uppercase(Locale.ROOT) }
            if (currencies.isNotEmpty() && currencies.all { it == currencies.first() }) {
                val netTotal = claim.receipts.fold(BigDecimal.ZERO) { sum, receipt -> sum.add(decimal(receipt.amount).subtract(receipt.details.gstAmount?.trim()?.takeIf(String::isNotEmpty)?.let(::decimal) ?: BigDecimal.ZERO)) }
                val gstTotal = claim.receipts.fold(BigDecimal.ZERO) { sum, receipt -> sum.add(receipt.details.gstAmount?.trim()?.takeIf(String::isNotEmpty)?.let(::decimal) ?: BigDecimal.ZERO) }
                setFormula(doc, total, "K$totalRowNum", "SUM(K21:K$lastData)", netTotal.stripTrailingZeros().toPlainString())
                if (gstProvided) setFormula(doc, total, "L$totalRowNum", "SUM(L21:L$lastData)", gstTotal.stripTrailingZeros().toPlainString())
                else setCell(doc, total, "L$totalRowNum", null, null)
            } else {
                setCell(doc, total, "K$totalRowNum", null, null)
                setCell(doc, total, "L$totalRowNum", null, null)
            }
        }
        val staffProfile = claim.details.staffProfile ?: claim.staffProfile
        val staffName = claim.claimantName.trim()
        val staffPosition = (claim.details.position ?: staffProfile.position).trim()
        setCell(doc, findRow(sheetData, 5) ?: error("Workbook name row missing"), "C5", staffName, null)
        findRow(sheetData, 6)?.let { setCell(doc, it, "C6", staffPosition, null) }
        writeBusinessUnitSummary(doc, sheetData, claim.copy(staffProfile = staffProfile), lastData)
        val cashAdvance = claim.details.cashAdvance?.trim()?.takeIf(String::isNotEmpty)?.let(::decimal)
        require(cashAdvance == null || cashAdvance.signum() >= 0) { "Cash advance must not be negative" }
        val cashAdvanceRow = 51 + extras
        val payableRow = 52 + extras
        findRow(sheetData, cashAdvanceRow)?.let { setCell(doc, it, "O$cashAdvanceRow", cashAdvance?.stripTrailingZeros()?.toPlainString(), "numeric") }
        findRow(sheetData, payableRow)?.let { payable ->
            setFormula(doc, payable, "O$payableRow", "O$totalRowNum+O$cashAdvanceRow", sumSgd(claim.receipts).add(cashAdvance ?: BigDecimal.ZERO).stripTrailingZeros().toPlainString())
        }
        writePaymentAndApprovals(doc, sheetData, claim, extras)
        val signatures = approvalSignatures(claim.details)
        if (signatures.isNotEmpty()) {
            val relsPath = "xl/worksheets/_rels/sheet2.xml.rels"
            val rels = entries[relsPath]?.let(::parseXml) ?: parseXml(EMPTY_RELATIONSHIPS.toByteArray())
            val relRoot = rels.documentElement
            relRoot.appendChild(rels.createElementNS(PKG_REL_NS, "Relationship").apply {
                setAttribute("Id", "rId1")
                setAttribute("Type", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing")
                setAttribute("Target", "../drawings/drawing1.xml")
            })
            entries[relsPath] = serializeXml(rels)
            val drawing = createSignatureDrawing(signatures.map { it.copy(row = it.row + extras) })
            entries["xl/drawings/drawing1.xml"] = serializeXml(drawing)
            entries["xl/drawings/_rels/drawing1.xml.rels"] = serializeXml(createSignatureDrawingRels(signatures.size))
            signatures.forEachIndexed { index, signature -> entries["xl/media/signature${index + 1}.png"] = signature.bytes }
            appendPngContentType(entries)
            doc.documentElement.appendChild(doc.createElementNS(MAIN_NS, "drawing").apply {
                setAttributeNS(OFFICE_REL_NS, "r:id", "rId1")
            })
        }
        entries[claimSheetPath] = serializeXml(doc)
        ZipOutputStream(FileOutputStream(target)).use { out ->
            entries.forEach { (name, value) -> out.putNextEntry(ZipEntry(name)); out.write(value); out.closeEntry() }
        }
    }

    private fun expandTemplateRows(doc: Document, sheetData: Element, extras: Int) {
        val original = findRow(sheetData, 49) ?: error("Workbook's final receipt row is missing")
        val rowList = mutableListOf<Element>()
        val children = sheetData.childNodes
        for (i in 0 until children.length) if (children.item(i) is Element) rowList += children.item(i) as Element
        rowList.filter { (it.getAttribute("r").toIntOrNull() ?: 0) >= 50 }.forEach { row ->
            val old = row.getAttribute("r").toInt()
            row.setAttribute("r", (old + extras).toString())
            val cells = row.getElementsByTagNameNS(MAIN_NS, "c")
            for (i in 0 until cells.length) {
                val cell = cells.item(i) as Element
                val ref = cell.getAttribute("r")
                val col = ref.takeWhile(Char::isLetter)
                val rowNum = ref.drop(col.length).toIntOrNull() ?: continue
                cell.setAttribute("r", "$col${rowNum + extras}")
                val formula = cell.getElementsByTagNameNS(MAIN_NS, "f").item(0) as? Element ?: continue
                formula.textContent = shiftFormula(formula.textContent, extras)
            }
        }
        var before: Node? = findRow(sheetData, 50 + extras)
        repeat(extras) { offset ->
            val clone = original.cloneNode(true) as Element
            val rowNumber = 50 + offset
            clone.setAttribute("r", rowNumber.toString())
            val cells = clone.getElementsByTagNameNS(MAIN_NS, "c")
            for (i in 0 until cells.length) {
                val cell = cells.item(i) as Element
                val ref = cell.getAttribute("r")
                cell.setAttribute("r", ref.takeWhile(Char::isLetter) + rowNumber)
                while (cell.firstChild != null) cell.removeChild(cell.firstChild)
            }
            sheetData.insertBefore(clone, before)
        }
        val dimension = doc.getElementsByTagNameNS(MAIN_NS, "dimension").item(0) as? Element
        dimension?.setAttribute("ref", dimension.getAttribute("ref").replace(Regex("(\\$?[A-Z]+\\$?)(\\d+)$")) { "${it.groupValues[1]}${it.groupValues[2].toInt() + extras}" })
        val all = doc.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val element = all.item(i) as? Element ?: continue
            if (element.localName == "mergeCell" || element.localName == "autoFilter") {
                if (element.hasAttribute("ref")) element.setAttribute("ref", shiftRange(element.getAttribute("ref"), extras))
            }
            if (element.hasAttribute("sqref")) element.setAttribute("sqref", element.getAttribute("sqref").split(' ').joinToString(" ") { shiftRange(it, extras) })
            // The active cell must stay inside the shifted selection range.
            if (element.hasAttribute("activeCell")) element.setAttribute("activeCell", shiftRange(element.getAttribute("activeCell"), extras))
            if (element.localName == "f" && element.hasAttribute("ref")) element.setAttribute("ref", shiftRange(element.getAttribute("ref"), extras))
        }
        // Existing formula ranges that ended on row 49 include added receipt rows.
        val formulaNodes = doc.getElementsByTagNameNS(MAIN_NS, "f")
        for (i in 0 until formulaNodes.length) {
            val f = formulaNodes.item(i) as Element
            f.textContent = f.textContent.replace(Regex("(\\$?[A-Z]+\\$?)49\\b"), "$1${49 + extras}")
        }
    }

    private fun shiftFormula(formula: String, amount: Int) = formula.replace(Regex("(\\$?[A-Z]+\\$?)(\\d+)")) { m ->
        val row = m.groupValues[2].toInt()
        if (row >= 50) "${m.groupValues[1]}${row + amount}" else m.value
    }

    private fun shiftRange(value: String, amount: Int): String = value.replace(Regex("(\\$?[A-Z]+\\$?)(\\d+)")) { m ->
        val row = m.groupValues[2].toIntOrNull() ?: return@replace m.value
        if (row >= 50) "${m.groupValues[1]}${row + amount}" else m.value
    }

    private fun shiftWorkbookRanges(bytes: ByteArray, amount: Int): ByteArray {
        val workbook = parseXml(bytes)
        workbook.documentElement.removeAttributeNS(MC_NS, "Ignorable")
        workbook.documentElement.removeAttributeNS(XMLNS_NS, "x15")
        val names = workbook.getElementsByTagNameNS(MAIN_NS, "definedName")
        for (i in 0 until names.length) {
            val name = names.item(i) as Element
            val shifted = shiftRange(name.textContent, amount)
            var updated = shifted.replace(Regex(":(\\$?)P(\\$?\\d+)")) { ":${it.groupValues[1]}S${it.groupValues[2]}" }
            if (name.getAttribute("name") == "_xlnm.Print_Area") {
                updated = updated.replace(Regex("(:\\$?S\\$?)(\\d+)$")) { "${it.groupValues[1]}${it.groupValues[2].toInt() + 1}" }
            }
            name.textContent = updated
        }
        workbook.getElementsByTagNameNS(MAIN_NS, "calcPr").item(0)?.let { calc ->
            (calc as Element).setAttribute("fullCalcOnLoad", "1")
            calc.setAttribute("forceFullCalc", "1")
        }
        return serializeXml(workbook)
    }

    private fun setFormula(doc: Document, row: Element, ref: String, formula: String, cachedValue: String? = null) {
        val cell = getOrCreateCell(doc, row, ref)
        while (cell.firstChild != null) cell.removeChild(cell.firstChild)
        cell.removeAttribute("t")
        val f = doc.createElementNS(MAIN_NS, "f").apply { textContent = formula }
        val v = doc.createElementNS(MAIN_NS, "v").apply { if (cachedValue != null) textContent = cachedValue }
        cell.appendChild(f); cell.appendChild(v)
    }

    private fun writeExpenseAccountSummary(doc: Document, sheetData: Element, claim: ReportClaim, lastData: Int) {
        val accounts = ExpenseAccounts.all
        check(accounts.size == 14) { "Expense account catalog must match the bundled summary layout" }
        val receiptAccountIds = claim.receipts.map { it.expenseAccountId }
        val sgdAmounts = claim.receipts.map { it.sgdEquivalent }
        val leftAccounts = accounts.take(7)
        val rightAccounts = accounts.drop(7)
        leftAccounts.forEachIndexed { index, account ->
            val summaryRow = 9 + index
            findRow(sheetData, summaryRow)?.let { row ->
                setCell(doc, row, "B$summaryRow", account.code, "numeric")
                setCell(doc, row, "C$summaryRow", account.summaryText, null)
                val amount = AccountSummaryFormula.cachedSgdTotal(account.id, receiptAccountIds, sgdAmounts)
                setFormula(doc, row, "F$summaryRow", "SUMIF(\$C\$21:\$C\$$lastData,B$summaryRow,\$O\$21:\$O\$$lastData)", amount.stripTrailingZeros().toPlainString())
            }
        }
        val duplicateCodes = rightAccounts.groupBy { it.code }.filterValues { it.size > 1 }.keys
        rightAccounts.forEachIndexed { index, account ->
            val summaryRow = 9 + index
            findRow(sheetData, summaryRow)?.let { row ->
                setCell(doc, row, "H$summaryRow", account.code, "numeric")
                setCell(doc, row, "I$summaryRow", account.summaryText, null)
                if (account.code in duplicateCodes) {
                    val formula = AccountSummaryFormula.formula(account.id, receiptAccountIds)
                    val amount = AccountSummaryFormula.cachedSgdTotal(account.id, receiptAccountIds, sgdAmounts)
                    setFormula(doc, row, "K$summaryRow", formula, amount.stripTrailingZeros().toPlainString())
                } else {
                    val amount = AccountSummaryFormula.cachedSgdTotal(account.id, receiptAccountIds, sgdAmounts)
                    setFormula(doc, row, "K$summaryRow", "SUMIF(\$C\$21:\$C\$$lastData,H$summaryRow,\$O\$21:\$O\$$lastData)", amount.stripTrailingZeros().toPlainString())
                }
            }
        }
        findRow(sheetData, 16)?.let { row ->
            setCell(doc, row, "F16", null, null)
            setFormula(doc, row, "K16", "SUM(F9:F15)+SUM(K9:K15)", sumSgd(claim.receipts.filter { ExpenseAccounts.find(it.expenseAccountId) != null }).stripTrailingZeros().toPlainString())
        }
    }

    private fun writeBusinessUnitSummary(doc: Document, sheetData: Element, claim: ReportClaim, lastData: Int) {
        val labels = ReportDetailsCodec.businessUnits
        labels.forEachIndexed { index, label ->
            val rowNum = 9 + index
            val row = findRow(sheetData, rowNum) ?: return@forEachIndexed
            val sum = claim.receipts.filter { receiptBusinessUnit(it, claim.staffProfile).equals(label, true) }
                .fold(BigDecimal.ZERO) { total, receipt -> total.add(decimal(receipt.sgdEquivalent)) }
            setFormula(doc, row, "N$rowNum", "SUMIF(\$P\$21:\$P\$$lastData,\$M\$$rowNum,\$O\$21:\$O\$$lastData)", sum.stripTrailingZeros().toPlainString())
        }
        val total = claim.receipts.filter { receiptBusinessUnit(it, claim.staffProfile).equalsAny(labels) }
            .fold(BigDecimal.ZERO) { sum, receipt -> sum.add(decimal(receipt.sgdEquivalent)) }
        findRow(sheetData, 15)?.let { setFormula(doc, it, "N15", "SUM(N9:N14)", total.stripTrailingZeros().toPlainString()) }
    }

    private fun receiptBusinessUnit(receipt: ReportReceipt, profile: StaffProfile): String =
        (receipt.details.businessUnit ?: profile.businessUnit).trim()

    private data class ApprovalSignature(val row: Int, val label: String, val bytes: ByteArray)

    private fun String.equalsAny(values: List<String>) = values.any { equals(it, ignoreCase = true) }

    private fun writePaymentAndApprovals(doc: Document, sheetData: Element, claim: ReportClaim, extras: Int) {
        val details = claim.details
        val method = details.paymentMethod?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val cashRow = 55 + extras
        val chequeRow = 57 + extras
        val paynowRow = 59 + extras
        findRow(sheetData, cashRow)?.let { setCell(doc, it, "B$cashRow", if (method == "cash") "Cash" else null, null) }
        findRow(sheetData, chequeRow)?.let {
            val chequeText = if (method == "cheque" || method == "check") "Cheque No: ${details.chequeNumber.orEmpty()}" else null
            setCell(doc, it, "B$chequeRow", chequeText, null)
            if (chequeText != null) setRowHeight(it, (estimateWrappedLines(chequeText, 22) * 13.5 + 5).coerceAtLeast(18.0))
        }
        findRow(sheetData, paynowRow)?.let {
            val text = if (method == "paynow" || method == "pay now") {
                details.paynowReference?.trim()?.takeIf(String::isNotEmpty)?.let { reference -> "Paynow: $reference" } ?: "Paynow"
            } else null
            setCell(doc, it, "B$paynowRow", text, null)
            if (text != null) setRowHeight(it, (estimateWrappedLines(text, 22) * 13.5 + 5).coerceAtLeast(18.0))
        }
        val approvals = listOf(
            Triple(55 + extras, details.submittedBy, details.submittedDate),
            Triple(56 + extras, details.checkedBy, details.checkedDate),
            Triple(57 + extras, details.approvedBy, details.approvedDate),
            Triple(58 + extras, details.receivedBy, details.receivedDate),
        )
        approvals.forEach { (rowNum, name, date) ->
            val row = findRow(sheetData, rowNum) ?: return@forEach
            val prefix = when (rowNum - extras) {
                55 -> "Submitted by"
                56 -> "Checked by"
                57 -> "Approved by"
                else -> "Received by"
            }
            setCell(doc, row, "H$rowNum", name?.trim()?.takeIf(String::isNotEmpty)?.let { "$prefix: $it" } ?: "$prefix:", null)
            setCell(doc, row, "L$rowNum", "DATE:", null)
            setCell(doc, row, "M$rowNum", date?.trim()?.takeIf(String::isNotEmpty), null)
            val stroke = when (rowNum - extras) {
                55 -> details.submittedSignature
                56 -> details.checkedSignature
                57 -> details.approvedSignature
                else -> details.receivedSignature
            }
            val renderedName = name?.trim()?.takeIf(String::isNotEmpty)?.let { "$prefix: $it" } ?: "$prefix:"
            setRowHeight(row, maxOf(
                if (stroke.any { it.points.isNotEmpty() }) 72.0 else 18.0,
                estimateWrappedLines(renderedName, 80) * 13.5 + 5,
            ).coerceAtMost(409.0))
        }
    }

    private fun approvalSignatures(details: ClaimDetails): List<ApprovalSignature> = listOf(
        55 to ("Submitted" to details.submittedSignature),
        56 to ("Checked" to details.checkedSignature),
        57 to ("Approved" to details.approvedSignature),
        58 to ("Received" to details.receivedSignature),
    ).mapNotNull { (row, pair) ->
        pair.second.takeIf { strokes -> strokes.any { it.points.isNotEmpty() } }?.let { ApprovalSignature(row, pair.first, signaturePng(it)) }
    }

    private fun signaturePng(strokes: List<SignatureStroke>): ByteArray {
        val bitmap = Bitmap.createBitmap(600, 120, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3.2f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        strokes.forEach { stroke ->
            val path = android.graphics.Path()
            stroke.points.forEachIndexed { index, point ->
                val x = 8f + point.x.coerceIn(0f, 1f) * 584f
                val y = 8f + point.y.coerceIn(0f, 1f) * 104f
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
    }

    private fun createSignatureDrawing(signatures: List<ApprovalSignature>): Document {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().newDocument()
        val root = doc.createElementNS(XDR_NS, "xdr:wsDr")
        root.setAttributeNS(XMLNS_NS, "xmlns:xdr", XDR_NS)
        root.setAttributeNS(XMLNS_NS, "xmlns:a", DRAWING_NS)
        root.setAttributeNS(XMLNS_NS, "xmlns:r", OFFICE_REL_NS)
        doc.appendChild(root)
        signatures.forEachIndexed { index, signature ->
            val anchor = doc.createElementNS(XDR_NS, "xdr:oneCellAnchor")
            root.appendChild(anchor)
            fun marker(name: String, col: Int, row: Int, rowOffset: Int = 0) {
                val node = doc.createElementNS(XDR_NS, "xdr:$name")
                listOf("col" to col, "colOff" to 0, "row" to row, "rowOff" to rowOffset).forEach { (tag, value) ->
                    node.appendChild(doc.createElementNS(XDR_NS, "xdr:$tag").apply { textContent = value.toString() })
                }
                anchor.appendChild(node)
            }
            marker("from", 7, signature.row - 1, 150000)
            anchor.appendChild(doc.createElementNS(XDR_NS, "xdr:ext").apply {
                setAttribute("cx", SIGNATURE_WIDTH_EMU.toString())
                setAttribute("cy", SIGNATURE_HEIGHT_EMU.toString())
            })
            val pic = doc.createElementNS(XDR_NS, "xdr:pic")
            anchor.appendChild(pic)
            val nv = doc.createElementNS(XDR_NS, "xdr:nvPicPr")
            pic.appendChild(nv)
            nv.appendChild(doc.createElementNS(XDR_NS, "xdr:cNvPr").apply {
                setAttribute("id", (index + 1).toString()); setAttribute("name", "${signature.label} signature")
            })
            nv.appendChild(doc.createElementNS(XDR_NS, "xdr:cNvPicPr"))
            val fill = doc.createElementNS(XDR_NS, "xdr:blipFill")
            pic.appendChild(fill)
            fill.appendChild(doc.createElementNS(DRAWING_NS, "a:blip").apply { setAttributeNS(OFFICE_REL_NS, "r:embed", "rId${index + 1}") })
            fill.appendChild(doc.createElementNS(DRAWING_NS, "a:stretch").apply { appendChild(doc.createElementNS(DRAWING_NS, "a:fillRect")) })
            val shape = doc.createElementNS(XDR_NS, "xdr:spPr")
            pic.appendChild(shape)
            shape.appendChild(doc.createElementNS(DRAWING_NS, "a:xfrm").apply {
                appendChild(doc.createElementNS(DRAWING_NS, "a:off").apply { setAttribute("x", "0"); setAttribute("y", "0") })
                appendChild(doc.createElementNS(DRAWING_NS, "a:ext").apply {
                    setAttribute("cx", SIGNATURE_WIDTH_EMU.toString()); setAttribute("cy", SIGNATURE_HEIGHT_EMU.toString())
                })
            })
            shape.appendChild(doc.createElementNS(DRAWING_NS, "a:prstGeom").apply {
                setAttribute("prst", "rect"); appendChild(doc.createElementNS(DRAWING_NS, "a:avLst"))
            })
            anchor.appendChild(doc.createElementNS(XDR_NS, "xdr:clientData"))
        }
        return doc
    }

    private fun createSignatureDrawingRels(count: Int): Document {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().newDocument()
        val root = doc.createElementNS(PKG_REL_NS, "Relationships")
        doc.appendChild(root)
        repeat(count) { index -> root.appendChild(doc.createElementNS(PKG_REL_NS, "Relationship").apply {
            setAttribute("Id", "rId${index + 1}")
            setAttribute("Type", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image")
            setAttribute("Target", "../media/signature${index + 1}.png")
        }) }
        return doc
    }

    private fun appendPngContentType(entries: MutableMap<String, ByteArray>) {
        val path = "[Content_Types].xml"
        val types = entries[path]?.let(::parseXml) ?: error("Workbook content types are missing")
        val hasPng = types.getElementsByTagNameNS(CONTENT_TYPES_NS, "Default").let { nodes ->
            (0 until nodes.length).any { (nodes.item(it) as Element).getAttribute("Extension").equals("png", true) }
        }
        if (!hasPng) {
            val node = types.createElementNS(CONTENT_TYPES_NS, "Default").apply {
                setAttribute("Extension", "png"); setAttribute("ContentType", "image/png")
            }
            val overrides = types.getElementsByTagNameNS(CONTENT_TYPES_NS, "Override")
            if (overrides.length > 0) types.documentElement.insertBefore(node, overrides.item(0)) else types.documentElement.appendChild(node)
        }
        val drawingPartName = "/xl/drawings/drawing1.xml"
        val hasDrawing = types.getElementsByTagNameNS(CONTENT_TYPES_NS, "Override").let { nodes ->
            (0 until nodes.length).any { (nodes.item(it) as Element).getAttribute("PartName") == drawingPartName }
        }
        if (!hasDrawing) types.documentElement.appendChild(types.createElementNS(CONTENT_TYPES_NS, "Override").apply {
            setAttribute("PartName", drawingPartName)
            setAttribute("ContentType", "application/vnd.openxmlformats-officedocument.drawing+xml")
        })
        entries[path] = serializeXml(types)
    }

    private fun cellText(row: Element?, ref: String): String {
        val cell = row?.let { findCell(it, ref) } ?: return ""
        return cell.getElementsByTagNameNS(MAIN_NS, "t").item(0)?.textContent
            ?: cell.getElementsByTagNameNS(MAIN_NS, "v").item(0)?.textContent.orEmpty()
    }

    private fun setRowHeight(row: Element, height: Double) {
        row.setAttribute("ht", height.toString())
        row.setAttribute("customHeight", "1")
    }

    private fun sumSgd(receipts: List<ReportReceipt>): BigDecimal =
        receipts.fold(BigDecimal.ZERO) { total, receipt -> total.add(decimal(receipt.sgdEquivalent)) }

    private fun sortReceipts(receipts: List<ReportReceipt>): List<ReportReceipt> = receipts.sortedWith(
        compareBy<ReportReceipt>({ ExpenseAccounts.find(it.expenseAccountId)?.code?.toIntOrNull() ?: Int.MAX_VALUE })
            .thenBy { ExpenseAccounts.find(it.expenseAccountId)?.id.orEmpty() }
            .thenBy { it.date }
            .thenBy { it.key }
    )

    private fun setCell(doc: Document, row: Element, ref: String, value: String?, type: String?) {
        val cell = getOrCreateCell(doc, row, ref)
        while (cell.firstChild != null) cell.removeChild(cell.firstChild)
        if (value == null) { cell.removeAttribute("t"); return }
        if (type == "numeric") {
            cell.removeAttribute("t")
            cell.appendChild(doc.createElementNS(MAIN_NS, "v").apply { textContent = value })
        } else {
            cell.setAttribute("t", "inlineStr")
            val inline = doc.createElementNS(MAIN_NS, "is")
            inline.appendChild(doc.createElementNS(MAIN_NS, "t").apply { textContent = value; if (value.startsWith(" ") || value.endsWith(" ")) setAttributeNS(XML_NS, "xml:space", "preserve") })
            cell.appendChild(inline)
        }
    }

    private fun getOrCreateCell(doc: Document, row: Element, ref: String): Element {
        findCell(row, ref)?.let { return it }
        val cell = doc.createElementNS(MAIN_NS, "c") as Element
        cell.setAttribute("r", ref)
        val col = ref.takeWhile(Char::isLetter)
        val before = row.getElementsByTagNameNS(MAIN_NS, "c")
        for (i in 0 until before.length) {
            val other = before.item(i) as Element
            if (columnIndex(other.getAttribute("r").takeWhile(Char::isLetter)) > columnIndex(col)) { row.insertBefore(cell, other); return cell }
        }
        row.appendChild(cell)
        return cell
    }

    private fun findCell(row: Element, ref: String): Element? {
        val cells = row.getElementsByTagNameNS(MAIN_NS, "c")
        for (i in 0 until cells.length) (cells.item(i) as Element).takeIf { it.getAttribute("r") == ref }?.let { return it }
        return null
    }
    private fun setRowHeight(row: Element, receipt: ReportReceipt, existingRowHeight: Double) {
        val values = listOf(
            receiptLabel(receipt) to 47,
            receipt.category to 18,
            receipt.details.travelStart.orEmpty() to 12,
            receipt.details.travelEnd.orEmpty() to 12,
            receipt.details.fromLocation.orEmpty() to 14,
            receipt.details.toLocation.orEmpty() to 14,
            receipt.details.purpose.orEmpty() to 18,
            receipt.details.eventName.orEmpty() to 18,
            receipt.details.businessUnit.orEmpty() to 14,
            receipt.details.costCentre1.orEmpty() to 14,
            receipt.details.departmentCode.orEmpty() to 14,
        )
        val lines = values.maxOf { (value, width) -> estimateWrappedLines(value, width) }.coerceAtLeast(1)
        // Excel caps row height at 409 points. Use a little vertical padding and
        // retain the template's normal single-line height for short receipts.
        val height = (lines * 15.0 + 3.0).coerceAtMost(409.0).coerceAtLeast(existingRowHeight)
        row.setAttribute("ht", height.toString())
        row.setAttribute("customHeight", "1")
    }

    private fun estimateWrappedLines(value: String, columnWidth: Int): Int {
        if (value.isEmpty()) return 1
        var totalLines = 0
        value.split('\n').forEach { paragraph ->
            var lineWidth = 0
            var paragraphLines = 1
            paragraph.codePoints().forEach { cp ->
                // East Asian wide characters and emoji normally take two Excel
                // character cells; combining marks do not advance the cursor.
                val type = Character.getType(cp)
                val width = when {
                    type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() -> 0
                    cp >= 0x1100 && (cp <= 0x115f || cp in 0x2e80..0xa4cf || cp in 0xac00..0xd7a3 || cp in 0xf900..0xfaff || cp in 0xfe10..0xfe6f || cp in 0xff00..0xff60 || cp in 0x1f300..0x1faff) -> 2
                    else -> 1
                }
                lineWidth += width
                if (lineWidth > columnWidth) {
                    paragraphLines += (lineWidth - 1) / columnWidth
                    lineWidth %= columnWidth
                }
            }
            totalLines += paragraphLines
        }
        return totalLines.coerceAtLeast(1)
    }

    private fun findRow(data: Element, n: Int): Element? {
        val rows = data.getElementsByTagNameNS(MAIN_NS, "row")
        for (i in 0 until rows.length) (rows.item(i) as Element).takeIf { it.getAttribute("r").toIntOrNull() == n }?.let { return it }
        return null
    }

    private fun readPdfSheet(workbook: File, claim: ReportClaim): PdfSheetData {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(workbook.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        val sheet = parseXml(entries["xl/worksheets/sheet2.xml"] ?: error("Workbook claim sheet missing"))
        val shared = entries["xl/sharedStrings.xml"]?.let(::parseXml)?.let { doc ->
            val nodes = doc.getElementsByTagNameNS(MAIN_NS, "si")
            (0 until nodes.length).map { index ->
                val item = nodes.item(index) as Element
                item.getElementsByTagNameNS(MAIN_NS, "t").let { ts -> (0 until ts.length).joinToString("") { ts.item(it).textContent } }
            }
        }.orEmpty()
        val styleDoc = entries["xl/styles.xml"]?.let(::parseXml)
        val fonts = styleDoc?.getElementsByTagNameNS(MAIN_NS, "font")
        val fontStyles = (0 until (fonts?.length ?: 0)).map { index ->
            val font = fonts!!.item(index) as Element
            val size = font.getElementsByTagNameNS(MAIN_NS, "sz").item(0)?.let { (it as Element).getAttribute("val").toFloatOrNull() } ?: 9f
            val bold = font.getElementsByTagNameNS(MAIN_NS, "b").length > 0
            Pair(size, bold)
        }
        val fills = styleDoc?.getElementsByTagNameNS(MAIN_NS, "fill")
        val fillColors = (0 until (fills?.length ?: 0)).map { index ->
            val fill = fills!!.item(index) as Element
            val fg = fill.getElementsByTagNameNS(MAIN_NS, "fgColor").item(0) as? Element
            fg?.getAttribute("rgb")?.takeLast(6)?.toLongOrNull(16)?.let { Color.rgb(((it shr 16) and 0xff).toInt(), ((it shr 8) and 0xff).toInt(), (it and 0xff).toInt()) }
        }
        val xfs = (styleDoc?.getElementsByTagNameNS(MAIN_NS, "cellXfs")?.item(0) as? Element)
            ?.getElementsByTagNameNS(MAIN_NS, "xf")
        val borderNodes = styleDoc?.getElementsByTagNameNS(MAIN_NS, "borders")?.item(0)?.let { (it as Element).getElementsByTagNameNS(MAIN_NS, "border") }
        val borderStyles = (0 until (borderNodes?.length ?: 0)).map { index ->
            val border = borderNodes!!.item(index) as Element
            buildSet {
                for (childIndex in 0 until border.childNodes.length) {
                    val child = border.childNodes.item(childIndex) as? Element ?: continue
                    if (child.getAttribute("style").isNotBlank() && child.getAttribute("style") != "none") add(child.localName)
                }
            }
        }
        val styles = (0 until (xfs?.length ?: 0)).map { index ->
            val xf = xfs!!.item(index) as Element
            val fontId = xf.getAttribute("fontId").toIntOrNull() ?: 0
            val fillId = xf.getAttribute("fillId").toIntOrNull() ?: 0
            val font = fontStyles.getOrNull(fontId) ?: (9f to false)
            val fill = fillColors.getOrNull(fillId)
            val align = (xf.getElementsByTagNameNS(MAIN_NS, "alignment").item(0) as? Element)?.getAttribute("horizontal") ?: "left"
            val borderId = xf.getAttribute("borderId").toIntOrNull() ?: 0
            PdfCellStyle(font.first, font.second, fill, align, borderStyles.getOrNull(borderId).orEmpty())
        }
        val cols = sheet.getElementsByTagNameNS(MAIN_NS, "col")
        val widths = MutableList(19) { 9f }
        for (i in 0 until cols.length) {
            val col = cols.item(i) as Element
            val min = col.getAttribute("min").toIntOrNull() ?: continue
            val max = col.getAttribute("max").toIntOrNull() ?: min
            val width = col.getAttribute("width").toFloatOrNull() ?: continue
            for (column in min..max) if (column in 1..19) widths[column - 1] = (width * 7f + 5f) * 0.75f
        }
        val defaultHeight = (sheet.getElementsByTagNameNS(MAIN_NS, "sheetFormatPr").item(0) as? Element)
            ?.getAttribute("defaultRowHeight")?.toFloatOrNull() ?: 14.5f
        val receiptDates = claim.receipts.map { it.date }
        val rowNodes = sheet.getElementsByTagNameNS(MAIN_NS, "row")
        val rows = linkedMapOf<Int, PdfRowData>()
        for (i in 0 until rowNodes.length) {
            val row = rowNodes.item(i) as Element
            val rowNumber = row.getAttribute("r").toIntOrNull() ?: continue
            val cells = linkedMapOf<Int, PdfCellData>()
            val cellNodes = row.getElementsByTagNameNS(MAIN_NS, "c")
            for (j in 0 until cellNodes.length) {
                val cell = cellNodes.item(j) as Element
                val ref = cell.getAttribute("r")
                val letters = ref.takeWhile(Char::isLetter)
                val col = columnIndex(letters)
                if (col !in 1..19) continue
                val type = cell.getAttribute("t")
                val raw = when (type) {
                    "s" -> cell.getElementsByTagNameNS(MAIN_NS, "v").item(0)?.textContent?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> cell.getElementsByTagNameNS(MAIN_NS, "t").let { ts -> (0 until ts.length).joinToString("") { ts.item(it).textContent } }
                    else -> cell.getElementsByTagNameNS(MAIN_NS, "v").item(0)?.textContent.orEmpty()
                }
                val text = if (rowNumber in 21..(20 + claim.receipts.size.coerceAtLeast(1)) && letters == "B")
                    receiptDates.getOrNull(rowNumber - 21).orEmpty()
                else raw
                val styleIndex = cell.getAttribute("s").toIntOrNull() ?: 0
                val style = styles.getOrNull(styleIndex) ?: PdfCellStyle()
                cells[col] = PdfCellData(text, style)
            }
            rows[rowNumber] = PdfRowData(row.getAttribute("ht").toFloatOrNull() ?: defaultHeight, cells)
        }
        val merges = linkedMapOf<Int, MutableMap<Int, Int>>()
        val covered = mutableSetOf<Pair<Int, Int>>()
        val mergeNodes = sheet.getElementsByTagNameNS(MAIN_NS, "mergeCell")
        for (i in 0 until mergeNodes.length) {
            val ref = (mergeNodes.item(i) as Element).getAttribute("ref")
            val parts = ref.split(':')
            if (parts.size != 2) continue
            val leftLetters = parts[0].takeWhile(Char::isLetter)
            val leftRow = parts[0].drop(leftLetters.length).toIntOrNull() ?: continue
            val rightLetters = parts[1].takeWhile(Char::isLetter)
            val rightRow = parts[1].drop(rightLetters.length).toIntOrNull() ?: continue
            if (leftRow != rightRow) continue
            val left = columnIndex(leftLetters); val right = columnIndex(rightLetters)
            if (left !in 1..19 || right !in left..19) continue
            merges.getOrPut(leftRow) { linkedMapOf() }[left] = right
            for (col in left + 1..right) covered += leftRow to col
        }
        return PdfSheetData(rows, widths, merges, covered, defaultHeight)
    }

    private fun writePdf(target: File, claim: ReportClaim, images: List<ImageInfo>, workbook: File) {
        val sheet = readPdfSheet(workbook, claim)
        val pdf = PdfDocument()
        val pageW = 1190; val pageH = 842
        val margin = 26f
        val availableWidth = pageW - margin * 2
        val rawWidth = sheet.columnWidths.sum()
        val scale = availableWidth / rawWidth
        val columnWidths = sheet.columnWidths.map { it * scale }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; style = Paint.Style.STROKE; strokeWidth = 0.55f }
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f
        val bottomLimit = pageH - 26f
        fun finishPage() {
            val current = page ?: return
            page = null; canvas = null
            pdf.finishPage(current)
        }
        fun newPage() {
            finishPage()
            pageNumber++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNumber).create())
            canvas = page!!.canvas
            y = 22f
        }
        fun showText(
            value: String,
            size: Float = 10f,
            bold: Boolean = false,
            continuationLabel: String = "Continued",
            pageHeader: (() -> Unit)? = null,
        ) {
            val actual = value.ifBlank { " " }
            paint.textSize = size
            paint.typeface = android.graphics.Typeface.create("sans-serif", if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            // StaticLayout retains its TextPaint and draws later. Continuation headers
            // and labels mutate the shared paint, so give each layout a stable copy.
            val layoutPaint = TextPaint(paint)
            val textWidth = (availableWidth - 8).toInt().coerceAtLeast(40)
            val layout = StaticLayout.Builder.obtain(actual, 0, actual.length, layoutPaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true).build()
            var first = 0
            while (first < layout.lineCount) {
                if (y >= bottomLimit) { newPage(); pageHeader?.invoke() }
                val top = layout.getLineTop(first)
                var end = first
                val room = bottomLimit - y
                while (end < layout.lineCount && layout.getLineBottom(end) - top <= room) end++
                if (end == first) {
                    newPage()
                    pageHeader?.invoke()
                    if (first > 0) {
                        paint.textSize = 9f
                        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                        canvas!!.drawText(continuationLabel, margin + 4, y + 10f, paint)
                        y += 15f
                    }
                    continue
                }
                val lastBottom = layout.getLineBottom(end - 1)
                canvas!!.save()
                canvas!!.translate(margin + 4, y - top)
                canvas!!.clipRect(0f, top.toFloat(), textWidth.toFloat(), lastBottom.toFloat())
                layout.draw(canvas!!)
                canvas!!.restore()
                y += (lastBottom - top) + 4f
                first = end
                if (first < layout.lineCount) {
                    newPage()
                    pageHeader?.invoke()
                    paint.textSize = 9f
                    paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                    canvas!!.drawText(continuationLabel, margin + 4, y + 10f, paint)
                    y += 15f
                }
            }
        }
        fun drawSignature(rowNum: Int, x: Float, top: Float, width: Float, height: Float) {
            val strokes = when (rowNum) {
                55 -> claim.details.submittedSignature
                56 -> claim.details.checkedSignature
                57 -> claim.details.approvedSignature
                else -> claim.details.receivedSignature
            }
            if (strokes.isEmpty()) return
            val signaturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.5f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
            val (signatureWidth, signatureHeight) = fitReportSignature(width, height * 0.78f)
            val leftInset = x + (width - signatureWidth) / 2f
            val topInset = top + (height - signatureHeight) / 2f
            strokes.forEach { stroke ->
                val path = android.graphics.Path()
                stroke.points.forEachIndexed { index, point ->
                    val px = leftInset + point.x * signatureWidth
                    val py = topInset + point.y * signatureHeight
                    if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                canvas!!.drawPath(path, signaturePaint)
            }
        }
        fun displayCellText(rowNum: Int, col: Int, text: String): String = when {
            rowNum >= 21 && col == 2 -> text
            ((col == 6 && rowNum in 9..16) || col in listOf(11, 12, 15) || (col == 14 && rowNum in 9..15)) && text.toBigDecimalOrNull() != null ->
                text.toBigDecimal().setScale(2, RoundingMode.HALF_UP).toPlainString()
            col == 14 && text.toBigDecimalOrNull() != null -> text.toBigDecimal().stripTrailingZeros().toPlainString()
            else -> text
        }
        val oversizedFormRows = mutableSetOf<Int>()
        val maxFormRowHeight = 160f
        fun renderedRowHeight(rowNum: Int): Float {
            val row = sheet.rows[rowNum] ?: return sheet.defaultRowHeight * scale
            var needed = row.height * scale
            row.cells.forEach { (col, cell) ->
                val mergeEnd = sheet.merges[rowNum]?.get(col)?.coerceAtMost(19) ?: col
                val width = (col..mergeEnd).sumOf { columnWidths[it - 1].toDouble() }.toFloat()
                paint.textSize = (cell.style.fontSize * scale).coerceIn(5.5f, 16f)
                paint.typeface = android.graphics.Typeface.create("sans-serif", if (cell.style.bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                val textWidth = (width - 5f).toInt().coerceAtLeast(5)
                val content = displayCellText(rowNum, col, cell.text)
                val layout = StaticLayout.Builder.obtain(content.ifEmpty { " " }, 0, content.ifEmpty { " " }.length, paint, textWidth)
                    .setIncludePad(false).build()
                needed = maxOf(needed, layout.height + 5f)
            }
            if (needed > maxFormRowHeight) oversizedFormRows += rowNum
            return needed.coerceAtMost(maxFormRowHeight)
        }
        fun drawBorders(rect: RectF, sides: Set<String>) {
            if (sides.isEmpty()) return
            if ("left" in sides) canvas!!.drawLine(rect.left, rect.top, rect.left, rect.bottom, borderPaint)
            if ("right" in sides) canvas!!.drawLine(rect.right, rect.top, rect.right, rect.bottom, borderPaint)
            if ("top" in sides) canvas!!.drawLine(rect.left, rect.top, rect.right, rect.top, borderPaint)
            if ("bottom" in sides) canvas!!.drawLine(rect.left, rect.bottom, rect.right, rect.bottom, borderPaint)
        }
        fun renderRow(rowNum: Int) {
            val rowData = sheet.rows[rowNum] ?: PdfRowData(sheet.defaultRowHeight, emptyMap())
            val height = maxOf(rowData.height.coerceAtLeast(12f) * scale, renderedRowHeight(rowNum)).coerceAtMost(maxFormRowHeight)
            var x = margin
            var col = 1
            while (col <= 19) {
                val mergeEnd = sheet.merges[rowNum]?.get(col)?.coerceAtMost(19) ?: col
                var cellWidth = 0f
                for (c in col..mergeEnd) cellWidth += columnWidths[c - 1]
                val cell = rowData.cells[col]
                if (cell != null) {
                    val rect = RectF(x, y, x + cellWidth, y + height)
                    cell.style.fill?.let { fill ->
                        canvas!!.drawRect(rect, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill; style = Paint.Style.FILL })
                    }
                    drawBorders(rect, cell.style.borders)
                    if (cell.text.isNotEmpty()) {
                        val content = if (rowNum in oversizedFormRows && rowNum in 21..(20 + claim.receipts.size) && col == 8) {
                            val receiptIndex = rowNum - 21
                            "${receiptTitle(claim.receipts[receiptIndex], receiptIndex)} — Full description and note continue in Receipt ${receiptIndex + 1} appendix."
                        } else displayCellText(rowNum, col, cell.text)
                        paint.textSize = (cell.style.fontSize * scale).coerceIn(5.5f, 16f)
                        paint.typeface = android.graphics.Typeface.create("sans-serif", if (cell.style.bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                        val padding = 2.5f
                        val textWidth = (cellWidth - padding * 2).toInt().coerceAtLeast(5)
                        val alignment = when (cell.style.horizontal) {
                            "center" -> Layout.Alignment.ALIGN_CENTER
                            "right" -> Layout.Alignment.ALIGN_OPPOSITE
                            else -> Layout.Alignment.ALIGN_NORMAL
                        }
                        val layout = StaticLayout.Builder.obtain(content, 0, content.length, paint, textWidth)
                            .setAlignment(alignment).setIncludePad(false).build()
                        val textTop = y + ((height - layout.height).coerceAtLeast(0f) / 2f)
                        canvas!!.save()
                        canvas!!.clipRect(x + 1, y + 1, x + cellWidth - 1, y + height - 1)
                        canvas!!.translate(x + padding, textTop)
                        layout.draw(canvas!!)
                        canvas!!.restore()
                    }
                    if (rowNum in (55 + (claim.receipts.size - 29).coerceAtLeast(0))..(58 + (claim.receipts.size - 29).coerceAtLeast(0)) && col == 8) {
                        drawSignature(rowNum - (claim.receipts.size - 29).coerceAtLeast(0), x, y, cellWidth, height)
                    }
                }
                x += cellWidth
                col = mergeEnd + 1
            }
            y += height
        }
        fun renderHeaderRow() {
            val h = sheet.rows[20]?.height?.times(scale) ?: 20f
            if (y + h > bottomLimit) newPage()
            renderRow(20)
        }
        try {
            newPage()
            (1..20).forEach { renderRow(it) }
            val firstReceiptRow = 21
            val lastReceiptRow = 20 + claim.receipts.size
            for (rowNum in firstReceiptRow..lastReceiptRow) {
                val rowHeight = renderedRowHeight(rowNum)
                if (y + rowHeight > bottomLimit) {
                    newPage()
                    renderHeaderRow()
                }
                renderRow(rowNum)
            }
            val extras = (claim.receipts.size - 29).coerceAtLeast(0)
            val footerStart = 50 + extras
            val footerEnd = 59 + extras
            for (rowNum in footerStart..footerEnd) {
                val rowHeight = renderedRowHeight(rowNum)
                if (y + rowHeight > bottomLimit) {
                    newPage()
                    renderHeaderRow()
                }
                renderRow(rowNum)
            }

            fun drawAppendixHeader(receipt: ReportReceipt, index: Int) {
                val account = ExpenseAccounts.find(receipt.expenseAccountId)
                val header = "${index + 1}. ${receiptTitle(receipt, index)}${account?.let { " · ${it.code} ${it.label}" }.orEmpty()}"
                paint.textSize = 17f
                paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                val textWidth = (availableWidth - 8).toInt().coerceAtLeast(40)
                val layout = StaticLayout.Builder.obtain(header, 0, header.length, paint, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true).build()
                canvas!!.save()
                canvas!!.translate(margin + 4, y)
                layout.draw(canvas!!)
                canvas!!.restore()
                y += layout.height + 5f
            }
            claim.receipts.forEachIndexed { index, receipt ->
                newPage()
                val appendixHeader = { drawAppendixHeader(receipt, index) }
                appendixHeader()
                val account = ExpenseAccounts.find(receipt.expenseAccountId)
                val appendixLines = buildList<Pair<String, Float>> {
                    add("Date: ${receipt.date} · Classification: ${receipt.category}" to 11f)
                    add("Amount paid (gross): ${receipt.currency} ${decimal(receipt.amount).setScale(2, RoundingMode.HALF_UP)} · Rate: ${receipt.exchangeRate} · S$ equivalent: ${receipt.sgdEquivalent}" to 11f)
                    receipt.details.gstAmount?.trim()?.takeIf(String::isNotEmpty)?.let { gst ->
                        val net = decimal(receipt.amount).subtract(decimal(gst))
                        add("Amount excluding GST: ${receipt.currency} ${net.setScale(2, RoundingMode.HALF_UP)} · GST included: ${receipt.currency} ${decimal(gst).setScale(2, RoundingMode.HALF_UP)}" to 11f)
                    }
                    account?.let { add("Expense account: ${it.code} · ${it.label}" to 11f) }
                    receiptDetails(receipt).forEach { (label, value) -> add("$label: $value" to 10f) }
                    receipt.details.run {
                        listOf(
                            "Travel start" to travelStart, "Travel end" to travelEnd, "From" to fromLocation, "To" to toLocation,
                            "Purpose" to purpose, "Event" to eventName, "Business unit" to (businessUnit ?: claim.staffProfile.businessUnit),
                            "Cost centre 1" to (costCentre1 ?: claim.staffProfile.costCentre1), "Department code" to (departmentCode ?: claim.staffProfile.departmentCode),
                        ).forEach { (label, value) -> value?.trim()?.takeIf(String::isNotEmpty)?.let { add("$label: $it" to 10f) } }
                    }
                }
                fun measuredHeight(text: String, size: Float): Pair<Int, Float> {
                    paint.textSize = size
                    paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
                    val width = (availableWidth - 8).toInt().coerceAtLeast(40)
                    val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true).build()
                    return layout.lineCount to (layout.height + 4f)
                }
                // Leave room for ordinary captions and optional fields before choosing the
                // image size. Very long text is allowed to continue on labeled pages.
                val metadataReserve = appendixLines.sumOf { (text, size) ->
                    val (lineCount, height) = measuredHeight(text, size)
                    if (lineCount <= 4) height.toDouble() else 0.0
                }.toFloat() + 56f
                val bitmap = decode(images[index])
                try {
                    val top = y + 8f
                    val maxWidth = pageW - 100f
                    val maxHeight = (bottomLimit - top - metadataReserve).coerceIn(180f, 510f)
                    val photoScale = minOf(maxWidth / bitmap.width, maxHeight / bitmap.height, 1f)
                    val width = bitmap.width * photoScale
                    val height = bitmap.height * photoScale
                    val left = (pageW - width) / 2f
                    canvas!!.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), Paint(Paint.FILTER_BITMAP_FLAG))
                    y = top + height + 12f
                } finally { bitmap.recycle() }
                appendixLines.forEach { (text, size) ->
                    showText(text, size, continuationLabel = "${text.substringBefore(':')} (continued)", pageHeader = appendixHeader)
                }
            }
            finishPage()
            FileOutputStream(target).use(pdf::writeTo)
        } finally {
            try { finishPage() } catch (_: Throwable) { }
            pdf.close()
        }
    }

    private fun writeDocx(target: File, claim: ReportClaim, images: List<ImageInfo>) {
        val signatures = approvalSignatures(claim.details)
        val docXml = StringBuilder()
        docXml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>")
        fun para(text: String, bold: Boolean = false, keepNext: Boolean = false) {
            docXml.append("<w:p>")
            if (keepNext) docXml.append("<w:pPr><w:keepNext/></w:pPr>")
            docXml.append("<w:r>")
            if (bold) docXml.append("<w:rPr><w:b/></w:rPr>")
            docXml.append("<w:t xml:space=\"preserve\">${xmlEscape(text)}</w:t></w:r></w:p>")
        }
        para(claim.title, true)
        claimMetadataLines(claim).forEach { para(it) }
        claimDetailsLines(claim).forEach { para(it) }
        if (signatures.isNotEmpty()) para("Approval signatures", true)
        signatures.forEachIndexed { index, signature ->
            para("${signature.label} signature", keepNext = true)
            val rid = "rId${images.size + index + 2}"
            val width = 2_600_000L
            val height = 520_000L
            docXml.append("<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"$width\" cy=\"$height\"/><wp:docPr id=\"${images.size + index + 1}\" name=\"${signature.label} signature\"/><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr id=\"0\" name=\"signature${index + 1}.png\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$width\" cy=\"$height\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
        }
        val total = claim.receipts.fold(BigDecimal.ZERO) { sum, r -> sum.add(decimal(r.sgdEquivalent)) }
        para("Total S$ equivalent: ${total.setScale(2, RoundingMode.HALF_UP)}")
        claim.receipts.forEachIndexed { i, receipt ->
            val label = receiptTitle(receipt, i)
            if (i > 0) docXml.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            para("${i + 1}. $label", true, keepNext = true)
            val rid = "rId${i + 2}"
            val rawCx = images[i].width * EMU_PER_PIXEL
            val rawCy = images[i].height * EMU_PER_PIXEL
            val scale = minOf(1.0, MAX_IMAGE_CX / rawCx, MAX_IMAGE_CY / rawCy)
            val cx = (rawCx * scale).toLong().coerceAtLeast(1)
            val cy = (rawCy * scale).toLong().coerceAtLeast(1)
            docXml.append("<w:p><w:pPr><w:keepNext/></w:pPr><w:r><w:drawing><wp:inline><wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"${i + 1}\" name=\"Receipt ${i + 1}\"/><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr id=\"0\" name=\"receipt${i + 1}.jpg\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
            val account = ExpenseAccounts.find(receipt.expenseAccountId)
            para("Date: ${receipt.date} · Category: ${receipt.category} · Account: ${account?.let { "${it.code} ${it.label}" } ?: "Unselected"} · Gross paid: ${receipt.currency} ${receipt.amount} · Rate: ${receipt.exchangeRate} · SGD: ${receipt.sgdEquivalent}", keepNext = true)
            receipt.details.run {
                listOf(
                    "Travel start" to travelStart, "Travel end" to travelEnd, "From" to fromLocation, "To" to toLocation,
                    "Purpose" to purpose, "Event" to eventName,
                    "Business unit" to (businessUnit ?: claim.staffProfile.businessUnit),
                    "Cost centre 1" to (costCentre1 ?: claim.staffProfile.costCentre1),
                    "Department code" to (departmentCode ?: claim.staffProfile.departmentCode),
                ).forEach { (detailLabel, value) -> value?.trim()?.takeIf(String::isNotEmpty)?.let { para("$detailLabel: $it") } }
                gstAmount?.trim()?.takeIf(String::isNotEmpty)?.let { gst ->
                    para("Amount excluding GST: ${receipt.currency} ${decimal(receipt.amount).subtract(decimal(gst)).setScale(2, RoundingMode.HALF_UP)} · GST included: ${receipt.currency} ${decimal(gst).setScale(2, RoundingMode.HALF_UP)}")
                }
            }
            val details = receiptDetails(receipt)
            details.forEachIndexed { detailIndex, (detailLabel, value) ->
                para("$detailLabel: $value", keepNext = detailIndex < details.lastIndex)
            }
        }
        docXml.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"720\" w:right=\"720\" w:bottom=\"720\" w:left=\"720\"/></w:sectPr></w:body></w:document>")
        val rels = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
            images.indices.forEach { append("<Relationship Id=\"rId${it + 2}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/receipt${it + 1}.jpg\"/>") }
            signatures.indices.forEach { append("<Relationship Id=\"rId${images.size + it + 2}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/signature${it + 1}.png\"/>") }
            append("</Relationships>")
        }
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            fun entry(name: String, content: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry() }
            entry("[Content_Types].xml", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"jpg\" ContentType=\"image/jpeg\"/><Default Extension=\"png\" ContentType=\"image/png\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>").toByteArray())
            entry("_rels/.rels", "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>".toByteArray())
            entry("word/document.xml", docXml.toString().toByteArray())
            entry("word/_rels/document.xml.rels", rels.toByteArray())
            entry("word/styles.xml", "<?xml version=\"1.0\"?><w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\"/><w:sz w:val=\"20\"/></w:rPr></w:style></w:styles>".toByteArray())
            images.forEachIndexed { i, info ->
                val bitmap = decode(info)
                val imageOut = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, imageOut)
                bitmap.recycle()
                entry("word/media/receipt${i + 1}.jpg", imageOut.toByteArray())
            }
            signatures.forEachIndexed { i, signature -> entry("word/media/signature${i + 1}.png", signature.bytes) }
        }
    }

    private fun parseXml(bytes: ByteArray): Document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(bytes.inputStream())
    private fun stripCompatibilityMetadata(doc: Document) {
        val elements = doc.getElementsByTagName("*")
        for (i in 0 until elements.length) {
            val element = elements.item(i) as? Element ?: continue
            element.removeAttributeNS(MC_NS, "Ignorable")
            val attrs = element.attributes
            val remove = mutableListOf<Node>()
            for (j in 0 until attrs.length) {
                val attr = attrs.item(j)
                if (attr.namespaceURI == X14AC_NS || attr.namespaceURI == X16R2_NS) remove += attr
            }
            remove.forEach { element.removeAttributeNode(it as org.w3c.dom.Attr) }
        }
    }
    private fun serializeXml(doc: Document): ByteArray {
        val serialized = ByteArrayOutputStream().also { out ->
            TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.ENCODING, "UTF-8")
                setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            }.transform(DOMSource(doc), StreamResult(out))
        }.toString(Charsets.UTF_8.name())
        // Android's XML transformer may encode a supplementary Unicode
        // character as two separate numeric references to its UTF-16
        // surrogates. XML 1.0 forbids references to surrogate code points.
        // Replace each valid pair with one reference to the scalar value.
        val validXml = SURROGATE_REFERENCE_PAIR.replace(serialized) { match ->
            val high = match.groupValues[1].toInt()
            val low = match.groupValues[2].toInt()
            if (high !in 0xD800..0xDBFF || low !in 0xDC00..0xDFFF) return@replace match.value
            val codePoint = 0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00)
            "&#$codePoint;"
        }
        return validXml.toByteArray(Charsets.UTF_8)
    }
    private fun columnName(n: Int): String { var value=n; var out=""; while(value>0){val rem=(value-1)%26;out=('A'.code+rem).toChar()+out;value=(value-1)/26};return out }
    private fun columnIndex(s: String): Int = s.fold(0) { a,c -> a*26+(c.uppercaseChar().code-'A'.code+1) }
    private fun numeric(value: String): String? = value.trim().replace(',', '.').toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()
    private fun decimal(value: String): BigDecimal = value.trim().replace(',', '.').toBigDecimalOrNull() ?: error("Invalid financial amount")
    private fun receiptTitle(receipt: ReportReceipt, index: Int): String = receipt.name?.trim()?.takeIf(String::isNotEmpty)
        ?.take(MAX_RECEIPT_TITLE_CHARS) ?: "Receipt ${index + 1}"
    private fun claimMetadataLines(claim: ReportClaim): List<String> = buildList {
        val profile = claim.details.staffProfile ?: claim.staffProfile
        claim.claimantName.trim().takeIf(String::isNotEmpty)?.let { add("Claimant: $it") }
        (claim.details.position ?: profile.position).trim().takeIf(String::isNotEmpty)?.let { add("Position: $it") }
        val start = claim.startDate?.trim()?.takeIf(String::isNotEmpty)
        val end = claim.endDate?.trim()?.takeIf(String::isNotEmpty)
        when {
            start != null && end != null -> add("Period: $start to $end")
            start != null -> add("From: $start")
            end != null -> add("Through: $end")
        }
    }
    private fun claimDetailsLines(claim: ReportClaim): List<String> = buildList {
        val details = claim.details
        details.paymentMethod?.trim()?.takeIf(String::isNotEmpty)?.let { add("Payment method: $it") }
        details.chequeNumber?.trim()?.takeIf(String::isNotEmpty)?.let { add("Cheque number: $it") }
        details.paynowReference?.trim()?.takeIf(String::isNotEmpty)?.let { add("PayNow reference: $it") }
        details.cashAdvance?.trim()?.takeIf(String::isNotEmpty)?.let { add("Cash advance (SGD): $it") }
        listOf(
            "Submitted by" to (details.submittedBy to details.submittedDate),
            "Checked by" to (details.checkedBy to details.checkedDate),
            "Approved by" to (details.approvedBy to details.approvedDate),
            "Received by" to (details.receivedBy to details.receivedDate),
        ).forEach { (label, values) ->
            val name = values.first?.trim()?.takeIf(String::isNotEmpty)
            val date = values.second?.trim()?.takeIf(String::isNotEmpty)
            if (name != null || date != null) add(listOfNotNull(name?.let { "$label: $it" }, date?.let { "Date: $it" }).joinToString(" · "))
        }
    }
    private fun receiptLabel(receipt: ReportReceipt): String {
        val name = receipt.name?.trim()?.takeIf(String::isNotEmpty)
        val description = receipt.description?.trim()?.takeIf(String::isNotEmpty)
        val details = listOfNotNull(name, description, receipt.note?.trim()?.takeIf(String::isNotEmpty))
            .fold(mutableListOf<String>()) { result, value -> if (result.none { it.equals(value, ignoreCase = true) }) result.apply { add(value) } else result }
        return details.joinToString(" — ")
    }
    private fun receiptDetails(receipt: ReportReceipt): List<Pair<String, String>> {
        val title = receipt.name?.trim()?.takeIf(String::isNotEmpty)
        val seen = mutableSetOf<String>()
        return listOf("Description" to receipt.description, "Note" to receipt.note).mapNotNull { (label, raw) ->
            val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            if (title != null && value.equals(title, ignoreCase = true)) return@mapNotNull null
            if (!seen.add(value.lowercase(Locale.ROOT))) return@mapNotNull null
            label to value
        }
    }
    private fun excelDate(value: String): String? = try {
        val millis = try { java.time.Instant.parse(value).toEpochMilli() } catch (_: Exception) { java.time.LocalDate.parse(value.take(10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }
        BigDecimal.valueOf(millis).divide(BigDecimal.valueOf(86_400_000L), 8, RoundingMode.HALF_UP).add(BigDecimal.valueOf(25569L)).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    } catch (_: Exception) { null }
    private fun xmlEscape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    companion object {
        const val MAX_RECEIPTS = 500
        const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
        const val MAX_TOTAL_IMAGE_BYTES = 50L * 1024 * 1024
        const val MAX_PIXELS = 40_000_000L
        private const val MAX_DECODE_EDGE = 1800
        private const val EMU_PER_PIXEL = 9525.0
        private const val MAX_IMAGE_CX = 6_100_000.0
        private const val MAX_IMAGE_CY = 5_800_000.0
        private const val MAX_RECEIPT_TITLE_CHARS = 120
        private const val SIGNATURE_WIDTH_EMU = 2_400_000L
        private const val SIGNATURE_HEIGHT_EMU = 480_000L
        private val SURROGATE_REFERENCE_PAIR = Regex("&#(\\d{5});&#(\\d{5});")
        private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        private const val XML_NS = "http://www.w3.org/XML/1998/namespace"
        private const val MC_NS = "http://schemas.openxmlformats.org/markup-compatibility/2006"
        private const val XMLNS_NS = "http://www.w3.org/2000/xmlns/"
        private const val X14AC_NS = "http://schemas.microsoft.com/office/spreadsheetml/2009/9/ac"
        private const val X16R2_NS = "http://schemas.microsoft.com/office/spreadsheetml/2015/02/main"
        private const val CONTENT_TYPES_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
        private const val PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
        private const val OFFICE_REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val XDR_NS = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing"
        private const val DRAWING_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
        private const val EMPTY_RELATIONSHIPS = "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>"
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val PDF_MIME = "application/pdf"
    }
}
