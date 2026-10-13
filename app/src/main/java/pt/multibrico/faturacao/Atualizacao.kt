package pt.multibrico.faturacao

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Atualizacao {
    private const val ULTIMA = "https://api.github.com/repos/pedromelomultibrico-sketch/faturacao-widget/releases/latest"

    // Devolve (número da versão, link do APK) se houver uma versão mais nova; senão devolve null.
    fun procurarNova(context: Context): Pair<Long, String>? {
        val conn = (URL(ULTIMA).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            val texto = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val j = JSONObject(texto)
            val versao = j.optString("tag_name").removePrefix("v").toLongOrNull() ?: return null
            if (versao <= versaoInstalada(context)) return null
            val ativos = j.optJSONArray("assets") ?: return null
            for (i in 0 until ativos.length()) {
                val a = ativos.getJSONObject(i)
                if (a.optString("name") == "app-debug.apk") {
                    return Pair(versao, a.getString("browser_download_url"))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    fun versaoInstalada(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    // Descarrega o APK pelo gestor de transferências do Android e abre o instalador quando acaba.
    fun descarregarEInstalar(context: Context, url: String, versao: Long) {
        val gestor = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val pedido = DownloadManager.Request(Uri.parse(url))
            .setTitle("Atualização da Faturação")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "faturacao-$versao.apk")
        val id = gestor.enqueue(pedido)

        val recetor = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != id) return
                ctx.unregisterReceiver(this)
                val uri = gestor.getUriForDownloadedFile(id) ?: return
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        val filtro = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 34) {
            context.registerReceiver(recetor, filtro, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(recetor, filtro)
        }
    }
}
