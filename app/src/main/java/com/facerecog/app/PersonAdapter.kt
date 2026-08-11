package com.facerecog.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.facerecog.app.databinding.ItemPersonBinding

class PersonAdapter(
    private val onClick: (Person) -> Unit,
    private val onDelete: (Person) -> Unit
) : ListAdapter<Person, PersonAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemPersonBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPersonBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val person = getItem(position)
        holder.binding.tvName.text = person.name
        if (!person.thumbnailUrl.isNullOrEmpty()) {
            Glide.with(holder.itemView).load(person.thumbnailUrl).circleCrop()
                .into(holder.binding.imgThumb)
        } else {
            holder.binding.imgThumb.setImageResource(android.R.drawable.ic_menu_gallery)
        }
        holder.binding.root.setOnClickListener { onClick(person) }
        holder.binding.btnDelete.setOnClickListener { onDelete(person) }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Person>() {
            override fun areItemsTheSame(oldItem: Person, newItem: Person) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Person, newItem: Person) = oldItem == newItem
        }
    }
}        val DIFF = object : DiffUtil.ItemCallback<Person>() {
            override fun areItemsTheSame(oldItem: Person, newItem: Person) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Person, newItem: Person) = oldItem == newItem
        }
    }
}
