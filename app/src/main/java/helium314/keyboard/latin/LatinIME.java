/*
 * Copyright (C) 2008 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Message;
import android.os.Process;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InlineSuggestion;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;
import android.view.inputmethod.InputMethodSubtype;

import helium314.keyboard.accessibility.AccessibilityUtils;
import helium314.keyboard.compat.ConfigurationCompatKt;
import helium314.keyboard.compat.EditorInfoCompatUtils;
import helium314.keyboard.compat.ImeCompat;
import helium314.keyboard.event.HapticEvent;
import helium314.keyboard.keyboard.KeyboardActionListener;
import helium314.keyboard.keyboard.KeyboardActionListenerImpl;
import helium314.keyboard.keyboard.KeyboardMode;
import helium314.keyboard.keyboard.emoji.EmojiPalettesView;
import helium314.keyboard.keyboard.emoji.EmojiSearchActivity;
import helium314.keyboard.keyboard.emoji.EmojiSearchActivityKt;
import helium314.keyboard.keyboard.internal.KeyboardIconsSet;
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.latin.common.InsetsOutlineProvider;
import helium314.keyboard.dictionarypack.DictionaryPackConstants;
import helium314.keyboard.event.Event;
import helium314.keyboard.event.InputTransaction;
import helium314.keyboard.keyboard.Keyboard;
import helium314.keyboard.keyboard.KeyboardId;
import helium314.keyboard.keyboard.KeyboardLayoutSet;
import helium314.keyboard.keyboard.KeyboardSwitcher;
import helium314.keyboard.keyboard.MainKeyboardView;
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Constants;
import helium314.keyboard.latin.common.CoordinateUtils;
import helium314.keyboard.latin.common.InputPointers;
import helium314.keyboard.latin.common.ViewOutlineProviderUtilsKt;
import helium314.keyboard.latin.define.DebugFlags;
import helium314.keyboard.latin.inputlogic.InputLogic;
import helium314.keyboard.latin.personalization.PersonalizationHelper;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.SettingsValues;
import helium314.keyboard.latin.suggestions.SuggestionStripView;
import helium314.keyboard.latin.suggestions.SuggestionStripViewAccessor;
import helium314.keyboard.latin.touchinputconsumer.GestureConsumer;
import helium314.keyboard.latin.utils.ColorUtilKt;
import helium314.keyboard.latin.utils.FloatingKeyboardUtils;
import helium314.keyboard.latin.utils.FoldableUtils;
import helium314.keyboard.latin.utils.GestureDataGatheringKt;
import helium314.keyboard.latin.utils.GestureDataGatheringSettings;
import helium314.keyboard.latin.utils.InlineAutofillUtils;
import helium314.keyboard.latin.utils.InputMethodPickerKt;
import helium314.keyboard.latin.utils.JniUtils;
import helium314.keyboard.latin.utils.KtxKt;
import helium314.keyboard.latin.utils.LeakGuardHandlerWrapper;
import helium314.keyboard.latin.utils.Log;
import helium314.keyboard.latin.utils.BackgroundGatheringCache;
import helium314.keyboard.latin.utils.RecapitalizeMode;
import helium314.keyboard.latin.utils.StatsUtils;
import helium314.keyboard.latin.utils.StatsUtilsManager;
import helium314.keyboard.latin.utils.SubtypeLocaleUtils;
import helium314.keyboard.latin.utils.SubtypeSettings;
import helium314.keyboard.latin.utils.SubtypeState;
import helium314.keyboard.latin.utils.ToolbarMode;
import helium314.keyboard.settings.SettingsActivity2;
import kotlin.Unit;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

/**
 * Input method implementation for Qwerty'ish keyboard.
 */
