package pt.multibrico.faturacao

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MesValor(val mes: String, val total: Double)

data class Resumo(
    val hoje: Double,
    val mes: Double,
    val meta: Double,
    val pedroHoje: Double,
    val pedroMes: Double,
    val pedroMeta: Double,
    val pedroMeses: List<MesValor>
)

object Api {
    private const val BASE = "https://multibrico15.netlify.app/.netlify/functions/faturacao"

    // Chama a mesma ação 'resumo' da app web. Lança exceção se não houver ligação ou o token não servir.
    fun resumo(token: String): Resumo {
        val hoje = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val corpo = JSONObject().put("action", "resumo").put("hoje", hoje).toString()

        val conn = (URL(BASE).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            conn.outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) }
            val codigo = conn.responseCode
            val stream = if (codigo in 200..299) conn.inputStream else conn.errorStream
            val texto = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (codigo !in 200..299) throw IllegalStateException("HTTP $codigo")
            val j = JSONObject(texto)
            val pedro = j.optJSONObject("pedro")
            val meses = ArrayList<MesValor>()
            val arr = j.optJSONArray("pedroMeses")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    meses.add(MesValor(o.optString("mes"), o.optDouble("total", 0.0)))
                }
            }
            return Resumo(
                j.optDouble("hoje", 0.0),
                j.optDouble("mes", 0.0),
                j.optDouble("meta", 0.0),
                pedro?.optDouble("hoje", 0.0) ?: 0.0,
                pedro?.optDouble("mes", 0.0) ?: 0.0,
                pedro?.optDouble("metaKz", 0.0) ?: 0.0,
                meses
            )
        } finally {
            conn.disconnect()
        }
    }
}
