package com.alibaba.mnnllm.android.llm


import android.os.SystemClock
import android.util.Log
import com.alibaba.mnnllm.android.chat.GenerateResultProcessor
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import fi.iki.elonen.NanoHTTPD
import java.io.*
import java.util.*
import java.util.concurrent.Executors


// 流式回调接口
interface StreamCallback {
    fun onPartialResponse(partialResponse: ChatCompletionStreamResponse)
    fun onPartialResponse(message: String)
    fun onComplete()
    fun onError(errorMessage: String)
}

// 数据模型
data class ChatCompletionRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Double = 0.7,
    val top_p: Double = 1.0,
    val n: Int = 1,
    val stream: Boolean = false,
    val max_tokens: Int = 2000
)

data class Message(
    val role: String,
    val content: String
)

data class ChatCompletionResponse(
    val id: String = UUID.randomUUID().toString(),
    val `object`: String = "chat.completion",
    val created: Long = System.currentTimeMillis() / 1000,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage
)

data class ChatCompletionStreamResponse(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<Choice>
)

data class Choice(
    val index: Int,
    val delta: Message? = null,
    val message: Message? = null,
    val finish_reason: String? = null
)

data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)

data class ModelsResponse(
    val data: MutableList<Model> = mutableListOf()
) {
    fun addModel(model: Model) {
        data.add(model)
    }
}

data class Model(
    val id: String,
    val `object`: String = "model",
    val created: Long = System.currentTimeMillis() / 1000,
    val owned_by: String = "local",
    val root: String? = null,
    val parent: String? = null,
    val permission: List<Any> = emptyList()
)

data class ErrorResponse(
    val error: Map<String, String>
) {
    constructor(message: String, type: String) : this(
        mapOf(
            "message" to message,
            "type" to type,
            "param" to "",
            "code" to ""
        )
    )
}
class LlmServer : NanoHTTPD(8080) {

    companion object {
        private const val PORT = 8080
        var runingLLM : ChatSession? = null
    }


    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val threadPool = Executors.newFixedThreadPool(4)
//    private val inferenceEngine = MNNInferenceEngine.getInstance()

    override fun serve(session: IHTTPSession): Response {
        // 处理CORS
        val defaultResponse = handleCORS(session)
        if (defaultResponse != null) return defaultResponse

        // 路由处理
        return try {
            when (session.uri) {
                "/v1/chat/completions" -> handleChatCompletion(session)
                "/v1/models" -> handleListModels()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            createErrorResponse(Response.Status.INTERNAL_ERROR, "server_error", e.message ?: "Unknown error")
        }
    }

    private fun handleCORS(session: IHTTPSession): Response? {
        if (session.method == Method.OPTIONS) {
            val response = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            addCORSHeaders(response)
            return response
        }
        return null
    }

    private fun handleChatCompletion(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return createErrorResponse(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed",
                "Method must be POST")
        }

        // 解析请求
        val request = parseChatCompletionRequest(session)
            ?: return createErrorResponse(Response.Status.BAD_REQUEST, "invalid_request",
                "Failed to parse request body")

        // 流式响应处理
        return if (request.stream) {
            handleStreamChatCompletion(request)
        }
        else {
            handleNormalChatCompletion(request)
        }
    }

    private fun parseChatCompletionRequest(session: IHTTPSession): ChatCompletionRequest? {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val requestBody = files["postData"]

        return try {
            gson.fromJson(requestBody, ChatCompletionRequest::class.java)
        } catch (e: JsonSyntaxException) {
            null
        }
    }

    private fun handleNormalChatCompletion(request: ChatCompletionRequest): Response {
        return try {
            // 异步执行推理
            val generateResultProcessor: GenerateResultProcessor =
                GenerateResultProcessor.R1GenerateResultProcessor(
                    "",""
                )
            generateResultProcessor.generateBegin()
            var llmResult : String? = ""
            val result = runingLLM?.generate(request.messages.last().content, mapOf(), object: GenerateProgressListener {
                override fun onProgress(progress: String?): Boolean {
                    generateResultProcessor.process(progress)
                    return false;
                }
            })


            // 获取结果（超时处理）
//            val response = future.get(60, TimeUnit.SECONDS)
//            val response = newFixedLengthResponse(Response.Status.OK,
//                "application/json", generateResultProcessor.getRawResult())
//            val response = ErrorResponse(generateResultProcessor.getRawResult(), "errorType")
            val msg = Message("assistant", generateResultProcessor.getRawResult())
            val responseCompletion = ChatCompletionRequest("", arrayListOf(msg));
            // 构建JSON响应
            val jsonResponse = gson.toJson(responseCompletion)
            val httpResponse = newFixedLengthResponse(Response.Status.OK,
                "application/json", jsonResponse)
            addCORSHeaders(httpResponse)
            httpResponse
        } catch (e: Exception) {
            createErrorResponse(Response.Status.INTERNAL_ERROR, "server_error", e.message ?: "Unknown error")
        }
    }

