#!/usr/bin/env bash
# 运行位置：GitHub Actions runner（非目标服务器）。
# 职责：用 sshpass 将新 jar 与远程部署脚本推送到目标服务器，并触发远程部署。
# 所有主机 / 账号 / 密码均来自环境变量（由工作流从 GitHub Secrets 注入），本脚本不含任何明文凭据。
#
# 必需环境变量：
#   SSHPASS       SSH 登录密码（sshpass -e 从此变量读取，不进入命令行历史/进程参数）
#   SSH_USER      SSH 登录用户
#   SSH_HOST      目标服务器地址
#   APP_NAME      应用名（bgssai-builder-user / bgssai-builder-admin）
#   APP_DIR       服务器上的部署目录
#   SERVICE_NAME  服务名（默认 systemctl 重启的服务名）
#   APP_PORT      应用端口（用于健康检查）
#   BUILD_ID      构建标识（一般为 commit sha，用于版本归档）
# 可选环境变量：
#   SSH_PORT         SSH 端口，默认 22
#   HEALTH_PATH      健康检查路径，默认 /bgssai/health/readiness（就绪探针，Standards §13.7）
#   HEALTH_SCHEME    健康检查协议 http/https；应用自身跑 TLS 时设 https（留空则远程脚本回落为 http）
#   RESTART_CMD      自定义重启命令；留空则远程脚本回落为 systemctl restart <SERVICE_NAME>
#   APP_PROFILE      启动档（dev/test/prod）；留空则远程脚本回落为 dev
#   SSH_KNOWN_HOSTS  目标主机公钥（ssh-keyscan 输出）；设置后启用严格主机指纹校验，防止中间人窃取密码
#   UPLOAD_MIN_KIBPS 上传吞吐下限（KiB/s），默认 256；每次上传的墙钟上限按「文件大小 / 本下限」推导。
#                    设 0 关闭该守护（不推荐，仅供确属慢速链路时的应急放行）。
#   UPLOAD_MIN_TIMEOUT_SECONDS
#                    上传墙钟上限的下界（秒），默认 120。避免小文件被推导出过短的上限。
set -euo pipefail

JAR_LOCAL="${1:?usage: ship.sh <local-jar-path>}"

: "${SSHPASS:?SSHPASS is required}"
: "${SSH_USER:?SSH_USER is required}"
: "${SSH_HOST:?SSH_HOST is required}"
: "${APP_NAME:?APP_NAME is required}"
: "${APP_DIR:?APP_DIR is required}"
: "${SERVICE_NAME:?SERVICE_NAME is required}"
: "${APP_PORT:?APP_PORT is required}"
: "${BUILD_ID:?BUILD_ID is required}"
SSH_PORT="${SSH_PORT:-22}"
HEALTH_PATH="${HEALTH_PATH:-/bgssai/health/readiness}"
# 留空则由远程脚本回落为默认 http（remote-deploy.sh 用 :- 处理空值）。
HEALTH_SCHEME="${HEALTH_SCHEME:-}"
RESTART_CMD="${RESTART_CMD:-}"
# 留空则由远程脚本回落为默认 180 秒（remote-deploy.sh 用 :- 处理空值）。
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-}"
# 留空则由远程脚本回落为默认 dev 档（remote-deploy.sh 用 :- 处理空值）。
APP_PROFILE="${APP_PROFILE:-}"

# 上传吞吐守护：把每次 scp 的墙钟上限按「文件大小 / 吞吐下限」推导，而不是钉死一个绝对秒数。
# 这样上限随 jar 大小自动伸缩，不需要为每个产品单独调参：50 MB 与 150 MB 的 jar 用同一个下限，
# 得到的却是各自合理的上限。
#
# 为什么要有这道守护：scp 只有 ConnectTimeout（建连超时），对「连上了但涓流」毫无办法。
# 一次全量生产部署曾因链路吞吐塌到约 15 KiB/s，单个 147 MB 的 jar 传了 2 小时 20 分钟仍未完成，
# 吃光流水线 4 小时预算后整个 Job 被判超时中止 —— 连碰都没碰过的 8 个产品一并成了 ABORTED，
# 且已完成的一端与未完成的一端留下版本落差。链路坏掉时「快失败并保持远端不变」远好过「慢慢拖死」。
UPLOAD_MIN_KIBPS="${UPLOAD_MIN_KIBPS:-256}"
UPLOAD_MIN_TIMEOUT_SECONDS="${UPLOAD_MIN_TIMEOUT_SECONDS:-120}"
if ! [[ "${UPLOAD_MIN_KIBPS}" =~ ^[0-9]+$ ]]; then
  echo "[ship] UPLOAD_MIN_KIBPS 必须是非负整数（当前 ${UPLOAD_MIN_KIBPS}）" >&2
  exit 1
