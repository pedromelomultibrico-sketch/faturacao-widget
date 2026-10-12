package pt.multibrico.faturacao

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Prefs {
    private const val NOME = "fat"

    fun token(ctx: Context): String? =
        ctx.getSharedPreferences(NOME, Context.MODE_PRIVATE).getString("token", null)

    fun guardar(ctx: Context, token: String) {
        ctx.getSharedPreferences(NOME, Context.MODE_PRIVATE).edit().putString("token", token).apply()
    }
}

object Fmt {
    // 1234567 -> "1 234 567 Akz", como na app web
    fun akz(v: Double): String {
        val s = Math.round(v).toString()
        val sb = StringBuilder()
        for ((i, c) in s.reversed().withIndex()) {
            if (i > 0 && i % 3 == 0) sb.append(' ')
            sb.append(c)
        }
        return sb.reverse().toString() + " Akz"
    }

    fun hora(): String = SimpleDateFormat("HH:mm", Locale.US).format(Date())
}

object Util {
    // Aceita o link completo (...faturacao.html?t=TOKEN) ou só o código.
    fun extrairToken(entrada: String): String {
        val s = entrada.trim()
        val m = Regex("[?&]t=([^&\\s]+)").find(s)
        val bruto = if (m != null) m.groupValues[1] else s
        // só descodifica %XX; mantém o '+' do base64 tal como está
        return bruto.replace(Regex("%([0-9A-Fa-f]{2})")) { it.groupValues[1].toInt(16).toChar().toString() }.trim()
    }
}
