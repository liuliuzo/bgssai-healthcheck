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
if [[ -n "${SSH_KNOWN_HOSTS:-}" ]]; then
  KNOWN_HOSTS_FILE="$(mktemp)"
  printf '%s\n' "${SSH_KNOWN_HOSTS}" > "${KNOWN_HOSTS_FILE}"
  COMMON_OPTS=(-o StrictHostKeyChecking=yes -o "UserKnownHostsFile=${KNOWN_HOSTS_FILE}" -o ConnectTimeout=15)
  echo "[ship] (${APP_NAME}) host-key pinning enabled (SSH_KNOWN_HOSTS set)"
else
  COMMON_OPTS=(-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=15)
  echo "[ship] (${APP_NAME}) WARNING: host-key verification disabled; set SSH_KNOWN_HOSTS to pin the target host key" >&2
fi
SSH_OPTS=("${COMMON_OPTS[@]}" -p "${SSH_PORT}")
SCP_OPTS=("${COMMON_OPTS[@]}" -P "${SSH_PORT}")

remote() {
  sshpass -e ssh "${SSH_OPTS[@]}" "${SSH_USER}@${SSH_HOST}" "$@"
}

# 远端目录做 shell 安全转义（printf %q），避免路径含空格 / 引号时被远端 shell 误解析。
q_app_dir="$(printf '%q' "${APP_DIR}")"

echo "[ship] (${APP_NAME}) ensure remote dir ${APP_DIR}"
remote "mkdir -p ${q_app_dir}"

echo "[ship] (${APP_NAME}) upload jar -> ${APP_DIR}/app.jar.incoming"
sshpass -e scp "${SCP_OPTS[@]}" "${JAR_LOCAL}" "${SSH_USER}@${SSH_HOST}:${APP_DIR}/app.jar.incoming"

echo "[ship] (${APP_NAME}) upload remote-deploy.sh"
sshpass -e scp "${SCP_OPTS[@]}" "${REMOTE_DEPLOY_SRC}" "${SSH_USER}@${SSH_HOST}:${APP_DIR}/remote-deploy.sh"

# 逐值转义每个环境变量后再拼进远端命令，任意值含空格 / 引号都不会破坏解析或注入。
remote_cmd="$(printf 'APP_NAME=%q APP_DIR=%q SERVICE_NAME=%q APP_PORT=%q HEALTH_PATH=%q HEALTH_SCHEME=%q RESTART_CMD=%q HEALTH_TIMEOUT_SECONDS=%q APP_PROFILE=%q BUILD_ID=%q bash %q' \
  "${APP_NAME}" "${APP_DIR}" "${SERVICE_NAME}" "${APP_PORT}" "${HEALTH_PATH}" "${HEALTH_SCHEME}" "${RESTART_CMD}" "${HEALTH_TIMEOUT_SECONDS}" "${APP_PROFILE}" "${BUILD_ID}" "${APP_DIR}/remote-deploy.sh")"

echo "[ship] (${APP_NAME}) run remote deploy"
remote "${remote_cmd}"

echo "[ship] (${APP_NAME}) done"
