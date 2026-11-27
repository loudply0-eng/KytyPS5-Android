package com.kytyps5.android.library

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.kytyps5.android.GameEntry
import com.kytyps5.android.LauncherI18n
import com.kytyps5.android.LauncherSettings
import com.kytyps5.android.GameLibrary

/**
 * Cover-grid cells (icon0.png + title + title id), the layout of Vita3K's AppsListScreen and RPCSX's
 * GamesScreen rebuilt for a platform GridView. Tap plays; the gear opens per-game settings.
 */
class GameGridAdapter(
    private val context: Context,
    private val covers: CoverLoader,
    private val coverPx: Int,
    private val onPlay: (GameEntry) -> Unit,
    private val onSettings: (GameEntry) -> Unit,
) : BaseAdapter() {

    var games: List<GameEntry> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private val density = context.resources.displayMetrics.density
    private val Int.dp: Int get() = (this * density).toInt()

    private class Holder(
        val cover: ImageView,
        val placeholder: TextView,
        val title: TextView,
        val subtitle: TextView,
        val gear: TextView,
        val play: Button,
    )

    override fun getCount(): Int = games.size
    override fun getItem(position: Int): GameEntry = games[position]
    override fun getItemId(position: Int): Long = games[position].path.hashCode().toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: createCell()
        val holder = view.tag as Holder
        val game = games[position]
        holder.title.text = game.title
        val subtitle = game.status ?: game.titleId ?: if (game.folder.isFile) GameLibrary.imageLabel(game.folder) else ""
        val language = LauncherSettings(context).language
        holder.subtitle.text = LauncherI18n.translate(subtitle, language)
        holder.play.text = LauncherI18n.translate(if (game.launchable) "Play" else "Metadata only", language)
        holder.play.isEnabled = game.launchable
        holder.play.alpha = if (game.launchable) 1f else 0.55f
        holder.play.setOnClickListener { onPlay(game) }
        holder.placeholder.text = initials(game.title).ifBlank { "PS5" }
        holder.placeholder.visibility = View.GONE
        covers.load(game.icon, holder.cover) { holder.placeholder.visibility = View.VISIBLE }
        view.setOnClickListener { onPlay(game) }
        view.setOnLongClickListener { onSettings(game); true }
        holder.gear.setOnClickListener { onSettings(game) }
        return view
    }

    private fun createCell(): View {
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply {
                setColor(Color.rgb(40, 44, 56))
                cornerRadius = 10f * density
            }
        }
        val placeholder = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 30f
            setTextColor(Color.argb(200, 255, 255, 255))
            setTypeface(typeface, Typeface.BOLD)
        }
        val title = TextView(context).apply {
            textSize = 14f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
        }
        val subtitle = TextView(context).apply {
            textSize = 11f
            alpha = 0.65f
            maxLines = 1
        }
        val gear = TextView(context).apply {
            text = "⚙"
            textSize = 20f
            gravity = Gravity.CENTER
            contentDescription = "Game settings"
            setPadding(6.dp, 0, 0, 0)
        }
        val play = Button(context).apply {
            text = LauncherI18n.translate("Play", LauncherSettings(context).language)
            isAllCaps = false
            minHeight = 36.dp
            setPadding(6.dp, 0, 6.dp, 0)
        }
        val coverFrame = FrameLayout(context).apply {
            addView(cover, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(placeholder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(subtitle)
        }
        val caption = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(gear, LinearLayout.LayoutParams(36.dp, 36.dp))
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6.dp, 6.dp, 6.dp, 6.dp)
            addView(coverFrame, LinearLayout.LayoutParams(coverPx, coverPx))
            addView(caption, LinearLayout.LayoutParams(coverPx, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(play, LinearLayout.LayoutParams(coverPx, 40.dp))
            tag = Holder(cover, placeholder, title, subtitle, gear, play)
        }
    }

    private fun initials(title: String): String =
        title.split(' ', '-', '_').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
}
