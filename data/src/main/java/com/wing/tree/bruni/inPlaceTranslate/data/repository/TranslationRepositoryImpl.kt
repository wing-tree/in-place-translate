package com.wing.tree.bruni.inPlaceTranslate.data.repository

import android.icu.util.Calendar
import com.wing.tree.bruni.core.constant.EMPTY
import com.wing.tree.bruni.core.constant.ONE
import com.wing.tree.bruni.inPlaceTranslate.data.BuildConfig
import com.wing.tree.bruni.inPlaceTranslate.data.entity.Request
import com.wing.tree.bruni.inPlaceTranslate.data.entity.Request.Body
import com.wing.tree.bruni.inPlaceTranslate.data.entity.Translation
import com.wing.tree.bruni.inPlaceTranslate.data.mapper.TranslationMapper
import com.wing.tree.bruni.inPlaceTranslate.domain.enum.DataSource
import com.wing.tree.bruni.inPlaceTranslate.domain.repository.TranslationRepository
import com.wing.tree.bruni.inPlaceTranslate.domain.useCase.IncrementCharactersUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Period
import javax.inject.Inject
import com.wing.tree.bruni.inPlaceTranslate.data.source.local.TranslationDataSource as LocalDataSource
import com.wing.tree.bruni.inPlaceTranslate.data.source.remote.TranslationDataSource as RemoteDataSource
import com.wing.tree.bruni.inPlaceTranslate.domain.model.Translation as Model

class TranslationRepositoryImpl @Inject constructor(
    private val localDataSource: LocalDataSource,
    private val remoteDataSource: RemoteDataSource,
    private val incrementCharactersUseCase: IncrementCharactersUseCase
) : TranslationRepository {
    private val expiredAt: Long get() = Calendar.getInstance().apply {
        add(Calendar.MONTH, expirationPeriod.months)
    }.timeInMillis

    private val ioDispatcher = Dispatchers.IO
    private val coroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val translationMapper = TranslationMapper()

    override suspend fun all(
        sourceText: String,
        target: String
    ): List<Model> {
        return localDataSource.all(sourceText, target)
    }

    override suspend fun insert(translation: Model) {
        localDataSource.insert(translation.toEntity())
    }

    override suspend fun insertAll(list: List<Model>) {
        localDataSource.insertAll(list.map { it.toEntity() })
    }

    override suspend fun translate(
        dataSource: DataSource,
        source: String,
        sourceText: String,
        target: String
    ): List<Model> {
        return when(dataSource) {
            DataSource.DEFAULT -> with(localDataSource.all(sourceText, target)) {
                filterNotExpired().ifEmpty { translate(sourceText, source, target) }
            }
            else -> translate(sourceText, source, target)
        }
    }

    private suspend fun translate(sourceText: String, source: String, target: String): List<Translation> {
        if (sourceText.isBlank()) {
            return emptyList()
        }

        val body = Body(format = FORMAT, q = sourceText, source = source, target = target)
        val request = Request(key = BuildConfig.API_KEY, body = body)

        val response = remoteDataSource.translate(request)
        val translations = response.data.translations

        return translations.map {
            val translatedText = it.translatedText

            coroutineScope.launch {
                incrementCharactersUseCase(translatedText.length)
            }

            Translation(
                rowid = it.rowid(source, sourceText, target),
                detectedSourceLanguage = it.detectedSourceLanguage ?: EMPTY,
                expiredAt = expiredAt,
                source = source,
                sourceText = sourceText,
                target = target,
                translatedText = translatedText
            )
        }.also {
            coroutineScope.launch(ioDispatcher) {
                insertAll(it)
            }
        }
    }

    private fun Model.toEntity() = translationMapper.toEntity(this)

    private fun Translation.isExpired(): Boolean {
        val calendar = Calendar.getInstance()
        val timeInMillis = calendar.timeInMillis

        return expiredAt < timeInMillis
    }

    private fun List<Translation>.filterNotExpired() = filterNot { it.isExpired() }

    companion object {
        private const val FORMAT = "text"
        val expirationPeriod: Period = Period.ofMonths(ONE)
    }
}
