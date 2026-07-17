# Simula l'arrivo di un'email con fattura allegata (formato webhook Postmark Inbound).
# Uso: .\scripts\send-test-email.ps1 [-ApiUrl http://localhost:8080]
# NB: file volutamente solo ASCII (PowerShell 5.1 legge male l'UTF-8 senza BOM).

param(
    [string]$ApiUrl = "http://localhost:8080",
    [string]$Token = ""
)

$invoiceText = @'
FATTURA INV-234

Fornitore: ABC SRL
P.IVA: IT01234567890

Data emissione: 02/07/2026
Scadenza: 15/07/2026

Descrizione                     Qta     Prezzo      Totale
Tubi acciaio zincato 2"          10     50,00       500,00
Raccordi flangiati DN50          20     25,00       500,00
Trasporto e consegna              1    285,90       285,90

Importo totale: EUR 1.285,90
'@

$payload = @{
    From              = "amministrazione@abcsrl.it"
    FromName          = "ABC SRL"
    To                = "dev@inbox.local"
    OriginalRecipient = "dev@inbox.local"
    Subject           = "Fattura INV-234 - ABC SRL"
    TextBody          = "Buongiorno, in allegato la fattura INV-234 di 1.285,90 EUR con scadenza 15/07/2026. Cordiali saluti, ABC SRL"
    Date              = (Get-Date).ToUniversalTime().ToString("R")
    MessageID         = [guid]::NewGuid().ToString()
    Attachments = @(
        @{
            Name          = "fattura-INV-234.txt"
            ContentType   = "text/plain"
            Content       = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($invoiceText))
            ContentLength = $invoiceText.Length
        }
    )
}

$headers = @{}
if ($Token -ne "") { $headers["X-Webhook-Token"] = $Token }

$response = Invoke-RestMethod -Method Post -Uri "$ApiUrl/webhooks/inbound-email" `
    -ContentType "application/json; charset=utf-8" -Headers $headers `
    -Body ([Text.Encoding]::UTF8.GetBytes(($payload | ConvertTo-Json -Depth 5)))

Write-Host "Risposta webhook:" ($response | ConvertTo-Json -Compress)
Write-Host "Apri http://localhost:3000 per vedere dashboard e attivita'."
