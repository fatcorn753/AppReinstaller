package com.tatu.reinstaller

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * アプリ選択ダイアログ用のアダプタ。
 *
 * アイコンの読み込み ([ApplicationInfo.loadIcon]) は1件あたりは軽くても件数が多いと
 * 確実にカクつくので、表示された行の分だけをバックグラウンドで読み、LruCache に貯める。
 */
class AppPickerAdapter(
    context: Context,
    private val entries: List<AppEntry>
) : ArrayAdapter<AppPickerAdapter.AppEntry>(context, 0, entries) {

    data class AppEntry(
        val packageName: String,
        val label: String,
        val info: ApplicationInfo
    )

    private val pm = context.packageManager
    private val inflater = LayoutInflater.from(context)
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(ICON_LOADER_THREADS)
    private val iconCache = LruCache<String, Drawable>(ICON_CACHE_SIZE)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_app, parent, false)
        val entry = entries[position]

        view.findViewById<TextView>(R.id.appLabel).text = entry.label
        view.findViewById<TextView>(R.id.appPackage).text = entry.packageName

        val iconView = view.findViewById<ImageView>(R.id.appIcon)
        // リサイクルされたビューに前の行のアイコンが残らないよう、tag で持ち主を確認する。
        iconView.tag = entry.packageName

        val cached = iconCache.get(entry.packageName)
        if (cached != null) {
            iconView.setImageDrawable(cached)
        } else {
            iconView.setImageDrawable(null)
            runCatching {
                executor.execute {
                    val icon = runCatching { entry.info.loadIcon(pm) }.getOrNull() ?: return@execute
                    iconCache.put(entry.packageName, icon)
                    handler.post {
                        if (iconView.tag == entry.packageName) iconView.setImageDrawable(icon)
                    }
                }
            }
        }
        return view
    }

    /** ダイアログを閉じたら読み込みスレッドを止める。 */
    fun shutdown() {
        executor.shutdownNow()
    }

    private companion object {
        const val ICON_LOADER_THREADS = 3
        const val ICON_CACHE_SIZE = 128
    }
}
