package com.subai.translator

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SubtitleAdapter(private val items: MutableList<SubtitleItem>) :
    RecyclerView.Adapter<SubtitleAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvOriginal: TextView = view.findViewById(R.id.tvOriginal)
        val tvTranslated: TextView = view.findViewById(R.id.tvTranslated)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_subtitle, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvTime.text = item.getTimeRange()
        holder.tvOriginal.text = item.originalText
        holder.tvTranslated.text = item.translatedText.ifEmpty { "[Menerjemahkan...]" }
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<SubtitleItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
