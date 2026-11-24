package com.boasnotas.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boasnotas.R
import com.boasnotas.data.NotebookStore
import com.boasnotas.model.PaperTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Abre as telas num Android simulado: pega erros que fariam o app fechar. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
class ScreensTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun telaInicialAbre() {
        ActivityScenario.launch(LibraryActivity::class.java).use { sc ->
            sc.moveToState(Lifecycle.State.RESUMED)
        }
    }

    private fun stylus(action: Int, x: Float, y: Float, down: Long, buttons: Int = 0): MotionEvent {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.5f; size = 0.01f }
        return MotionEvent.obtain(
            down, SystemClock.uptimeMillis(), action, 1, arrayOf(props), arrayOf(coords),
            0, buttons, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_STYLUS, 0,
        )
    }

    private fun drag(v: View, from: Pair<Float, Float>, to: Pair<Float, Float>, buttons: Int = 0) {
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(stylus(MotionEvent.ACTION_DOWN, from.first, from.second, t, buttons))
        for (i in 1..10) {
            val k = i / 10f
            v.dispatchTouchEvent(
                stylus(
                    MotionEvent.ACTION_MOVE,
                    from.first + (to.first - from.first) * k, from.second + (to.second - from.second) * k, t, buttons,
                ),
            )
        }
        v.dispatchTouchEvent(stylus(MotionEvent.ACTION_UP, to.first, to.second, t, buttons))
    }

    @Test
    fun cadernoAbreEscreveApagaEFecha() {
        val nb = NotebookStore(ctx).create("Teste", 0xFF3F51B5.toInt(), PaperTemplate.LINED)
        val intent = Intent(ctx, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_ID, nb.id)
        ActivityScenario.launch<EditorActivity>(intent).use { sc ->
            sc.moveToState(Lifecycle.State.RESUMED)
            sc.onActivity { a ->
                val canvas = a.findViewById<NoteCanvasView>(R.id.canvas)
                val w = 1600
                val h = 1000
                canvas.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
                )
                canvas.layout(0, 0, w, h)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                canvas.draw(Canvas(bmp))

                // escreve um traço com a caneta
                canvas.tool = ToolKind.PEN
                drag(canvas, 300f to 300f, 900f to 350f)
                canvas.draw(Canvas(bmp))
                assertEquals(1, nb.pages.size.coerceAtLeast(1))
                assertTrue(canvas.history.canUndo)

                // botão da S Pen apertado = borracha
                drag(canvas, 280f to 320f, 920f to 330f, MotionEvent.BUTTON_STYLUS_PRIMARY)
                canvas.draw(Canvas(bmp))

                // laço, texto, formas, páginas
                canvas.tool = ToolKind.LASSO
                drag(canvas, 200f to 200f, 1000f to 600f)
                canvas.draw(Canvas(bmp))
                canvas.tool = ToolKind.TEXT
                canvas.addPage(0, PaperTemplate.GRID)
                canvas.draw(Canvas(bmp))
                canvas.undo()
                canvas.redo()
                canvas.draw(Canvas(bmp))
            }
            sc.moveToState(Lifecycle.State.DESTROYED)
        }
    }

    @Test
    fun buscaAbre() {
        ActivityScenario.launch(SearchActivity::class.java).use { sc ->
            sc.moveToState(Lifecycle.State.RESUMED)
        }
    }
}
