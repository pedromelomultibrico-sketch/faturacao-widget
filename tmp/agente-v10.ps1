# Agente da Faturacao (Multibrico). Corre no computador onde esta o PHC.
# Le as faturas e as encomendas de cliente recentes do PHC (so leitura) e envia-as para a faturacao,
# junto com o stock do armazem 4 (so os artigos que mudaram).
# Fica sempre ligado: envia tudo de 10 em 10 minutos e, de 20 em 20 segundos, pergunta ao servidor
# se alguem carregou em "Atualizar" na app (nesse caso envia logo).
# Arranca com a tarefa agendada (ao iniciar sessao e de 10 em 10 min); se ja estiver a correr, a nova copia sai.
# Tambem copia faturas, encomendas, clientes e artigos (desde 2025) para a base de dados D1 da Cloudflare,
# para analises: "agente.ps1 -Carga" faz a carga inicial (retoma onde parou); depois o ciclo envia so o que mudou.
# Nao guarda palavras-passe da conta Claude nem da Cloud: so os tokens deste computador (faturacao e Cloudflare).
param([switch]$Carga)

$ErrorActionPreference = "Stop"
$VersaoLocal = 10
$script:Relancar = $false
$Servidor = "https://multibrico15.netlify.app"
$Funcao = "$Servidor/.netlify/functions/faturacao"
$Pasta = Join-Path $env:LOCALAPPDATA "FaturacaoAgente"
$Registo = Join-Path $Pasta "agente.log"
$Config = Get-Content (Join-Path $Pasta "config.json") -Raw | ConvertFrom-Json

$IntervaloEnvio = [TimeSpan]::FromMinutes(10)
$IntervaloVerificar = 20   # segundos
$IntervaloAtualizar = [TimeSpan]::FromHours(1)

function Escrever-Registo([string]$msg) {
    $linha = (Get-Date -Format "yyyy-MM-dd HH:mm:ss") + " " + $msg
    Add-Content -Path $Registo -Value $linha
    $todas = Get-Content $Registo
    if ($todas.Count -gt 500) { $todas | Select-Object -Last 500 | Set-Content $Registo }
}

# Se o servidor tiver uma versao mais nova, descarrega-a para substituir este ficheiro.
# Devolve $true quando atualizou (o ciclo sai e a tarefa agendada arranca a versao nova).
function Atualizar-Se-Preciso {
    try {
        $versaoNova = [int]((Invoke-WebRequest -Uri "$Servidor/agente/versao.txt" -UseBasicParsing).Content.Trim())
        if ($versaoNova -gt $VersaoLocal) {
            $tmp = Join-Path $env:TEMP "agente-nova.ps1"
            Invoke-WebRequest -Uri "$Servidor/agente/agente.ps1" -OutFile $tmp -UseBasicParsing
            Copy-Item $tmp (Join-Path $Pasta "agente.ps1") -Force
            Escrever-Registo "Atualizado para a versao $versaoNova (arranca na proxima execucao da tarefa)."
            return $true
        }
    }
    catch {
        Escrever-Registo ("Nao consegui verificar atualizacoes: " + $_.Exception.Message)
    }
    return $false
}

