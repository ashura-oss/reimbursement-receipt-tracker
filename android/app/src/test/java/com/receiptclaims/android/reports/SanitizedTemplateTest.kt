package com.receiptclaims.android.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class SanitizedTemplateTest {
    private val template = File("src/main/assets/reports/sanitizedtemplate.xlsx")
    private val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

    @Test fun templateKeepsClaimLayoutButContainsNoSampleClaimData() {
        assertTrue("Sanitized workbook asset is missing", template.isFile)
        ZipFile(template).use { zip ->
            val allXml = zip.entries().asSequence().filter { it.name.endsWith(".xml") || it.name.endsWith(".rels") }
                .joinToString("\n") { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
                .lowercase()
            assertFalse("Compatibility Ignorable tokens must not outlive their namespace bindings", allXml.contains("ignorable="))
            assertFalse("Unsupported row/font extension attributes must not remain in the sanitized workbook", allXml.contains("x14ac:") || allXml.contains("x16r2:"))
            assertTrue("The source workbook's original expense classification summary was lost", allXml.contains("sumif"))
            assertTrue("The workbook's amount paid column should be present", allXml.contains("amount excluding gst"))
            assertFalse("Sample signature artwork must not be distributed", zip.entries().asSequence().any { it.name.contains("drawing") || it.name.contains("media") || it.name.contains("comment") || it.name.startsWith("customXml/") })
            val claim = parse(zip.getInputStream(zip.getEntry("xl/worksheets/sheet2.xml")).readBytes())
            val sharedStrings = parse(zip.getInputStream(zip.getEntry("xl/sharedStrings.xml")).readBytes())
            val strings = sharedStrings.getElementsByTagNameNS(ns, "t")
            val sharedText = (0 until strings.length).map { strings.item(it).textContent }
            assertBlankCells(claim, sharedText, (5..6).flatMap { row -> (3..9).map { "${columnName(it)}$row" } }, "Claimant and position inputs")
            val approvalInputs = (55..58).flatMap { row ->
                (3..7).map { "${columnName(it)}$row" } +
                    (9..11).map { "${columnName(it)}$row" } +
                    (13..16).map { "${columnName(it)}$row" }
            }
            assertBlankCells(claim, sharedText, approvalInputs, "Approval and signature inputs")
            val coreProps = parse(zip.getInputStream(zip.getEntry("docProps/core.xml")).readBytes())
            assertBlankProperty(coreProps, "http://purl.org/dc/elements/1.1/", "creator", "Document author")
            assertBlankProperty(coreProps, "http://schemas.openxmlformats.org/package/2006/metadata/core-properties", "lastModifiedBy", "Last editor")
            val appProps = parse(zip.getInputStream(zip.getEntry("docProps/app.xml")).readBytes())
            assertBlankProperty(appProps, "http://schemas.openxmlformats.org/officeDocument/2006/extended-properties", "Company", "Company metadata")
            val formulaCells = claim.getElementsByTagNameNS(ns, "f")
            assertTrue("Template formulas must remain available for recalculation", formulaCells.length > 10)
            val allCells = claim.getElementsByTagNameNS(ns, "c")
            for (i in 0 until allCells.length) {
                val cell = allCells.item(i) as org.w3c.dom.Element
                if (cell.getElementsByTagNameNS(ns, "f").length > 0) {
                    assertEquals("Formula ${cell.getAttribute("r")} must not retain a sample cached value", 0, cell.getElementsByTagNameNS(ns, "v").length)
                }
            }
            val k9 = claim.getElementsByTagNameNS(ns, "c").let { cells ->
                (0 until cells.length).map { cells.item(it) }.first { (it as org.w3c.dom.Element).getAttribute("r") == "K9" } as org.w3c.dom.Element
            }
            assertTrue("K10 must retain its shared cost-centre SGD SUMIF formula", k9.getElementsByTagNameNS(ns, "f").item(0).textContent.contains("SUMIF"))
            val mergeCells = claim.getElementsByTagNameNS(ns, "mergeCell")
            val merges = (0 until mergeCells.length).map { (mergeCells.item(it) as org.w3c.dom.Element).getAttribute("ref") }
            assertFalse("The original receipt rows contain no merged ranges to replicate", merges.any { ref ->
                Regex("[A-Z]+(\\d+)").findAll(ref).any { it.groupValues[1].toInt() in 21..49 }
            })
            assertTrue("The source total row formatting is preserved", merges.contains("A50:J50"))
            val receiptRows = claim.getElementsByTagNameNS(ns, "row")
            for (rowNumber in 21..49) {
                val row = (0 until receiptRows.length).map { receiptRows.item(it) as org.w3c.dom.Element }
                    .firstOrNull { it.getAttribute("r").toIntOrNull() == rowNumber } ?: continue
                val cells = row.getElementsByTagNameNS(ns, "c")
                for (i in 0 until cells.length) {
                    val cell = cells.item(i) as org.w3c.dom.Element
                    assertFalse("Sanitized receipt row $rowNumber still has a cached sample value", cell.getElementsByTagNameNS(ns, "v").length > 0 || cell.getElementsByTagNameNS(ns, "is").length > 0)
                }
            }
        }
    }

    @Test fun bundledClassificationTabMatchesOfflineCategoryCatalog() {
        ZipFile(template).use { zip ->
            val strings = parse(zip.getInputStream(zip.getEntry("xl/sharedStrings.xml")).readBytes())
            val values = strings.getElementsByTagNameNS(ns, "t")
            val text = (0 until values.length).map { values.item(it).textContent }
            val expected = listOf("Accounts&Finance", "Admin", "Catering", "Engineering", "HR", "IT", "Shared", "Technical Service", "Warehouse")
            expected.forEach { assertTrue("Missing offline category $it", text.contains(it)) }
            val workbook = parse(zip.getInputStream(zip.getEntry("xl/workbook.xml")).readBytes())
            val names = workbook.getElementsByTagNameNS(ns, "definedName")
            val printArea = (0 until names.length).map { names.item(it) as org.w3c.dom.Element }
                .first { it.getAttribute("name") == "_xlnm.Print_Area" }.textContent
            assertTrue("Workbook print area should include the classification column", printArea.contains("$" + "S58"))
            val categoriesSheet = parse(zip.getInputStream(zip.getEntry("xl/worksheets/sheet9.xml")).readBytes())
            val cells = categoriesSheet.getElementsByTagNameNS(ns, "c")
            val visibleValues = (0 until cells.length).mapNotNull { i ->
                val cell = cells.item(i) as org.w3c.dom.Element
                val value = cell.getElementsByTagNameNS(ns, "v").item(0)?.textContent?.toIntOrNull() ?: return@mapNotNull null
                text.getOrNull(value)
            }
            assertEquals("The bundled dropdown should contain the title and exactly the app's nine categories", listOf("Cost Centre2") + expected, visibleValues)
        }
    }

    private fun parse(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(bytes.inputStream())

    private fun assertBlankCells(doc: org.w3c.dom.Document, sharedText: List<String>, refs: List<String>, label: String) {
        refs.forEach { ref ->
            val cells = doc.getElementsByTagNameNS(ns, "c")
            val cell = (0 until cells.length).map { cells.item(it) as org.w3c.dom.Element }
                .firstOrNull { it.getAttribute("r") == ref } ?: return@forEach
            assertEquals("$label cell $ref must not contain a formula", 0, cell.getElementsByTagNameNS(ns, "f").length)
            val raw = cell.getElementsByTagNameNS(ns, "v").item(0)?.textContent.orEmpty()
            val text = when (cell.getAttribute("t")) {
                "s" -> raw.toIntOrNull()?.let(sharedText::getOrNull).orEmpty()
                "inlineStr" -> cell.getElementsByTagNameNS(ns, "t").item(0)?.textContent.orEmpty()
                else -> raw
            }
            assertTrue("$label cell $ref must be blank", text.isBlank())
        }
    }

    private fun assertBlankProperty(doc: org.w3c.dom.Document, namespace: String, localName: String, label: String) {
        val nodes = doc.getElementsByTagNameNS(namespace, localName)
        assertTrue("$label must be blank", (0 until nodes.length).all { nodes.item(it).textContent.isNullOrBlank() })
    }

    private fun columnName(n: Int): String { var value=n; var out=""; while(value>0){val rem=(value-1)%26;out=('A'.code+rem).toChar()+out;value=(value-1)/26};return out }
}
