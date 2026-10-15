package com.receiptclaims.android.data.repository

import com.receiptclaims.android.data.local.CategoryDao
import com.receiptclaims.android.data.local.CategoryEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepository @Inject constructor(private val dao: CategoryDao) {
    fun observeCategories(): Flow<List<CategoryEntity>> = dao.observeAll()

    suspend fun ensureDefaults() {
        if (dao.all().isEmpty()) dao.upsertAll(DEFAULT_CATEGORIES)
    }

    companion object {
        val DEFAULT_CATEGORIES = listOf("Accounts&Finance", "Admin", "Catering", "Engineering", "HR", "IT", "Shared", "Technical Service", "Warehouse")
            .mapIndexed { index, name -> CategoryEntity(id = index + 1, name = name, accountCode = null) }
    }
}
