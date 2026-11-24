package com.example.ui.navigation

import kotlinx.serialization.Serializable

sealed interface WeblioRoute {
    @Serializable data object Splash : WeblioRoute
    @Serializable data object Browser : WeblioRoute
    @Serializable data object Tabs : WeblioRoute
    @Serializable data object Bookmarks : WeblioRoute
    @Serializable data object History : WeblioRoute
    @Serializable data object Downloads : WeblioRoute
    @Serializable data object Extensions : WeblioRoute
    @Serializable data object AdBlock : WeblioRoute
    @Serializable data object Profiles : WeblioRoute
    @Serializable data object Passwords : WeblioRoute
    @Serializable data object Settings : WeblioRoute
    @Serializable data object BackupRestore : WeblioRoute
    @Serializable data object QrScanner : WeblioRoute
    @Serializable data object SitePermissions : WeblioRoute
    @Serializable data object SecurityCenter : WeblioRoute
}
