package pt.multibrico.faturacao

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

// Barra da meta do Pedro: quanto já vendeu e onde devia estar hoje (previsto)
object Progresso {
    private const val LARGURA = 720f
    private const val ALTURA = 130f
    private const val MARGEM = 10f
    private const val Y_TOPO = 44f
    private const val Y_FUNDO = 84f

    fun barra(real: Double, meta: Double, previsto: Double): Bitmap {
        val bmp = Bitmap.createBitmap(LARGURA.toInt(), ALTURA.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val larguraUtil = LARGURA - 2 * MARGEM

        val trilho = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E3E5E8") }
        c.drawRoundRect(RectF(MARGEM, Y_TOPO, LARGURA - MARGEM, Y_FUNDO), 20f, 20f, trilho)

        val fracReal = if (meta > 0) (real / meta).coerceIn(0.0, 1.0).toFloat() else 0f
        if (fracReal > 0f) {
            val vendido = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D7182A") }
            c.drawRoundRect(RectF(MARGEM, Y_TOPO, MARGEM + larguraUtil * fracReal, Y_FUNDO), 20f, 20f, vendido)
        }

        val fracPrev = if (meta > 0) (previsto / meta).coerceIn(0.0, 1.0).toFloat() else 0f
        val xPrev = MARGEM + larguraUtil * fracPrev
        val marca = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1F2328"); strokeWidth = 5f }
        c.drawLine(xPrev, Y_TOPO - 10f, xPrev, Y_FUNDO + 10f, marca)

        val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6B7280"); textSize = 26f }
        texto.textAlign = Paint.Align.CENTER
        c.drawText("previsto hoje", xPrev.coerceIn(90f, LARGURA - 90f), 28f, texto)
        texto.textAlign = Paint.Align.LEFT
        c.drawText("0", MARGEM, ALTURA - 8f, texto)
        texto.textAlign = Paint.Align.RIGHT
        c.drawText("meta", LARGURA - MARGEM, ALTURA - 8f, texto)
        return bmp
    }
}
