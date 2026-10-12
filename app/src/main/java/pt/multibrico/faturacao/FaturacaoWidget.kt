package pt.multibrico.faturacao

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlin.concurrent.thread

class FaturacaoWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        // a rede não pode correr na thread principal
        thread {
            val views = RemoteViews(context.packageName, R.layout.widget_faturacao)
            val abrir = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
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
                    views.setImageViewBitmap(R.id.w_grafico, Grafico.barras(r.pedroMeses))
                    // topo: meta do Pedro (só a dele) e o progresso das suas vendas do mês
                    if (r.pedroMeta > 0) {
                        val pct = minOf(100, Math.round(r.pedroMes / r.pedroMeta * 100).toInt())
                        val falta = r.pedroMeta - r.pedroMes
                        val sufixo = if (falta > 0) " · faltam " + Fmt.akz(falta) else " · meta superada"
                        views.setProgressBar(R.id.w_meta_bar, 100, pct, false)
                        views.setTextViewText(R.id.w_meta_txt, "Meta Pedro " + Fmt.akz(r.pedroMeta) + " · " + pct + "%" + sufixo)
                    } else {
                        views.setProgressBar(R.id.w_meta_bar, 100, 0, false)
                        views.setTextViewText(R.id.w_meta_txt, "Sem meta definida.")
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
