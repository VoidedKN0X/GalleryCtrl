package com.vknxworks.galleryctrl.media

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.vknxworks.galleryctrl.GalleryCtrlApp
import java.io.FileNotFoundException

/**
 * Hands a vault video to GalleryCtrl's own video player as a stream ([Vault.streamUri]): decrypted piece by piece
 * while it plays, so it can seek and nothing decrypted is written anywhere. Not exported and grants nothing: only
 * GalleryCtrl itself can open it, read-only, and only while the vault is open ([Vault.viewing]).
 */
class VaultStreamProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun vault(): Vault? = (context?.applicationContext as? GalleryCtrlApp)?.container?.photoVault

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("not allowed")
        if (mode != "r") throw FileNotFoundException("read-only")
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != "v") throw FileNotFoundException("unknown")
        return vault()?.openStream(segments[1]) ?: throw FileNotFoundException("not available")
    }

    override fun getType(uri: Uri): String? {
        if (Binder.getCallingUid() != Process.myUid()) return null
        val id = uri.pathSegments.getOrNull(1) ?: return null
        return vault()?.entries?.value?.firstOrNull { it.id == id }?.mime
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
