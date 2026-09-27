# Builds desktop\dist\MusicPlayer.exe (windowed, no console) via PyInstaller.
# Run from anywhere; paths below are relative to this script.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

pip install -r requirements.txt pyinstaller

pyinstaller --noconfirm --onefile --windowed --name MusicPlayer --exclude-module numpy player.py

Write-Host "Built: $PSScriptRoot\dist\MusicPlayer.exe"
Write-Host "Copy MusicPlayer.exe into any folder of .mp3 files and run it."
