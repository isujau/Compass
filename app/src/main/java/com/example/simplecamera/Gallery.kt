package com.example.simplecamera

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import java.io.File

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Where photos live: the app's private storage. */
object PhotoStore {
    fun dir(context: Context): File {
        val d = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        d.mkdirs()
        return d
    }

    /** Newest first. */
    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.extension.equals("jpg", true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
}

/** Thumbnail grid of all captured photos. */
class GalleryActivity : AppCompatActivity() {

    private lateinit var empty: TextView
    private val thumbs = ThumbAdapter { position ->
        startActivity(
            Intent(this@GalleryActivity, ViewerActivity::class.java)
                .putExtra(ViewerActivity.EXTRA_POSITION, position)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Photos"

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val recycler = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@GalleryActivity, 3)
            adapter = thumbs
        }
        empty = TextView(this).apply {
            text = "No photos yet"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }
        root.addView(recycler, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(empty, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val photos = PhotoStore.list(this)
        thumbs.submit(photos)
        empty.visibility = if (photos.isEmpty()) TextView.VISIBLE else TextView.GONE
    }
}

private class ThumbAdapter(val onClick: (Int) -> Unit) :
    RecyclerView.Adapter<ThumbAdapter.VH>() {

    private var items: List<File> = emptyList()

    class VH(val image: ImageView) : RecyclerView.ViewHolder(image)

    fun submit(list: List<File>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val size = parent.resources.displayMetrics.widthPixels / 3
        val iv = ImageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setPadding(2, 2, 2, 2)
        }
        return VH(iv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        Glide.with(holder.image).load(items[position]).centerCrop().override(300)
            .into(holder.image)
        holder.image.setOnClickListener { onClick(holder.bindingAdapterPosition) }
    }

    override fun getItemCount() = items.size
}

/** Full-screen viewer: swipe between photos, share or delete. */
class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_POSITION = "position"
    }

    private lateinit var photos: MutableList<File>
    private lateinit var pager: ViewPager2
    private lateinit var counter: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        photos = PhotoStore.list(this).toMutableList()
        if (photos.isEmpty()) {
            finish()
            return
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        pager = ViewPager2(this)
        pager.adapter = PageAdapter(photos)
        pager.setCurrentItem(
            intent.getIntExtra(EXTRA_POSITION, 0).coerceIn(0, photos.lastIndex), false
        )
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateCounter()
        })
        root.addView(pager, FrameLayout.LayoutParams(MATCH, MATCH))

        counter = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(48, 0, 48, 0)
        }
        val share = Button(this).apply {
            text = "Share"
            setOnClickListener { sharePhoto() }
        }
        val delete = Button(this).apply {
            text = "Delete"
            setOnClickListener { confirmDelete() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x88000000.toInt())
            setPadding(24, 24, 24, 48)
            addView(share)
            addView(counter)
            addView(delete)
        }
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        setContentView(root)
        updateCounter()
    }

    private fun updateCounter() {
        if (photos.isNotEmpty()) counter.text = "${pager.currentItem + 1} / ${photos.size}"
    }

    private fun sharePhoto() {
        val file = photos[pager.currentItem]
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share photo"))
    }

    private fun confirmDelete() {
        val position = pager.currentItem
        val file = photos[position]
        AlertDialog.Builder(this)
            .setTitle("Delete this photo?")
            .setMessage(file.name)
            .setPositiveButton("Delete") { _, _ ->
                if (file.delete()) {
                    photos.removeAt(position)
                    if (photos.isEmpty()) {
                        finish()
                    } else {
                        pager.adapter?.notifyDataSetChanged()
                        pager.post { updateCounter() }
                    }
                } else {
                    Toast.makeText(this, "Could not delete", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

private class PageAdapter(val items: List<File>) : RecyclerView.Adapter<PageAdapter.VH>() {

    class VH(val image: ImageView) : RecyclerView.ViewHolder(image)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val iv = ImageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        return VH(iv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        Glide.with(holder.image).load(items[position]).into(holder.image)
    }

    override fun getItemCount() = items.size
}