# Ligacao ao SQL Server do PHC. Usa a sessao Windows, ou um utilizador de leitura se estiver configurado.
function Ler-Faturas([switch]$Encomendas) {
    if ($Config.sqlUtilizador) {
        $ligacaoTxt = "Server=$($Config.sqlServidor);Database=$($Config.sqlBase);User Id=$($Config.sqlUtilizador);Password=$($Config.sqlPalavraPasse)"
    }
    else {
        $ligacaoTxt = "Server=$($Config.sqlServidor);Database=$($Config.sqlBase);Integrated Security=True"
    }
    # Envia sempre os ultimos 40 dias: o servidor substitui esses dias pelo que vem do PHC, por isso nao ha duplicados.
    # Uma linha por fatura x vendedor das linhas (FI.fivendnm): no mesmo cliente o material eletrico e de um
    # vendedor e as ferramentas de outro. Linha sem vendedor fica com o vendedor da fatura.
    $vendLinha = "COALESCE(NULLIF(RTRIM(fi.fivendnm), ''), RTRIM(ft.vendnm))"
    # Como o mapa de vendas: valor sem IVA do PHC (ettiliq); fora as faturas anuladas e as N/C de adiantamento.
    $sql = "SELECT ft.ftstamp, ft.nmdoc, ft.fno, CONVERT(varchar(10), ft.fdata, 120) AS fdata, ft.nome, ft.etotal, ft.ettiliq, $vendLinha AS vendnm, SUM(fi.etiliquido) AS liquido " +
           "FROM FT ft LEFT JOIN FI fi ON fi.ftstamp = ft.ftstamp " +
           "WHERE ft.fdata >= DATEADD(day, -40, CAST(GETDATE() AS date)) AND ft.anulado = 0 AND RTRIM(ft.nmdoc) NOT LIKE 'N/C Adiant%' " +
           "GROUP BY ft.ftstamp, ft.nmdoc, ft.fno, ft.fdata, ft.nome, ft.etotal, ft.ettiliq, $vendLinha"
    if ($Encomendas) {
        # Encomendas de cliente (dossier 12, "Enc. de Cliente Balcao"), com as mesmas colunas que as faturas.
        $vendLinha = "COALESCE(NULLIF(RTRIM(bi.vendnm), ''), RTRIM(bo.vendnm))"
        $sql = "SELECT bo.bostamp AS ftstamp, 'Enc' AS nmdoc, bo.obrano AS fno, CONVERT(varchar(10), bo.dataobra, 120) AS fdata, bo.nome, bo.etotal, $vendLinha AS vendnm, SUM(bi.ettdeb) AS liquido " +
               "FROM BO bo LEFT JOIN BI bi ON bi.bostamp = bo.bostamp " +
               "WHERE bo.ndos = 12 AND bo.dataobra >= DATEADD(day, -40, CAST(GETDATE() AS date)) " +
               "GROUP BY bo.bostamp, bo.obrano, bo.dataobra, bo.nome, bo.etotal, $vendLinha"
    }
    $ligacao = New-Object System.Data.SqlClient.SqlConnection $ligacaoTxt
    $ligacao.Open()
    try {
        $cmd = $ligacao.CreateCommand()
        $cmd.CommandText = $sql
        $cmd.CommandTimeout = 120
        $leitor = $cmd.ExecuteReader()
        $tabela = New-Object System.Data.DataTable
        $tabela.Load($leitor)
        # A virgula impede o PowerShell de desfazer a DataTable nas suas linhas.
        return ,$tabela
    }
    finally {
        $ligacao.Close()
    }
}

# Converte as linhas no formato de texto (TAB) que a importacao da faturacao ja aceita.
function Montar-Tsv($tabela) {
    $cultura = [System.Globalization.CultureInfo]::InvariantCulture
    $linhas = New-Object System.Collections.Generic.List[string]
    $temSiva = $tabela.Columns.Contains("ettiliq")
    if ($temSiva) { $linhas.Add("TextoNoZero`tNome`tFdata`tEtotal`tVendedor`tSiva") } else { $linhas.Add("TextoNoZero`tNome`tFdata`tEtotal`tVendedor") }
    # O total da fatura (com IVA) reparte-se pelos vendedores na proporcao do liquido das linhas de cada um,
    # para a soma continuar igual ao etotal do PHC.
    foreach ($grupo in ($tabela.Rows | Group-Object { [string]$_["ftstamp"] })) {
        $partes = @($grupo.Group)
        $etotal = [decimal]$partes[0]["etotal"]
        $somaLiq = [decimal]0
        foreach ($p in $partes) { if ($p["liquido"] -isnot [DBNull]) { $somaLiq += [decimal]$p["liquido"] } }
        $docBase = ([string]$partes[0]["nmdoc"]).Trim() + " " + ([string]$partes[0]["fno"]).Trim()
        $data = [datetime]::ParseExact([string]$partes[0]["fdata"], "yyyy-MM-dd", $cultura).ToString("dd-MM-yyyy")
        $nome = ([string]$partes[0]["nome"]).Replace("`t", " ").Trim()
        $restante = $etotal
        $siva = [decimal]0
        if ($temSiva -and $partes[0]["ettiliq"] -isnot [DBNull]) { $siva = [decimal]$partes[0]["ettiliq"] }
        $restSiva = $siva
        for ($i = 0; $i -lt $partes.Count; $i++) {
            $p = $partes[$i]
            $vend = ([string]$p["vendnm"]).Replace("`t", " ").Trim()
            if ($i -eq $partes.Count - 1) { $valor = $restante; $valSiva = $restSiva }
            elseif ($somaLiq -ne 0 -and $p["liquido"] -isnot [DBNull]) { $valor = [math]::Round($etotal * [decimal]$p["liquido"] / $somaLiq, 2); $valSiva = [math]::Round($siva * [decimal]$p["liquido"] / $somaLiq, 2) }
            else { $valor = [math]::Round($etotal / $partes.Count, 2); $valSiva = [math]::Round($siva / $partes.Count, 2) }
            $restante -= $valor
            $restSiva -= $valSiva
            $doc = if ($partes.Count -le 1) { $docBase } elseif ($vend) { "$docBase / $vend" } else { "$docBase / sem vendedor" }
            if ($temSiva) { $linhas.Add("$doc`t$nome`t$data`t$($valor.ToString('0.00', $cultura))`t$vend`t$($valSiva.ToString('0.00', $cultura))") }
            else { $linhas.Add("$doc`t$nome`t$data`t$($valor.ToString('0.00', $cultura))`t$vend") }
        }
    }
    return ($linhas -join "`n")
}

