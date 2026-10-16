package com.receiptclaims.android.data.local

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

data class StaffProfile(
    val name: String = "",
    val position: String = "",
    val businessUnit: String = "",
    val costCentre1: String = "",
    val departmentCode: String = ""
)

data class NormalizedPoint(val x: Float, val y: Float)
data class SignatureStroke(val points: List<NormalizedPoint>)

data class ReceiptDetails(
    val travelStart: String? = null,
    val travelEnd: String? = null,
    val fromLocation: String? = null,
    val toLocation: String? = null,
    val purpose: String? = null,
    val eventName: String? = null,
    val gstAmount: String? = null,
    val businessUnit: String? = null,
    val costCentre1: String? = null,
    val departmentCode: String? = null
) {
    fun displayLines(): List<String> = buildList {
        travelStart?.let { start -> add("Travel time: $start${travelEnd?.let { " to $it" } ?: ""}") }
        fromLocation?.let { add("From: $it") }; toLocation?.let { add("To: $it") }
        purpose?.let { add("Purpose: $it") }; eventName?.let { add("Event: $it") }
        gstAmount?.let { add("GST included: $it") }; businessUnit?.let { add("Business unit: $it") }
        costCentre1?.takeIf(String::isNotBlank)?.let { add("Cost centre 1: $it") }; departmentCode?.takeIf(String::isNotBlank)?.let { add("Department code: $it") }
    }
}

data class ClaimDetails(
    val position: String? = null,
    val paymentMethod: String? = null,
    val chequeNumber: String? = null,
    val cashAdvance: String? = null,
    val submittedBy: String? = null,
    val submittedDate: String? = null,
    val checkedBy: String? = null,
    val checkedDate: String? = null,
    val approvedBy: String? = null,
    val approvedDate: String? = null,
    val receivedBy: String? = null,
    val receivedDate: String? = null,
    val submittedSignature: List<SignatureStroke> = emptyList(),
    val checkedSignature: List<SignatureStroke> = emptyList(),
    val approvedSignature: List<SignatureStroke> = emptyList(),
    val receivedSignature: List<SignatureStroke> = emptyList(),
    val staffProfile: StaffProfile? = null,
    val paynowReference: String? = null
)

/** Canonical serializer and validation boundary shared by UI, backup, and report generation. */
object ReportDetailsCodec {
    const val STAFF_PROFILE_SETTING_KEY = "staffProfile"
    const val CLAIMANT_NAME_SETTING_KEY = "claimantName"
    val businessUnits = listOf("Scientific", "Medical", "Healthcare", "Service", "Shared", "Esco Aster")

    fun validateStaffProfile(value: StaffProfile): StaffProfile = value.copy(
        name = text(value.name, "Staff name", 120),
        position = text(value.position, "Position", 120),
        businessUnit = businessUnit(value.businessUnit),
        costCentre1 = text(value.costCentre1, "Cost centre 1", 80),
        departmentCode = text(value.departmentCode, "Department code", 80)
    )

    fun encodeStaffProfile(value: StaffProfile): String = staffToJson(validateStaffProfile(value)).toString()
    fun decodeStaffProfile(json: String): StaffProfile = try {
        staffFromJson(JSONObject(json)).let(::validateStaffProfile)
    } catch (e: IllegalArgumentException) { throw e } catch (e: Exception) {
        throw IllegalArgumentException("Invalid staff profile details.", e)
    }

    fun validateReceipt(value: ReceiptDetails, grossAmount: String? = null): ReceiptDetails {
        val gst = value.gstAmount?.trim()?.takeIf(String::isNotEmpty)?.let { raw ->
            val parsed = decimal(raw, "GST amount", 6)
            require(parsed >= BigDecimal.ZERO) { "GST amount cannot be negative." }
            grossAmount?.let {
                val gross = decimal(it, "Receipt amount", 6)
                require(parsed <= gross) { "GST amount cannot exceed the receipt total." }
            }
            parsed.stripTrailingZeros().toPlainString()
        }
        return value.copy(
            travelStart = time(value.travelStart, "Travel start"),
            travelEnd = time(value.travelEnd, "Travel end"),
            fromLocation = nullableText(value.fromLocation, "From", 160),
            toLocation = nullableText(value.toLocation, "To", 160),
            purpose = nullableText(value.purpose, "Purpose", 500),
            eventName = nullableText(value.eventName, "Event name", 160),
            gstAmount = gst,
            businessUnit = value.businessUnit?.let(::businessUnit),
            costCentre1 = value.costCentre1?.let { text(it, "Cost centre 1", 80) },
            departmentCode = value.departmentCode?.let { text(it, "Department code", 80) }
        )
    }

    fun encodeReceipt(value: ReceiptDetails, grossAmount: String? = null): String = receiptToJson(validateReceipt(value, grossAmount)).toString()
    fun decodeReceipt(json: String, grossAmount: String? = null): ReceiptDetails = try {
        receiptFromJson(JSONObject(json)).let { validateReceipt(it, grossAmount) }
    } catch (e: IllegalArgumentException) { throw e } catch (e: Exception) {
        throw IllegalArgumentException("Invalid receipt details.", e)
    }

