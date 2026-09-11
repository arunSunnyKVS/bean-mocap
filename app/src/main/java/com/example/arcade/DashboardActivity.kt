package com.example.arcade

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.example.arcade.games.BrickStackerActivity
import com.example.arcade.games.PoseViewerActivity
import com.example.arcade.games.ReactionGridActivity
import com.example.arcade.games.RunnerActivity
import com.example.arcade.games.SlicerActivity

/** Game picker. Built in code — it's a list of five cards, not worth a layout file. */
class DashboardActivity : AppCompatActivity() {

    private data class Entry(
        val title: String,
        val posture: String,
        val blurb: String,
        val accent: String,
        val target: Class<*>,
    )

    private val entries = listOf(
        Entry(
            "Endless Runner", "Standing",
            "Lean to switch lanes, jump the barriers, duck the beams.",
            "#EF5350", RunnerActivity::class.java,
        ),
        Entry(
            "Slicer", "Seated",
            "Swipe a hand through the targets before they fall.",
            "#42A5F5", SlicerActivity::class.java,
        ),
        Entry(
            "Reaction Grid", "Seated",
            "Hover the lit cell to hit it. Beat the clock.",
            "#66BB6A", ReactionGridActivity::class.java,
        ),
        Entry(
            "Brick Stacker", "Seated",
            "Hover a brick for two seconds to pick it up, then stack it.",
            "#FFCA28", BrickStackerActivity::class.java,
        ),
        Entry(
            "Pose Viewer", "Either",
            "Skeleton overlay and a live 3D stick figure. No game, just tracking.",
            "#AB47BC", PoseViewerActivity::class.java,
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20))
            setBackgroundColor(Color.parseColor("#0F1020"))
        }

        column.addView(
            TextView(this).apply {
                text = "Motion Arcade"
                textSize = 30f
                setTextColor(Color.WHITE)
                setPadding(0, dp(36), 0, dp(4))
            },
        )
        column.addView(
            TextView(this).apply {
                text = "Games you play with your body. Front camera, no controller."
                textSize = 14f
                setTextColor(Color.parseColor("#A0A4C0"))
                setPadding(0, 0, 0, dp(20))
            },
        )

        for (entry in entries) {
            column.addView(card(entry, ::dp))
        }

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(Color.parseColor("#0F1020"))
                addView(column)
            },
        )
    }

    private fun card(entry: Entry, dp: (Int) -> Int): ViewGroup {
        val accent = Color.parseColor(entry.accent)

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(this@DashboardActivity).apply {
                    text = entry.title
                    textSize = 19f
                    setTextColor(Color.WHITE)
                },
            )
            addView(
                TextView(this@DashboardActivity).apply {
                    text = "  ${entry.posture}"
                    textSize = 11f
                    setTextColor(accent)
                },
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
            setBackgroundColor(Color.parseColor("#1A1C33"))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }

            addView(titleRow)
            addView(
                TextView(this@DashboardActivity).apply {
                    text = entry.blurb
                    textSize = 13f
                    setTextColor(Color.parseColor("#9DA2C0"))
                    setPadding(0, dp(6), 0, 0)
                },
            )

            setOnClickListener {
                startActivity(Intent(this@DashboardActivity, entry.target))
            }
        }
    }
}