# Stock do armazem 4 ("NOVO", de onde saem as vendas) e quantidades reservadas a clientes (ST.qttcli).
# Guarda em memoria o ultimo envio e manda so os artigos que mudaram; no arranque manda a lista completa.
$script:StockEnviado = $null
function Enviar-Stock {
    if ($Config.sqlUtilizador) {
        $ligacaoTxt = "Server=$($Config.sqlServidor);Database=$($Config.sqlBase);User Id=$($Config.sqlUtilizador);Password=$($Config.sqlPalavraPasse)"
    }
    else {
        $ligacaoTxt = "Server=$($Config.sqlServidor);Database=$($Config.sqlBase);Integrated Security=True"
    }
    $sql = "SELECT RTRIM(st.ref) AS ref, ISNULL(sa.stock, 0) AS stock, ISNULL(st.qttcli, 0) AS reservado " +
           "FROM ST st LEFT JOIN SA sa ON sa.ref = st.ref AND sa.armazem = 4 " +
           "WHERE st.inactivo = 0 AND RTRIM(st.ref) <> ''"
    $ligacao = New-Object System.Data.SqlClient.SqlConnection $ligacaoTxt
    $ligacao.Open()
    try {
        $cmd = $ligacao.CreateCommand()
        $cmd.CommandText = $sql
        $cmd.CommandTimeout = 120
        $tabela = New-Object System.Data.DataTable
        $tabela.Load($cmd.ExecuteReader())
    }
    finally {
        $ligacao.Close()
    }
    $atual = @{}
    foreach ($r in $tabela.Rows) { $atual[([string]$r["ref"]).ToUpper()] = @([double]$r["stock"], [double]$r["reservado"]) }
    $completo = ($null -eq $script:StockEnviado)
    $mudou = @{}
    foreach ($ref in $atual.Keys) {
        $a = $atual[$ref]
        if ($completo) { $mudou[$ref] = $a; continue }
        $b = $script:StockEnviado[$ref]
        if ($null -eq $b -or $b[0] -ne $a[0] -or $b[1] -ne $a[1]) { $mudou[$ref] = $a }
    }
    if ($mudou.Count -gt 0) {
        $resp = Enviar-Json @{ action = "stock_enviar"; completo = $completo; itens = $mudou }
        if (-not $resp.ok) { throw ("o servidor recusou: " + $resp.error) }
    }
    $script:StockEnviado = $atual
    return $mudou.Count
}

# Clientes ativos do PHC para a app (data/clientes.json). Mesmos campos do ficheiro antigo, mais as condicoes de pagamento.
# O plafond e o saldo nao vao (a lista e publica). Envia a lista inteira so quando alguma coisa muda.
$script:ClientesAssinatura = $null
function Enviar-Clientes {
    $sql = "SELECT RTRIM(nome) AS nome, RTRIM(morada) AS morada, RTRIM(local) AS localidade, " +
           "RTRIM(CASE WHEN RTRIM(telefone) <> '' THEN telefone ELSE tlmvl END) AS telefone, desconto, RTRIM(email) AS email, " +
           "no AS numero, RTRIM(contacto) AS contacto, RTRIM(tpdesc) AS condicoes " +
           "FROM CL WHERE estab = 0 AND inactivo = 0 AND RTRIM(nome) <> '' ORDER BY no"
    $lista = Para-Objetos (Consultar $sql)
    foreach ($c in $lista) { $c["desconto"] = [double]$c["desconto"]; $c["numero"] = [int]$c["numero"]; $c["plafond"] = 0 }
    $json = $lista | ConvertTo-Json -Compress -Depth 4
    $assin = [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($json)))
    if ($assin -eq $script:ClientesAssinatura) { return "sem alteracoes" }
    $r = Enviar-Json @{ action = "clientes_enviar"; clientes = $lista.ToArray() }
    if (-not $r.ok) { throw ("o servidor recusou: " + $r.error) }
    $script:ClientesAssinatura = $assin
    return "$($r.clientes) enviados"
}

