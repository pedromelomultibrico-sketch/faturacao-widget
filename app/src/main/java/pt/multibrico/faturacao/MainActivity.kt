package pt.multibrico.faturacao

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        // Página da Multibrico que lê a sessão (ou pede login) e devolve a ligação à app.
        private const val PAGINA_LIGAR = "https://multibrico15.netlify.app/ligar-faturacao.html"
    }

    private lateinit var campo: EditText
    private lateinit var estado: TextView
    private lateinit var btnLigar: Button
    private lateinit var btnAtualizar: Button
    private lateinit var btnVersao: Button

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

        estado = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.START
            setPadding(0, pad / 2, 0, pad)
        }
        raiz.addView(estado)

        btnLigar = Button(this).apply {
            text = "Ligar"
            setOnClickListener { ligar() }
        }
        raiz.addView(btnLigar)

        btnAtualizar = Button(this).apply {
            text = "Atualizar números agora"
            setOnClickListener { atualizarNumeros() }
        }
        raiz.addView(btnAtualizar)

        btnVersao = Button(this).apply {
            text = "Instalar versão nova"
            setOnClickListener { procurarVersao(true) }
        }
        raiz.addView(btnVersao)

        raiz.addView(Button(this).apply {
            text = "Abrir a faturação no browser"
            setOnClickListener { abrirWeb() }
        })

        // Alternativa: colar o link à mão (como antes).
        raiz.addView(TextView(this).apply {
            text = "Ou cola o link da faturação:"
            textSize = 13f
            setPadding(0, pad, 0, 0)
        })
        campo = EditText(this).apply {
            hint = "Link ou código da faturação"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        raiz.addView(campo)
        raiz.addView(Button(this).apply {
            text = "Guardar link"
            setOnClickListener { guardarCampo() }
        })

        setContentView(raiz)

        if (!receberLigacao(intent)) mostrarEstado()

        // ao abrir a app, o widget vai logo buscar os números (útil depois de uma atualização)
        if (Prefs.token(this) != null) FaturacaoWidget.atualizar(this)

        procurarVersao(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receberLigacao(intent)
    }

    private fun mostrarEstado() {
        val ligado = Prefs.token(this) != null
        estado.text = if (ligado)
            "Ligado. O widget atualiza sozinho de meia em meia hora."
        else
            "Ainda não está ligado. Toque em Ligar."
        btnLigar.text = if (ligado) "Ligar outra vez" else "Ligar"
        btnAtualizar.visibility = if (ligado) View.VISIBLE else View.GONE
    }

    // Abre a página da Multibrico; ela devolve mbfaturacao://ligar?t=...
    private fun ligar() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PAGINA_LIGAR)))
    }

    // Recebe a ligação devolvida pela página. Devolve true se tratou o pedido.
    private fun receberLigacao(i: Intent?): Boolean {
        val dados = i?.data ?: return false
        if (dados.scheme != "mbfaturacao") return false
        val token = Util.extrairToken(dados.toString())
        if (token.isEmpty()) {
            estado.text = "A ligação não trouxe o código. Tente Ligar outra vez."
            return true
        }
        Prefs.guardar(this, token)
        FaturacaoWidget.atualizar(this)
        mostrarEstado()
        estado.text = "Ligado. O widget vai atualizar-se dentro de instantes."
        Toast.makeText(this, "Ligado", Toast.LENGTH_SHORT).show()
        return true
    }

    // Pede ao PC do PHC as faturas novas e redesenha o widget quando ele responder.
    private fun atualizarNumeros() {
        val token = Prefs.token(this) ?: run { mostrarEstado(); return }
        btnAtualizar.isEnabled = false
        estado.text = "A pedir ao PC do PHC as faturas novas…"
        thread {
            val texto = try {
                val desde = Api.pedirAgente(token)
                var respondeu = false
                val t0 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < 50000) {
                    Thread.sleep(4000)
                    val q = try { Api.ultimoEnvioAgente(token) } catch (e: Exception) { "" }
                    if (q.isNotEmpty() && q > desde) { respondeu = true; break }
                }
                FaturacaoWidget.atualizar(this)
                if (respondeu) "Números atualizados às " + Fmt.hora() + "."
                else "O PC do PHC não respondeu (está ligado?). Atualizei o widget com o que já havia."
            } catch (e: Exception) {
                "Sem ligação. Tente outra vez."
            }
            runOnUiThread {
                estado.text = texto
                btnAtualizar.isEnabled = true
            }
        }
    }

    // Procura uma versão nova. manual=true: instala logo, e avisa se já está na mais recente.
    private fun procurarVersao(manual: Boolean) {
        if (manual) {
            btnVersao.isEnabled = false
            estado.text = "A procurar versão nova…"
        }
        thread {
            val nova = try { Atualizacao.procurarNova(this) } catch (e: Exception) { null }
            runOnUiThread {
                if (manual) btnVersao.isEnabled = true
                if (nova != null) {
                    if (manual) {
                        estado.text = "A descarregar a versão " + nova.first + "… No fim, toque em Instalar."
                        Atualizacao.descarregarEInstalar(this, nova.second, nova.first)
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("Nova versão disponível")
                            .setMessage("Há uma versão nova da Faturação. Quer atualizar agora?")
                            .setPositiveButton("Atualizar") { _, _ -> Atualizacao.descarregarEInstalar(this, nova.second, nova.first) }
                            .setNegativeButton("Mais tarde", null)
                            .show()
                    }
                } else if (manual) {
                    estado.text = "Já tem a versão mais recente (" + Atualizacao.versaoInstalada(this) + ")."
                }
            }
        }
    }

    private fun guardarCampo() {
        val token = Util.extrairToken(campo.text.toString())
        if (token.isEmpty()) {
            estado.text = "Cola o link ou o código primeiro."
            return
        }
        Prefs.guardar(this, token)
        FaturacaoWidget.atualizar(this)
        campo.setText("")
        mostrarEstado()
        estado.text = "Guardado. O widget vai atualizar-se dentro de instantes."
        Toast.makeText(this, "Guardado", Toast.LENGTH_SHORT).show()
    }

    private fun abrirWeb() {
        val token = Prefs.token(this)
        val url = if (token == null)
            "https://multibrico15.netlify.app/faturacao.html"
        else
            "https://multibrico15.netlify.app/faturacao.html?t=" + Uri.encode(token)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
