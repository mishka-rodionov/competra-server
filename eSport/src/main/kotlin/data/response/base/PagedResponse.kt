package com.competra.data.response.base

import com.google.gson.annotations.SerializedName

/** Страница списка: элементы + признак наличия следующей страницы (без отдельного count-запроса). */
data class PagedResponse<T>(
    @SerializedName("items")
    val items: List<T>,
    @SerializedName("hasMore")
    val hasMore: Boolean
)