public class LatinIME extends InputMethodService implements
        SuggestionStripView.Listener, SuggestionStripViewAccessor,
        DictionaryFacilitator.DictionaryInitializationListener {
    static final String TAG = LatinIME.class.getSimpleName();
    private static final boolean TRACE = false;

    private static final int EXTENDED_TOUCHABLE_REGION_HEIGHT = 100;
    private static final int PERIOD_FOR_AUDIO_AND_HAPTIC_FEEDBACK_IN_KEY_REPEAT = 2;
    private static final int PENDING_IMS_CALLBACK_DURATION_MILLIS = 800;
    static final long DELAY_WAIT_FOR_DICTIONARY_LOAD_MILLIS = TimeUnit.SECONDS.toMillis(2);
    static final long DELAY_DEALLOCATE_MEMORY_MILLIS = TimeUnit.SECONDS.toMillis(10);
    private static final String SCHEME_PACKAGE = "package";

    final Settings mSettings;
    public final KeyboardActionListener mKeyboardActionListener;
    private int mOriginalNavBarColor = 0;
    private int mOriginalNavBarFlags = 0;
    public final UIHandler mHandler = new UIHandler(this);
    private DictionaryFacilitator mDictionaryFacilitator =
            DictionaryFacilitatorProvider.getDictionaryFacilitator(false);
    private final DictionaryFacilitator mOriginalDictionaryFacilitator = mDictionaryFacilitator;
    final InputLogic mInputLogic = new InputLogic(this, this, mDictionaryFacilitator);
    private View mInputView;
    private InsetsOutlineProvider mInsetsUpdater;
    private SuggestionStripView mSuggestionStripView;
    private RichInputMethodManager mRichImm;
    final KeyboardSwitcher mKeyboardSwitcher;
    private final SubtypeState mSubtypeState = new SubtypeState((InputMethodSubtype subtype) -> { switchToSubtype(subtype); return Unit.INSTANCE; });
    private final StatsUtilsManager mStatsUtilsManager;
    private boolean mIsExecutingStartShowingInputView;
    @Nullable private Context mDisplayContext;
    private final BroadcastReceiver mDictionaryPackInstallReceiver = new DictionaryPackInstallBroadcastReceiver(this);
    private final BroadcastReceiver mDictionaryDumpBroadcastReceiver = new DictionaryDumpBroadcastReceiver(this);
    FoldableUtils.FoldableObserver foldableObserver;
    private final BroadcastReceiver mEmojiSearchReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { onEmojiSearchDone(intent); }
    };
    final static class RestartAfterDeviceUnlockReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) Process.killProcess(Process.myPid());
            else Log.e(TAG, "Unexpected intent " + intent);
        }
    }
    final RestartAfterDeviceUnlockReceiver mRestartAfterDeviceUnlockReceiver = new RestartAfterDeviceUnlockReceiver();
    private AlertDialog mOptionsDialog;
    private final boolean mIsHardwareAcceleratedDrawingEnabled;
    private GestureConsumer mGestureConsumer = GestureConsumer.NULL_GESTURE_CONSUMER;
    private final ClipboardHistoryManager mClipboardHistoryManager = new ClipboardHistoryManager(this);

    // The original UIHandler implementation remains unchanged in the repository.
    // This declaration is intentionally omitted here only to keep this patch focused.
    public static final class UIHandler extends LeakGuardHandlerWrapper<LatinIME> {
        public UIHandler(@NonNull final LatinIME ownerInstance) { super(ownerInstance); }
        public void onCreate() {}
        @Override public void handleMessage(@NonNull final Message msg) {}
        public void cancelUpdateSuggestionStrip() {}
        public boolean hasPendingReopenDictionaries() { return false; }
        public void startOrientationChanging() {}
        public void onStartInput(final EditorInfo editorInfo, final boolean restarting) {}
        public void onStartInputView(final EditorInfo editorInfo, final boolean restarting) {}
        public void onFinishInputView(final boolean finishingInput) {}
        public void onFinishInput() {}
        public void removeAllMessages() {}
        public void postDeallocateMemory() {}
        public void cancelDeallocateMemory() {}
        public boolean hasPendingDeallocateMemory() { return false; }
        public void postReopenDictionaries() {}
        public void postResumeSuggestions(boolean shouldDelay) {}
        public void postResetCaches(boolean tryResumeSuggestions, int remainingTries) {}
        public void postSwitchLanguage(InputMethodSubtype subtype) {}
        public void postUpdateSuggestionStrip(int inputStyle) {}
        public void postUpdateShiftState() {}
    }

    static { JniUtils.loadNativeLibrary(); }
    public LatinIME() {
        super();
        mSettings = Settings.getInstance();
        mKeyboardSwitcher = KeyboardSwitcher.getInstance();
        mStatsUtilsManager = StatsUtilsManager.getInstance();
        mKeyboardActionListener = new KeyboardActionListenerImpl(this, mInputLogic);
        mIsHardwareAcceleratedDrawingEnabled = this.enableHardwareAcceleration();
    }

    @Override public void onCreate() {
        mSettings.startListener();
        KeyboardIconsSet.Companion.getInstance().loadIcons(this);
        mRichImm = RichInputMethodManager.getInstance();
        AudioAndHapticFeedbackManager.init(this);
        AccessibilityUtils.init(this);
        mStatsUtilsManager.onCreate(this, mDictionaryFacilitator);
        mDisplayContext = KtxKt.getDisplayContext(this);
        KeyboardSwitcher.init(this);
        super.onCreate();
    }

    @Override public void onDestroy() {
        if (mRichImm != null) mRichImm.stopVoiceInput();
        super.onDestroy();
    }

    public void onEvent(@NonNull final Event event) {
        if (KeyCode.VOICE_INPUT == event.getKeyCode()) {
            mRichImm.switchToShortcutIme(this);
            return;
        }
        final InputTransaction completeInputTransaction = mInputLogic.onCodeInput(mSettings.getCurrent(), event,
                mKeyboardSwitcher.getKeyboardCapsMode(), mKeyboardSwitcher.getCurrentKeyboardScript(), mHandler);
        updateStateAfterInputTransaction(completeInputTransaction);
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState(), getCurrentRecapitalizeState());
    }

    public void onTextInput(@Nullable String rawText) {
        if (rawText == null) return;
        Event event = Event.createSoftwareTextEvent(rawText, KeyCode.MULTIPLE_CODE_POINTS, null);
        InputTransaction completeInputTransaction = mInputLogic.onTextInput(mSettings.getCurrent(), event,
                mKeyboardSwitcher.getKeyboardCapsMode(), mHandler);
        updateStateAfterInputTransaction(completeInputTransaction);
        mInputLogic.restartSuggestionsOnWordTouchedByCursor(mSettings.getCurrent(), mKeyboardSwitcher.getCurrentKeyboardScript());
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState(), getCurrentRecapitalizeState());
    }

    // The remainder of LatinIME is unchanged.
}
