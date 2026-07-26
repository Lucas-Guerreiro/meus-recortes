# Script para acompanhar commits em tempo real no Windows (PowerShell)
# Meus Recortes - Workflow de Commits 🎬

Clear-Host
$host.ui.RawUI.WindowTitle = "Monitor de Commits - Meus Recortes"

Write-Host "====================================================" -ForegroundColor Cyan
Write-Host "       Acompanhamento de Commits - Meus Recortes    " -ForegroundColor Green
Write-Host "====================================================" -ForegroundColor Cyan
Write-Host "Pressione [CTRL+C] para sair. Monitorando a cada 5 segundos...`n" -ForegroundColor Yellow

# Função para checar se o git está disponível
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Write-Host "Erro: O Git não está instalado ou não está no PATH do sistema!" -ForegroundColor Red
    Exit
}

while ($true) {
    Clear-Host
    Write-Host "======================================================================" -ForegroundColor Cyan
    Write-Host " ÚLTIMOS COMMITS (Atualizado em: $(Get-Date -Format 'dd/MM/yyyy HH:mm:ss'))" -ForegroundColor Green
    Write-Host "======================================================================" -ForegroundColor Cyan
    Write-Host "Hash    - Data/Hora    - Mensagem do Commit <Autor>" -ForegroundColor Gray
    Write-Host "----------------------------------------------------------------------" -ForegroundColor DarkGray
    
    # Executa o git log formatado com cores
    # %h = hash curto, %ad = data formatada, %s = mensagem, %an = autor
    git log -n 12 --date=format:'%d/%m %H:%M' --pretty=format:'%C(yellow)%h%Creset - %C(cyan)%ad%Creset %s %C(bold green)<%an>%Creset'
    
    Write-Host "`n----------------------------------------------------------------------" -ForegroundColor DarkGray
    Write-Host "Pressione [CTRL+C] para encerrar o monitor." -ForegroundColor DarkGray
    
    # Aguarda 5 segundos antes de atualizar
    Start-Sleep -Seconds 5
}
