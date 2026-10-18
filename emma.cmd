@echo off
rem Run a finished ElFin image on Emma 02 - see emma.ps1.
rem   emma.cmd images\chess.hex [32k|elf|mini] [-Clock 100] [-DryRun]
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0emma.ps1" %*
