// Created by ruoyi.sjd on 2025/2/6.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
package com.alibaba.mnnllm.android.widgets

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.PopupWindow
import android.widget.TextView
import com.alibaba.mnnllm.android.R

class PopupWindowHelper {
    fun showPopupWindow(
        context: Context?,
        view: View?,
        x: Int,
        y: Int,
        onClickListener: View.OnClickListener
    ) {
        val popupView =
            LayoutInflater.from(context).inflate(R.layout.assistant_text_popup_menu, null)

        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        // If you want to dismiss popup on outside touch
        popupWindow.isOutsideTouchable = true
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val copyItem = popupView.findViewById<TextView>(R.id.assistant_text_copy)
        val selectItem = popupView.findViewById<TextView>(R.id.assistant_text_select)
        val reportIssueItem = popupView.findViewById<TextView>(R.id.assistant_text_report)

        copyItem.setOnClickListener { v: View? ->
            onClickListener.onClick(v)
            popupWindow.dismiss()
        }

        selectItem.setOnClickListener { v: View? ->
            onClickListener.onClick(v)
            popupWindow.dismiss()
        }

        reportIssueItem.setOnClickListener { v: View? ->
            onClickListener.onClick(v)
            popupWindow.dismiss()
        }

        popupWindow.showAtLocation(view, Gravity.NO_GRAVITY, x, y)
    }
    fun showPromptPopupWindow(context: Context, view: View?, x: Int, y: Int, onClickListener: View.OnClickListener) {
        // Inflate the popup_layout view
        val popupView = LayoutInflater.from(context).inflate(R.layout.chat_prompt, null)

        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        // If you want to dismiss popup on outside touch
        popupWindow.isOutsideTouchable = true
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.rgb(240, 240, 240)))

        val sharedPreferences = context.getSharedPreferences("Prompt", Context.MODE_PRIVATE)
        val prompt = popupView.findViewById<TextView>(R.id.chat_prompt)
        val cancel = popupView.findViewById<Button>(R.id.chat_prompt_cancel)
        val sure = popupView.findViewById<Button>(R.id.chat_prompt_sure)
        prompt.text = sharedPreferences.getString("Prompt", "")

        cancel.setOnClickListener { v: View? ->
            onClickListener.onClick(v)
            popupWindow.dismiss()
        }

        sure.setOnClickListener { v: View? ->
            sharedPreferences.edit().putString("Prompt", prompt.text.toString()).apply()
            onClickListener.onClick(v)
            popupWindow.dismiss()
        }

        popupWindow.showAtLocation(view, Gravity.CENTER_HORIZONTAL or Gravity.TOP, x, y)
    }
}
