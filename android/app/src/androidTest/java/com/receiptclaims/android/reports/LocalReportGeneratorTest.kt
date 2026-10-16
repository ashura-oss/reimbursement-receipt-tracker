package com.receiptclaims.android.reports

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.receiptclaims.android.data.local.ExpenseAccounts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(AndroidJUnit4::class)
class LocalReportGeneratorTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val mainNs = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

    @Test fun thirtyMixedCurrencyReceiptsExpandTemplateAndKeepSgdOnlyTotal() = runBlocking {
        val portrait = testImage("portrait", 900, 1600)
        val landscape = testImage("landscape", 1600, 900)
        val receipts = (1..30).map { i ->
            val isUsd = i % 2 == 0
            ReportReceipt("r$i", "12.50", if (isUsd) "USD" else "SGD", if (isUsd) "1.2" else "1", if (isUsd) "15.00" else "12.50", "2026-10-12", "Admin", if (i == 30) "Long description ".repeat(18) else "Receipt $i", if (i == 1) "🧾 airport transfer" else null, (if (isUsd) landscape else portrait).absolutePath, name = "Shop $i", expenseAccountId = ExpenseAccounts.all[(i - 1) % ExpenseAccounts.all.size].id)
        }
        val generated = LocalReportGenerator(context).generate(ReportClaim(-40001, "Mixed claim", "2026-10-01", "2026-10-15", "", receipts))
        assertEquals(listOf("XLSX", "DOCX", "PDF"), generated.map { it.type })
        assertTrue(generated.all { it.file.isFile && it.file.length() > 0 })
        val xlsx = generated.first { it.type == "XLSX" }.file
        val qaWorkbook = File(context.getExternalFilesDir(null) ?: context.filesDir, "account-code-qa.xlsx")
        xlsx.copyTo(qaWorkbook, overwrite = true)
        ZipFile(xlsx).use { zip ->
            val packageXml = zip.entries().asSequence().filter { it.name.endsWith(".xml") || it.name.endsWith(".rels") }
                .map { zip.getInputStream(it).use { input -> input.readBytes().toString(Charsets.UTF_8) } }.toList()
            assertFalse("Excel compatibility Ignorable lists must not reference missing namespace prefixes", packageXml.any { it.contains("Ignorable=") })
            val sheetBytes = zip.getInputStream(zip.getEntry("xl/worksheets/sheet2.xml")).readBytes()
            val sheetXml = sheetBytes.toString(Charsets.UTF_8)
            assertFalse("XLSX must not contain invalid XML references to UTF-16 surrogate code points", Regex("&#(\\d+);").findAll(sheetXml).any { it.groupValues[1].toInt() in 0xD800..0xDFFF })
            assertTrue("Supplementary Unicode must be retained as a valid XML code point", sheetXml.contains("&#129534;") || sheetXml.contains("🧾"))
            val sheet = parse(sheetBytes)
            val selections = sheet.getElementsByTagNameNS(mainNs, "selection")
            val activeSelection = (0 until selections.length).map { selections.item(it) as org.w3c.dom.Element }
                .firstOrNull { it.hasAttribute("activeCell") }
            assertEquals("Expanded active cell should move with its selected cell range", activeSelection?.getAttribute("sqref"), activeSelection?.getAttribute("activeCell"))
            assertFormula(sheet, "K9", "SUMIF(\$C\$21:\$C\$50,H9,\$O\$21:\$O\$50)")
            assertFormula(sheet, "O51", "SUM(O21:O50)")
            assertFormula(sheet, "F12", "SUMIF(\$C\$21:\$C\$50,B12,\$O\$21:\$O\$50)")
            assertFormula(sheet, "K10", "SUM(O29,O43)")
            assertFormula(sheet, "K11", "SUM(O30,O44)")
            assertFormula(sheet, "K16", "SUM(F9:F15)+SUM(K9:K15)")
            assertFalse("Blank account code must not be counted in the expense summaries", hasFormula(sheet, "F16"))
            assertEquals("SGD grand-total cache should include every receipt", "412.5", cellValue(sheet, "O51"))
            assertEquals("Account summary total cache should include every selected account", "412.5", cellValue(sheet, "K16"))
            assertEquals(listOf("37.5", "45", "25", "30", "25", "30", "25"), (9..15).map { cellValue(sheet, "F$it") })
            assertEquals(listOf("30", "25", "30", "25", "30", "25", "30"), (9..15).map { cellValue(sheet, "K$it") })
            assertEquals("Motor Vehicle summary must include only motor selections", "25", cellValue(sheet, "K10"))
            assertEquals("Forklift summary must include only forklift selections", "30", cellValue(sheet, "K11"))
            assertFalse("Mixed currencies must not be added in the source-currency column", hasFormula(sheet, "K51"))
            assertEquals("12.5", cellValue(sheet, "K21"))
            assertEquals("12.5", cellValue(sheet, "O21"))
            assertEquals("12.5", cellValue(sheet, "K50"))
            assertEquals("15", cellValue(sheet, "O50"))
            assertEquals("1", cellValue(sheet, "N21"))
            assertEquals("1.2", cellValue(sheet, "N50"))
            assertEquals("Exchange rates should retain small FX values", "0.########", numberFormat(zip, sheet, "N21"))
            assertEquals("Expanded receipt rows should retain the precise FX format", "0.########", numberFormat(zip, sheet, "N50"))
            (21..50).forEach { row ->
                assertEquals("Receipt description cell H$row should wrap", "1", wrapText(zip, sheet, "H$row"))
            }
            assertEquals("Shop 1 — Receipt 1 — 🧾 airport transfer", cellText(sheet, "H21"))
            assertEquals("SGD", cellText(sheet, "M21"))
            assertEquals("USD", cellText(sheet, "M50"))
            ExpenseAccounts.all.forEachIndexed { index, account ->
                val row = 21 + index
                assertEquals("Selected account code should populate receipt row $row", account.code, cellValue(sheet, "C$row"))
            }
            ExpenseAccounts.all.take(7).forEachIndexed { index, account ->
                assertEquals(account.code, cellValue(sheet, "B${9 + index}"))
            }
            ExpenseAccounts.all.drop(7).forEachIndexed { index, account ->
                assertEquals(account.code, cellValue(sheet, "H${9 + index}"))
            }
            assertEquals("Motor Vehicle Expenses", cellText(sheet, "I10"))
            assertEquals("Maintenance-Forklift", cellText(sheet, "I11"))
            assertEquals("Subscriptions", cellText(sheet, "I13"))
            assertEquals("752000", cellValue(sheet, "C29"))
            assertEquals("752000", cellValue(sheet, "C30"))
            val sgdTotal = (21..50).fold(BigDecimal.ZERO) { total, row -> total.add(BigDecimal(cellValue(sheet, "O$row")!!)) }
            assertEquals("412.5", sgdTotal.stripTrailingZeros().toPlainString())
            assertTrue("Date serial should be a positive Excel date", (cellValue(sheet, "B21")?.toDoubleOrNull() ?: 0.0) > 45_000)
            assertTrue("Total row must shift after the original 29-row receipt capacity", hasCell(sheet, "A51"))
            assertEquals("Classification", cellText(sheet, "S20"))
            val merges = (0 until sheet.getElementsByTagNameNS(mainNs, "mergeCell").length)
                .map { (sheet.getElementsByTagNameNS(mainNs, "mergeCell").item(it) as org.w3c.dom.Element).getAttribute("ref") }
            assertTrue("The merged total row must shift intact", merges.contains("A51:J51"))
            assertTrue("Workbook print area must include the classification column and expanded total", printArea(zip).contains("\$S59"))
            assertTrue("Long descriptions should expand the receipt row height", rowHeight(sheet, 50) > 30.0)
        }
        val docx = generated.first { it.type == "DOCX" }.file
        ZipFile(docx).use { zip ->
            assertNotNull(zip.getEntry("word/media/receipt30.jpg"))
            val xml = zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().use { it.readText() }
            assertEquals(30, Regex("<w:drawing>").findAll(xml).count())
            assertEquals("Receipt name should appear once as the heading", 1, "<w:t xml:space=\"preserve\">Shop 1</w:t>".toRegex().findAll(xml).count())
            assertTrue("Description should remain separate from the receipt name", xml.contains("Description: Receipt 1"))
            assertTrue("Distinct notes should be retained with Unicode", xml.contains("Note: 🧾 airport transfer"))
            assertTrue("Amount and date caption should follow each embedded receipt image", xml.indexOf("</w:drawing>") < xml.indexOf("Date: 2026-10-12"))
            val doc = parse(xml.toByteArray())
            val extent = doc.getElementsByTagName("wp:extent").item(0) as org.w3c.dom.Element
            val extents = doc.getElementsByTagName("wp:extent")
            val first = extents.item(0) as org.w3c.dom.Element
            val second = extents.item(1) as org.w3c.dom.Element
            listOf(first, second).forEach { size ->
                assertTrue("DOCX image width should remain visible", size.getAttribute("cx").toLong() >= 300_000L)
                assertTrue("DOCX image width should fit the page", size.getAttribute("cx").toLong() <= 6_100_000L)
                assertTrue("DOCX image height should remain visible", size.getAttribute("cy").toLong() >= 300_000L)
                assertTrue("DOCX image height should fit the page", size.getAttribute("cy").toLong() <= 5_800_000L)
            }
            assertEquals(900.0 / 1600.0, first.getAttribute("cx").toDouble() / first.getAttribute("cy").toDouble(), 0.01)
            assertEquals(1600.0 / 900.0, second.getAttribute("cx").toDouble() / second.getAttribute("cy").toDouble(), 0.01)
            assertTrue("Receipt heading should stay attached to its image", xml.contains("<w:pPr><w:keepNext/></w:pPr>"))
            assertTrue("Image paragraph should stay attached to its caption", xml.contains("<w:p><w:pPr><w:keepNext/></w:pPr><w:r><w:drawing>"))
        }
        assertTrue(generated.first { it.type == "PDF" }.file.readBytes().take(4).toByteArray().decodeToString().startsWith("%PDF"))
    }

    @Test fun singleCurrencyClaimMaySumItsReceiptAmount() = runBlocking {
        val image = testImage("single", 400, 600)
        val receipt = ReportReceipt("one", "8.25", "EUR", "1.45", "11.96", "2026-10-13", "IT", "Taxi", null, image.absolutePath)
        val generated = LocalReportGenerator(context).generate(ReportClaim(-40002, "Single currency", null, null, "", listOf(receipt)))
        val emptyMetadataDocx = generated.first { it.type == "DOCX" }.file
        ZipFile(emptyMetadataDocx).use { zip ->
            val document = parse(zip.getInputStream(zip.getEntry("word/document.xml")).readBytes())
            val text = document.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "t")
            val combined = (0 until text.length).joinToString(" ") { text.item(it).textContent }
            assertFalse("Blank claimant should not produce a placeholder line", combined.contains("Claimant:"))
            assertFalse("Blank claim dates should not produce an empty period line", combined.contains("Period:"))
        }
        ZipFile(generated.first { it.type == "XLSX" }.file).use { zip ->
            val sheet = parse(zip.getInputStream(zip.getEntry("xl/worksheets/sheet2.xml")).readBytes())
            assertFormula(sheet, "K50", "SUM(K21:K21)")
            assertFormula(sheet, "O50", "SUM(O21:O21)")
        }
        val oneSided = LocalReportGenerator(context).generate(ReportClaim(-40004, "From-only claim", "2026-10-13", null, "", listOf(receipt)))
        ZipFile(oneSided.first { it.type == "DOCX" }.file).use { zip ->
            val document = parse(zip.getInputStream(zip.getEntry("word/document.xml")).readBytes())
            val text = document.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "t")
            val combined = (0 until text.length).joinToString(" ") { text.item(it).textContent }
            assertTrue("Start-only claim date should use a From label", combined.contains("From: 2026-10-13"))
            assertFalse("Start-only claim date should not produce a blank Through label", combined.contains("Through:"))
            assertFalse("Start-only claim date should not use an incomplete period label", combined.contains("Period:"))
        }
        val throughOnly = LocalReportGenerator(context).generate(ReportClaim(-40005, "Through-only claim", null, "2026-10-14", "", listOf(receipt)))
        ZipFile(throughOnly.first { it.type == "DOCX" }.file).use { zip ->
            val document = parse(zip.getInputStream(zip.getEntry("word/document.xml")).readBytes())
            val text = document.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "t")
            val combined = (0 until text.length).joinToString(" ") { text.item(it).textContent }
            assertTrue("End-only claim date should use a Through label", combined.contains("Through: 2026-10-14"))
            assertFalse("End-only claim date should not use an incomplete period label", combined.contains("Period:"))
        }
    }

    @Test fun corruptImageFailsBeforeAnyReportDirectoryIsPublished() = runBlocking {
        val id = -System.currentTimeMillis()
        val reportRoot = File(context.filesDir, "reports/$id")
        reportRoot.deleteRecursively()
        val bad = File(context.cacheDir, "corrupt-report-image.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val receipt = ReportReceipt("bad", "1", "SGD", "1", "1", "2026-10-14", "Admin", null, null, bad.absolutePath)
        var rejected = false
        try { LocalReportGenerator(context).generate(ReportClaim(id, "Bad image", null, null, "", listOf(receipt))) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue("Invalid receipt image should be rejected", rejected)
        assertFalse("No report output directory should appear for an invalid image", reportRoot.exists())
    }

    @Test fun longUnnamedUnicodeDescriptionAndNoteStayAfterTheReceiptImage() = runBlocking {
        val image = testImage("unicode", 900, 1600)
        val description = "咖啡 shop 🧾 · 東京 ".repeat(180)
        val note = "Traveler note — 新加坡 ".repeat(180)
        val receipt = ReportReceipt("unicode", "9.40", "JPY", "0.009", "0.08", "2026-10-14", "Catering", description, note, image.absolutePath)
        val generated = LocalReportGenerator(context).generate(ReportClaim(-40003, "Unicode report", null, null, "", listOf(receipt)))
        val docx = generated.first { it.type == "DOCX" }.file
        ZipFile(docx).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().use { it.readText() }
            assertTrue(xml.contains("Receipt 1"))
            val drawingEnd = xml.indexOf("</w:drawing>")
            val descriptionStart = xml.indexOf("Description:")
            assertTrue("Receipt image should precede its long description", drawingEnd >= 0 && descriptionStart > drawingEnd)
            val textNodes = parse(xml.toByteArray()).getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "t")
            val documentText = (0 until textNodes.length).joinToString("") { textNodes.item(it).textContent }
            // The renderer trims boundary whitespace before display; verify the
            // complete visible values, including every repeated Unicode phrase.
            assertTrue("Full long Unicode description should be retained", documentText.contains(description.trim()))
            assertTrue("Full long Unicode note should be retained", documentText.contains(note.trim()))
        }
        assertTrue("Long Unicode PDF content should still render", generated.first { it.type == "PDF" }.file.length() > 0)
    }

    private fun testImage(name: String, width: Int, height: Int): File = File(context.cacheDir, "report-test-$name.png").also { file ->
        if (file.exists()) return@also
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun parse(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(bytes.inputStream())
    private fun printArea(zip: ZipFile): String {
        val workbook = parse(zip.getInputStream(zip.getEntry("xl/workbook.xml")).readBytes())
        val names = workbook.getElementsByTagNameNS(mainNs, "definedName")
        return (0 until names.length).map { names.item(it) as org.w3c.dom.Element }
            .first { it.getAttribute("name") == "_xlnm.Print_Area" }.textContent
    }
    private fun numberFormat(zip: ZipFile, sheet: org.w3c.dom.Document, ref: String): String? {
        val styleIndex = cell(sheet, ref)?.getAttribute("s")?.toIntOrNull() ?: return null
        val styles = parse(zip.getInputStream(zip.getEntry("xl/styles.xml")).readBytes())
        val xfs = styles.getElementsByTagNameNS(mainNs, "cellXfs").item(0).childNodes
        val xf = xfs.item(styleIndex) as? org.w3c.dom.Element ?: return null
        val formatId = xf.getAttribute("numFmtId")
        val formats = styles.getElementsByTagNameNS(mainNs, "numFmt")
        return (0 until formats.length).map { formats.item(it) as org.w3c.dom.Element }
            .firstOrNull { it.getAttribute("numFmtId") == formatId }?.getAttribute("formatCode")
    }
    private fun wrapText(zip: ZipFile, sheet: org.w3c.dom.Document, ref: String): String? {
        val styleIndex = cell(sheet, ref)?.getAttribute("s")?.toIntOrNull() ?: return null
        val styles = parse(zip.getInputStream(zip.getEntry("xl/styles.xml")).readBytes())
        val xfs = styles.getElementsByTagNameNS(mainNs, "cellXfs").item(0).childNodes
        val xf = xfs.item(styleIndex) as? org.w3c.dom.Element ?: return null
        return (xf.getElementsByTagNameNS(mainNs, "alignment").item(0) as? org.w3c.dom.Element)?.getAttribute("wrapText")
    }
    private fun rowHeight(sheet: org.w3c.dom.Document, rowNumber: Int): Double =
        (0 until sheet.getElementsByTagNameNS(mainNs, "row").length)
            .map { sheet.getElementsByTagNameNS(mainNs, "row").item(it) as org.w3c.dom.Element }
            .first { it.getAttribute("r").toInt() == rowNumber }.getAttribute("ht").toDouble()
    private fun cell(doc: org.w3c.dom.Document, ref: String): org.w3c.dom.Element? {
        val cells = doc.getElementsByTagNameNS(mainNs, "c")
        return (0 until cells.length).map { cells.item(it) as org.w3c.dom.Element }.firstOrNull { it.getAttribute("r") == ref }
    }
    private fun cellValue(doc: org.w3c.dom.Document, ref: String): String? = cell(doc, ref)?.getElementsByTagNameNS(mainNs, "v")?.item(0)?.textContent
    private fun cellText(doc: org.w3c.dom.Document, ref: String): String? = cell(doc, ref)?.getElementsByTagNameNS(mainNs, "t")?.item(0)?.textContent
    private fun hasCell(doc: org.w3c.dom.Document, ref: String) = cell(doc, ref) != null
    private fun hasFormula(doc: org.w3c.dom.Document, ref: String) = (cell(doc, ref)?.getElementsByTagNameNS(mainNs, "f")?.length ?: 0) > 0
    private fun assertFormula(doc: org.w3c.dom.Document, ref: String, expected: String) {
            val formulaElement = cell(doc, ref)?.getElementsByTagNameNS(mainNs, "f")?.item(0) as? org.w3c.dom.Element
            val formula = formulaElement?.textContent
        assertEquals("Incorrect formula in $ref", expected, formula)
    }
}
