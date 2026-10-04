#!/usr/bin/env bash
# Gera o APK release assinado com a chave própria do app, pronto para distribuir.
#
# Na primeira execução cria a chave em ~/.config/ponto/ponto-release.jks (ou em
# $PONTO_KEYSTORE). Guarde uma cópia dela: sem a mesma chave, quem já instalou o
# app não consegue atualizá-lo.
#
# Uso: android/release.sh   (o APK assinado fica em dist/)
set -euo pipefail
cd "$(dirname "$0")/.."

ferramentas="${BRIEFCASE_HOME:-$HOME/.cache/briefcase}/tools"
sdk="${ANDROID_HOME:-$ferramentas/android_sdk}"
java="${JAVA_HOME:-$ferramentas/java17}"
export PATH="$java/bin:$PATH"  # o apksigner precisa do java
chave="${PONTO_KEYSTORE:-$HOME/.config/ponto/ponto-release.jks}"

if [[ ! -f src/ponto/segredo_telegram.py ]]; then
    echo "Aviso: sem src/ponto/segredo_telegram.py, o APK sai sem notificações do Telegram." >&2
fi

if [[ ! -f $chave ]]; then
    echo "Criando a chave de assinatura em $chave"
    mkdir -p "$(dirname "$chave")"
    "$java/bin/keytool" -genkeypair -keystore "$chave" -alias ponto \
        -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Ponto IFES"
    chmod 600 "$chave"
    echo "Faça uma cópia de $chave e guarde a senha."
fi

briefcase package android -u -p apk

versao=$(sed -n 's/^version = "\(.*\)"/\1/p' pyproject.toml | head -1)
entrada="dist/Ponto IFES-$versao.apk"
alinhado="dist/ponto-ifes-$versao.alinhado.apk"
saida="dist/ponto-ifes-$versao.apk"

build_tools=$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)
"$build_tools/zipalign" -p -f 4 "$entrada" "$alinhado"
"$build_tools/apksigner" sign --ks "$chave" --ks-key-alias ponto --out "$saida" "$alinhado"
"$build_tools/apksigner" verify "$saida"
rm -f "$alinhado" "$saida.idsig" "$entrada"

echo "APK assinado: $saida"
