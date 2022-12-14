package com.wing.tree.bruni.inPlaceTranslate.view

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.method.ScrollingMovementMethod
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.ads.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.wing.tree.bruni.core.constant.EMPTY
import com.wing.tree.bruni.core.constant.ONE
import com.wing.tree.bruni.core.constant.ZERO
import com.wing.tree.bruni.core.extension.*
import com.wing.tree.bruni.core.regular.then
import com.wing.tree.bruni.core.useCase.Result
import com.wing.tree.bruni.inPlaceTranslate.BuildConfig
import com.wing.tree.bruni.inPlaceTranslate.R
import com.wing.tree.bruni.inPlaceTranslate.ad.InterstitialAdLoader
import com.wing.tree.bruni.inPlaceTranslate.ad.InterstitialAdLoaderImpl
import com.wing.tree.bruni.inPlaceTranslate.constant.DEGREE_180
import com.wing.tree.bruni.inPlaceTranslate.constant.LIMIT_CHARACTERS
import com.wing.tree.bruni.inPlaceTranslate.constant.PITCH
import com.wing.tree.bruni.inPlaceTranslate.constant.SPEECH_RATE
import com.wing.tree.bruni.inPlaceTranslate.databinding.ActivityProcessTextBinding
import com.wing.tree.bruni.inPlaceTranslate.extension.clear
import com.wing.tree.bruni.inPlaceTranslate.extension.letIsViewGroup
import com.wing.tree.bruni.inPlaceTranslate.regular.findDisplayLanguageByLanguage
import com.wing.tree.bruni.inPlaceTranslate.regular.findLanguageTagByLanguage
import com.wing.tree.bruni.inPlaceTranslate.regular.findLocaleByLanguage
import com.wing.tree.bruni.inPlaceTranslate.viewModel.ProcessTextViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.zip
import kotlinx.coroutines.launch
import java.util.*

@AndroidEntryPoint
class ProcessTextActivity : AppCompatActivity(), InterstitialAdLoader by InterstitialAdLoaderImpl() {
    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            with(BottomSheetBehavior.from(binding.bottomSheet)) {
                if (state == BottomSheetBehavior.STATE_HIDDEN) {
                    finish()
                } else {
                    state = BottomSheetBehavior.STATE_HIDDEN
                }
            }
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            with(binding.recognizeSpeech) {
                val configLongAnimTime = resources.configLongAnimTime
                val duration = configLongAnimTime.quarter.long
                val interpolator = context.decelerateQuadInterpolator

                tintFade(
                    duration,
                    interpolator,
                    imageTintList?.defaultColor ?: colorOnSurface,
                    Color.RED
                )
            }
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onError(error: Int) {
            with(binding.recognizeSpeech) {
                val configMediumAnimTime = resources.configMediumAnimTime
                val duration = configMediumAnimTime.quarter.long
                val interpolator = context.accelerateQuadInterpolator

                tintFade(
                    duration,
                    interpolator,
                    imageTintList?.defaultColor ?: Color.RED,
                    colorOnSurface
                )
            }
        }

        override fun onResults(results: Bundle?) {
            val stringArrayList = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)

            stringArrayList?.firstOrNull()?.let { string ->
                viewModel.sourceText.update { string }
            }

            with(binding.recognizeSpeech) {
                val configMediumAnimTime = resources.configMediumAnimTime
                val duration = configMediumAnimTime.quarter.long
                val interpolator = context.accelerateQuadInterpolator

                tintFade(
                    duration,
                    interpolator,
                    imageTintList?.defaultColor ?: Color.RED,
                    colorOnSurface
                )
            }
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private val binding by lazy {
        ActivityProcessTextBinding.inflate(layoutInflater)
            .apply {
                lifecycleOwner = this@ProcessTextActivity
                setSourceText(viewModel.sourceText)
            }
    }

