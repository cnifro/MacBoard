/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.latin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import helium314.keyboard.compat.locale
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.LocaleUtils.getBestMatch
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.LanguageOnSpacebarUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ScriptUtils.script
import helium314.keyboard.latin.utils.SubtypeLocaleUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getSecondaryLocales
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/** Enrichment class for InputMethodManager to simplify interaction and add functionality. */
class RichInputMethodManager private constructor() {
    private lateinit var context: Context
    private lateinit var imm: InputMethodManager
    private lateinit var inputMethodInfoCache: InputMethodInfoCache
    private lateinit var currentRichInputMethodSubtype: RichInputMethodSubtype
    private val scope = CoroutineScope(Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    private val isInitializedInternal get() = this::imm.isInitialized

    val currentSubtypeLocale get() = forcedSubtypeForTesting?.locale ?: currentSubtype.locale
    val currentSubtype get() = forcedSubtypeForTesting ?: currentRichInputMethodSubtype
    val combiningRulesExtraValueOfCurrentSubtype get() =
        SubtypeLocaleUtils.getCombiningRulesExtraValue(currentSubtype.rawSubtype)
    val inputMethodInfoOfThisIme get() = inputMethodInfoCache.inputMethodOfThisIme

    val inputMethodManager: InputMethodManager get() {
        checkInitialized()
        return imm
    }

    private var shortcuts = listOf<Shortcut>()
    val isShortcutImeReady get() = shortcuts.isNotEmpty()

    fun getEnabledInputMethodSubtypes(imi: InputMethodInfo, allowsImplicitlySelectedSubtypes: Boolean) =
        inputMethodInfoCache.getEnabledInputMethodSubtypeList(imi, allowsImplicitlySelectedSubtypes)

    fun hasMultipleEnabledIMEsOrSubtypes(shouldIncludeAuxiliarySubtypes: Boolean) =
        hasMultipleEnabledSubtypes(shouldIncludeAuxiliarySubtypes, imm.enabledInputMethodList)

    fun hasMultipleEnabledSubtypesInThisIme(shouldIncludeAuxiliarySubtypes: Boolean) =
        SubtypeSettings.getEnabledSubtypes(shouldIncludeAuxiliarySubtypes).size > 1

    fun getNextSubtypeInThisIme(onlyCurrentIme: Boolean): InputMethodSubtype? {
        val currentSubtype = currentSubtype.rawSubtype
        val enabledSubtypes = SubtypeSettings.getEnabledSubtypes(true)
        val currentIndex = enabledSubtypes.indexOf(currentSubtype)
        if (currentIndex == -1) {
            Log.w(TAG, "Can't find current subtype in enabled subtypes: subtype=" +
                    SubtypeLocaleUtils.getSubtypeNameForLogging(currentSubtype))
            return if (onlyCurrentIme) enabledSubtypes[0] else null
        }
        val nextIndex = (currentIndex + 1) % enabledSubtypes.size
        if (nextIndex <= currentIndex && !onlyCurrentIme) return null
        return enabledSubtypes[nextIndex]
    }

    fun findSubtypeForHintLocale(locale: Locale): InputMethodSubtype? {
        val subtypes = SubtypeSettings.getEnabledSubtypes(true)
        var bestMatch = getBestMatch(locale, subtypes) { it.locale() }
        if (bestMatch != null) return bestMatch
        val language = locale.language
        val script = locale.script()
        for (subtype in subtypes) {
            val subtypeLocale = subtype.locale()
            if (subtypeLocale.script() != script) continue
            bestMatch = subtype
            val secondaryLocales = getSecondaryLocales(subtype.extraValue)
            for (secondaryLocale in secondaryLocales) {
                if (secondaryLocale.language == language) return bestMatch
            }
        }
        if (script != currentSubtypeLocale.script()) return bestMatch
        return null
    }

    fun onSubtypeChanged(newSubtype: InputMethodSubtype) {
        SubtypeSettings.setSelectedSubtype(context.prefs(), newSubtype)
        currentRichInputMethodSubtype = RichInputMethodSubtype.get(newSubtype)
        scope.launch { updateShortcutIme() }
        if (DEBUG) Log.w(TAG, "onSubtypeChanged: $currentRichInputMethodSubtype")
    }

    fun refreshSubtypeCaches() {
        inputMethodInfoCache.clear()
        currentRichInputMethodSubtype = RichInputMethodSubtype.get(SubtypeSettings.getSelectedSubtype(context.prefs()))
        scope.launch { updateShortcutIme() }
    }

    /** Starts voice input without switching away from this keyboard. */
    fun switchToShortcutIme(inputMethodService: InputMethodService) {
        mainHandler.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                inputMethodService.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "RECORD_AUDIO permission is not granted; voice input was not started")
                return@post
            }
            if (!SpeechRecognizer.isRecognitionAvailable(inputMethodService)) {
                Log.w(TAG, "No speech recognition service is available")
                return@post
            }

            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(inputMethodService)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onError(error: Int) {
                    Log.w(TAG, "Speech recognition error: $error")
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        inputMethodService.currentInputConnection?.commitText(text, 1)
                    }
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                }
            })

            val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentSubtypeLocale.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentSubtypeLocale.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer?.startListening(intent)
        }
    }

    private fun updateShortcutIme() {
        if (DEBUG) {
            val old = shortcuts.joinToString("; ") { "${it.imi.id}: ${it.subtype.locale()}, ${it.subtype.mode}" }
            Log.d(TAG, "Update shortcut IMEs from: $old")
        }
        val richSubtype = currentRichInputMethodSubtype
        val implicitlyEnabledSubtype = SubtypeSettings.isEnabled(richSubtype.rawSubtype) &&
                !SubtypeSettings.getEnabledSubtypes(false).contains(richSubtype.rawSubtype)
        val systemLocale = context.resources.configuration.locale()
        LanguageOnSpacebarUtils.onSubtypeChanged(richSubtype, implicitlyEnabledSubtype, systemLocale)
        LanguageOnSpacebarUtils.setEnabledSubtypes(SubtypeSettings.getEnabledSubtypes(true))
        shortcuts = inputMethodManager.shortcutInputMethodsAndSubtypes.entries.flatMap { (imi, subtypes) ->
            subtypes.map { Shortcut(imi, it) }
        }
        if (DEBUG) {
            val new = shortcuts.joinToString("; ") { "${it.imi.id}: ${it.subtype.locale()}, ${it.subtype.mode}" }
            Log.d(TAG, "Update shortcut IMEs to: $new")
        }
    }

    private fun hasMultipleEnabledSubtypes(shouldIncludeAuxiliarySubtypes: Boolean, imiList: List<InputMethodInfo>): Boolean {
        var filteredImisCount = 0
        imiList.forEach { imi ->
            if (filteredImisCount > 1) return true
            val subtypes = getEnabledInputMethodSubtypes(imi, true)
            if (subtypes.isEmpty()) { ++filteredImisCount; return@forEach }
            var auxCount = 0
            for (subtype in subtypes) {
                if (!subtype.isAuxiliary) { ++filteredImisCount; return@forEach }
                ++auxCount
            }
            if (shouldIncludeAuxiliarySubtypes && auxCount > 1) ++filteredImisCount
        }
        if (filteredImisCount > 1) return true
        val subtypes = SubtypeSettings.getEnabledSubtypes(true)
        return subtypes.count { it.mode == Constants.Subtype.KEYBOARD_MODE } > 1
    }

    private fun checkInitialized() {
        if (!isInitializedInternal) throw RuntimeException("$TAG is used before initialization")
    }

    private fun initInternal(ctx: Context) {
        if (isInitializedInternal) return
        imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        context = ctx
        inputMethodInfoCache = InputMethodInfoCache(imm, ctx.packageName)
        refreshSubtypeCaches()
    }

    companion object {
        private val TAG = RichInputMethodManager::class.java.simpleName
        private const val DEBUG = false
        private val instance = RichInputMethodManager()
        @JvmStatic fun getInstance(): RichInputMethodManager { instance.checkInitialized(); return instance }
        fun init(ctx: Context) { instance.initInternal(ctx) }
        @JvmStatic fun isInitialized() = instance.isInitializedInternal
        private var forcedSubtypeForTesting: RichInputMethodSubtype? = null
        fun forceSubtype(subtype: InputMethodSubtype) { forcedSubtypeForTesting = RichInputMethodSubtype.get(subtype) }
        fun canSwitchLanguage(): Boolean {
            if (!isInitialized()) return false
            if (Settings.getValues().mLanguageSwitchKeyToOtherSubtypes && instance.hasMultipleEnabledSubtypesInThisIme(false)) return true
            if (Settings.getValues().mLanguageSwitchKeyToOtherImes && instance.imm.enabledInputMethodList.size > 1) return true
            return false
        }
    }
}

