#!/data/data/com.termux/files/usr/bin/bash
# setup.sh — recria o ambiente do Termux para os scripts do ponto.
# Uso (após reinstalar o Termux e rodar termux-setup-storage):
#   bash ~/storage/shared/Mega/mobile/ponto/setup.sh
set -e

BASE="$HOME/storage/shared/Mega/mobile/ponto"
SCRIPTS="$BASE/scripts"
LOGS="$BASE/logs"
NOMES="abrir_ponto fechar_ponto registrar_pit"
BIN="/data/data/com.termux/files/usr/bin"
CRED_DIR="$HOME/.config/ponto"
CRED="$CRED_DIR/credenciais.env"

if [ ! -d "$SCRIPTS" ]; then
  echo "ERRO: $SCRIPTS não encontrada. Rode termux-setup-storage e confira o Mega." >&2
  exit 1
fi

echo "==> Instalando pacotes"
pkg update -y
pkg upgrade -y
pkg install -y python
pip install requests beautifulsoup4

# termux-setup-storage

# SSH
pkg install -y openssh
# passwd
# sshd

echo "==> Criando wrappers"
mkdir -p "$HOME/.termux/tasker" "$HOME/.termux/widget/dynamic_shortcuts" "$LOGS"

cria_wrapper() {  # $1 = destino, $2 = nome, $3 = segundos de espera
  cat > "$1" << EOF
#!$BIN/bash
termux-wake-lock
trap termux-wake-unlock EXIT
sleep $3
LOG="$LOGS/$2.log"
set -a; . "$CRED"; set +a
cd "$SCRIPTS"
echo "=== \$(date '+%F %T') ===" >> "\$LOG"
python "$2.py" >> "\$LOG" 2>&1
echo "saída: \$?" >> "\$LOG"
tail -n 300 "\$LOG" > "\$LOG.tmp" && mv "\$LOG.tmp" "\$LOG"
EOF
  chmod +x "$1"
}

for n in $NOMES; do
  [ -f "$SCRIPTS/$n.py" ] || echo "AVISO: $SCRIPTS/$n.py não existe"
  cria_wrapper "$HOME/.termux/tasker/$n.sh" "$n" 5   # Tasker: espera a rede
  cria_wrapper "$HOME/.termux/widget/dynamic_shortcuts/$n.sh" "$n" 0   # Widget: execução manual
done

echo "==> SSH automático ao abrir o Termux"
grep -q 'pgrep -x sshd' "$HOME/.bashrc" 2>/dev/null || \
  echo 'pgrep -x sshd >/dev/null || sshd' >> "$HOME/.bashrc"

echo
echo "Pronto. Wrappers do Tasker:"
ls "$HOME/.termux/tasker"
echo "Logs em: $LOGS"
if grep -q PREENCHER "$CRED"; then
  echo ">>> Preencha as credenciais: nano $CRED"
fi
echo "Se for uma instalação nova, defina a senha do SSH com: passwd"


