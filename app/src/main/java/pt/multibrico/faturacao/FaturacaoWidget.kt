package pt.multibrico.faturacao

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import java.util.Calendar
import kotlin.concurrent.thread

class FaturacaoWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        // a rede não pode correr na thread principal
        thread {
            val views = RemoteViews(context.packageName, R.layout.widget_faturacao)
            // toque no widget: abre a faturação na página da Multibrico
            val abrir = PendingIntent.getActivity(
                context, 0,
                Intent(Intent.ACTION_VIEW, Uri.parse("https://multibrico15.netlify.app/?faturacao=1")),
                PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, abrir)

            val token = Prefs.token(context)
            if (token.isNullOrBlank()) {
                views.setTextViewText(R.id.w_hora, "Abre a app e cola o link da faturação")
            } else {
                try {
                    val r = Api.resumo(token)
                    views.setTextViewText(R.id.w_dia_val, Fmt.akz(r.hoje))
                    views.setTextViewText(R.id.w_mes_val, Fmt.akz(r.mes))
                    views.setTextViewText(R.id.w_ped_dia, Fmt.akz(r.pedroHoje))
                    views.setTextViewText(R.id.w_ped_mes, Fmt.akz(r.pedroMes))
                    if (r.pedroMeta > 0) {
                        val pct = minOf(100, Math.round(r.pedroMes / r.pedroMeta * 100).toInt())
                        views.setProgressBar(R.id.w_meta_bar, 100, pct, false)
                        views.setTextViewText(R.id.w_meta_txt, "Meta Pedro " + Fmt.akz(r.pedroMeta) + " · " + pct + "%")

                        // ritmo: onde devia estar hoje, e o que falta por dia
                        val cal = Calendar.getInstance()
                        val dia = cal.get(Calendar.DAY_OF_MONTH)
                        val diasMes = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                        val diasFaltam = diasMes - dia
                        val previsto = r.pedroMeta * dia / diasMes
                        val diff = r.pedroMes - previsto
                        val adianto = if (diff >= 0) "Adiantado " + Fmt.akz(diff) else "Atrasado " + Fmt.akz(-diff)
                        val falta = r.pedroMeta - r.pedroMes
                        val prazo = when {
                            falta <= 0 -> "Meta atingida"
                            diasFaltam <= 0 -> "Último dia · faltam " + Fmt.akz(falta)
                            else -> "Faltam " + diasFaltam + " dias · " + Fmt.akz(falta / diasFaltam) + " por dia"
                        }
                        views.setImageViewBitmap(R.id.w_grafico, Progresso.barra(r.pedroMes, r.pedroMeta, previsto))
                        views.setTextViewText(R.id.w_ritmo, adianto + "\n" + prazo)
                        views.setViewVisibility(R.id.w_grafico, View.VISIBLE)
                        views.setViewVisibility(R.id.w_ritmo, View.VISIBLE)
                    } else {
                        views.setProgressBar(R.id.w_meta_bar, 100, 0, false)
                        views.setTextViewText(R.id.w_meta_txt, "Sem meta definida.")
                        views.setViewVisibility(R.id.w_grafico, View.GONE)
                        views.setViewVisibility(R.id.w_ritmo, View.GONE)
                    }
                    views.setTextViewText(R.id.w_hora, "Atualizado às " + Fmt.hora())
                } catch (e: Exception) {
                    views.setTextViewText(R.id.w_hora, "Sem ligação. Toca para tentar de novo.")
                }
            }
            mgr.updateAppWidget(ids, views)
        }
    }

    companion object {
        // pede ao sistema para redesenhar todos os widgets desta app
        fun atualizar(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, FaturacaoWidget::class.java))
            if (ids.isEmpty()) return
            ctx.sendBroadcast(
                Intent(ctx, FaturacaoWidget::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }
    }
}
