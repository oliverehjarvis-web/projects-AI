package com.oli.projectsai.features.settings.update

data class UpdateInfo(
    val tagName: String,
    val downloadUrl: String,
    /** `tagName` with leading 'v' stripped, e.g. "1.1.2" */
    val version: String
)