function Enviar-Json($corpo, $tempo = 100) {
    $json = $corpo | ConvertTo-Json -Compress -Depth 6
    return Invoke-RestMethod -Uri $Funcao -Method Post -Body ([Text.Encoding]::UTF8.GetBytes($json)) -ContentType "application/json; charset=utf-8" -Headers @{ Authorization = "Bearer $($Config.token)" } -TimeoutSec $tempo
}

function Reportar($ok, $importadas, $erro) {
    try {
        Enviar-Json @{ action = "agente_pulso"; nome = $env:COMPUTERNAME; versao = $VersaoLocal; ok = $ok; importadas = $importadas; erro = $erro } | Out-Null
    }
    catch {
        Escrever-Registo ("Nao consegui reportar o estado: " + $_.Exception.Message)
    }
}

function Sincronizar([string]$motivo) {
    try {
        $tabela = Ler-Faturas
        if ($tabela.Rows.Count -eq 0) {
            Escrever-Registo "Sem faturas nos ultimos 40 dias."
            Reportar $true 0 ""
        }
        else {
            $tsv = Montar-Tsv $tabela
            $resp = Enviar-Json @{ action = "importar"; texto = $tsv }
            if ($resp.ok) {
                $encTxt = ""
                try {
                    $enc = Ler-Faturas -Encomendas
                    if ($enc.Rows.Count -gt 0) {
                        $respEnc = Enviar-Json @{ action = "importar"; fonte = "enc"; texto = (Montar-Tsv $enc) }
                        $encTxt = if ($respEnc.ok) { ", " + $respEnc.importadas + " encomendas" } else { ", encomendas recusadas: " + $respEnc.error }
                    }
                }
                catch { $encTxt = ", ERRO nas encomendas: " + $_.Exception.Message }
                try { $encTxt += ", clientes: " + (Enviar-Clientes) }
                catch { $encTxt += ", ERRO nos clientes: " + $_.Exception.Message }
                try { $encTxt += ", stock: " + (Enviar-Stock) + " artigos" }
                catch { $encTxt += ", ERRO no stock: " + $_.Exception.Message }
                if ($Config.cfUrl) {
                    try { $encTxt += ", Cloudflare: " + (Sincronizar-Cloud) }
                    catch { $encTxt += ", ERRO na Cloudflare: " + $_.Exception.Message }
                }
                Escrever-Registo ("OK" + $motivo + ": " + $resp.importadas + " faturas enviadas" + $encTxt + ".")
                Reportar $true $resp.importadas ""
            }
            else {
                Escrever-Registo ("Servidor recusou: " + $resp.error)
                Reportar $false 0 ([string]$resp.error)
            }
        }
    }
    catch {
        $msg = $_.Exception.Message
        Escrever-Registo ("ERRO: " + $msg)
        Reportar $false 0 $msg
    }
}

