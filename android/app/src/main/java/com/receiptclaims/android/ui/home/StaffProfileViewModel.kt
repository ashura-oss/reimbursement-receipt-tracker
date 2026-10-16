package com.receiptclaims.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.receiptclaims.android.data.local.AppDatabase
import com.receiptclaims.android.data.local.LocalSettingEntity
import com.receiptclaims.android.data.local.ReportDetailsCodec
import com.receiptclaims.android.data.local.StaffProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StaffProfileViewModel @Inject constructor(private val database: AppDatabase) : ViewModel() {
    private val _profile = MutableStateFlow(StaffProfile())
    val profile: StateFlow<StaffProfile> = _profile.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    init { viewModelScope.launch { database.localSettingDao().get(PROFILE_KEY)?.let { json -> runCatching { ReportDetailsCodec.decodeStaffProfile(json) }.onSuccess { _profile.value = it } } } }
    fun update(value: StaffProfile) { _profile.value = value; _message.value = null }
    fun save() { viewModelScope.launch {
        runCatching { ReportDetailsCodec.encodeStaffProfile(_profile.value) }.onSuccess { encoded ->
            val settings = database.localSettingDao()
            val oldProfile = settings.get(PROFILE_KEY)?.let { runCatching { ReportDetailsCodec.decodeStaffProfile(it) }.getOrNull() } ?: StaffProfile()
            val savedClaimant = settings.get(ReportDetailsCodec.CLAIMANT_NAME_SETTING_KEY)
            settings.upsert(LocalSettingEntity(PROFILE_KEY, encoded))
            if (savedClaimant == null || savedClaimant == oldProfile.name) {
                settings.upsert(LocalSettingEntity(ReportDetailsCodec.CLAIMANT_NAME_SETTING_KEY, _profile.value.name))
            }
            _message.value = "Staff details saved on this device."
        }.onFailure { _message.value = it.message ?: "Unable to save staff details." }
    } }
    companion object { const val PROFILE_KEY = ReportDetailsCodec.STAFF_PROFILE_SETTING_KEY }
}
