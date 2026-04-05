package com.sportenth.data.response.base

import com.google.gson.annotations.SerializedName

class CommonModel<T> : BaseModel() {
    @SerializedName("result")
    var result: T? = null
}