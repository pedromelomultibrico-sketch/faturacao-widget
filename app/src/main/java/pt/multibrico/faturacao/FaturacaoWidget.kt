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
                Intent(Intent.ACTION_VIEW, Uri.parse("https://multibrico-gestao.pedromelomultibrico.workers.dev")),
                PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, abrir)
            // botão ↻: pede ao PC do PHC para enviar já as faturas e redesenha o widget quando ele responder
            val sincronizar = PendingIntent.getBroadcast(
                context, 1,
                Intent(context, FaturacaoWidget::class.java).setAction(ACAO_SINCRONIZAR),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.w_atualizar, sincronizar)

            val token = Prefs.token(context)
            if (token.isNullOrBlank()) {
                views.setTextViewText(R.id.w_hora, "Abre a app e cola o link da faturação")
            } else {
                try {
                    val r = Api.resumo(token)
                    views.setTextViewText(R.id.w_dia_val, Fmt.curto(r.hoje))
                    views.setTextViewText(R.id.w_mes_val, Fmt.curto(r.mes))
                    views.setTextViewText(R.id.w_ped_linha, "Hoje " + Fmt.curto(r.pedroHoje) + " · Mês " + Fmt.curto(r.pedroMes))
                    if (r.pedroMeta > 0) {
                        val pct = minOf(100, Math.round(r.pedroMes / r.pedroMeta * 100).toInt())
                        views.setImageViewBitmap(R.id.w_anel, Progresso.anel(pct, true))

                        // ritmo: onde devia estar hoje, e o que falta por dia
                        val cal = Calendar.getInstance()
                        val dia = cal.get(Calendar.DAY_OF_MONTH)
                        val diasMes = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                        val diasFaltam = diasMes - dia
                        val previsto = r.pedroMeta * dia / diasMes
                        val diff = r.pedroMes - previsto
                        if (diff >= 0) {
                            views.setTextViewText(R.id.w_estado, "▲ Adiantado " + Fmt.curto(diff))
                            views.setInt(R.id.w_estado, "setBackgroundResource", R.drawable.w_chip_verde)
                        } else {
                            views.setTextViewText(R.id.w_estado, "▼ Atrasado " + Fmt.curto(-diff))
                            views.setInt(R.id.w_estado, "setBackgroundResource", R.drawable.w_chip_vermelho)
                        }
                        val falta = r.pedroMeta - r.pedroMes
                        val prazo = when {
                            falta <= 0 -> "Objetivo atingido"
                            diasFaltam <= 0 -> "Último dia · faltam " + Fmt.curto(falta)
                            else -> "Faltam " + diasFaltam + " dias · " + Fmt.curto(falta / diasFaltam) + " por dia"
                        }
                        views.setImageViewBitmap(R.id.w_grafico, Progresso.barra(r.pedroMes, r.pedroMeta, previsto))
                        views.setTextViewText(R.id.w_ritmo, prazo)
                        views.setTextViewText(R.id.w_obj_info, Fmt.curto(r.pedroMes) + " de " + Fmt.curto(r.pedroMeta))
                        views.setViewVisibility(R.id.w_estado, View.VISIBLE)
                        views.setViewVisibility(R.id.w_grafico, View.VISIBLE)
                    } else {
                        views.setImageViewBitmap(R.id.w_anel, Progresso.anel(0, false))
                        views.setViewVisibility(R.id.w_estado, View.GONE)
                        views.setTextViewText(R.id.w_ritmo, "Sem objetivo definido.")
                        views.setTextViewText(R.id.w_obj_info, "")
                        views.setViewVisibility(R.id.w_grafico, View.GONE)
                    }
                    // barra do mês da empresa
                    val calM = Calendar.getInstance()
                    val diaM = calM.get(Calendar.DAY_OF_MONTH)
                    val diasMesM = calM.getActualMaximum(Calendar.DAY_OF_MONTH)
                    if (r.meta > 0) {
                        val prevM = r.meta * diaM / diasMesM
                        val pctM = Math.round(r.mes / r.meta * 100).toInt()
                        views.setTextViewText(R.id.w_mes_info, Fmt.curto(r.mes) + " de " + Fmt.curto(r.meta) + " · " + pctM + "%")
                        views.setImageViewBitmap(R.id.w_grafico_mes, Progresso.barra(r.mes, r.meta, prevM))
                    } else {
                        views.setTextViewText(R.id.w_mes_info, "Dia " + diaM + " de " + diasMesM + " · " + Fmt.curto(r.mes))
                        views.setImageViewBitmap(R.id.w_grafico_mes, Progresso.barra(diaM.toDouble(), diasMesM.toDouble(), diaM.toDouble(), "hoje", "dia " + diasMesM, "dia 1"))
                    }
                    views.setTextViewText(R.id.w_hora, "● " + Fmt.hora())
                } catch (e: Exception) {
                    views.setTextViewText(R.id.w_hora, "Sem ligação. Toca para tentar de novo.")
                }
            }
            mgr.updateAppWidget(ids, views)
        }
    }

    // ao desbloquear o telemóvel, pede uma atualização (o sistema atrasa as de 30 em 30 min)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_PRESENT) {
            atualizar(context)
        } else if (intent.action == ACAO_SINCRONIZAR) {
            sincronizar(context, goAsync())
        } else {
            super.onReceive(context, intent)
        }
    }

    companion object {
        const val ACAO_SINCRONIZAR = "pt.multibrico.faturacao.SINCRONIZAR"

        // mostra uma mensagem na linha de baixo do widget, sem mexer no resto
        private fun aviso(ctx: Context, texto: String) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, FaturacaoWidget::class.java))
            if (ids.isEmpty()) return
            val v = RemoteViews(ctx.packageName, R.layout.widget_faturacao)
            v.setTextViewText(R.id.w_hora, texto)
            mgr.partiallyUpdateAppWidget(ids, v)
        }

        // Pede ao PC do PHC para enviar já as faturas, espera até ~50 s que ele responda e redesenha.
        fun sincronizar(ctx: Context, fim: android.content.BroadcastReceiver.PendingResult?) {
            thread {
                try {
                    val token = Prefs.token(ctx)
                    if (token.isNullOrBlank()) {
                        aviso(ctx, "Abre a app e cola o link da faturação")
                    } else {
                        aviso(ctx, "A pedir ao PC do PHC…")
                        val desde = Api.pedirAgente(token)
                        var respondeu = false
                        val t0 = System.currentTimeMillis()
                        while (System.currentTimeMillis() - t0 < 50000) {
                            Thread.sleep(4000)
                            val q = try { Api.ultimoEnvioAgente(token) } catch (e: Exception) { "" }
                            if (q.isNotEmpty() && q > desde) { respondeu = true; break }
                        }
                        if (respondeu) atualizar(ctx) else aviso(ctx, "O PC do PHC não respondeu. Está ligado?")
                    }
                } catch (e: Exception) {
                    aviso(ctx, "Sem ligação. Toca em ↻ para tentar de novo.")
                } finally {
                    try { fim?.finish() } catch (e: Exception) { }
                }
            }
        }

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
