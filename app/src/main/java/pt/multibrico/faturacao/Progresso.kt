package pt.multibrico.faturacao

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface

// Gráficos do widget desenhados com relevo (sombras, gradientes e brilho), em imagens.
object Progresso {
    private val VERM_ESCURO = Color.parseColor("#8E0B1F")
    private val VERM = Color.parseColor("#C8102E")
    private val VERM_CLARO = Color.parseColor("#F0384D")
    private val TINTA = Color.parseColor("#22180F")
    private val SUAVE = Color.parseColor("#5E5248")

    // Anel da meta: percentagem no centro.
    fun anel(pct: Int, temMeta: Boolean): Bitmap {
        val s = 300
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val esp = 34f
        val m = 30f
        val r = RectF(m, m, s - m, s - m)

        // sombra por baixo do anel (dá volume)
        val sombra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = esp; color = Color.parseColor("#E6DDD1")
            setShadowLayer(12f, 0f, 7f, Color.parseColor("#55000000"))
        }
        c.drawArc(r, 0f, 360f, false, sombra)

        // trilho com um leve degradê (parece cavado)
        val trilho = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = esp
            shader = LinearGradient(0f, m, 0f, s - m, Color.parseColor("#DCD2C4"), Color.parseColor("#F4EFE8"), Shader.TileMode.CLAMP)
        }
        c.drawArc(r, 0f, 360f, false, trilho)

        val p = pct.coerceIn(0, 100)
        if (temMeta && p > 0) {
            val g = SweepGradient(s / 2f, s / 2f, intArrayOf(VERM_ESCURO, VERM_CLARO, VERM, VERM_ESCURO), floatArrayOf(0f, 0.35f, 0.75f, 1f))
            val rot = Matrix(); rot.postRotate(-90f, s / 2f, s / 2f); g.setLocalMatrix(rot)
            val arco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = esp; strokeCap = Paint.Cap.ROUND; shader = g
                setShadowLayer(8f, 0f, 4f, Color.parseColor("#66000000"))
            }
            c.drawArc(r, -90f, 360f * p / 100f, false, arco)
            // brilho fino por cima do arco
            val brilho = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND
                color = Color.argb(110, 255, 255, 255)
            }
            val ri = RectF(m - esp / 4, m - esp / 4, s - m + esp / 4, s - m + esp / 4)
            c.drawArc(ri, -88f, (360f * p / 100f - 4f).coerceAtLeast(0f), false, brilho)
        }

        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TINTA; textSize = 74f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER; setShadowLayer(4f, 0f, 3f, Color.parseColor("#55000000"))
        }
        val leg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SUAVE; textSize = 30f; textAlign = Paint.Align.CENTER
        }
        if (temMeta) {
            c.drawText("$p%", s / 2f, s / 2f + 14f, num)
            c.drawText("do objetivo", s / 2f, s / 2f + 54f, leg)
        } else {
            leg.textSize = 34f
            c.drawText("sem", s / 2f, s / 2f - 4f, leg)
            c.drawText("objetivo", s / 2f, s / 2f + 34f, leg)
        }
        return bmp
    }

    // Barra da meta: quanto já vendeu e onde devia estar hoje. O rótulo fica por cima, sem tocar na barra.
    fun barra(real: Double, meta: Double, previsto: Double, rotulo: String = "previsto hoje", fimTxt: String = "objetivo", inicioTxt: String = "0"): Bitmap {
        val w = 800f
        val h = 100f
        val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val mx = 14f
        val topo = 46f
        val fundo = 74f
        val util = w - 2 * mx

        // trilho cavado
        val trilho = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, topo, 0f, fundo, Color.parseColor("#D6CCBF"), Color.parseColor("#F3EEE7"), Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(RectF(mx, topo, w - mx, fundo), 14f, 14f, trilho)

        val fr = if (meta > 0) (real / meta).coerceIn(0.0, 1.0).toFloat() else 0f
        if (fr > 0f) {
            val fim = mx + util * fr
            val enchimento = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, topo, 0f, fundo, VERM_CLARO, VERM_ESCURO, Shader.TileMode.CLAMP)
                setShadowLayer(6f, 0f, 3f, Color.parseColor("#66000000"))
            }
            c.drawRoundRect(RectF(mx, topo, fim, fundo), 14f, 14f, enchimento)
            // brilho na metade de cima (efeito vidro)
            val brilho = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 255, 255, 255) }
            c.drawRoundRect(RectF(mx + 6f, topo + 3f, (fim - 6f).coerceAtLeast(mx + 6f), topo + 12f), 6f, 6f, brilho)
        }

        // marca do previsto: triângulo + traço
        val fp = if (meta > 0) (previsto / meta).coerceIn(0.0, 1.0).toFloat() else 0f
        val xp = mx + util * fp
        val marca = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TINTA; strokeWidth = 5f; setShadowLayer(3f, 0f, 2f, Color.parseColor("#55000000"))
        }
        c.drawLine(xp, topo - 4f, xp, fundo + 6f, marca)
        val tri = Path().apply { moveTo(xp - 10f, topo - 16f); lineTo(xp + 10f, topo - 16f); lineTo(xp, topo - 4f); close() }
        c.drawPath(tri, marca)

        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SUAVE; textSize = 24f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val lw = txt.measureText(rotulo)
        val xr = (xp - lw / 2f).coerceIn(mx, w - mx - lw)
        c.drawText(rotulo, xr, 22f, txt)
        txt.typeface = Typeface.DEFAULT
        txt.textSize = 20f
        c.drawText(inicioTxt, mx, h - 4f, txt)
        val lm = txt.measureText(fimTxt)
        c.drawText(fimTxt, w - mx - lm, h - 4f, txt)
        return bmp
    }
}
