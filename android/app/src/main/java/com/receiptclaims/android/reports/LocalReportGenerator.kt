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
)

data class ReportClaim(
    val id: Long,
    val title: String,
    val startDate: String?,
    val endDate: String?,
    val claimantName: String,
    val receipts: List<ReportReceipt>,
)

data class GeneratedReportFile(val type: String, val name: String, val mimeType: String, val file: File)

/** Creates all claim exports on-device and publishes them as one complete generation. */
class LocalReportGenerator @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun generate(claim: ReportClaim): List<GeneratedReportFile> = withContext(Dispatchers.IO) {
        require(claim.id != 0L) { "Claim id must be nonzero" }
        require(claim.receipts.size <= MAX_RECEIPTS) { "A report can contain at most $MAX_RECEIPTS receipts" }
        // Validate every source image before creating any report files.
        claim.receipts.forEach { receipt ->
            require(numeric(receipt.amount) != null) { "Receipt ${receipt.key} has an invalid amount" }
            require(numeric(receipt.exchangeRate) != null) { "Receipt ${receipt.key} has an invalid exchange rate" }
            require(numeric(receipt.sgdEquivalent) != null) { "Receipt ${receipt.key} has an invalid SGD equivalent" }
            require(excelDate(receipt.date) != null) { "Receipt ${receipt.key} has an invalid date" }
        }
        var imageBytes = 0L
        val images = claim.receipts.map { receipt ->
            val info = validateImage(receipt)
            imageBytes += info.file.length()
            require(imageBytes <= MAX_TOTAL_IMAGE_BYTES) { "Receipt images exceed the 50 MiB report limit" }
            info
        }
        val reportRoot = File(context.filesDir, "reports/${claim.id}").apply { mkdirs() }
        val staging = File(reportRoot, ".${UUID.randomUUID()}.tmp").apply { mkdirs() }
        try {
            writeWorkbook(File(staging, "expenses.xlsx"), claim)
            writeDocx(File(staging, "receipt-images.docx"), claim, images)
            writePdf(File(staging, "combined.pdf"), claim, images)
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
            setCell(doc, header, "K20", "Receipt Amount (as paid)", null)
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
            setCell(doc, row, "A$rowNum", (index + 1).toString(), null)
            setCell(doc, row, "B$rowNum", excelDate(receipt.date), "numeric")
            setCell(doc, row, "C$rowNum", account?.code, "numeric")
            setCell(doc, row, "H$rowNum", receiptLabel(receipt), null)
            setCell(doc, row, "K$rowNum", numeric(receipt.amount), "numeric")
            setCell(doc, row, "M$rowNum", receipt.currency, null)
            setCell(doc, row, "N$rowNum", numeric(receipt.exchangeRate), "numeric")
            setCell(doc, row, "O$rowNum", numeric(receipt.sgdEquivalent), "numeric")
            setCell(doc, row, "S$rowNum", receipt.category, null)
            setRowHeight(row, receipt, existingRowHeight = row.getAttribute("ht").toDoubleOrNull() ?: 18.0)
        }
        writeExpenseAccountSummary(doc, sheetData, claim, lastData = 20 + claim.receipts.size.coerceAtLeast(1))
        // Formula totals only use SGD equivalents. Original currency amounts stay unsummed.
        val lastData = 20 + claim.receipts.size.coerceAtLeast(1)
        val totalRowNum = 50 + extras
        findRow(sheetData, totalRowNum)?.let { total ->
            setFormula(doc, total, "O$totalRowNum", "SUM(O21:O$lastData)", sumSgd(claim.receipts).stripTrailingZeros().toPlainString())
            val currencies = claim.receipts.map { it.currency.trim().uppercase(Locale.ROOT) }
            if (currencies.isNotEmpty() && currencies.all { it == currencies.first() }) {
                val originalTotal = claim.receipts.fold(BigDecimal.ZERO) { sum, receipt -> sum.add(decimal(receipt.amount)) }
                setFormula(doc, total, "K$totalRowNum", "SUM(K21:K$lastData)", originalTotal.stripTrailingZeros().toPlainString())
            } else {
                setCell(doc, total, "K$totalRowNum", null, null)
            }
        }
        setCell(doc, findRow(sheetData, 5) ?: error("Workbook name row missing"), "C5", claim.claimantName.trim(), null)
        // Remove template sample dates and people while retaining the approval block and styling.
        listOf(55, 56, 57, 58).forEach { r ->
            findRow(sheetData, r + extras)?.let { row ->
                (8..13).forEach { c ->
                    val ref = "${columnName(c)}${r + extras}"
                    val cell = findCell(row, ref) ?: return@forEach
                    val existing = cell.getElementsByTagNameNS(MAIN_NS, "t").item(0)?.textContent.orEmpty()
                    if (existing.startsWith("Submitted by:") || existing.startsWith("Checked by:") || existing.startsWith("Approved by:") || existing.startsWith("Received by:")) {
                        setCell(doc, row, ref, existing.substringBefore(':') + ":", null)
                    } else if (existing.trim().equals("DATE:", true)) setCell(doc, row, "${columnName(c + 1)}${r + extras}", null, null)
                }
            }
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
            name.textContent = shifted.replace(Regex(":(\\$?)P(\\$?\\d+)")) { ":${it.groupValues[1]}S${it.groupValues[2]}" }
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

    private fun sumSgd(receipts: List<ReportReceipt>): BigDecimal =
        receipts.fold(BigDecimal.ZERO) { total, receipt -> total.add(decimal(receipt.sgdEquivalent)) }

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
        val description = receiptLabel(receipt)
        val descriptionLines = estimateWrappedLines(description, 47)
        val categoryLines = estimateWrappedLines(receipt.category, 18)
        val lines = maxOf(1, descriptionLines, categoryLines)
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

    private fun writePdf(target: File, claim: ReportClaim, images: List<ImageInfo>) {
        val pdf = PdfDocument()
        val pageW = 595; val pageH = 842; val margin = 36f
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 10f }
        var pageNum = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f
        fun finishPage() {
            val current = page ?: return
            page = null
            canvas = null
            pdf.finishPage(current)
        }
        fun newPage() {
            finishPage()
            pageNum++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
            canvas = page!!.canvas
            y = 42f
        }
        fun text(value: String, size: Float = 10f, bold: Boolean = false, width: Int = pageW - 72) {
            paint.textSize = size; paint.typeface = if (bold) android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD) else android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            val layout = StaticLayout.Builder.obtain(value.ifBlank { " " }, 0, value.ifBlank { " " }.length, paint, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true).build()
            var firstLine = 0
            while (firstLine < layout.lineCount) {
                if (y >= pageH - 30) newPage()
                val top = layout.getLineTop(firstLine)
                val available = pageH - 30f - y
                var endLine = firstLine
                while (endLine < layout.lineCount && layout.getLineBottom(endLine) - top <= available) endLine++
                if (endLine == firstLine) { newPage(); continue }
                val bottom = layout.getLineBottom(endLine - 1)
                canvas!!.save()
                canvas!!.translate(margin, y - top)
                canvas!!.clipRect(0f, top.toFloat(), width.toFloat(), bottom.toFloat())
                layout.draw(canvas!!)
                canvas!!.restore()
                y += (bottom - top) + 6f
                firstLine = endLine
                if (firstLine < layout.lineCount) newPage()
            }
        }
        try {
        newPage()
        text(claim.title, 18f, true)
        claimMetadataLines(claim).forEach { text(it) }
        val sgdTotal = claim.receipts.fold(BigDecimal.ZERO) { sum, r -> sum.add(decimal(r.sgdEquivalent)) }
        text("Total S$ equivalent: ${sgdTotal.setScale(2, RoundingMode.HALF_UP)}", 12f, true)
        text("No.   Date          Receipt / Category / Currency and amount / SGD", 9f, true)
        claim.receipts.forEachIndexed { i, r ->
            text("${i + 1}. ${r.date}  ${receiptLabel(r)}\n${r.category} · ${r.currency} ${r.amount} · SGD ${r.sgdEquivalent}", 9f)
        }
        claim.receipts.forEachIndexed { i, receipt ->
            newPage()
            text(receiptTitle(receipt, i), 15f, true)
            val bitmap = decode(images[i])
            try {
                val available = RectF(margin, y + 6f, pageW - margin, pageH - 138f)
                check(available.width() > 0 && available.height() > 0) { "Receipt image area is too small" }
                val scale = minOf(available.width() / bitmap.width, available.height() / bitmap.height, 1f)
                val w = bitmap.width * scale; val h = bitmap.height * scale
                canvas!!.drawBitmap(bitmap, null, RectF(margin + (available.width() - w) / 2f, available.top, margin + (available.width() + w) / 2f, available.top + h), Paint(Paint.FILTER_BITMAP_FLAG))
            } finally {
                bitmap.recycle()
            }
            y = pageH - 125f
            receiptDetails(receipt).forEach { (label, value) -> text("$label: $value") }
            text("Date: ${receipt.date}  ·  Category: ${receipt.category}")
            text("Amount paid: ${receipt.currency} ${receipt.amount}  ·  Rate: ${receipt.exchangeRate}  ·  SGD: ${receipt.sgdEquivalent}")
        }
        finishPage()
        FileOutputStream(target).use(pdf::writeTo)
        } finally {
            try { finishPage() } catch (_: Throwable) { }
            pdf.close()
        }
    }

    private fun writeDocx(target: File, claim: ReportClaim, images: List<ImageInfo>) {
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
        val total = claim.receipts.fold(BigDecimal.ZERO) { sum, r -> sum.add(decimal(r.sgdEquivalent)) }
        para("Total S$ equivalent: ${total.setScale(2, RoundingMode.HALF_UP)}")
        claim.receipts.forEachIndexed { i, receipt ->
            val label = receiptTitle(receipt, i)
            if (i > 0) docXml.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            para(label, true, keepNext = true)
            val rid = "rId${i + 2}"
            val rawCx = images[i].width * EMU_PER_PIXEL
            val rawCy = images[i].height * EMU_PER_PIXEL
            val scale = minOf(1.0, MAX_IMAGE_CX / rawCx, MAX_IMAGE_CY / rawCy)
            val cx = (rawCx * scale).toLong().coerceAtLeast(1)
            val cy = (rawCy * scale).toLong().coerceAtLeast(1)
            docXml.append("<w:p><w:pPr><w:keepNext/></w:pPr><w:r><w:drawing><wp:inline><wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"${i + 1}\" name=\"Receipt ${i + 1}\"/><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr id=\"0\" name=\"receipt${i + 1}.jpg\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
            val details = receiptDetails(receipt)
            details.forEachIndexed { detailIndex, (detailLabel, value) ->
                para("$detailLabel: $value", keepNext = detailIndex < details.lastIndex)
            }
            para("Date: ${receipt.date} · Category: ${receipt.category} · Amount paid: ${receipt.currency} ${receipt.amount} · Rate: ${receipt.exchangeRate} · SGD: ${receipt.sgdEquivalent}")
        }
        docXml.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"720\" w:right=\"720\" w:bottom=\"720\" w:left=\"720\"/></w:sectPr></w:body></w:document>")
        val rels = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
            images.indices.forEach { append("<Relationship Id=\"rId${it + 2}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/receipt${it + 1}.jpg\"/>") }
            append("</Relationships>")
        }
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            fun entry(name: String, content: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry() }
            entry("[Content_Types].xml", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"jpg\" ContentType=\"image/jpeg\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>").toByteArray())
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
        claim.claimantName.trim().takeIf(String::isNotEmpty)?.let { add("Claimant: $it") }
        val start = claim.startDate?.trim()?.takeIf(String::isNotEmpty)
        val end = claim.endDate?.trim()?.takeIf(String::isNotEmpty)
        when {
            start != null && end != null -> add("Period: $start to $end")
            start != null -> add("From: $start")
            end != null -> add("Through: $end")
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
        private val SURROGATE_REFERENCE_PAIR = Regex("&#(\\d{5});&#(\\d{5});")
        private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        private const val XML_NS = "http://www.w3.org/XML/1998/namespace"
        private const val MC_NS = "http://schemas.openxmlformats.org/markup-compatibility/2006"
        private const val XMLNS_NS = "http://www.w3.org/2000/xmlns/"
        private const val X14AC_NS = "http://schemas.microsoft.com/office/spreadsheetml/2009/9/ac"
        private const val X16R2_NS = "http://schemas.microsoft.com/office/spreadsheetml/2015/02/main"
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val PDF_MIME = "application/pdf"
    }
}