# ---------------- Cloudflare D1 (analises) ----------------
# Os nomes das colunas (AS ...) sao os da D1 (ver cloudflare\schema.sql). {0} = condicao WHERE.
$SqlCloud = @{
    faturas = "SELECT RTRIM(ft.ftstamp) AS stamp, RTRIM(ft.nmdoc) AS tipo, ft.ndoc, ft.fno AS numero, CONVERT(varchar(10), ft.fdata, 120) AS data, ft.no AS cliente_no, ft.estab AS cliente_estab, RTRIM(ft.nome) AS cliente, RTRIM(ft.ncont) AS nif, ft.etotal AS total, ft.ettiliq AS total_sem_iva, ft.ettiva AS iva, RTRIM(ft.vendnm) AS vendedor, CAST(ft.anulado AS int) AS anulada, RTRIM(ft.zona) AS zona, RTRIM(ft.local) AS local, CONVERT(varchar(10), ft.usrdata, 120) + ' ' + ft.usrhora AS alterado FROM FT ft WHERE {0}"
    faturas_linhas = "SELECT RTRIM(fi.fistamp) AS stamp, RTRIM(fi.ftstamp) AS pai, fi.lordem AS ordem, RTRIM(fi.ref) AS ref, RTRIM(fi.design) AS design, fi.qtt, fi.epv AS preco, fi.desconto, fi.etiliquido AS liquido, fi.iva, RTRIM(fi.fivendnm) AS vendedor, RTRIM(fi.familia) AS familia, fi.armazem, fi.ecusto AS custo FROM FI fi JOIN FT ft ON ft.ftstamp = fi.ftstamp WHERE {0}"
    encomendas = "SELECT RTRIM(bo.bostamp) AS stamp, bo.obrano AS numero, CONVERT(varchar(10), bo.dataobra, 120) AS data, bo.no AS cliente_no, bo.estab AS cliente_estab, RTRIM(bo.nome) AS cliente, bo.etotal AS total, RTRIM(bo.vendnm) AS vendedor, CAST(bo.fechada AS int) AS fechada, CONVERT(varchar(10), bo.datafecho, 120) AS data_fecho, CONVERT(varchar(10), bo.usrdata, 120) + ' ' + bo.usrhora AS alterado FROM BO bo WHERE bo.ndos = 12 AND {0}"
    encomendas_linhas = "SELECT RTRIM(bi.bistamp) AS stamp, RTRIM(bi.bostamp) AS pai, bi.lordem AS ordem, RTRIM(bi.ref) AS ref, RTRIM(bi.design) AS design, bi.qtt, bi.qtt2 AS qtt_satisfeita, bi.edebito AS preco, bi.desconto, bi.ettdeb AS total, RTRIM(bi.vendnm) AS vendedor, RTRIM(bi.familia) AS familia, bi.armazem, CAST(bi.fechada AS int) AS fechada FROM BI bi JOIN BO bo ON bo.bostamp = bi.bostamp WHERE bo.ndos = 12 AND {0}"
    clientes = "SELECT no, estab, RTRIM(nome) AS nome, RTRIM(ncont) AS nif, RTRIM(local) AS local, RTRIM(zona) AS zona, RTRIM(tipo) AS tipo, RTRIM(vendnm) AS vendedor, CAST(inactivo AS int) AS inativo, esaldo AS saldo, eplafond AS plafond FROM CL"
    artigos = "SELECT RTRIM(st.ref) AS ref, RTRIM(st.design) AS design, RTRIM(st.familia) AS familia, RTRIM(st.usr1) AS marca, RTRIM(st.fornecedor) AS fornecedor, st.epv1 AS preco, st.epcult AS custo_ultimo, st.epcpond AS custo_medio, st.stock, ISNULL(sa.stock, 0) AS stock_venda, CAST(st.inactivo AS int) AS inativo, RTRIM(st.unidade) AS unidade FROM ST st LEFT JOIN SA sa ON sa.ref = st.ref AND sa.armazem = 4 WHERE RTRIM(st.ref) <> ''"
}
# Coluna de data de cada tipo de documento e coluna "pai" das linhas na D1.
$DocCloud = @{
    faturas = @{ data = "ft.fdata"; alterado = "CONVERT(varchar(10), ft.usrdata, 120) + ' ' + ft.usrhora"; linhas = "faturas_linhas"; pai = "fatura_stamp" }
    encomendas = @{ data = "bo.dataobra"; alterado = "CONVERT(varchar(10), bo.usrdata, 120) + ' ' + bo.usrhora"; linhas = "encomendas_linhas"; pai = "encomenda_stamp" }
}
$CloudInicio = "2025-01-01"
$CloudEstado = Join-Path $Pasta "cloudflare-estado.json"

function Consultar([string]$sql) {
    if ($Config.sqlUtilizador) {
        $ligacaoTxt = "Server=$($Config.sqlServidor);Database=$($Config.sqlBase);User Id=$($Config.sqlUtilizador);Password=$($Config.sqlPalavraPasse)"
    }
    else {
        $ligacaoTxt = "Server=$($Config.sqlServidor);Database=$($Config.sqlBase);Integrated Security=True"
    }
    $ligacao = New-Object System.Data.SqlClient.SqlConnection $ligacaoTxt
    $ligacao.Open()
    try {
        $cmd = $ligacao.CreateCommand()
        $cmd.CommandText = $sql
        $cmd.CommandTimeout = 300
        $tabela = New-Object System.Data.DataTable
        $tabela.Load($cmd.ExecuteReader())
        return ,$tabela
    }
    finally {
        $ligacao.Close()
    }
}

