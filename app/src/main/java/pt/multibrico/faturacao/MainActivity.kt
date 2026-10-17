package pt.multibrico.faturacao

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var campo: EditText
    private lateinit var estado: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        raiz.addView(TextView(this).apply {
            text = "Faturação Multibrico"
            textSize = 22f
        })
        raiz.addView(TextView(this).apply {
            text = "Cola aqui o link da faturação (o mesmo que enviaste ao colega). A app guarda-o e o widget do ecrã inicial passa a mostrar os totais e o objetivo."
            textSize = 14f
            setPadding(0, pad / 2, 0, pad / 2)
        })

        campo = EditText(this).apply {
            hint = "Link ou código da faturação"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        raiz.addView(campo)

        raiz.addView(Button(this).apply {
            text = "Guardar"
            setOnClickListener { guardar() }
        })
        raiz.addView(Button(this).apply {
            text = "Abrir a faturação no browser"
            setOnClickListener { abrirWeb() }
        })

        estado = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.START
            setPadding(0, pad, 0, 0)
        }
        raiz.addView(estado)
        setContentView(raiz)

        estado.text = if (Prefs.token(this) != null)
            "Ligado. O widget atualiza sozinho de meia em meia hora."
        else
            "Ainda não está ligado."

        // ao abrir a app, o widget vai logo buscar os números (útil depois de uma atualização)
        if (Prefs.token(this) != null) FaturacaoWidget.atualizar(this)

        verificarAtualizacao()
    }

    // Procura uma versão nova em segundo plano; se houver, pergunta se quer atualizar.
    private fun verificarAtualizacao() {
        thread {
            val nova = try { Atualizacao.procurarNova(this) } catch (e: Exception) { null }
            if (nova != null) {
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Nova versão disponível")
                        .setMessage("Há uma versão nova da Faturação. Quer atualizar agora?")
                        .setPositiveButton("Atualizar") { _, _ -> Atualizacao.descarregarEInstalar(this, nova.second, nova.first) }
                        .setNegativeButton("Mais tarde", null)
                        .show()
                }
            }
        }
    }

    private fun guardar() {
        val token = Util.extrairToken(campo.text.toString())
        if (token.isEmpty()) {
            estado.text = "Cola o link ou o código primeiro."
            return
        }
        Prefs.guardar(this, token)
        FaturacaoWidget.atualizar(this)
        campo.setText("")
        estado.text = "Guardado. O widget vai atualizar-se dentro de instantes."
        Toast.makeText(this, "Guardado", Toast.LENGTH_SHORT).show()
    }

    private fun abrirWeb() {
        val token = Prefs.token(this)
        val url = if (token == null)
            "https://multibrico15.netlify.app/faturacao.html"
        else
            "https://multibrico15.netlify.app/faturacao.html?t=" + token
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
