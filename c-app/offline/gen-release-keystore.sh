#!/usr/bin/env bash
#
# 读 signing/keystore.properties 生成正式签名 keystore，并打印三个指纹
# （MD5 / SHA1 / SHA256，冒号分隔十六进制）。
#
# **SHA1 是这一步的产物里最要紧的那个**：DCloud 的离线 AppKey 按
# 「包名 + 证书 SHA1」签发，Google Maps 的 Android 限制也按这一对配。
# 换了 keystore 就要重新申请 appkey，否则装上去启动白屏。
#
#   cp signing/keystore.properties.example signing/keystore.properties
#   vi signing/keystore.properties && chmod 600 signing/keystore.properties
#   ./gen-release-keystore.sh
#
# **已存在就不覆盖，直接打指纹**。覆盖等于换了一把钥匙：同包名的新旧安装包
# 签名对不上，老用户装不上更新 —— 而这件事在打包时不会报错。
set -euo pipefail

cd "$(dirname "$0")"
CONF=signing/keystore.properties
PKG="${PB_PKG:-ai.neargo.powerbank}"

[ -f "$CONF" ] || { echo "缺 $CONF —— 先 cp signing/keystore.properties.example $CONF 再填" >&2; exit 1; }

# 只认 key=value，忽略注释与空行。值里含空格（dname）所以不能用 xargs
get() {
  local v
  v=$(grep -E "^$1=" "$CONF" | head -1 | cut -d= -f2-)
  [ -n "$v" ] || { echo "$CONF 里 $1 是空的" >&2; exit 1; }
  printf '%s' "$v"
}

STORE_FILE=$(get storeFile); STORE_PASS=$(get storePassword); KEY_PASS=$(get keyPassword)
KEY_ALIAS=$(get keyAlias);   DNAME=$(get dname);              VALIDITY=$(get validityDays)

# `~` 是 shell 的展开，properties 里写了不会展开 —— 不补的话会在当前目录
# 建出一个名叫 "~" 的目录，而报错要到很久以后
STORE_FILE="${STORE_FILE/#\~/$HOME}"

if [ -f "$STORE_FILE" ]; then
  echo "keystore 已存在，跳过生成：$STORE_FILE"
else
  mkdir -p "$(dirname "$STORE_FILE")"
  # 密码走 -storepass/-keypass 会进 ps 输出（同机其他账号看得到），所以从标准输入喂
  printf '%s\n%s\n%s\n' "$STORE_PASS" "$STORE_PASS" "$KEY_PASS" | \
    keytool -genkeypair -v -keystore "$STORE_FILE" -alias "$KEY_ALIAS" \
      -keyalg RSA -keysize 2048 -validity "$VALIDITY" -dname "$DNAME" >/dev/null
  chmod 600 "$STORE_FILE"
  echo "已生成：$STORE_FILE（有效期 $VALIDITY 天）"
  echo
  echo "⚠️  现在就去备份它。这把钥匙丢了，包名 $PKG 就再也发不了更新 ——"
  echo "    只能换包名重新上架，老用户全部重装、本地数据全丢、商店评分清零。"
fi

CER=$(mktemp -t relcert); trap 'rm -f "$CER"' EXIT
# **导 DER，不要 -rfc**：指纹是证书 DER 字节的摘要。导成 PEM 再算得到的是
# 那段 base64 文本的摘要 —— 一个长得完全正常、但哪儿都对不上的值
printf '%s\n' "$STORE_PASS" | keytool -exportcert -keystore "$STORE_FILE" -alias "$KEY_ALIAS" -file "$CER" >/dev/null

# -c = 冒号分隔。JDK 17 起 keytool -list 不再打印 MD5，所以三个都从证书本身算
echo
echo "包名：$PKG"
for alg in md5 sha1 sha256; do
  printf '%-8s%s\n' "$(printf '%s' "$alg" | tr 'a-z' 'A-Z'):" \
    "$(openssl dgst -"$alg" -c "$CER" | sed 's/.*= //' | tr 'a-f' 'A-F')"
done
echo
echo "拿 SHA1 去办两件事：dev.dcloud.net.cn 申请离线 AppKey（绑包名+SHA1）、"
echo "Google Cloud Console 给 Maps 的 Android 限制加这一对。"
