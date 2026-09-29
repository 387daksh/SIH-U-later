package org.sih.offlinespeech

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.widget.Toast

class LanguagePreferenceActivity : Activity() {

    // Body content (cards, text, backgrounds) adapts to the system theme; the header stays a fixed dark navy.
    private val theme by lazy { SutraPalette.of(this) }
    private val ink by lazy { theme.ink }
    private val mutedInk by lazy { theme.muted }
    private val surface by lazy { theme.surface }
    private val canvas by lazy { theme.paper }
    private val divider by lazy { theme.line }
    private val blue by lazy { theme.blue }
    private val accentTeal = Color.rgb(143, 230, 214)
    private val tealDark = Color.rgb(22, 55, 70)
    private lateinit var nameInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = SutraPalette.chromeBackground
        window.navigationBarColor = SutraPalette.chromeBackground
        window.decorView.systemUiVisibility = 0

        val prefs = getSharedPreferences("sutra", Context.MODE_PRIVATE)
        val forceChange = intent.getBooleanExtra("force_change", false)
        val existingLanguage = prefs.getString("preferred_language", null)

        // The redesigned first-run flow lives in MainActivity; this screen remains for language changes.
        if (!forceChange) {
            startMainActivity()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SutraPalette.chromeBackground)
            fitsSystemWindows = true
        }

        // Top branded header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SutraPalette.chromeBackground)
            setPadding(dp(20), dp(24), dp(20), dp(20))

            val titleRow = LinearLayout(this@LanguagePreferenceActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(TextView(this@LanguagePreferenceActivity).apply {
                    text = "SUTRA"
                    textSize = 24f
                    letterSpacing = 0.25f
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                addView(TextView(this@LanguagePreferenceActivity).apply {
                    text = "Voice, together"
                    visibility = if (resources.configuration.fontScale > 1.3f) View.GONE else View.VISIBLE
                    textSize = 10.5f
                    gravity = Gravity.CENTER
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(accentTeal)
                    background = roundedBackground(tealDark, 14)
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                })
            }

            addView(titleRow)

            addView(TextView(this@LanguagePreferenceActivity).apply {
                text = "Every voice. Closer."
                textSize = 20f
                setTextColor(Color.rgb(160, 175, 195))
                setPadding(0, dp(4), 0, 0)
            })
        }

        root.addView(header)

        // Scrollable content area
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(canvas)
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(24))

            addView(TextView(this@LanguagePreferenceActivity).apply {
                text = "Make yourself understood."
                textSize = 28f
                setTextColor(ink)
                setTypeface(null, Typeface.BOLD)
            })

            addView(TextView(this@LanguagePreferenceActivity).apply {
                text = "Choose your language for translated messages. Navigation stays in English."
                textSize = 15f
                setTextColor(mutedInk)
                setPadding(0, dp(4), 0, dp(18))
            })

            addView(TextView(this@LanguagePreferenceActivity).apply {
                text = "What should we call you?"
                textSize = 16f
                setTextColor(ink)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, dp(6))
            })
            nameInput = EditText(this@LanguagePreferenceActivity).apply {
                hint = "Your name (optional)"
                textSize = 16f
                setSingleLine()
                setTextColor(ink)
                setHintTextColor(mutedInk)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = roundedBackground(surface, 14).apply { setStroke(dp(1), divider) }
                prefs.getString("display_name", null)?.let(::setText)
            }
            addView(nameInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
                bottomMargin = dp(18)
            })

            // Language cards
            IndicTranslator.supportedLanguages.forEach { lang ->
                val isSelected = lang.key == existingLanguage

                val card = LinearLayout(this@LanguagePreferenceActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rippleBackground(if (isSelected) theme.mint else surface, 20)
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    isClickable = true
                    isFocusable = true

                    val textContainer = LinearLayout(this@LanguagePreferenceActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                        addView(TextView(this@LanguagePreferenceActivity).apply {
                            text = lang.nativeName
                            textSize = 20f
                            setTextColor(if (isSelected) blue else ink)
                            setTypeface(null, Typeface.BOLD)
                        })

                        addView(TextView(this@LanguagePreferenceActivity).apply {
                            text = lang.englishName
                            textSize = 12f
                            setTextColor(if (isSelected) blue else mutedInk)
                        })
                    }

                    addView(textContainer)

                    if (isSelected) {
                        addView(TextView(this@LanguagePreferenceActivity).apply {
                            text = "Selected"
                            textSize = 12f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(blue)
                            background = roundedBackground(theme.mint, 10)
                            setPadding(dp(8), dp(4), dp(8), dp(4))
                        })
                    }

                    setOnClickListener {
                        selectLanguage(lang)
                    }
                }

                val layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                }

                addView(card, layoutParams)
            }
        }

        scrollView.addView(body)
        root.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun selectLanguage(lang: IndicTranslator.LanguageInfo) {
        val prefs = getSharedPreferences("sutra", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("preferred_language", lang.key)
            .putString("display_name", nameInput.text.toString().trim().take(28))
            .apply()

        Toast.makeText(this, "Language set to ${lang.nativeName} (${lang.englishName})", Toast.LENGTH_SHORT).show()
        startMainActivity()
    }

    private fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        })
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundedBackground(color: Int, radiusDp: Int = 18) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun rippleBackground(color: Int, radiusDp: Int) = RippleDrawable(
        ColorStateList.valueOf(Color.argb(45, 255, 255, 255)),
        roundedBackground(color, radiusDp).apply { setStroke(dp(1), divider) },
        roundedBackground(Color.WHITE, radiusDp),
    )
}