    fun encodeReceiptDraft(value: ReceiptDetails): String = receiptToJson(value).toString()
    fun decodeReceiptDraft(json: String): ReceiptDetails = try { receiptFromJson(JSONObject(json)) } catch (_: Exception) { ReceiptDetails() }

    fun validateClaim(value: ClaimDetails): ClaimDetails {
        val method = value.paymentMethod?.trim()?.takeIf(String::isNotEmpty)
        require(method == null || method in setOf("Cash", "Cheque", "PayNow")) { "Payment method must be Cash, Cheque, or PayNow." }
        val advance = value.cashAdvance?.trim()?.takeIf(String::isNotEmpty)?.let {
            val parsed = decimal(it, "Cash advance", 2)
            require(parsed >= BigDecimal.ZERO) { "Cash advance cannot be negative." }
            parsed.stripTrailingZeros().toPlainString()
        }
        return value.copy(
            position = value.position?.let { text(it, "Position", 120) },
            paymentMethod = method,
            chequeNumber = nullableText(value.chequeNumber, "Cheque number", 80),
            cashAdvance = advance,
            submittedBy = nullableText(value.submittedBy, "Submitted by", 120),
            submittedDate = date(value.submittedDate, "Submitted date"),
            checkedBy = nullableText(value.checkedBy, "Checked by", 120),
            checkedDate = date(value.checkedDate, "Checked date"),
            approvedBy = nullableText(value.approvedBy, "Approved by", 120),
            approvedDate = date(value.approvedDate, "Approved date"),
            receivedBy = nullableText(value.receivedBy, "Received by", 120),
            receivedDate = date(value.receivedDate, "Received date"),
            submittedSignature = validateStrokes(value.submittedSignature),
            checkedSignature = validateStrokes(value.checkedSignature),
            approvedSignature = validateStrokes(value.approvedSignature),
            receivedSignature = validateStrokes(value.receivedSignature),
            staffProfile = value.staffProfile?.let(::validateStaffProfile),
            paynowReference = nullableText(value.paynowReference, "PayNow reference", 120)
        )
    }

    fun encodeClaim(value: ClaimDetails): String = claimToJson(validateClaim(value)).toString()
    fun decodeClaim(json: String): ClaimDetails = try {
        claimFromJson(JSONObject(json)).let(::validateClaim)
    } catch (e: IllegalArgumentException) { throw e } catch (e: Exception) {
        throw IllegalArgumentException("Invalid claim details.", e)
    }

    /** Draft codecs preserve temporarily incomplete fields during activity recreation. */
    fun encodeClaimDraft(value: ClaimDetails): String = claimToJson(value).toString()
    fun decodeClaimDraft(json: String): ClaimDetails = try { claimFromJson(JSONObject(json)) } catch (e: Exception) { ClaimDetails() }

    private fun staffToJson(v: StaffProfile) = JSONObject().put("name", v.name).put("position", v.position)
        .put("businessUnit", v.businessUnit).put("costCentre1", v.costCentre1).put("departmentCode", v.departmentCode)
    private fun staffFromJson(o: JSONObject) = StaffProfile(o.optString("name", ""), o.optString("position", ""), o.optString("businessUnit", ""), o.optString("costCentre1", ""), o.optString("departmentCode", ""))

    private fun receiptToJson(v: ReceiptDetails) = JSONObject().apply {
        putOpt("travelStart", v.travelStart); putOpt("travelEnd", v.travelEnd); putOpt("fromLocation", v.fromLocation)
        putOpt("toLocation", v.toLocation); putOpt("purpose", v.purpose); putOpt("eventName", v.eventName)
        putOpt("gstAmount", v.gstAmount); putNullablePreservingBlank("businessUnit", v.businessUnit); putNullablePreservingBlank("costCentre1", v.costCentre1)
        putNullablePreservingBlank("departmentCode", v.departmentCode)
    }
    private fun receiptFromJson(o: JSONObject) = ReceiptDetails(
        o.optNullable("travelStart"), o.optNullable("travelEnd"), o.optNullable("fromLocation"), o.optNullable("toLocation"),
        o.optNullable("purpose"), o.optNullable("eventName"), o.optNullable("gstAmount"), o.optNullable("businessUnit"),
        o.optNullable("costCentre1"), o.optNullable("departmentCode")
    )