    private val viewModel by viewModels<ProcessTextViewModel>()
    private val requestRecordAudioPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { result ->
        if (result) {
            recognizeSpeech()
        }
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        binding.bind()
        viewModel.collect()
        processText(intent)

        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                binding.speakSourceText.enable()
                binding.speakTranslatedText.enable()
            } else {
                binding.speakSourceText.disable()
                binding.speakTranslatedText.disable()
            }
        }

        loadInterstitialAd(this)
    }

    override fun onPause() {
        overridePendingTransition(ZERO, ZERO)
        super.onPause()
    }

    override fun onDestroy() {
        speechRecognizer?.clear()
        textToSpeech?.clear()

        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        processText(intent)
    }

    private fun recognizeSpeech() {
        val source = viewModel.source.value
        val languageTag = findLanguageTagByLanguage(source) ?: Locale.getDefault().toLanguageTag()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        }

        val speechRecognizer = speechRecognizer ?: SpeechRecognizer
            .createSpeechRecognizer(this)
            .apply { setRecognitionListener(recognitionListener) }
            .also { speechRecognizer = it }

        speechRecognizer.startListening(intent)
    }

    private fun processText(intent: Intent?) {
        val processText = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        val sourceText = processText?.string ?: EMPTY

        viewModel.sourceText.update { sourceText }
    }

    private fun speak(loc: Locale, text: CharSequence) = textToSpeech?.let {
        val utteranceId = loc.language.plus(text).hashCode().string

        when(it.setLanguage(loc)) {
            TextToSpeech.LANG_MISSING_DATA -> showToast(R.string.language_data_is_missing)
            TextToSpeech.LANG_NOT_SUPPORTED -> showToast(R.string.language_is_not_supported)
            else -> {
                it.setSpeechRate(SPEECH_RATE)
                it.setPitch(PITCH)
                it.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
        }
    }

    private fun ActivityProcessTextBinding.bind() {
        bottomSheet()
        textView()
        iconButton()
        adView()
    }

    private fun ActivityProcessTextBinding.bottomSheet() {
        val bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)

        bottomSheetBehavior.skipCollapsed = true
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN

        Handler(Looper.getMainLooper()).postDelayed(
            {
                bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                    override fun onStateChanged(bottomSheet: View, newState: Int) {
                        if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                            finish()
                        }
                    }

                    override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
                })

                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            },
            DELAY_MILLIS
        )
    }

    private fun ActivityProcessTextBinding.textView() {
        source.text = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)
        target.text = Locale.getDefault().getDisplayLanguage(Locale.KOREAN)
        translatedText.movementMethod = ScrollingMovementMethod()
    }

    private fun ActivityProcessTextBinding.iconButton() {
        swap.setOnClickListener {
            it.isClickable = false

            val duration = resources.configShortAnimTime.long
            val rotationY = it.rotationY.plus(DEGREE_180).rem(Float.MAX_VALUE)
            val x = dimen(R.dimen.layout_margin_16dp)

            it.rotateY(duration, rotationY)

            with(source) {
                translateRight(duration.half, x) {
                    viewModel.swap().then {
                        translateLeft(duration.half, ZERO.float) {
                            it.isClickable = true
                        }.alpha(ONE.float)
                    }
                }.alpha(ZERO.float)
            }

            with(target) {
                translateLeft(duration.half, x) {
                    translateRight(duration.half, ZERO.float).alpha(ONE.float)
                }.alpha(ZERO.float)
            }
        }

        recognizeSpeech.setOnIconClickListener {
            when {
                checkPermission(Manifest.permission.RECORD_AUDIO) -> recognizeSpeech()
                shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                    // todo check.
                }
                else -> requestRecordAudioPermissionsLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        speakSourceText.setOnIconClickListener {
            val language = viewModel.source.value
            val loc = findLocaleByLanguage(language) ?: Locale.getDefault()

            speak(loc, sourceText.text)
        }

        copyToClipboard.setOnIconClickListener {
            translatedText.text?.let { text ->
                if (text.isNotBlank()) {
                    copyPlainTextToClipboard(text)
                        .then {
                            showToast(R.string.copied_to_clipboard)
                        }
                }
            }
        }

        speakTranslatedText.setOnIconClickListener {
            val language = viewModel.target.value
            val loc = findLocaleByLanguage(language) ?: Locale.getDefault()

            speak(loc, translatedText.text)
        }
    }

    private fun ActivityProcessTextBinding.adView() {
        val adRequest = AdRequest.Builder().build()

        @StringRes
        val resId = if (BuildConfig.DEBUG) {
            R.string.sample_banner_ad_unit_id
        } else {
            R.string.banner_ad_unit_id
        }

        AdView(context).apply {
            adUnitId = getString(resId)
            adListener = object : AdListener() {
                override fun onAdLoaded() {
                    super.onAdLoaded()

                    parent?.letIsViewGroup {
                        it.removeView(this@apply)
                    }

                    adView.addView(this@apply)
                }
            }

            setAdSize(AdSize(AD_WIDTH, AD_HEIGHT))
        }.loadAd(adRequest)
    }

    private fun ProcessTextViewModel.collect() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                adsRemoved.zip(characters) { adsRemoved, characters ->
                    adsRemoved.not().and(characters > LIMIT_CHARACTERS)
                }.collect { condition ->
                    if (condition) {
                        showInterstitialAd(
                            this@ProcessTextActivity,
                            onAdDismissedFullScreenContent = {
                                loadInterstitialAd(this@ProcessTextActivity)
                            },
                            onAdFailedToShowFullScreenContent = {
                                if (it.code == FullScreenContentCallback.ERROR_CODE_AD_REUSED) {
                                    loadInterstitialAd(this@ProcessTextActivity)
                                }
                            }
                        )

                        clearCharacters()
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                source.collect { source ->
                    binding.source.text = findDisplayLanguageByLanguage(source)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                target.collect { target ->
                    binding.target.text = findDisplayLanguageByLanguage(target)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                translations.collect label@ {
                    when(it) {
                        Result.Loading -> binding.circularProgressIndicator.show()
                        is Result.Success -> with(binding) {
                            circularProgressIndicator.hide()

                            val data = it.data.ifEmpty { return@label }
                            val translation = data.first()

                            translatedText.text = translation.translatedText
                        }
                        is Result.Failure -> {
                            binding.circularProgressIndicator.hide()

                            with(it.throwable) {
                                showToast(message ?: string)
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val AD_HEIGHT = 32
        private const val AD_WIDTH = 320
        private const val DELAY_MILLIS = 200L
    }
}
