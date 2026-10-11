package com.receiptclaims.android.data.repository

import com.receiptclaims.android.core.network.ApiService
import com.receiptclaims.android.data.local.CategoryDao
import com.receiptclaims.android.data.local.CategoryEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepository @Inject constructor(
    private val api: ApiService,
    private val dao: CategoryDao
) {
    fun observeCategories(): Flow<List<CategoryEntity>> = dao.observeAll()

    suspend fun refresh() {
        val categories = requireNotNull(api.categories().data)
        dao.upsertAll(categories.map { CategoryEntity(it.id, it.name) })
    }
}
