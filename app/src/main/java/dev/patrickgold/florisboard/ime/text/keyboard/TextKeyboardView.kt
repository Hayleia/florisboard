/*
 * Copyright (C) 2021 Patrick Goldinger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.text.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.PaintDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.content.res.AppCompatResources.getDrawable
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.debug.*
import dev.patrickgold.florisboard.ime.core.FlorisBoard
import dev.patrickgold.florisboard.ime.core.ImeOptions
import dev.patrickgold.florisboard.ime.core.PrefHelper
import dev.patrickgold.florisboard.ime.text.TextInputManager
import dev.patrickgold.florisboard.ime.text.key.*
import dev.patrickgold.florisboard.ime.theme.Theme
import dev.patrickgold.florisboard.ime.theme.ThemeManager
import dev.patrickgold.florisboard.ime.theme.ThemeValue
import dev.patrickgold.florisboard.util.ViewLayoutUtils
import kotlin.math.roundToInt

class TextKeyboardView : View, ThemeManager.OnThemeUpdatedListener {
    private val florisboard: FlorisBoard?
        get() = FlorisBoard.getInstanceOrNull()
    private val prefs: PrefHelper
        get() = PrefHelper.getDefaultInstance(context)
    private val themeManager: ThemeManager?
        get() = ThemeManager.defaultOrNull()

    private var computedKeyboard: TextKeyboard? = null
    private var capsState: Boolean = false
    private var keyVariation: KeyVariation = KeyVariation.NORMAL
    private var isRecomputingRequested: Boolean = true

    private var activePointerId: Int = -1
    internal var isSmartbarKeyboardView: Boolean = false
    private var isPreviewMode: Boolean = false
    private var isLoadingPlaceholderKeyboard: Boolean = false

    val desiredKey: TextKey = TextKey(data = TextKeyData.UNSPECIFIED)

    private var keyBackgroundDrawable: PaintDrawable = PaintDrawable().apply {
        setCornerRadius(ViewLayoutUtils.convertDpToPixel(6.0f, context))
    }

    private var keyboardBackground: PaintDrawable = PaintDrawable()
    private var foregroundDrawable: Drawable? = null
    private var label: String? = null
    private var labelPaintTextSize: Float = resources.getDimension(R.dimen.key_textSize)
    private var labelPaint: Paint = Paint().apply {
        color = 0
        isAntiAlias = true
        isFakeBoldText = false
        textAlign = Paint.Align.CENTER
        textSize = labelPaintTextSize
        typeface = Typeface.DEFAULT
    }
    private var hintedLabel: String? = null
    private var hintedLabelPaintTextSize: Float = resources.getDimension(R.dimen.key_textHintSize)
    private var hintedLabelPaint: Paint = Paint().apply {
        color = 0
        isAntiAlias = true
        isFakeBoldText = false
        textAlign = Paint.Align.CENTER
        textSize = hintedLabelPaintTextSize
        typeface = Typeface.MONOSPACE
    }
    private val tempRect: Rect = Rect()

    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        context.obtainStyledAttributes(attrs, R.styleable.TextKeyboardView).apply {
            isPreviewMode = getBoolean(R.styleable.TextKeyboardView_isPreviewKeyboard, false)
            isSmartbarKeyboardView = getBoolean(R.styleable.TextKeyboardView_isSmartbarKeyboard, false)
            isLoadingPlaceholderKeyboard = getBoolean(R.styleable.TextKeyboardView_isLoadingPlaceholderKeyboard, false)
            recycle()
        }
        setWillNotDraw(false)
    }

    fun setComputedKeyboard(keyboard: TextKeyboard) {
        flogInfo(LogTopic.TEXT_KEYBOARD_VIEW) { keyboard.toString() }
        computedKeyboard = keyboard
        notifyStateChanged(capsState, keyVariation)
    }

    fun setComputedKeyboard(keyboard: TextKeyboard, tim: TextInputManager) {
        flogInfo(LogTopic.TEXT_KEYBOARD_VIEW) { keyboard.toString() }
        computedKeyboard = keyboard
        notifyStateChanged(tim)
    }

    fun setComputedKeyboard(keyboard: TextKeyboard, newCapsState: Boolean, newKeyVariation: KeyVariation) {
        computedKeyboard = keyboard
        notifyStateChanged(newCapsState, newKeyVariation)
    }

    fun notifyStateChanged(tim: TextInputManager) {
        notifyStateChanged(tim.caps || tim.capsLock, tim.keyVariation)
    }

    fun notifyStateChanged(newCapsState: Boolean, newKeyVariation: KeyVariation) {
        flogInfo(LogTopic.TEXT_KEYBOARD_VIEW) { "newCapsState=$newCapsState, newKeyVariation=$newKeyVariation" }
        capsState = newCapsState
        keyVariation = newKeyVariation
        isRecomputingRequested = true
        onLayoutInternal()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        themeManager?.registerOnThemeUpdatedListener(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        themeManager?.unregisterOnThemeUpdatedListener(this)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event ?: return false
        return false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = MeasureSpec.getSize(widthMeasureSpec).toFloat()
        val desiredHeight = if (isSmartbarKeyboardView || isPreviewMode) {
            MeasureSpec.getSize(heightMeasureSpec).toFloat()
        } else {
            (florisboard?.inputView?.desiredTextKeyboardViewHeight ?: MeasureSpec.getSize(heightMeasureSpec).toFloat())
        } * if (isPreviewMode) {
            0.90f
        } else {
            1.00f
        }

        super.onMeasure(
            MeasureSpec.makeMeasureSpec(desiredWidth.roundToInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(desiredHeight.roundToInt(), MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        onLayoutInternal()
    }

    private fun onLayoutInternal() {
        val keyboard = computedKeyboard
        if (keyboard == null) {
            flogWarning(LogTopic.TEXT_KEYBOARD_VIEW) { "Computed keyboard is null!" }
            return
        } else {
            flogInfo(LogTopic.TEXT_KEYBOARD_VIEW)
        }

        val keyMarginH: Int
        val keyMarginV: Int

        if (isSmartbarKeyboardView) {
            keyMarginH = resources.getDimension(R.dimen.key_marginH).toInt()
            keyMarginV = resources.getDimension(R.dimen.key_marginV).toInt()
        } else {
            keyMarginH = ViewLayoutUtils.convertDpToPixel(prefs.keyboard.keySpacingHorizontal, context).toInt()
            keyMarginV = ViewLayoutUtils.convertDpToPixel(prefs.keyboard.keySpacingVertical, context).toInt()
        }

        desiredKey.touchBounds.apply {
            right = if (isSmartbarKeyboardView) {
                measuredWidth / 6
            } else {
                measuredWidth / 10
            }
            bottom = when {
                isSmartbarKeyboardView -> {
                    measuredHeight
                }
                florisboard?.inputView?.shouldGiveAdditionalSpace == true -> {
                    (measuredHeight / (keyboard.rowCount + 0.5f).coerceAtMost(5.0f)).toInt()
                }
                else -> {
                    measuredHeight / keyboard.rowCount
                }
            }
        }
        desiredKey.visibleBounds.apply {
            left = keyMarginH
            right = desiredKey.touchBounds.width() - keyMarginH
            when {
                isSmartbarKeyboardView -> {
                    top = (0.75 * keyMarginV).toInt()
                    bottom = (desiredKey.touchBounds.height() - 0.75 * keyMarginV).toInt()
                }
                else -> {
                    top = keyMarginV
                    bottom = desiredKey.touchBounds.height() - keyMarginV
                }
            }
        }
        TextKeyboard.layoutDrawableBounds(desiredKey)
        TextKeyboard.layoutLabelBounds(desiredKey)

        if (isRecomputingRequested) {
            isRecomputingRequested = false
            for (key in keyboard.keys()) {
                key.compute(capsState, keyVariation)
            }
        }

        keyboard.layout(this)
    }

    /**
     * Automatically sets the text size of [boxPaint] for given [text] so it fits within the given
     * bounds.
     *
     * Implementation based on this blog post by Lucas (SketchingDev), written on Aug 20, 2015
     *  https://sketchingdev.co.uk/blog/resizing-text-to-fit-into-a-container-on-android.html
     *
     * @param boxPaint The [Paint] object which the text size should be applied to.
     * @param boxWidth The max width for the surrounding box of [text].
     * @param boxHeight The max height for the surrounding box of [text].
     * @param text The text for which the size should be calculated.
     */
    private fun setTextSizeFor(boxPaint: Paint, boxWidth: Float, boxHeight: Float, text: String, multiplier: Float = 1.0f): Float {
        var stage = 1
        var textSize = 0.0f
        while (stage < 3) {
            if (stage == 1) {
                textSize += 10.0f
            } else if (stage == 2) {
                textSize -= 1.0f
            }
            boxPaint.textSize = textSize
            boxPaint.getTextBounds(text, 0, text.length, tempRect)
            val fits = tempRect.width() < boxWidth && tempRect.height() < boxHeight
            if (stage == 1 && !fits || stage == 2 && fits) {
                stage++
            }
        }
        textSize *= multiplier
        boxPaint.textSize = textSize
        return textSize
    }

    override fun onThemeUpdated(theme: Theme) {
        if (isPreviewMode) {
            keyboardBackground.apply {
                setTint(theme.getAttr(Theme.Attr.KEYBOARD_BACKGROUND).toSolidColor().color)
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        if (canvas == null) {
            flogWarning(LogTopic.TEXT_KEYBOARD_VIEW) { "Cannot draw: 'canvas' is null!" }
            return
        } else {
            flogInfo(LogTopic.TEXT_KEYBOARD_VIEW)
        }

        if (isPreviewMode) {
            keyboardBackground.apply {
                setBounds(0, 0, measuredWidth, measuredHeight)
                draw(canvas)
            }
        }

        onDrawComputedKeyboard(canvas)
    }

    private fun onDrawComputedKeyboard(canvas: Canvas) {
        val keyboard = computedKeyboard ?: return
        setTextSizeFor(labelPaint, desiredKey.visibleLabelBounds.width().toFloat(), desiredKey.visibleLabelBounds.height().toFloat(), "X")
        labelPaintTextSize = labelPaint.textSize
        setTextSizeFor(hintedLabelPaint, desiredKey.visibleBounds.width() * 1.0f / 5.0f, desiredKey.visibleBounds.height() * 1.0f / 5.0f, "X")
        hintedLabelPaintTextSize = hintedLabelPaint.textSize
        for (key in keyboard.keys()) {
            onDrawComputedKey(canvas, key)
        }
    }

    private fun onDrawComputedKey(canvas: Canvas, key: TextKey) {
        val theme = themeManager?.activeTheme ?: Theme.BASE_THEME
        val keyBackground: ThemeValue
        val keyForeground: ThemeValue
        val shouldShowBorder: Boolean
        val themeLabel = key.computedData.asString(isForDisplay = false)
        when {
            isLoadingPlaceholderKeyboard -> {
                shouldShowBorder = theme.getAttr(Theme.Attr.KEY_SHOW_BORDER, themeLabel).toOnOff().state
                if (key.isPressed) {
                    keyBackground = theme.getAttr(Theme.Attr.KEY_BACKGROUND_PRESSED, themeLabel)
                    keyForeground = theme.getAttr(Theme.Attr.KEY_FOREGROUND_PRESSED, themeLabel)
                } else {
                    keyBackground = if (shouldShowBorder) {
                        theme.getAttr(Theme.Attr.KEY_BACKGROUND, themeLabel)
                    } else {
                        theme.getAttr(Theme.Attr.SMARTBAR_BUTTON_BACKGROUND, themeLabel)
                    }
                    keyForeground = theme.getAttr(Theme.Attr.KEY_FOREGROUND, themeLabel)
                }
            }
            isSmartbarKeyboardView -> {
                shouldShowBorder = false
                if (key.isPressed) {
                    keyBackground = theme.getAttr(Theme.Attr.SMARTBAR_BUTTON_BACKGROUND)
                    keyForeground = theme.getAttr(Theme.Attr.SMARTBAR_FOREGROUND)
                } else {
                    keyBackground = theme.getAttr(Theme.Attr.SMARTBAR_BACKGROUND)
                    keyForeground = if (!key.isEnabled) {
                        theme.getAttr(Theme.Attr.SMARTBAR_FOREGROUND_ALT)
                    } else {
                        theme.getAttr(Theme.Attr.SMARTBAR_FOREGROUND)
                    }
                }
            }
            else -> {
                val capsSpecific = when {
                    florisboard?.textInputManager?.capsLock == true -> {
                        "capslock"
                    }
                    florisboard?.textInputManager?.caps == true -> {
                        "caps"
                    }
                    else -> {
                        null
                    }
                }
                shouldShowBorder = theme.getAttr(Theme.Attr.KEY_SHOW_BORDER, themeLabel, capsSpecific).toOnOff().state
                if (key.isPressed) {
                    keyBackground = theme.getAttr(Theme.Attr.KEY_BACKGROUND_PRESSED, themeLabel, capsSpecific)
                    keyForeground = theme.getAttr(Theme.Attr.KEY_FOREGROUND_PRESSED, themeLabel, capsSpecific)
                } else {
                    keyBackground = theme.getAttr(Theme.Attr.KEY_BACKGROUND, themeLabel, capsSpecific)
                    keyForeground = theme.getAttr(Theme.Attr.KEY_FOREGROUND, themeLabel, capsSpecific)
                }
            }
        }

        keyBackgroundDrawable.apply {
            bounds = key.visibleBounds
            elevation = if (shouldShowBorder) 4.0f else 0.0f
            setTint(keyBackground.toSolidColor().color)
            draw(canvas)
        }

        computeLabelsAndDrawables(key)

        val label = label
        if (label != null && key.computedData.code != KeyCode.SPACE) {
            labelPaint.apply {
                color = keyForeground.toSolidColor().color
                textSize = labelPaintTextSize
                val centerX = key.visibleLabelBounds.exactCenterX()
                val centerY = key.visibleLabelBounds.exactCenterY() + (labelPaint.textSize - labelPaint.descent()) / 2
                canvas.drawText(label, centerX, centerY, labelPaint)
            }
        }

        val hintedLabel = hintedLabel
        if (hintedLabel != null) {
            labelPaint.apply {
                color = keyForeground.toSolidColor().color
                textSize = labelPaintTextSize
                val centerX = key.visibleBounds.width() * 5.0f / 6.0f
                val centerY = key.visibleBounds.height() * 1.0f / 6.0f + (hintedLabelPaint.textSize - hintedLabelPaint.descent()) / 2
                canvas.drawText(hintedLabel, centerX, centerY, labelPaint)
            }
        }

        val foregroundDrawable = foregroundDrawable
        foregroundDrawable?.apply {
            bounds = key.visibleDrawableBounds
            setTint(keyForeground.toSolidColor().color)
            draw(canvas)
        }
    }

    /**
     * Computes the labels and drawables needed to draw the key.
     */
    private fun computeLabelsAndDrawables(key: TextKey) {
        // Reset attributes first to avoid invalid states if not updated
        label = null
        hintedLabel = null
        foregroundDrawable = null

        val data = key.computedData
        if (data.type == KeyType.CHARACTER && data.code != KeyCode.SPACE
            && data.code != KeyCode.HALF_SPACE && data.code != KeyCode.KESHIDA || data.type == KeyType.NUMERIC
        ) {
            label = data.asString(isForDisplay = true)
            val hint = key.computedPopups.hint
            if (prefs.keyboard.hintedNumberRowMode != KeyHintMode.DISABLED && hint?.type == KeyType.NUMERIC) {
                hintedLabel = hint.asString(isForDisplay = true)
            }
            if (prefs.keyboard.hintedSymbolsMode != KeyHintMode.DISABLED && hint?.type == KeyType.CHARACTER) {
                hintedLabel = hint.asString(isForDisplay = true)
            }

        } else {
            when (data.code) {
                KeyCode.ARROW_LEFT -> {
                    foregroundDrawable = getDrawable(context, R.drawable.ic_keyboard_arrow_left)
                }
                KeyCode.ARROW_RIGHT -> {
                    foregroundDrawable = getDrawable(context, R.drawable.ic_keyboard_arrow_right)
                }
                KeyCode.CLIPBOARD_COPY -> {
                    foregroundDrawable = getDrawable(context, R.drawable.ic_content_copy)
                }
                KeyCode.CLIPBOARD_CUT -> {
                    foregroundDrawable = getDrawable(context, R.drawable.ic_content_cut)
                }
                KeyCode.CLIPBOARD_PASTE -> {
                    foregroundDrawable = getDrawable(context, R.drawable.ic_content_paste)
                }
                KeyCode.CLIPBOARD_SELECT_ALL -> {
                    foregroundDrawable = getDrawable(context, R.drawable.ic_select_all)
                }
                KeyCode.DELETE -> {
                    foregroundDrawable = getDrawable(context, R.drawable.ic_backspace)
                }
                KeyCode.ENTER -> {
                    val imeOptions = florisboard?.activeEditorInstance?.imeOptions ?: ImeOptions.default()
                    foregroundDrawable = getDrawable(context, when (imeOptions.action) {
                        ImeOptions.Action.DONE -> R.drawable.ic_done
                        ImeOptions.Action.GO -> R.drawable.ic_arrow_right_alt
                        ImeOptions.Action.NEXT -> R.drawable.ic_arrow_right_alt
                        ImeOptions.Action.NONE -> R.drawable.ic_keyboard_return
                        ImeOptions.Action.PREVIOUS -> R.drawable.ic_arrow_right_alt
                        ImeOptions.Action.SEARCH -> R.drawable.ic_search
                        ImeOptions.Action.SEND -> R.drawable.ic_send
                        ImeOptions.Action.UNSPECIFIED -> R.drawable.ic_keyboard_return
                    })
                    if (imeOptions.flagNoEnterAction) {
                        foregroundDrawable = getDrawable(context, R.drawable.ic_keyboard_return)
                    }
                }
                KeyCode.LANGUAGE_SWITCH -> {
                    foregroundDrawable = getDrawable(context, R.drawable.ic_language)
                }
                KeyCode.PHONE_PAUSE -> label = resources.getString(R.string.key__phone_pause)
                KeyCode.PHONE_WAIT -> label = resources.getString(R.string.key__phone_wait)
                KeyCode.SHIFT -> {
                    foregroundDrawable = getDrawable(context, when (florisboard?.textInputManager?.caps) {
                        true -> R.drawable.ic_keyboard_capslock
                        else -> R.drawable.ic_keyboard_arrow_up
                    })
                }
                KeyCode.SPACE -> {
                    when (computedKeyboard?.mode) {
                        KeyboardMode.NUMERIC,
                        KeyboardMode.NUMERIC_ADVANCED,
                        KeyboardMode.PHONE,
                        KeyboardMode.PHONE2 -> {
                            foregroundDrawable = getDrawable(context, R.drawable.ic_space_bar)
                        }
                        KeyboardMode.CHARACTERS -> {
                            label = florisboard?.activeSubtype?.locale?.displayName
                        }
                        else -> {
                        }
                    }
                }
                KeyCode.SWITCH_TO_MEDIA_CONTEXT -> {
                    foregroundDrawable = getDrawable(context, R.drawable.ic_sentiment_satisfied)
                }
                KeyCode.SWITCH_TO_CLIPBOARD_CONTEXT -> {
                    foregroundDrawable = getDrawable(context, R.drawable.ic_assignment)
                }
                KeyCode.SWITCH_TO_TEXT_CONTEXT,
                KeyCode.VIEW_CHARACTERS -> {
                    label = resources.getString(R.string.key__view_characters)
                }
                KeyCode.VIEW_NUMERIC,
                KeyCode.VIEW_NUMERIC_ADVANCED -> {
                    label = resources.getString(R.string.key__view_numeric)
                }
                KeyCode.VIEW_PHONE -> {
                    label = resources.getString(R.string.key__view_phone)
                }
                KeyCode.VIEW_PHONE2 -> {
                    label = resources.getString(R.string.key__view_phone2)
                }
                KeyCode.VIEW_SYMBOLS -> {
                    label = resources.getString(R.string.key__view_symbols)
                }
                KeyCode.VIEW_SYMBOLS2 -> {
                    label = resources.getString(R.string.key__view_symbols2)
                }
                KeyCode.HALF_SPACE -> {
                    label = resources.getString(R.string.key__view_half_space)
                }
                KeyCode.KESHIDA -> {
                    label = resources.getString(R.string.key__view_keshida)
                }
            }
        }
    }
}