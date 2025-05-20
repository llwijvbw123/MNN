// Created by ruoyi.sjd on 2024/12/25.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
package com.alibaba.mnnllm.android.llm

import android.text.TextUtils
import com.alibaba.mnnllm.android.chat.model.ChatDataItem

class ChatService {
    private val transformerSessionMap: MutableMap<String, ChatSession> = HashMap()
    private val diffusionSessionMap: MutableMap<String, ChatSession> = HashMap()

    @Synchronized
    fun createLlmSession(
        modelId: String?,
        modelDir: String?,
        sessionIdParam: String?,
        chatDataItemList: List<ChatDataItem>?,
        supportOmni:Boolean
    ): LlmSession {
        return createLlmSession(modelId,modelDir,sessionIdParam,chatDataItemList,supportOmni,"")
    }
    @Synchronized
    fun createLlmSession(
        modelId: String?,
        modelDir: String?,
        sessionIdParam: String?,
        chatDataItemList: List<ChatDataItem>?,
        supportOmni:Boolean,
        prompt:String?
    ): LlmSession {
        var sessionId:String = if (TextUtils.isEmpty(sessionIdParam)) {
            System.currentTimeMillis().toString()
        } else {
            sessionIdParam!!
        }
        val session = LlmSession(modelId!!, sessionId, modelDir!!, chatDataItemList,prompt)
        session.supportOmni = supportOmni
        transformerSessionMap[sessionId] = session
        return session
    }
    @Synchronized
    fun createDiffusionSession(
        modelId: String?,
        modelDir: String?,
        sessionIdParam: String?,
        chatDataItemList: List<ChatDataItem>?
    ): ChatSession {
        return createDiffusionSession(modelId,modelDir,sessionIdParam,chatDataItemList,"");
    }
    @Synchronized
    fun createDiffusionSession(
        modelId: String?,
        modelDir: String?,
        sessionIdParam: String?,
        chatDataItemList: List<ChatDataItem>?,
        prompt:String?
    ): ChatSession {
        var sessionId:String = if (TextUtils.isEmpty(sessionIdParam)) {
            System.currentTimeMillis().toString()
        } else {
            sessionIdParam!!
        }
        val session = DiffusionSession( sessionId, modelDir!!)
        diffusionSessionMap[sessionId] = session
        return session
    }

    @Synchronized
    fun getSession(sessionId: String): ChatSession? {
        return if (transformerSessionMap.containsKey(sessionId)) {
            transformerSessionMap[sessionId]
        } else {
            diffusionSessionMap[sessionId]
        }
    }

    @Synchronized
    fun removeSession(sessionId: String) {
        transformerSessionMap.remove(sessionId)
        diffusionSessionMap.remove(sessionId)
    }

    companion object {
        private var instance: ChatService? = null

        @JvmStatic
        @Synchronized
        fun provide(): ChatService {
            if (instance == null) {
                instance = ChatService()
            }
            return instance!!
        }
    }
}
