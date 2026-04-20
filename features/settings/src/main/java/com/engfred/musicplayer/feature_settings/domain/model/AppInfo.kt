package com.engfred.musicplayer.feature_settings.domain.model

/** Lightweight model for the "More Apps" catalogue. */
data class AppInfo(
    val emoji: String,
    val name: String,
    val description: String,
    /**
     * The repository slug (the part after "https://github.com/EngFred/").
     * Used to build the GitHub releases URL the user is sent to on tap.
     */
    val repoSlug: String
) {
    /** Full URL to the latest release of this app on GitHub. */
    val releasesUrl: String
        get() = "https://github.com/EngFred/$repoSlug/releases/latest"
}