    private fun claimToJson(v: ClaimDetails) = JSONObject().apply {
        if (v.position == null) put("position", JSONObject.NULL) else put("position", v.position)
        putOpt("paymentMethod", v.paymentMethod); putOpt("chequeNumber", v.chequeNumber)
        putOpt("cashAdvance", v.cashAdvance); putOpt("submittedBy", v.submittedBy); putOpt("submittedDate", v.submittedDate)
        putOpt("checkedBy", v.checkedBy); putOpt("checkedDate", v.checkedDate); putOpt("approvedBy", v.approvedBy)
        putOpt("approvedDate", v.approvedDate); putOpt("receivedBy", v.receivedBy); putOpt("receivedDate", v.receivedDate)
        put("submittedSignature", strokesToJson(v.submittedSignature)); put("checkedSignature", strokesToJson(v.checkedSignature))
        put("approvedSignature", strokesToJson(v.approvedSignature)); put("receivedSignature", strokesToJson(v.receivedSignature))
        v.staffProfile?.let { put("staffProfile", staffToJson(validateStaffProfile(it))) }
        putOpt("paynowReference", v.paynowReference)
    }
    private fun claimFromJson(o: JSONObject) = ClaimDetails(
        o.optNullable("position"), o.optNullable("paymentMethod"), o.optNullable("chequeNumber"), o.optNullable("cashAdvance"),
        o.optNullable("submittedBy"), o.optNullable("submittedDate"), o.optNullable("checkedBy"), o.optNullable("checkedDate"),
        o.optNullable("approvedBy"), o.optNullable("approvedDate"), o.optNullable("receivedBy"), o.optNullable("receivedDate"),
        strokesFromJson(o.optJSONArray("submittedSignature")), strokesFromJson(o.optJSONArray("checkedSignature")),
        strokesFromJson(o.optJSONArray("approvedSignature")), strokesFromJson(o.optJSONArray("receivedSignature")),
        if (o.has("staffProfile") && !o.isNull("staffProfile")) staffFromJson(o.getJSONObject("staffProfile")) else null,
        o.optNullable("paynowReference")
    )

    private fun strokesToJson(strokes: List<SignatureStroke>) = JSONArray().apply {
        strokes.forEach { stroke -> put(JSONArray().apply { stroke.points.forEach { put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) } }) }
    }
    private fun strokesFromJson(array: JSONArray?): List<SignatureStroke> {
        if (array == null) return emptyList()
        require(array.length() <= 20) { "A signature has too many strokes." }
        return (0 until array.length()).map { i ->
            val points = array.getJSONArray(i)
            require(points.length() in 1..500) { "A signature stroke has an invalid point count." }
            SignatureStroke((0 until points.length()).map { j ->
                val pair = points.getJSONArray(j)
                require(pair.length() == 2) { "A signature point is invalid." }
                NormalizedPoint(pair.getDouble(0).toFloat(), pair.getDouble(1).toFloat())
            })
        }
    }
    private fun validateStrokes(strokes: List<SignatureStroke>): List<SignatureStroke> {
        require(strokes.size <= 20 && strokes.sumOf { it.points.size } <= 2000) { "Signature is too detailed." }
        strokes.forEach { stroke ->
            require(stroke.points.isNotEmpty() && stroke.points.size <= 500) { "A signature stroke has an invalid point count." }
            stroke.points.forEach { point -> require(point.x.isFinite() && point.y.isFinite() && point.x in 0f..1f && point.y in 0f..1f) { "Signature points must be within the signing area." } }
        }
        return strokes
    }

    private fun businessUnit(v: String): String = v.trim().also { require(it.isEmpty() || it in businessUnits) { "Choose a listed business unit." } }
    private fun nullableBusinessUnit(v: String?): String? = v?.trim()?.takeIf(String::isNotEmpty)?.let(::businessUnit)
    private fun text(v: String, label: String, max: Int): String = v.trim().also { require(it.length <= max) { "$label is too long." } }
    private fun nullableText(v: String?, label: String, max: Int): String? = v?.trim()?.takeIf(String::isNotEmpty)?.also { require(it.length <= max) { "$label is too long." } }
    private fun time(v: String?, label: String): String? = nullableText(v, label, 5)?.also { require(runCatching { LocalTime.parse(it) }.isSuccess) { "$label must use HH:mm." } }
    private fun date(v: String?, label: String): String? = nullableText(v, label, 10)?.also { require(runCatching { LocalDate.parse(it) }.isSuccess) { "$label must use YYYY-MM-DD." } }
    private fun decimal(v: String, label: String, scale: Int): BigDecimal = try {
        require(v.length <= 64) { "$label is too long." }
        BigDecimal(v).also {
            require(it.scale().coerceAtLeast(0) <= scale && it.precision() <= 18) { "$label has too many decimal places or digits." }
            require(it.abs() <= BigDecimal("1000000000")) { "$label is too large." }
        }
    } catch (e: Exception) { throw IllegalArgumentException("$label must be a valid amount.", e) }
    private fun JSONObject.putOpt(key: String, value: String?) { if (value.isNullOrBlank()) put(key, JSONObject.NULL) else put(key, value) }
    private fun JSONObject.putNullablePreservingBlank(key: String, value: String?) { if (value == null) put(key, JSONObject.NULL) else put(key, value) }
    private fun JSONObject.optNullable(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
}
