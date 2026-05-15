package com.competra.data.response.base

import com.google.gson.annotations.SerializedName

open class BaseModel(
    @SerializedName("status")
    var status: Int? = null,

    @SerializedName("errors")
    var errors: List<BaseError>? = null
)