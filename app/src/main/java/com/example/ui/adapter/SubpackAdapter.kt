package com.example.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.R
import com.example.data.local.WhatsAppSubpackEntity
import com.example.databinding.ItemWhatsappSubpackBinding
import com.example.whatsapp.SubpackBuilder
import com.example.whatsapp.WhatsAppPackExporter
import java.io.File

class SubpackAdapter(
    private val onAddToWhatsApp: (WhatsAppSubpackEntity) -> Unit
) : ListAdapter<WhatsAppSubpackEntity, SubpackAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemWhatsappSubpackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemWhatsappSubpackBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(sp: WhatsAppSubpackEntity) {
            val ctx = binding.root.context
            binding.tvSubpackName.text = sp.displayName
            val typeStr = ctx.getString(
                if (sp.isAnimated) R.string.subpack_type_animated else R.string.subpack_type_static
            )
            binding.tvSubpackInfo.text = ctx.getString(R.string.subpack_info_format, typeStr, sp.stickerCount)

            val trayFile = File(sp.trayIconPath)
            if (trayFile.exists()) {
                binding.ivSubpackTray.load(trayFile)
            } else {
                binding.ivSubpackTray.setImageResource(R.drawable.ic_launcher_art)
            }

            if (!sp.isWhatsAppReady) {
                val needed = maxOf(1, SubpackBuilder.MIN_STICKERS_PER_PACK - sp.stickerCount)
                binding.tvSubpackDraftHint.visibility = View.VISIBLE
                binding.tvSubpackDraftHint.text = ctx.getString(R.string.subpack_draft_too_few, needed)
                binding.btnAddWhatsapp.isEnabled = false
                binding.btnAddWhatsapp.alpha = 0.45f
                binding.btnAddWhatsapp.setText(R.string.add_to_whatsapp)
            } else {
                binding.tvSubpackDraftHint.visibility = View.GONE
                binding.btnAddWhatsapp.isEnabled = true
                binding.btnAddWhatsapp.alpha = 1f
                val isAdded = sp.addedToWhatsApp || WhatsAppPackExporter.isWhitelistedInWhatsApp(ctx, sp.id)
                binding.btnAddWhatsapp.setText(
                    if (isAdded) R.string.already_in_whatsapp else R.string.add_to_whatsapp
                )
            }

            binding.btnAddWhatsapp.setOnClickListener { onAddToWhatsApp(sp) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<WhatsAppSubpackEntity>() {
            override fun areItemsTheSame(oldItem: WhatsAppSubpackEntity, newItem: WhatsAppSubpackEntity) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: WhatsAppSubpackEntity, newItem: WhatsAppSubpackEntity) =
                oldItem == newItem
        }
    }
}
