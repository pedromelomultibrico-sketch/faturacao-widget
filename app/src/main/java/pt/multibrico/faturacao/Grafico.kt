package pt.multibrico.faturacao

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

// Gráfico de barras mensal (uma barra por mês, do início do ano até ao mês atual), desenhado num Bitmap.
object Grafico {
    private val MESES = arrayOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")

    fun barras(valores: List<MesValor>): Bitmap {
        val w = 640
        val h = 240
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.parseColor("#F5F3EF"))
        if (valores.isEmpty()) return bmp

        val topo = 40f
        val base = h - 30f
        val maior = maxOf(1.0, valores.maxOf { it.total })
        val slot = w.toFloat() / valores.size

        val barra = Paint().apply { color = Color.parseColor("#D7182A"); isAntiAlias = true }
        val letra = Paint().apply {
            color = Color.parseColor("#6B7280"); textSize = 24f; isAntiAlias = true; textAlign = Paint.Align.CENTER
        }
        val valorP = Paint().apply {
            color = Color.parseColor("#1F2328"); textSize = 20f; isAntiAlias = true
            textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        }

        for ((i, v) in valores.withIndex()) {
            val x = i * slot + slot * 0.2f
            val bw = slot * 0.6f
            val alt = ((v.total / maior) * (base - topo)).toFloat()
            val cx = x + bw / 2
            if (alt > 0f) c.drawRect(x, base - alt, x + bw, base, barra)
            if (v.total > 0) c.drawText(String.format("%.1fM", v.total / 1_000_000.0), cx, base - alt - 6f, valorP)
            val mesIdx = (v.mes.substring(5, 7).toIntOrNull() ?: (i + 1)) - 1
            c.drawText(MESES[mesIdx.coerceIn(0, 11)], cx, h - 6f, letra)
        }
        return bmp
    }

    // Barras diárias do mês atual: uma por dia (valor em M por cima) e uma linha no objetivo diário.
    fun diasDoMes(dias: List<DiaValor>, diasMes: Int, hoje: Int, objetivoDia: Double): Bitmap {
        val w = 640
        val h = 240
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.parseColor("#F5F3EF"))
        if (diasMes <= 0) return bmp

        val topo = 30f
        val base = h - 30f
        val porDia = HashMap<Int, Double>()
        for (d in dias) porDia[d.dia] = d.total
        val maior = maxOf(1.0, maxOf(porDia.values.maxOrNull() ?: 0.0, objetivoDia))
        val slot = w.toFloat() / diasMes

        val barra = Paint().apply { color = Color.parseColor("#D7182A"); isAntiAlias = true }
        val linhaObj = Paint().apply { color = Color.parseColor("#1F2328"); strokeWidth = 2f; isAntiAlias = true }
        val letra = Paint().apply {
            color = Color.parseColor("#6B7280"); textSize = 18f; isAntiAlias = true; textAlign = Paint.Align.CENTER
        }
        val valorP = Paint().apply {
            color = Color.parseColor("#1F2328"); textSize = 16f; isAntiAlias = true
            textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        }

        if (objetivoDia > 0) {
            val yObj = base - ((objetivoDia / maior) * (base - topo)).toFloat()
            c.drawLine(0f, yObj, w.toFloat(), yObj, linhaObj)
        }

        for (dia in 1..diasMes) {
            val total = porDia[dia] ?: 0.0
            val x = (dia - 1) * slot + slot * 0.15f
            val bw = slot * 0.7f
            val cx = x + bw / 2
            if (dia <= hoje && total > 0) {
                val alt = ((total / maior) * (base - topo)).toFloat()
                c.drawRect(x, base - alt, x + bw, base, barra)
                c.drawText(String.format("%.1f", total / 1_000_000.0), cx, base - alt - 4f, valorP)
            }
            if (dia == 1 || dia % 5 == 0 || dia == diasMes) c.drawText(dia.toString(), cx, h - 6f, letra)
        }
        return bmp
    }
}
