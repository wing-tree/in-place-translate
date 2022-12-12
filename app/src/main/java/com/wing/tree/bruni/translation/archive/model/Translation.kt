package com.wing.tree.bruni.translation.archive.model

import com.wing.tree.bruni.translation.archive.domain.model.Translation

data class Translation(
    override val detectedSourceLanguage: String,
    override val sourceText: String,
    override val target: String,
    override val translatedText: String
) : Translation