private class InputMethodInfoCache(private val imm: InputMethodManager, private val imePackageName: String) {
    private var cachedThisImeInfo: InputMethodInfo? = null
    private val cachedSubtypeListWithImplicitlySelected = HashMap<InputMethodInfo, List<InputMethodSubtype>>()
    private val cachedSubtypeListOnlyExplicitlySelected = HashMap<InputMethodInfo, List<InputMethodSubtype>>()
    @get:Synchronized val inputMethodOfThisIme: InputMethodInfo get() {
        if (cachedThisImeInfo == null) cachedThisImeInfo = imm.inputMethodList.firstOrNull { it.packageName == imePackageName }
        cachedThisImeInfo?.let { return it }
        throw RuntimeException("For $imePackageName no input method was found, only found ${imm.inputMethodList.map { it.packageName }}")
    }
    @Synchronized fun getEnabledInputMethodSubtypeList(imi: InputMethodInfo, allowsImplicitlySelectedSubtypes: Boolean): List<InputMethodSubtype> {
        val cache = if (allowsImplicitlySelectedSubtypes) cachedSubtypeListWithImplicitlySelected else cachedSubtypeListOnlyExplicitlySelected
        cache[imi]?.let { return it }
        val result = if (imi == inputMethodOfThisIme) SubtypeSettings.getEnabledSubtypes(allowsImplicitlySelectedSubtypes)
        else imm.getEnabledInputMethodSubtypeList(imi, allowsImplicitlySelectedSubtypes)
        cache[imi] = result
        return result
    }
    @Synchronized fun clear() { cachedThisImeInfo = null; cachedSubtypeListWithImplicitlySelected.clear(); cachedSubtypeListOnlyExplicitlySelected.clear() }
}

private class Shortcut(val imi: InputMethodInfo, val subtype: InputMethodSubtype)
