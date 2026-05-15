package com.competra.domain

import com.google.gson.annotations.SerializedName

enum class SportsCategory(val alias: String) {
    @SerializedName("first_junior")
    FIRST_JUNIOR("Iю"),
    @SerializedName("second_junior")
    SECOND_JUNIOR("IIю"),
    @SerializedName("third_junior")
    THIRD_JUNIOR("IIIю"),
    @SerializedName("first")
    FIRST("I"),
    @SerializedName("second")
    SECOND("II"),
    @SerializedName("third")
    THIRD("III"),
    @SerializedName("candidate")
    CANDIDATE("КМС"),
    @SerializedName("master_of_sport")
    MASTER_OF_SPORT("МС"),
    @SerializedName("international_master_of_sport")
    INTERNATIONAL_MASTER_OF_SPORT("МСМК"),

}