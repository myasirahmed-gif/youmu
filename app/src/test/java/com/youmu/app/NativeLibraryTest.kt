package com.youmu.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35])
class NativeLibraryTest {
    @Before fun clearLibrary(){RuntimeEnvironment.getApplication().getSharedPreferences("youmu_library",Context.MODE_PRIVATE).edit().clear().commit()}
    private fun tree(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{tree(view.getChildAt(it))}else emptyList()
    private fun click(root:View,title:String){tree(root).filterIsInstance<Button>().first{it.text.toString()==title}.performClick()}
    private fun addPlaylist(activity:MainActivity){
        click(activity.window.decorView,"＋  Import playlist")
        val dialog=ShadowAlertDialog.getLatestAlertDialog()
        val fields=tree(dialog.window!!.decorView).filterIsInstance<EditText>()
        fields.first{it.hint=="Playlist link"}.setText("https://youtube.com/playlist?list=PL1234567890")
        fields.first{it.hint=="Playlist name (optional)"}.setText("Late night rotation")
        click(dialog.window!!.decorView,"Import playlist")
    }
    @Test fun nativeImportPersistsAndFavoritesWork(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get();addPlaylist(activity)
        assertEquals("Late night rotation",PlaylistStore(activity).load().single().title)
        tree(activity.window.decorView).first{it.contentDescription=="Favorite Late night rotation"}.performClick()
        assertTrue(PlaylistStore(activity).load().single().favorite)
        click(activity.window.decorView,"Favorites")
        assertTrue(tree(activity.window.decorView).filterIsInstance<TextView>().any{it.text=="Late night rotation"})
        controller.pause().stop().destroy()
    }
    @Test fun duplicateImportShowsErrorWithoutAddingItem(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        addPlaylist(activity);addPlaylist(activity)
        val dialog=ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        assertTrue(tree(dialog.window!!.decorView).filterIsInstance<TextView>().any{it.text=="This playlist is already in your library."})
        assertEquals(1,PlaylistStore(activity).load().size)
        dialog.dismiss();controller.pause().stop().destroy()
    }
    @Test fun androidShareIntentPrefillsImportDialog(){
        val intent=Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"My music\nhttps://music.youtube.com/playlist?list=PL1234567890")
        val controller=Robolectric.buildActivity(MainActivity::class.java,intent).setup()
        val dialog=ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("https://music.youtube.com/playlist?list=PL1234567890",tree(dialog.window!!.decorView).filterIsInstance<EditText>().first{it.hint=="Playlist link"}.text.toString())
        dialog.dismiss();controller.pause().stop().destroy()
    }
    @Test fun privateStorageRoundTripsMetadata(){
        val store=PlaylistStore(RuntimeEnvironment.getApplication())
        store.save(listOf(Playlist("PL1234567890","My playlist",favorite=true,lastPlayed=123L,fromAccount=true,count=51,added=42L)))
        val p=store.load().single();assertTrue(p.favorite);assertTrue(p.fromAccount);assertEquals(123L,p.lastPlayed);assertEquals(51,p.count);assertEquals(42L,p.added)
    }
}