fi
if ! [[ "${UPLOAD_MIN_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "[ship] UPLOAD_MIN_TIMEOUT_SECONDS 必须是正整数（当前 ${UPLOAD_MIN_TIMEOUT_SECONDS}）" >&2
  exit 1
fi

# 以脚本自身位置定位 remote-deploy.sh，与 runner 当前工作目录无关。
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REMOTE_DEPLOY_SRC="${SCRIPT_DIR}/../remote-deploy.sh"

if [[ ! -f "${JAR_LOCAL}" ]]; then
  echo "[ship] local jar not found: ${JAR_LOCAL}" >&2
  exit 1
fi
if [[ ! -f "${REMOTE_DEPLOY_SRC}" ]]; then
  echo "[ship] remote-deploy.sh not found: ${REMOTE_DEPLOY_SRC}" >&2
  exit 1
fi

# 主机指纹校验策略：
#   - 设置了 SSH_KNOWN_HOSTS（目标主机公钥，ssh-keyscan 的输出）时，写入临时 known_hosts 并启用严格校验，
#     从根本上阻止中间人在密码登录时窃取密码（强烈推荐，尤其密码方式登录 root 时）。
#   - 未设置时回落为不校验主机指纹（仅便于快速接入，安全性较低），并打印告警。
KNOWN_HOSTS_FILE=""
# 用 if 而非 `[[ ... ]] && rm`：作为 EXIT trap 最后一条命令，`&&` 短路在条件为假时返回 1，
# 会把整个脚本退出码顶成 1（即便部署已成功）——用 if 保证清理不改动脚本退出码。
cleanup() {
  if [[ -n "${KNOWN_HOSTS_FILE}" && -f "${KNOWN_HOSTS_FILE}" ]]; then
    rm -f "${KNOWN_HOSTS_FILE}"
  fi
}
trap cleanup EXIT
# ConnectTimeout 只管建连；连上之后链路悄悄死掉（跨境线路常见）时，ssh/scp 会无限期挂着等一个
# 永远不会来的包。ServerAliveInterval/CountMax 让客户端每 15 秒探一次、连续 4 次无应答即断开，
# 把「静默挂死」变成约 60 秒内的一次明确失败。
KEEPALIVE_OPTS=(-o ServerAliveInterval=15 -o ServerAliveCountMax=4)
if [[ -n "${SSH_KNOWN_HOSTS:-}" ]]; then
  KNOWN_HOSTS_FILE="$(mktemp)"
  printf '%s\n' "${SSH_KNOWN_HOSTS}" > "${KNOWN_HOSTS_FILE}"
  COMMON_OPTS=(-o StrictHostKeyChecking=yes -o "UserKnownHostsFile=${KNOWN_HOSTS_FILE}" -o ConnectTimeout=15 "${KEEPALIVE_OPTS[@]}")
  echo "[ship] (${APP_NAME}) host-key pinning enabled (SSH_KNOWN_HOSTS set)"
else
  COMMON_OPTS=(-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=15 "${KEEPALIVE_OPTS[@]}")
  echo "[ship] (${APP_NAME}) WARNING: host-key verification disabled; set SSH_KNOWN_HOSTS to pin the target host key" >&2
fi
SSH_OPTS=("${COMMON_OPTS[@]}" -p "${SSH_PORT}")
SCP_OPTS=("${COMMON_OPTS[@]}" -P "${SSH_PORT}")

remote() {
  sshpass -e ssh "${SSH_OPTS[@]}" "${SSH_USER}@${SSH_HOST}" "$@"
}

# timeout(coreutils) 缺失时只告警并放行：守护工具不到位不该成为拒绝部署的理由。
if command -v timeout >/dev/null 2>&1; then
  HAVE_TIMEOUT=true
else
  HAVE_TIMEOUT=false
  echo "[ship] (${APP_NAME}) WARNING: 未找到 timeout(coreutils)，本次上传不设吞吐守护" >&2
fi

human_size() {
  local bytes="$1"
  if (( bytes >= 1048576 )); then
    printf '%s MiB' "$(( bytes / 1048576 ))"
  elif (( bytes >= 1024 )); then
    printf '%s KiB' "$(( bytes / 1024 ))"
  else
    printf '%s B' "${bytes}"
  fi
}

# upload <本地文件> <远端绝对路径> <用途说明>
# 带吞吐守护地上传单个文件。成功时打印实测吞吐 —— 链路劣化是渐进的，把每次的实测值留在构建日志里，
# 下次再出问题可以直接对比历史，不必靠人工去减两行时间戳。
upload() {
  local src="$1" dest="$2" what="$3"
  local bytes limit started elapsed rate rc=0
  bytes="$(wc -c < "${src}")"
  echo "[ship] (${APP_NAME}) upload ${what} -> ${dest} ($(human_size "${bytes}"))"

  local -a cmd=(sshpass -e scp "${SCP_OPTS[@]}" "${src}" "${SSH_USER}@${SSH_HOST}:${dest}")
  limit=0
  if [[ "${HAVE_TIMEOUT}" == "true" ]] && (( UPLOAD_MIN_KIBPS > 0 )); then
    limit=$(( bytes / (UPLOAD_MIN_KIBPS * 1024) + 1 ))
    # 用 if 而非 `(( ... )) && limit=...`：条件为假时 `(( ))` 返回 1，在 set -e 下会直接终止脚本。
    if (( limit < UPLOAD_MIN_TIMEOUT_SECONDS )); then
      limit="${UPLOAD_MIN_TIMEOUT_SECONDS}"
    fi
    # TERM 之后再给 10 秒宽限，scp 不理会 TERM 时由 timeout 补一刀 KILL。
    cmd=(timeout --signal=TERM --kill-after=10s "${limit}" "${cmd[@]}")
  fi

  started="${SECONDS}"
  "${cmd[@]}" || rc=$?
  elapsed=$(( SECONDS - started ))
  if (( elapsed < 1 )); then
    elapsed=1
  fi

  if (( rc == 0 )); then
    rate=$(( bytes / elapsed / 1024 ))
    echo "[ship] (${APP_NAME}) uploaded ${what} in ${elapsed}s (${rate} KiB/s)"
    return 0
  fi

  # 124 = timeout 发出 TERM 后超时；137 = TERM 无效、由 --kill-after 补的 KILL。
  if (( rc == 124 || rc == 137 )); then
    echo "[ship] (${APP_NAME}) ERROR: 上传超时：$(human_size "${bytes}") 在 ${limit}s 内未传完，实测吞吐低于下限 ${UPLOAD_MIN_KIBPS} KiB/s。" >&2
    echo "[ship] (${APP_NAME}) 目标 ${SSH_USER}@${SSH_HOST}:${SSH_PORT}。这是链路问题，不是构建问题。" >&2
    echo "[ship] (${APP_NAME}) 远端未被改动：jar 未替换、服务未重启，仍在跑上一版本（只会留下一个未传完的 ${dest}，下次部署即覆盖）。" >&2
    echo "[ship] (${APP_NAME}) 定位：从本机 scp 一个 10 MB 测试文件到该主机计时，并用 mtr/ping 看 RTT 与丢包。" >&2
    echo "[ship] (${APP_NAME}) 确属慢速链路时，可在主机清单为该端点设 <KEY>_UPLOAD_MIN_KIBPS 放宽下限（设 0 关闭守护）。" >&2
    return 1
  fi
  echo "[ship] (${APP_NAME}) ERROR: 上传失败（scp 退出码 ${rc}），耗时 ${elapsed}s" >&2
  return "${rc}"
}

# 远端目录做 shell 安全转义（printf %q），避免路径含空格 / 引号时被远端 shell 误解析。
q_app_dir="$(printf '%q' "${APP_DIR}")"

echo "[ship] (${APP_NAME}) ensure remote dir ${APP_DIR}"
remote "mkdir -p ${q_app_dir}"

upload "${JAR_LOCAL}" "${APP_DIR}/app.jar.incoming" jar

upload "${REMOTE_DEPLOY_SRC}" "${APP_DIR}/remote-deploy.sh" remote-deploy.sh

# 逐值转义每个环境变量后再拼进远端命令，任意值含空格 / 引号都不会破坏解析或注入。
remote_cmd="$(printf 'APP_NAME=%q APP_DIR=%q SERVICE_NAME=%q APP_PORT=%q HEALTH_PATH=%q HEALTH_SCHEME=%q RESTART_CMD=%q HEALTH_TIMEOUT_SECONDS=%q APP_PROFILE=%q BUILD_ID=%q bash %q' \
  "${APP_NAME}" "${APP_DIR}" "${SERVICE_NAME}" "${APP_PORT}" "${HEALTH_PATH}" "${HEALTH_SCHEME}" "${RESTART_CMD}" "${HEALTH_TIMEOUT_SECONDS}" "${APP_PROFILE}" "${BUILD_ID}" "${APP_DIR}/remote-deploy.sh")"

# 这一步刻意**不设**墙钟上限：远端正在停服务、换 jar、重启、等健康检查（最长 HEALTH_TIMEOUT_SECONDS，
# 失败还要再走一轮回滚）。从客户端把它拦腰砍断，会让目标机停在一个谁也说不清的中间态 —— 比多等一会儿
# 危险得多。链路真死掉的情况由上面的 ServerAliveInterval/CountMax 兜底，约 60 秒内即断开报错。
echo "[ship] (${APP_NAME}) run remote deploy"
remote "${remote_cmd}"

echo "[ship] (${APP_NAME}) done"
