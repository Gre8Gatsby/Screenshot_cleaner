package com.expirer.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.expirer.R
import com.expirer.core.ScreenshotRecord
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenshotAdapter(
    private val onKeepClicked: (ScreenshotRecord) -> Unit,
    private val onDeleteClicked: (ScreenshotRecord) -> Unit
) : ListAdapter<ScreenshotRecord, ScreenshotAdapter.ViewHolder>(DiffCallback) {

    object DiffCallback : DiffUtil.ItemCallback<ScreenshotRecord>() {
        override fun areItemsTheSame(oldItem: ScreenshotRecord, newItem: ScreenshotRecord): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ScreenshotRecord, newItem: ScreenshotRecord): Boolean {
            return oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tagged_screenshot, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTag: TextView = itemView.findViewById(R.id.tvTag)
        private val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
        private val tvExpiresIn: TextView = itemView.findViewById(R.id.tvExpiresIn)
        private val tvFileSize: TextView = itemView.findViewById(R.id.tvFileSize)
        private val btnKeep: Button = itemView.findViewById(R.id.btnKeep)
        private val btnDelete: Button = itemView.findViewById(R.id.btnDelete)

        fun bind(item: ScreenshotRecord) {
            val file = File(item.filePath)
            tvFileName.text = file.name

            // Tag styling
            tvTag.text = item.tag
            applyTagBadgeStyle(tvTag, item.tag)

            // Format size
            val sizeKb = item.fileSizeBytes / 1024
            tvFileSize.text = if (sizeKb > 1024) {
                String.format(Locale.getDefault(), "%.1f MB", sizeKb / 1024.0)
            } else {
                "$sizeKb KB"
            }

            // Expiry countdown
            val remainingMs = item.getRemainingTimeMillis()
            if (remainingMs <= 0) {
                tvExpiresIn.text = "Expired (pending delete)"
                tvExpiresIn.setTextColor(Color.parseColor("#E53935"))
            } else {
                val hours = remainingMs / (1000 * 60 * 60)
                val mins = (remainingMs % (1000 * 60 * 60)) / (1000 * 60)
                tvExpiresIn.text = "Expires in ${hours}h ${mins}m"
                tvExpiresIn.setTextColor(Color.parseColor("#FB8C00"))
            }

            btnKeep.setOnClickListener { onKeepClicked(item) }
            btnDelete.setOnClickListener { onDeleteClicked(item) }
        }

        private fun applyTagBadgeStyle(textView: TextView, tag: String) {
            val (bgColor, textColor) = when (tag.uppercase(Locale.ROOT)) {
                "OTP" -> Pair("#FFECEB", "#D32F2F")
                "PAYMENT" -> Pair("#E8F5E9", "#2E7D32")
                "BOARDING_PASS", "BOARDING", "TICKET" -> Pair("#E3F2FD", "#1565C0")
                "RECEIPT" -> Pair("#FFF8E1", "#F57F17")
                else -> Pair("#F3E5F5", "#7B1FA2")
            }

            val drawable = GradientDrawable().apply {
                setColor(Color.parseColor(bgColor))
                cornerRadius = 16f
            }
            textView.background = drawable
            textView.setTextColor(Color.parseColor(textColor))
        }
    }
}