# DataTable -> lista de dicionarios prontos para JSON (DBNull -> null, numeros como double).
function Para-Objetos($tabela) {
    $cols = @($tabela.Columns | ForEach-Object { $_.ColumnName })
    $lista = New-Object System.Collections.Generic.List[object]
    foreach ($r in $tabela.Rows) {
        $o = [ordered]@{}
        foreach ($c in $cols) {
            $v = $r[$c]
            if ($v -is [DBNull]) { $v = $null }
            elseif ($v -is [decimal] -or $v -is [single]) { $v = [double]$v }
            elseif ($v -is [string]) { $v = $v.Trim() }
            elseif ($v -is [bool]) { $v = [int]$v }
            $o[$c] = $v
        }
        $lista.Add($o)
    }
    return ,$lista
}

function Enviar-Cloud($corpo) {
    $json = $corpo | ConvertTo-Json -Compress -Depth 6
    $r = Invoke-RestMethod -Uri ($Config.cfUrl + "/sync") -Method Post -Body ([Text.Encoding]::UTF8.GetBytes($json)) -ContentType "application/json; charset=utf-8" -Headers @{ Authorization = "Bearer $($Config.cfToken)" } -TimeoutSec 180
    if (-not $r.ok) { throw ("a Cloudflare recusou: " + $r.error) }
}

# Envia os documentos (faturas ou encomendas) que cumprem $onde, com as suas linhas, em lotes.
# Devolve [docs, linhas, maior "alterado"].
function Enviar-Documentos([string]$tipo, [string]$onde) {
    $d = $DocCloud[$tipo]
    $docs = Para-Objetos (Consultar ($SqlCloud[$tipo] -f $onde))
    if ($docs.Count -eq 0) { return @(0, 0, "") }
    $linhas = Para-Objetos (Consultar ($SqlCloud[$d.linhas] -f $onde))
    $porPai = @{}
    foreach ($l in $linhas) {
        $p = $l["pai"]; $l.Remove("pai"); $l[$d.pai] = $p
        if (-not $porPai.ContainsKey($p)) { $porPai[$p] = New-Object System.Collections.Generic.List[object] }
        $porPai[$p].Add($l)
    }
    $maior = ""
    $lote = New-Object System.Collections.Generic.List[object]; $loteLinhas = New-Object System.Collections.Generic.List[object]
    for ($i = 0; $i -lt $docs.Count; $i++) {
        $doc = $docs[$i]
        if ([string]$doc["alterado"] -gt $maior) { $maior = [string]$doc["alterado"] }
        $lote.Add($doc)
        if ($porPai.ContainsKey($doc["stamp"])) { $loteLinhas.AddRange($porPai[$doc["stamp"]]) }
        # Lotes de ~150 documentos ou ~1500 linhas.
        if ($lote.Count -ge 150 -or $loteLinhas.Count -ge 1500 -or $i -eq $docs.Count - 1) {
            Enviar-Cloud @{ tipo = $tipo; docs = $lote.ToArray(); linhas = $loteLinhas.ToArray() }
            $lote.Clear(); $loteLinhas.Clear()
        }
    }
    return @($docs.Count, $linhas.Count, $maior)
}

# Envia uma tabela inteira (clientes, artigos) em lotes; com $soMudou compara com o ultimo envio em memoria.
$script:CloudTabelas = @{}
function Enviar-Tabela([string]$tipo, [string]$chave) {
    $linhas = Para-Objetos (Consultar $SqlCloud[$tipo])
    $anterior = $script:CloudTabelas[$tipo]
    $atual = @{}; $mudou = New-Object System.Collections.Generic.List[object]
    foreach ($l in $linhas) {
        $k = (@($chave -split ',' | ForEach-Object { [string]$l[$_] }) -join '|')
        $assin = ($l.Values | ForEach-Object { [string]$_ }) -join '|'
        $atual[$k] = $assin
        if ($null -eq $anterior -or $anterior[$k] -ne $assin) { $mudou.Add($l) }
    }
    for ($i = 0; $i -lt $mudou.Count; $i += 1000) {
        Enviar-Cloud @{ tipo = $tipo; docs = $mudou.GetRange($i, [Math]::Min(1000, $mudou.Count - $i)).ToArray() }
    }
    $script:CloudTabelas[$tipo] = $atual
    return $mudou.Count
}

