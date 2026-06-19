// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.videoplayer.app.data.opensubtitles.OpenSubtitlesCredentials

/**
 * Settings → OpenSubtitles. Collects the user's OWN Api-Key + username/password (the app embeds
 * none), logs in, shows quota/level, and lets the user log out + edit favorite languages.
 * The password lives only in [password] (transient) — it is passed to login and never persisted.
 */
@Composable
fun OpenSubtitlesSettings(
    creds: OpenSubtitlesCredentials,
    loginStatus: String?,
    onApiKeyChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onFavLangsChange: (String) -> Unit,
    onLogin: (apiKey: String, username: String, password: String) -> Unit,
    onLogout: () -> Unit,
) {
    var apiKey by remember(creds.apiKey) { mutableStateOf(creds.apiKey) }
    var username by remember(creds.username) { mutableStateOf(creds.username) }
    var password by remember { mutableStateOf("") }
    var favLangs by remember(creds.favoriteLanguages) { mutableStateOf(creds.favoriteLanguages.joinToString(",")) }
    val loggedIn = creds.token != null

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("OpenSubtitles", style = MaterialTheme.typography.titleMedium)
        Text(
            "Search and download subtitles online. You need your own free account + Api-Key:\n" +
                "1) Sign up at opensubtitles.com  2) Create a free API Consumer at " +
                "opensubtitles.com/consumers  3) Paste the Api-Key below and log in.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (loggedIn) {
            Text(
                "Signed in as ${creds.username}" +
                    (creds.level?.let { " · $it" } ?: "") +
                    " · ${creds.remaining}/${creds.allowedDownloads} downloads left today",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = favLangs,
                onValueChange = { favLangs = it; onFavLangsChange(it) },
                label = { Text("Favorite languages (comma-separated, e.g. tr,en)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = onLogout) { Text("Log out") }
        } else {
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it; onApiKeyChange(it) },
                label = { Text("Api-Key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username, onValueChange = { username = it; onUsernameChange(it) },
                label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onLogin(apiKey, username, password) },
                enabled = apiKey.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
            ) { Text("Log in") }
        }

        loginStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}
