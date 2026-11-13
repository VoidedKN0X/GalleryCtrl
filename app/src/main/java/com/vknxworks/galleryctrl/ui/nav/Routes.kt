package com.vknxworks.galleryctrl.ui.nav

import kotlinx.serialization.Serializable

// Tabs
@Serializable data object PhotosRoute
@Serializable data object AlbumsRoute

/** An album grid. [kind]: "album" (with [albumId]), "favorites", "videos". */
@Serializable data class AlbumRoute(val kind: String, val albumId: Long = 0, val title: String = "")

@Serializable data object ViewerRoute
@Serializable data object VaultRoute
@Serializable data object DuplicatesRoute
@Serializable data object SearchRoute
@Serializable data object OnThisDayRoute
@Serializable data object MapRoute

// Opened from the profile picture: the You page, and Settings from there
@Serializable data object YouRoute
@Serializable data object SettingsRoute
