package org.sih.offlinespeech

import android.app.AlertDialog
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Real messages only. Existing rows retain focus and word selection when receipts arrive. */
internal class ConversationView(context: Context, private val replay: (ConversationEntry, String?) -> Unit) : LinearLayout(context) {
    private val theme = SutraPalette.of(context)
    private val ink = theme.ink
    private val muted = theme.muted
    private val teal = theme.teal
    private val rows = LinkedHashMap<String, Pair<View, TextView>>()
    private var language = ""
    init { orientation = VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(12)) }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    }
    private fun button(value: String, action: () -> Unit) = Button(context).apply {
        text = value; textSize = 14f; isAllCaps = false
        setTextColor(teal); minHeight = dp(48); minWidth = dp(72); backgroundTintList = null
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(8), 0, dp(8), 0)
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(28, 12, 103, 91)), null,
            GradientDrawable().apply { setColor(theme.surface); cornerRadius = dp(12).toFloat() })
        setOnClickListener { action() }
    }

    fun render(entries: List<ConversationEntry>, preferredLanguage: String) {
        if (language != preferredLanguage) { removeAllViews(); rows.clear(); language = preferredLanguage }
        val keys = entries.map { it.key }.toSet()
        rows.keys.filter { it !in keys }.forEach { removeView(rows.remove(it)?.first) }
        entries.forEach { entry ->
            if (entry.key !in rows) addMessage(entry)
            rows.getValue(entry.key).second.apply {
                if (text.toString() != entry.status) text = entry.status
                setTextColor(if (entry.playbackFailed) theme.amber else teal)
            }
        }
    }

    private fun addMessage(entry: ConversationEntry) {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(10))
            background = GradientDrawable().apply {
                setColor(if (entry.outgoing) theme.mint else theme.surface)
                cornerRadii = floatArrayOf(24f,24f,24f,24f,if (entry.outgoing) 6f else 24f,
                    if (entry.outgoing) 6f else 24f,if (entry.outgoing) 24f else 6f,if (entry.outgoing) 24f else 6f)
                    .map { dp(it.toInt()).toFloat() }.toFloatArray()
                if (entry.alert) setStroke(dp(2), theme.accent)
            }
        }
        val lang = IndicTranslator.supportedLanguages.find { it.key == entry.language }
        card.addView(label("${if (entry.outgoing) "You" else "Nearby teammate"} · ${lang?.nativeName ?: entry.language}" +
            if (entry.alert) " · EMERGENCY" else "", 13f, if (entry.alert) theme.accent else muted, true))
        val original = label(entry.text, 20f).apply { setPadding(0, dp(12), 0, dp(14)); setTextIsSelectable(true) }
        card.addView(original)
        if (entry.language != language) {
            val translated = IndicTranslator.translate(entry.text, entry.language, language)
            if (translated.isNotBlank() && translated != entry.text) {
                val name = IndicTranslator.supportedLanguages.find { it.key == language }?.nativeName ?: language
                card.addView(View(context).apply { setBackgroundColor(theme.line) }, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))
                card.addView(label(name, 12f, muted, true).apply { setPadding(0, dp(12), 0, dp(4)) })
                card.addView(label(translated, 16f, muted).apply { setPadding(0, 0, 0, dp(12)) })
            }
        }
        val state = label(entry.status, 13f, teal, true).apply {
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, dp(4), 0, dp(4))
        }
        card.addView(state)
        var emphasized: String? = null
        val emphasisHint = label("", 13f, teal).apply { visibility = GONE }
        fun selectWord(word: String?) {
            emphasized = word
            val highlighted = SpannableString(entry.text)
            Regex("[\\p{L}\\p{M}]+").findAll(entry.text).forEach { match ->
                highlighted.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) = selectWord(match.value)
                    override fun updateDrawState(ds: TextPaint) { ds.color = ink; ds.isUnderlineText = false }
                }, match.range.first, match.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            // The calibrated TTS emphasizes the first occurrence of the chosen word per chunk.
            if (word != null) Regex("[\\p{L}\\p{M}]+").findAll(entry.text).firstOrNull { it.value == word }?.let {
                highlighted.setSpan(BackgroundColorSpan(theme.mint), it.range.first, it.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            original.setTextIsSelectable(false)
            original.text = highlighted
            original.movementMethod = LinkMovementMethod.getInstance()
            emphasisHint.text = word?.let { "Replay emphasis: $it · tap another word to change" } ?: "Neutral replay · tap a word to emphasize it"
            emphasisHint.visibility = VISIBLE
        }
        card.addView(emphasisHint)
        card.addView(LinearLayout(context).apply {
            gravity = Gravity.START
            addView(button("Replay") { replay(entry, emphasized) }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(button("Copy") {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SUTRA message", entry.text))
                Toast.makeText(context, "Message copied", Toast.LENGTH_SHORT).show()
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        })
        if (entry.language == "hindi") {
            card.addView(button("Emphasize a word") {
                val words = listOf("Neutral replay") + emphasisWords(entry.text)
                AlertDialog.Builder(context).setTitle("Hindi replay emphasis")
                    .setSingleChoiceItems(words.toTypedArray(), words.indexOf(emphasized).coerceAtLeast(0)) { dialog, which ->
                        selectWord(words[which].takeIf { which > 0 }); dialog.dismiss()
                    }.setNegativeButton("Cancel", null).show()
            })
        }
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(18)
            if (entry.outgoing) marginStart = dp(28) else marginEnd = dp(28)
        })
        if (isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()) {
            card.alpha = 0f
            card.translationY = dp(10).toFloat()
            card.animate().alpha(1f).translationY(0f).setDuration(180).start()
        }
        rows[entry.key] = card to state
    }
}
