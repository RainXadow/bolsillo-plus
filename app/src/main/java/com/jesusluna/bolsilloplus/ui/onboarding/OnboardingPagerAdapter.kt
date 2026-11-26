package com.jesusluna.bolsilloplus.ui.onboarding

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.jesusluna.bolsilloplus.R

class OnboardingPagerAdapter(
    private val pages: List<OnboardingPage>
) : RecyclerView.Adapter<OnboardingPagerAdapter.PageVH>() {

    inner class PageVH(view: View) : RecyclerView.ViewHolder(view) {
        val ivImage: ImageView       = view.findViewById(R.id.iv_onboarding_image)
        val tvTitle: TextView        = view.findViewById(R.id.tv_onboarding_title)
        val tvSubtitle: TextView     = view.findViewById(R.id.tv_onboarding_subtitle)
        val llBullets: LinearLayout  = view.findViewById(R.id.ll_onboarding_bullets)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_onboarding_page, parent, false)
        return PageVH(view)
    }

    override fun onBindViewHolder(holder: PageVH, position: Int) {
        val page = pages[position]
        holder.ivImage.setImageResource(page.imageRes)
        holder.ivImage.contentDescription = page.title
        holder.tvTitle.text = page.title
        holder.tvSubtitle.text = page.subtitle

        holder.llBullets.removeAllViews()
        page.bullets.forEach { bullet ->
            val tv = TextView(holder.itemView.context).apply {
                text = "• $bullet"
                textSize = 14f
                setPadding(0, 4, 0, 4)
                setTextColor(context.getColor(R.color.text_primary))
            }
            holder.llBullets.addView(tv)
        }
    }

    override fun getItemCount() = pages.size
}