    private fun handleStreamChatCompletion(request: ChatCompletionRequest): Response {

        val pipe = PipedOutputStream()
        val inputStream = PipedInputStream(pipe)


        // 在新线程中处理推理和输出
        threadPool.submit {
            try {
                val writer = PrintWriter(BufferedWriter(OutputStreamWriter(pipe)), true)
                val streamCallback = object : StreamCallback {

                    override fun onPartialResponse(partialResponse: ChatCompletionStreamResponse) {
                        try {
                            val data = "data: ${gson.toJson(partialResponse)}\n\n"
                            writer.print(data)
                            writer.flush()  // 刷新Writer
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    override fun onPartialResponse(message: String) {
                        val responseId = UUID.randomUUID().toString()
                        val createdTime = System.currentTimeMillis()
                        val partialResponse = ChatCompletionStreamResponse(
                            id = responseId,
                            created = createdTime,
                            model = request.model,
                            choices = listOf(
                                Choice(
                                    index = 0,
                                    delta = Message("system",message),
                                    finish_reason = "stop"
                                )
                            )
                        )
                        onPartialResponse(partialResponse);
                    }

                    override fun onComplete() {
                        try {
                            val data = "data: [DONE]\n\n"
                            writer.print(data)
                            writer.flush()
                            writer.close()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    override fun onError(errorMessage: String) {
                        try {
                            val error = ErrorResponse(errorMessage, "stream_error")
                            val data = "data: ${gson.toJson(error)}\n\n"
                            writer.print(data)
                            writer.flush()
                            writer.close()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                val generateResultProcessor: GenerateResultProcessor =
                    GenerateResultProcessor.R1GenerateResultProcessor(
                        "",""
                    )
                generateResultProcessor.generateBegin()
                val result = runingLLM?.generate(request.messages.last().content, mapOf(), object: GenerateProgressListener {
                    override fun onProgress(progress: String?): Boolean {
                        if (progress == null) {
//                            val responseId = UUID.randomUUID().toString()
//                            val createdTime = System.currentTimeMillis()
//                            val partialResponse = ChatCompletionStreamResponse(
//                                id = responseId,
//                                created = createdTime,
//                                model = request.model,
//                                choices = listOf(
//                                    Choice(
//                                        index = 0,
//                                        delta = Message("assistant",""),
//                                        finish_reason = "stop"
//                                    )
//                                )
//                            )
//
//                            // 回调发送部分响应
//                            streamCallback.onPartialResponse(partialResponse)
                            return true;
                        }
                        // 初始化响应元数据
                        val responseId = UUID.randomUUID().toString()
                        val createdTime = System.currentTimeMillis() / 1000
                        val partialResponse = ChatCompletionStreamResponse(
                            id = responseId,
                            created = createdTime,
                            model = request.model,
                            choices = listOf(
                                Choice(
                                    index = 0,
                                    delta = Message("assistant",progress!!),
                                    finish_reason = null
                                )
                            )
                        )

                        // 回调发送部分响应
                        streamCallback.onPartialResponse(partialResponse)
                        generateResultProcessor.process(progress)
                        return false;
                    }
                })
                val responseId = UUID.randomUUID().toString()
                val createdTime = System.currentTimeMillis()
                val partialResponse = ChatCompletionStreamResponse(
                    id = responseId,
                    created = createdTime,
                    model = request.model,
                    choices = listOf(
                        Choice(
                            index = 0,
                            delta = Message("system",gson.toJson(result)),
                            finish_reason = "stop"
                        )
                    )
                )
                // 回调发送部分响应
                streamCallback.onPartialResponse(partialResponse)
                streamCallback.onComplete()
            } catch (e: Exception) {
                try {
                    pipe.close()
                } catch (ignored: IOException) {}
            }
        }

        val response = newFixedLengthResponse(Response.Status.OK, "text/event-stream", inputStream, -1)
        addCORSHeaders(response)
        response.addHeader("Cache-Control", "no-cache, no-transform")
        response.addHeader("X-Accel-Buffering", "no")  // 禁用Nginx缓冲（如果适用）
        return response







//
//
//
//
//
//        return try {
//            // 使用自定义InputStream实现流式响应
//            val inputStream = object : InputStream() {
//                private var bufferIndex = 0
//                private var isClosed = false
//                private val lock = Object() // 用于线程同步的对象
//                val pipe = PipedOutputStream()
//                val inputStream = PipedInputStream(pipe)
//                val writer = PrintWriter(BufferedWriter(OutputStreamWriter(pipe)), true)
//
//                private val streamCallback = object : StreamCallback {
//
//                    override fun onPartialResponse(partialResponse: ChatCompletionStreamResponse) {
//                        try {
//                            val data = "data: ${gson.toJson(partialResponse)}\n\n".toByteArray(Charsets.UTF_8)
//                            synchronized(lock) {
//                                writer.print(data)
//                                writer.flush()  // 刷新Writer
////                                outputStream.flush()  // 强制刷新底层输出流
//                                lock.notifyAll() // 通知等待的读取线程
//                            }
//                        } catch (e: Exception) {
//                            e.printStackTrace()
//                        }
//                    }
//
//                    override fun onComplete() {
//                        try {
//                            val data = "data: [DONE]\n\n".toByteArray(Charsets.UTF_8)
//                            synchronized(lock) {
//                                writer.print(data)
//                                writer.flush()
//                                isClosed = true
//                                lock.notifyAll() // 通知等待的读取线程
//                            }
//                        } catch (e: Exception) {
//                            e.printStackTrace()
//                        }
//                    }
//
//                    override fun onError(errorMessage: String) {
//                        try {
//                            val error = ErrorResponse(errorMessage, "stream_error")
//                            val data = "data: ${gson.toJson(error)}\n\n".toByteArray(Charsets.UTF_8)
//                            synchronized(lock) {
//                                writer.print(data)
//                                writer.flush()
//                                isClosed = true
//                                lock.notifyAll() // 通知等待的读取线程
//                            }
//                        } catch (e: Exception) {
//                            e.printStackTrace()
//                        }
//                    }
//                }
//
//                // 启动异步推理
//                init {
//
//                    threadPool.submit {
//                        try {
//                            val generateResultProcessor: GenerateResultProcessor =
//                                GenerateResultProcessor.R1GenerateResultProcessor(
//                                    "",""
//                                )
//                            generateResultProcessor.generateBegin()
//                            val result = runingLLM?.generate(request.messages.last().content, mapOf(), object: GenerateProgressListener {
//                                override fun onProgress(progress: String?): Boolean {
//                                    if (progress == null) {
//                                        return true;
//                                    }
//                                    Log.d("ttt",progress!!)
//                                    // 初始化响应元数据
//                                    val responseId = UUID.randomUUID().toString()
//                                    val createdTime = System.currentTimeMillis() / 1000
//                                    val partialResponse = ChatCompletionStreamResponse(
//                                        id = responseId,
//                                        created = createdTime,
//                                        model = request.model,
//                                        choices = listOf(
//                                            Choice(
//                                                index = 0,
//                                                delta = Message("assistant",progress!!),
//                                                finish_reason = null
//                                            )
//                                        )
//                                    )
//
//                                    // 回调发送部分响应
//                                    streamCallback.onPartialResponse(partialResponse)
//
//                                    generateResultProcessor.process(progress)
//                                    return false;
//                                }
//                            })
//                            val responseId = UUID.randomUUID().toString()
//                            val createdTime = System.currentTimeMillis() / 1000
//                            val partialResponse = ChatCompletionStreamResponse(
//                                id = responseId,
//                                created = createdTime,
//                                model = request.model,
//                                choices = listOf(
//                                    Choice(
//                                        index = 0,
//                                        delta = Message("assistant",""),
//                                        finish_reason = "stop"
//                                    )
//                                )
//                            )
//
//                            // 回调发送部分响应
//                            streamCallback.onPartialResponse(partialResponse)
//                            streamCallback.onComplete()
//
//                        } catch (e: Exception) {
//                            streamCallback.onError("Internal error: ${e.message}")
//                        }
//                    }
//                }
//            }
//
//            // 创建流式响应
//            val response = newFixedLengthResponse(Response.Status.OK, "text/event-stream", inputStream, -1)
//            addCORSHeaders(response)
//            response.addHeader("Cache-Control", "no-cache, no-transform")
//            response.addHeader("X-Accel-Buffering", "no")
//            response.addHeader("Connection", "keep-alive")
//            return response
//        } catch (e: Exception) {
//            createErrorResponse(Response.Status.INTERNAL_ERROR, "server_error", e.message ?: "Unknown error")
//        }
    }

    private fun handleListModels(): Response {
        val modelsResponse = ModelsResponse()
        modelsResponse.addModel(Model("local-mnn-model", "text-davinci-003 equivalent"))
        modelsResponse.addModel(Model("local-mnn-llama2", "llama2-7b equivalent"))

        val jsonResponse = gson.toJson(modelsResponse)
        val response = newFixedLengthResponse(Response.Status.OK,
            "application/json", jsonResponse)
        addCORSHeaders(response)
        return response
    }

    private fun createErrorResponse(status: Response.Status, errorType: String, message: String): Response {
        val error = ErrorResponse(message, errorType)
        val errorJson = gson.toJson(error)
        val response = newFixedLengthResponse(status, "application/json", errorJson)
        addCORSHeaders(response)
        return response
    }

    private fun addCORSHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
    }

    // 启动和停止服务器方法
    fun startServer() {
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            println("Server started on port $PORT")
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun stopServer() {
        stop()
        threadPool.shutdown()
        println("Server stopped")
    }

}