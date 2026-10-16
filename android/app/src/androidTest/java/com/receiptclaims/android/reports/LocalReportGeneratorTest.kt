package com.receiptclaims.android.reports

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.receiptclaims.android.data.local.ExpenseAccounts
import com.receiptclaims.android.data.local.ClaimDetails
import com.receiptclaims.android.data.local.NormalizedPoint
import com.receiptclaims.android.data.local.ReceiptDetails
import com.receiptclaims.android.data.local.SignatureStroke
import com.receiptclaims.android.data.local.StaffProfile
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
    private val sortedReceiptKeys = listOf(
        "r14", "r28", "r21", "r7", "r22", "r8", "r13", "r27", "r1", "r15", "r29",
        "r10", "r24", "r23", "r9", "r12", "r26", "r20", "r6", "r11", "r25",
        "r16", "r2", "r30", "r17", "r3", "r18", "r4", "r19", "r5",
    )

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
        val qaDir = context.getExternalFilesDir(null) ?: context.filesDir
        generated.forEach { it.file.copyTo(File(qaDir, "account-code-qa.${it.type.lowercase()}"), overwrite = true) }
        val xlsx = generated.first { it.type == "XLSX" }.file
        val qaWorkbook = File(qaDir, "account-code-qa.xlsx")
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
            assertFormula(sheet, "K10", "SUM(O34:O35)")
            assertFormula(sheet, "K11", "SUM(O32:O33)")
            assertFormula(sheet, "K16", "SUM(F9:F15)+SUM(K9:K15)")
            assertFalse("Blank account code must not be counted in the expense summaries", hasFormula(sheet, "F16"))
            assertEquals("SGD grand-total cache should include every receipt", "412.5", cellValue(sheet, "O51"))
            assertEquals("Account summary total cache should include every selected account", "412.5", cellValue(sheet, "K16"))
            assertEquals(listOf("37.5", "45", "25", "30", "25", "30", "25"), (9..15).map { cellValue(sheet, "F$it") })
            assertEquals(listOf("30", "25", "30", "25", "30", "25", "30"), (9..15).map { cellValue(sheet, "K$it") })
            assertEquals("Motor Vehicle summary must include only motor selections", "25", cellValue(sheet, "K10"))
            assertEquals("Forklift summary must include only forklift selections", "30", cellValue(sheet, "K11"))
            assertFalse("Mixed currencies must not be added in the source-currency column", hasFormula(sheet, "K51"))
            assertEquals("15", cellValue(sheet, "O21"))
            assertEquals("12.5", cellValue(sheet, "K50"))
            assertEquals("12.5", cellValue(sheet, "O50"))
            assertEquals("1.2", cellValue(sheet, "N21"))
            assertEquals("1", cellValue(sheet, "N50"))
            assertEquals("Exchange rates should retain small FX values", "0.########", numberFormat(zip, sheet, "N21"))
            assertEquals("Expanded receipt rows should retain the precise FX format", "0.########", numberFormat(zip, sheet, "N50"))
            (21..50).forEach { row ->
                assertEquals("Receipt description cell H$row should wrap", "1", wrapText(zip, sheet, "H$row"))
            }
            assertEquals("Shop 14 — Receipt 14", cellText(sheet, "H21"))
            assertEquals("USD", cellText(sheet, "M21"))
            assertEquals("SGD", cellText(sheet, "M50"))
            val expectedSortedCodes = listOf("740140", "740140", "750500", "750500", "751500", "751500", "751600", "751600", "751900", "751900", "751900", "752000", "752000", "752000", "752000", "752200", "752200", "752300", "752300", "752800", "752800", "759500", "759500", "759500", "759600", "759600", "759700", "759700", "759800", "759800")
            expectedSortedCodes.forEachIndexed { index, code -> assertEquals("Sorted account code at row ${21 + index}", code, cellValue(sheet, "C${21 + index}")) }
            ExpenseAccounts.all.take(7).forEachIndexed { index, account ->
                assertEquals(account.code, cellValue(sheet, "B${9 + index}"))
            }
            ExpenseAccounts.all.drop(7).forEachIndexed { index, account ->
                assertEquals(account.code, cellValue(sheet, "H${9 + index}"))
            }
            assertEquals("Motor Vehicle Expenses", cellText(sheet, "I10"))
            assertEquals("Maintenance-Forklift", cellText(sheet, "I11"))
            assertEquals("Subscriptions", cellText(sheet, "I13"))
            assertEquals("752000", cellValue(sheet, "C32"))
            assertEquals("752000", cellValue(sheet, "C35"))
            sortedReceiptKeys.forEachIndexed { index, key -> assertEquals(key, requireNotNull(cellText(sheet, "H${21 + index}")).substringBefore(" — ").let { "r${it.removePrefix("Shop ").toInt()}" }) }
            val sgdTotal = (21..50).fold(BigDecimal.ZERO) { total, row -> total.add(BigDecimal(cellValue(sheet, "O$row")!!)) }
            assertEquals("412.5", sgdTotal.stripTrailingZeros().toPlainString())
            assertTrue("Date serial should be a positive Excel date", (cellValue(sheet, "B21")?.toDoubleOrNull() ?: 0.0) > 45_000)
            assertTrue("Total row must shift after the original 29-row receipt capacity", hasCell(sheet, "A51"))
            assertEquals("Classification", cellText(sheet, "S20"))
            val merges = (0 until sheet.getElementsByTagNameNS(mainNs, "mergeCell").length)
                .map { (sheet.getElementsByTagNameNS(mainNs, "mergeCell").item(it) as org.w3c.dom.Element).getAttribute("ref") }
            assertTrue("The merged total row must shift intact", merges.contains("A51:J51"))
            assertTrue("Workbook print area must include classification and Paynow row", printArea(zip).contains("\$S60"))
            assertTrue("Long descriptions should expand their sorted receipt row", rowHeight(sheet, 21 + sortedReceiptKeys.indexOf("r30")) > 30.0)
        }
        val docx = generated.first { it.type == "DOCX" }.file
        ZipFile(docx).use { zip ->
            assertNotNull(zip.getEntry("word/media/receipt30.jpg"))
            val xml = zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().use { it.readText() }
            assertEquals(30, Regex("<w:drawing>").findAll(xml).count())
            assertEquals("Receipt name should appear once in its numbered heading", 1, "Shop 1</w:t>".toRegex().findAll(xml).count())
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
            assertEquals(1600.0 / 900.0, first.getAttribute("cx").toDouble() / first.getAttribute("cy").toDouble(), 0.01)
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

    @Test fun fullTemplateUsesSnapshotsGroupsReceiptsAndAddsCashAdvanceOnce() = runBlocking {
        val image = testImage("full-form", 1200, 800)
        val profile = StaffProfile("Saved Profile Name", "Saved Profile Position", "Scientific", "CC-1", "DEP-1")
        val motor = ReportReceipt(
            "motor", "108.00", "SGD", "1", "108.00", "2026-10-14", "Motor", "Fuel", "Station 1", image.absolutePath,
            name = "Motor Shop", expenseAccountId = "motor_vehicle",
            details = ReceiptDetails(purpose = "Site visit", businessUnit = "Healthcare", costCentre1 = "CC-MOTOR", departmentCode = "DEP-M"),
        )
        val office = ReportReceipt(
            "office", "20.00", "SGD", "1", "20.00", "2026-10-12", "Office", "Paper", null, image.absolutePath,
            name = "Office Shop", expenseAccountId = "office_maintenance",
        )
        val forklift = ReportReceipt(
            "forklift", "50.00", "USD", "1.3", "65.00", "2026-10-13", "Repairs", "Hydraulic service", "Forklift 7", image.absolutePath,
            name = "Lift Repair", expenseAccountId = "forklift_maintenance",
            details = ReceiptDetails(travelStart = "09:15", travelEnd = "10:00", fromLocation = "Depot", toLocation = "Workshop", purpose = "Repair", eventName = "Service call", gstAmount = "5.00", businessUnit = "Medical", costCentre1 = "CC-FORK", departmentCode = "DEP-F"),
        )
        val details = ClaimDetails(
            position = "Entered Claim Position", paymentMethod = "PayNow", paynowReference = "REF-2026-16", cashAdvance = "25.00",
            submittedBy = "Submitted Person", submittedDate = "2026-10-15", checkedBy = "Checked Person", checkedDate = "2026-10-16",
            approvedBy = "Approver", approvedDate = "2026-10-16", receivedBy = "Receiver", receivedDate = "2026-10-17",
            submittedSignature = listOf(SignatureStroke(listOf(NormalizedPoint(.1f, .7f), NormalizedPoint(.5f, .2f), NormalizedPoint(.9f, .75f)))),
            staffProfile = profile,
        )
        val generated = LocalReportGenerator(context).generate(
            ReportClaim(-40006, "Full form claim", "2026-10-12", "2026-10-14", "Entered Claimant", listOf(motor, office, forklift), details, profile.copy(name = "ReportClaim Profile")),
        )
        val xlsx = generated.first { it.type == "XLSX" }.file
        val pdf = generated.first { it.type == "PDF" }.file
        val qaDir = context.getExternalFilesDir(null) ?: context.filesDir
        xlsx.copyTo(File(qaDir, "fullform-qa.xlsx"), overwrite = true)
        pdf.copyTo(File(qaDir, "fullform-qa.pdf"), overwrite = true)
        generated.first { it.type == "DOCX" }.file.copyTo(File(qaDir, "fullform-qa.docx"), overwrite = true)
        ZipFile(xlsx).use { zip ->
            val sheet = parse(zip.getInputStream(zip.getEntry("xl/worksheets/sheet2.xml")).readBytes())
            assertEquals("751900", cellValue(sheet, "C21"))
            assertEquals("752000", cellValue(sheet, "C22"))
            assertEquals("752000", cellValue(sheet, "C23"))
            assertEquals("20", cellValue(sheet, "K21"))
            assertEquals("45", cellValue(sheet, "K22"))
            assertEquals("5", cellValue(sheet, "L22"))
            assertEquals("108", cellValue(sheet, "K23"))
            assertEquals(null, cellValue(sheet, "L23"))
            assertEquals("108", cellValue(sheet, "O23"))
            assertEquals("108", cellValue(sheet, "K10"))
            assertEquals("65", cellValue(sheet, "K11"))
            assertEquals("20", cellValue(sheet, "N9"))
            assertEquals("65", cellValue(sheet, "N10"))
            assertEquals("108", cellValue(sheet, "N11"))
            assertEquals("193", cellValue(sheet, "N15"))
            assertEquals("193", cellValue(sheet, "O50"))
            assertEquals("25", cellValue(sheet, "O51"))
            assertFormula(sheet, "O52", "O50+O51")
            assertEquals("218", cellValue(sheet, "O52"))
            assertFalse("Mixed-currency net totals must stay blank", hasFormula(sheet, "K50"))
            assertFalse("Mixed-currency GST totals must stay blank", hasFormula(sheet, "L50"))
            assertEquals("Entered Claimant", cellText(sheet, "C5"))
            assertEquals("Entered Claim Position", cellText(sheet, "C6"))
            assertEquals("Amount Excluding GST", cellText(sheet, "K20"))
            assertEquals("GST Included", cellText(sheet, "L20"))
            assertEquals("Paynow: REF-2026-16", cellText(sheet, "B59"))
            assertEquals("Submitted by: Submitted Person", cellText(sheet, "H55"))
            assertEquals("2026-10-15", cellText(sheet, "M55"))
            assertEquals("Scientific", cellText(sheet, "P21"))
            assertEquals("CC-1", cellText(sheet, "Q21"))
            assertEquals("DEP-1", cellText(sheet, "R21"))
            assertEquals("Medical", cellText(sheet, "P22"))
            assertEquals("Healthcare", cellText(sheet, "P23"))
            assertTrue("Print area must contain the complete payment footer", printArea(zip).contains("\$S59"))
            val drawingEntry = zip.getEntry("xl/drawings/drawing1.xml")
            assertNotNull("Saved signature strokes must be embedded in the workbook", drawingEntry)
            val drawingXml = zip.getInputStream(drawingEntry).readBytes().decodeToString()
            assertTrue(drawingXml.contains("Submitted signature"))
            val drawingDoc = parse(drawingXml.toByteArray())
            val anchorExtent = drawingDoc.getElementsByTagNameNS("http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing", "ext").item(0) as org.w3c.dom.Element
            assertEquals(5.0, anchorExtent.getAttribute("cx").toDouble() / anchorExtent.getAttribute("cy").toDouble(), 0.001)
            assertNotNull("Signature image must be packaged", zip.getEntry("xl/media/signature1.png"))
            val contentTypes = parse(zip.getInputStream(zip.getEntry("[Content_Types].xml")).readBytes())
            val drawingOverrides = contentTypes.getElementsByTagNameNS("http://schemas.openxmlformats.org/package/2006/content-types", "Override")
            assertTrue("Drawing part must have the OOXML drawing content type", (0 until drawingOverrides.length).any { index ->
                val element = drawingOverrides.item(index) as org.w3c.dom.Element
                element.getAttribute("PartName") == "/xl/drawings/drawing1.xml" && element.getAttribute("ContentType") == "application/vnd.openxmlformats-officedocument.drawing+xml"
            })
            val sheetRels = zip.getInputStream(zip.getEntry("xl/worksheets/_rels/sheet2.xml.rels")).bufferedReader().use { it.readText() }
            assertTrue(sheetRels.contains("../drawings/drawing1.xml"))
        }
        ZipFile(generated.first { it.type == "DOCX" }.file).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().use { it.readText() }
            assertTrue(xml.contains("Entered Claimant"))
            assertTrue(xml.contains("Entered Claim Position"))
            assertTrue(xml.contains("751900 SG Office - Maintenance Expenses"))
            assertTrue(xml.contains("Amount excluding GST: USD 45.00"))
            assertTrue(xml.contains("GST included: USD 5.00"))
            assertTrue(xml.contains("Business unit: Medical"))
            assertTrue(xml.indexOf("1. Office Shop") < xml.indexOf("2. Lift Repair"))
            assertTrue(xml.indexOf("2. Lift Repair") < xml.indexOf("3. Motor Shop"))
            assertTrue("Receipt date and amount caption must precede long descriptions", xml.indexOf("Date: 2026-10-13") < xml.indexOf("Description: Hydraulic service"))
            assertTrue("DOCX must include the supplied approval signature", xml.contains("Approval signatures") && xml.contains("Submitted signature"))
            assertTrue("DOCX must package signature artwork", zip.getEntry("word/media/signature1.png") != null)
            val doc = parse(xml.toByteArray())
            val firstExtent = doc.getElementsByTagNameNS("http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing", "extent").item(0) as org.w3c.dom.Element
            assertEquals(5.0, firstExtent.getAttribute("cx").toDouble() / firstExtent.getAttribute("cy").toDouble(), 0.001)
        }
        assertTrue("The form and photo appendix should produce a readable PDF", pdf.length() > 10_000)
        assertTrue(pdf.readBytes().take(4).toByteArray().decodeToString().startsWith("%PDF"))
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertTrue("The claim form and all three photo pages should be present", renderer.pageCount >= 4)
                renderer.openPage(0).use { page -> assertTrue("Claim form PDF should be landscape", page.width > page.height) }
            }
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

    @Test fun signatureFitPreservesFiveToOneAspectRatioWithinAvailableBox() {
        val wide = fitReportSignature(300f, 40f)
        assertEquals(200f, wide.first, 0.001f)
        assertEquals(40f, wide.second, 0.001f)
        val short = fitReportSignature(300f, 20f)
        assertEquals(100f, short.first, 0.001f)
        assertEquals(20f, short.second, 0.001f)
        assertEquals(5f, short.first / short.second, 0.001f)
    }

    @Test fun longUnnamedUnicodeDescriptionAndNoteStayAfterTheReceiptImage() = runBlocking {
        val image = testImage("unicode", 900, 1600)
        val description = "咖啡 shop 🧾 · 東京 ".repeat(180)
        val note = "Traveler note — 新加坡 ".repeat(180)
        val receipt = ReportReceipt("unicode", "9.40", "JPY", "0.009", "0.08", "2026-10-14", "Catering", description, note, image.absolutePath)
        val generated = LocalReportGenerator(context).generate(ReportClaim(-40003, "Unicode report", null, null, "", listOf(receipt)))
        val qaDir = context.getExternalFilesDir(null) ?: context.filesDir
        generated.forEach { it.file.copyTo(File(qaDir, "longform-qa.${it.type.lowercase()}").apply { parentFile?.mkdirs() }, overwrite = true) }
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
        val pdf = generated.first { it.type == "PDF" }.file
        assertTrue("Long Unicode PDF content should still render", pdf.length() > 0)
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertTrue("Long text must continue on appendix pages instead of being clipped", renderer.pageCount >= 3)
            }
        }
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