function Ler-EstadoCloud {
    if (Test-Path $CloudEstado) { try { return (Get-Content $CloudEstado -Raw | ConvertFrom-Json) } catch {} }
    return [pscustomobject]@{}
}
function Gravar-EstadoCloud($estado) { $estado | ConvertTo-Json -Depth 4 | Set-Content $CloudEstado -Encoding UTF8 }
function Hora-Sql { return [string](Consultar "SELECT CONVERT(varchar(19), GETDATE(), 120) AS agora").Rows[0]["agora"] }

# Ciclo normal: so documentos alterados desde a ultima marca (e clientes/artigos que mudaram).
function Sincronizar-Cloud {
    $estado = Ler-EstadoCloud
    if (-not $estado.carga_concluida) { return "a aguardar a carga inicial" }
    $txt = @()
    foreach ($tipo in 'faturas', 'encomendas') {
        $d = $DocCloud[$tipo]
        $marca = [string]$estado.$tipo
        if (-not $marca) { $marca = $estado.marca_inicio }
        $r = Enviar-Documentos $tipo ("$($d.data) >= '$CloudInicio' AND $($d.alterado) >= '$marca'")
        if ($r[2]) { $estado | Add-Member -NotePropertyName $tipo -NotePropertyValue $r[2] -Force }
        $txt += "$($r[0]) $tipo"
    }
    Gravar-EstadoCloud $estado
    $txt += "$(Enviar-Tabela 'clientes' 'no,estab') clientes"
    $txt += "$(Enviar-Tabela 'artigos' 'ref') artigos"
    $txt += "nao regularizado: $(Enviar-Pendentes)"
    return ($txt -join ", ")
}

# Conta corrente dos clientes em aberto (o "nao regularizado"): bate com o saldo da ficha (CL.esaldo).
# Manda a lista inteira so quando muda; na Cloudflare substitui a anterior (o que foi pago desaparece).
$script:PendentesAssinatura = $null
function Enviar-Pendentes {
    $sql = "SELECT RTRIM(cc.ccstamp) AS stamp, cc.no AS cliente_no, cc.estab AS cliente_estab, RTRIM(cc.nome) AS cliente, RTRIM(cc.cmdesc) AS tipo, " +
           "RTRIM(CAST(cc.nrdoc AS varchar(30))) AS documento, CONVERT(varchar(10), cc.datalc, 120) AS data, CONVERT(varchar(10), cc.dataven, 120) AS vencimento, " +
           "cc.edeb - cc.ecred AS valor, (cc.edeb - cc.edebf) - (cc.ecred - cc.ecredf) AS em_aberto, RTRIM(cc.vendnm) AS vendedor " +
           "FROM CC cc WHERE (cc.edeb - cc.edebf) <> 0 OR (cc.ecred - cc.ecredf) <> 0 ORDER BY cc.ccstamp"
    $lista = Para-Objetos (Consultar $sql)
    $json = $lista | ConvertTo-Json -Compress -Depth 4
    $assin = [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes([string]$json)))
    if ($assin -eq $script:PendentesAssinatura) { return "sem alteracoes" }
    Enviar-Cloud @{ tipo = "pendentes"; substituir = $true; docs = $lista.ToArray(); marca = @{ chave = "pendentes"; valor = [string]$lista.Count } }
    $script:PendentesAssinatura = $assin
    return "$($lista.Count) documentos"
}

