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
import android.widget.Toast

class LanguagePreferenceActivity : Activity() {

    private val ink = Color.rgb(15, 29, 51)
    private val mutedInk = Color.rgb(91, 103, 121)
    private val surface = Color.WHITE
    private val canvas = Color.rgb(241, 242, 244)
    private val divider = Color.rgb(226, 230, 235)
    private val accentTeal = Color.rgb(143, 230, 214)
    private val tealDark = Color.rgb(22, 55, 70)
    private val blue = Color.rgb(40, 81, 127)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ink
        window.navigationBarColor = surface

        val prefs = getSharedPreferences("sutra", Context.MODE_PRIVATE)
        val forceChange = intent.getBooleanExtra("force_change", false)
        val existingLanguage = prefs.getString("preferred_language", null)

        // If a language was already chosen and user didn't explicitly request to change it, skip straight to MainActivity
        if (existingLanguage != null && !forceChange) {
            startMainActivity()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(canvas)
        }

        // Top branded header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ink)
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
                    text = "OFFLINE"
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
                text = "Multi-Lingual Offline Speech & Emergency Mesh"
                textSize = 12f
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
                text = "Choose your preferred language"
                textSize = 18f
                setTextColor(ink)
                setTypeface(null, Typeface.BOLD)
            })

            addView(TextView(this@LanguagePreferenceActivity).apply {
                text = "Transcripts will show a translated subtitle row in this language. The app interface remains in English."
                textSize = 13f
                setTextColor(mutedInk)
                setPadding(0, dp(4), 0, dp(18))
            })

            // Language cards
            IndicTranslator.supportedLanguages.forEach { lang ->
                val isSelected = lang.key == existingLanguage

                val card = LinearLayout(this@LanguagePreferenceActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rippleBackground(if (isSelected) Color.rgb(231, 242, 240) else surface, 14)
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    isClickable = true
                    isFocusable = true

                    val textContainer = LinearLayout(this@LanguagePreferenceActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                        addView(TextView(this@LanguagePreferenceActivity).apply {
                            text = lang.nativeName
                            textSize = 17f
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
                            text = "CURRENT"
                            textSize = 10f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(blue)
                            background = roundedBackground(Color.rgb(210, 230, 245), 10)
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
        prefs.edit().putString("preferred_language", lang.key).apply()

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
