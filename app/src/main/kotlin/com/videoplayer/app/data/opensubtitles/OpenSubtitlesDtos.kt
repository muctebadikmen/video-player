// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.opensubtitles

import com.videoplayer.core.playback.SubtitleSearchResult
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(
    val token: String,
    val base_url: String? = null,
    val user: UserDto? = null,
)

@Serializable
data class UserDto(
    val allowed_downloads: Int = 0,
    val level: String? = null,
    val vip: Boolean = false,
)

@Serializable
data class SearchResponse(
    val data: List<SearchItemDto> = emptyList(),
    val total_count: Int = 0,
)

@Serializable
data class SearchItemDto(val attributes: SearchAttributesDto)

@Serializable
data class SearchAttributesDto(
    val language: String? = null,
    val download_count: Int = 0,
    val ratings: Double = 0.0,
    val from_trusted: Boolean = false,
    val ai_translated: Boolean = false,
    val machine_translated: Boolean = false,
    val moviehash_match: Boolean = false,
    val release: String? = null,
    val files: List<FileDto> = emptyList(),
)

@Serializable
data class FileDto(val file_id: Long, val file_name: String? = null)

@Serializable
data class DownloadRequest(val file_id: Long)

@Serializable
data class DownloadResponse(
    val link: String,
    val remaining: Int = 0,
    val reset_time_utc: String? = null,
)

/** Flatten the OpenSubtitles search payload to the pure ranking model. One result per file. */
fun SearchResponse.toSearchResults(): List<SubtitleSearchResult> =
    data.flatMap { item ->
        val a = item.attributes
        a.files.map { f ->
            SubtitleSearchResult(
                fileId = f.file_id,
                fileName = f.file_name ?: a.release ?: "subtitle.srt",
                language = (a.language ?: "").lowercase(),
                release = a.release ?: "",
                downloadCount = a.download_count,
                rating = a.ratings,
                fromTrusted = a.from_trusted,
                machineTranslated = a.ai_translated || a.machine_translated,
                hashMatch = a.moviehash_match,
            )
        }
    }