# Carga inicial desde 2025, mes a mes; regista o que ja foi feito para retomar se for interrompida.
function Carga-Cloudflare {
    $estado = Ler-EstadoCloud
    if (-not $estado.marca_inicio) { $estado | Add-Member -NotePropertyName marca_inicio -NotePropertyValue (Hora-Sql) -Force; Gravar-EstadoCloud $estado }
    $feitos = @($estado.feitos)
    $mes = [datetime]::ParseExact($CloudInicio, "yyyy-MM-dd", $null)
    $fim = (Get-Date).Date.AddDays(1)
    while ($mes -lt $fim) {
        $seg = $mes.AddMonths(1)
        foreach ($tipo in 'faturas', 'encomendas') {
            $chave = "$tipo " + $mes.ToString("yyyy-MM")
            if ($feitos -contains $chave) { continue }
            $col = $DocCloud[$tipo].data
            $r = Enviar-Documentos $tipo ("$col >= '" + $mes.ToString("yyyy-MM-dd") + "' AND $col < '" + $seg.ToString("yyyy-MM-dd") + "'")
            $feitos += $chave
            $estado | Add-Member -NotePropertyName feitos -NotePropertyValue $feitos -Force
            Gravar-EstadoCloud $estado
            Escrever-Registo ("Carga Cloudflare: $chave - $($r[0]) documentos, $($r[1]) linhas.")
        }
        $mes = $seg
    }
    $nc = Enviar-Tabela 'clientes' 'no,estab'
    $na = Enviar-Tabela 'artigos' 'ref'
    $estado | Add-Member -NotePropertyName carga_concluida -NotePropertyValue $true -Force
    Gravar-EstadoCloud $estado
    Escrever-Registo ("Carga Cloudflare concluida: $nc clientes, $na artigos. A partir de agora o ciclo envia so o que muda.")
}

if ($Carga) {
    # Corre a parte (pode ser ao mesmo tempo que o agente). Uma carga de cada vez.
    $mutexCarga = New-Object System.Threading.Mutex($false, "Local\FaturacaoAgenteCarga")
    if (-not $mutexCarga.WaitOne(0)) { Write-Host "Ja ha uma carga a correr."; exit }
    try { Carga-Cloudflare } catch { Escrever-Registo ("Carga Cloudflare interrompida (volta a correr para retomar): " + $_.Exception.Message); throw }
    finally { $mutexCarga.ReleaseMutex() }
    exit
}

# Uma so copia de cada vez.
$mutex = New-Object System.Threading.Mutex($false, "Local\FaturacaoAgente")
if (-not $mutex.WaitOne(0)) { exit }

try {
    if (Atualizar-Se-Preciso) { exit }
    Escrever-Registo "Agente versao $VersaoLocal a correr."
    Sincronizar ""
    $ultimoEnvio = Get-Date
    $ultimaAtualizacao = Get-Date
    $ultimoAvisoRede = [datetime]::MinValue

    while ($true) {
        Start-Sleep -Seconds $IntervaloVerificar
        $pedido = $false
        $tipoPedido = ""
        try {
            $resp = Enviar-Json @{ action = "agente_verificar"; versao = $VersaoLocal } 15
            $pedido = [bool]$resp.pedido
            if ($resp.tipo) { $tipoPedido = [string]$resp.tipo }
        }
        catch {
            # Sem internet ou servidor lento: regista no maximo uma vez a cada 10 minutos.
            if (((Get-Date) - $ultimoAvisoRede) -ge $IntervaloEnvio) {
                Escrever-Registo ("Sem contacto com o servidor: " + $_.Exception.Message)
                $ultimoAvisoRede = Get-Date
            }
        }
        # Pedido remoto da app de gestao: atualizar (descarrega a versao nova, se houver) e/ou reiniciar o agente.
        if ($pedido -and ($tipoPedido -eq "atualizar" -or $tipoPedido -eq "reiniciar")) {
            Escrever-Registo "Pedido remoto da app: $tipoPedido."
            if ($tipoPedido -eq "atualizar") { [void](Atualizar-Se-Preciso) }
            $script:Relancar = $true
            break
        }
        if ($pedido) {
            Sincronizar " (pedido da app)"
            $ultimoEnvio = Get-Date
        }
        elseif (((Get-Date) - $ultimoEnvio) -ge $IntervaloEnvio) {
            Sincronizar ""
            $ultimoEnvio = Get-Date
        }
        if (((Get-Date) - $ultimaAtualizacao) -ge $IntervaloAtualizar) {
            $ultimaAtualizacao = Get-Date
            if (Atualizar-Se-Preciso) { $script:Relancar = $true; break }
        }
    }
}
finally {
    $mutex.ReleaseMutex()
    $mutex.Dispose()
}
# Reinicio pedido a distancia: arranca ja a copia (eventualmente nova) em vez de esperar pela tarefa agendada.
if ($script:Relancar) {
    $ficheiro = Join-Path $Pasta "agente.ps1"
    Start-Process -FilePath "powershell.exe" -ArgumentList "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$ficheiro`"" -WindowStyle Hidden
}
