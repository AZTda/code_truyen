package com.truyencloud.shared.api

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable
data class SettingsRemoteResponse(
    val has_api_key: Boolean = false,
    val engine: String = "ANTIGRAVITY_CLI",
    val host: String = "0.0.0.0",
    val port: Int = 8000,
    val agy_installed: Boolean = false,
    val agy_authenticated: Boolean = false,
    val active_model: String = "gemini-3.8-flash-high",
    val cli_version: String = "1.1.27"
)

@Serializable
data class ModelItem(
    val id: String,
    val name: String,
    val provider: String = "Google",
    val reasoning: String = "high"
)

@Serializable
data class ModelCatalogResponse(
    val cli_version: String = "1.1.27",
    val last_updated: String = "",
    val active_model: String = "gemini-3.8-flash-high",
    val active_effort: String = "high",
    val timeout_seconds: Int = 900,
    val models: List<ModelItem> = emptyList()
)

@Serializable
data class SelectModelRequest(
    val model_id: String,
    val effort: String? = null,
    val timeout_seconds: Int? = null
)

@Serializable
data class TranslationProfileResponse(
    val genre: String = "Tiên hiệp / Huyền huyễn",
    val sino_vietnamese_ratio: String = "Cân bằng tự nhiên",
    val custom_prompt: String = "",
    val prefetch_chapters: Int = 1,
    val auto_recrawl: Boolean = true,
    val reader_mode: String = "translated"
)

@Serializable
data class ApiKeyRequestBody(
    val api_key: String
)

@Serializable
data class ApiKeyRemoteResponse(
    val status: String = "success",
    val message: String = "",
    val engine: String = ""
)

@Serializable
data class UpdateTranslationProfileResponse(
    val status: String = "success",
    val profile: TranslationProfileResponse? = null
)

class SettingsApi {
    private val client = TruyenCloudApi.httpClient
    private val base: String get() = TruyenCloudApi.baseUrl

    suspend fun getSettings(): SettingsRemoteResponse {
        return client.get("$base/v1/settings").throwIfError().body()
    }

    suspend fun getModels(): ModelCatalogResponse {
        return client.get("$base/v1/settings/models").throwIfError().body()
    }

    /** Chỉ quản trị viên (403 với người dùng thường). */
    suspend fun refreshModels(): ModelCatalogResponse {
        return client.post("$base/v1/settings/models/refresh").throwIfError().body()
    }

    /** Chỉ quản trị viên (403 với người dùng thường). */
    suspend fun selectModel(modelId: String, effort: String? = null, timeoutSeconds: Int? = null): ModelCatalogResponse {
        return client.post("$base/v1/settings/models/select") {
            contentType(ContentType.Application.Json)
            setBody(SelectModelRequest(model_id = modelId, effort = effort, timeout_seconds = timeoutSeconds))
        }.throwIfError().body()
    }

    suspend fun getTranslationProfile(): TranslationProfileResponse {
        return client.get("$base/v1/settings/profile").throwIfError().body()
    }

    /** Chỉ quản trị viên: hồ sơ dịch dùng chung cho mọi người dùng. */
    suspend fun updateTranslationProfile(profile: TranslationProfileResponse): UpdateTranslationProfileResponse {
        return client.post("$base/v1/settings/profile") {
            contentType(ContentType.Application.Json)
            setBody(profile)
        }.throwIfError().body()
    }

    suspend fun updateApiKey(apiKey: String): ApiKeyRemoteResponse {
        return client.post("$base/v1/settings/api-key") {
            contentType(ContentType.Application.Json)
            setBody(ApiKeyRequestBody(apiKey))
        }.throwIfError().body()
    }
}
