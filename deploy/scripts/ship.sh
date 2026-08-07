#!/usr/bin/env bash
# 运行位置：Jenkins 控制器 / agent（非目标服务器）。
# 职责：把新 jar 与远程部署脚本推送到目标服务器（rsync 增量续传，无 rsync 时回落 scp），并触发远程部署。
# 所有主机 / 账号 / 密码均来自环境变量（由流水线从 Jenkins 凭据注入），本脚本不含任何明文凭据。
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
#   UPLOAD_MIN_KIBPS 上传吞吐下限（KiB/s），默认 64；每次上传的墙钟上限按「文件大小 / 本下限」推导。
#                    设 0 关闭该守护（不推荐，仅供确属慢速链路时的应急放行）。
#   UPLOAD_MIN_TIMEOUT_SECONDS
#                    上传墙钟上限的下界（秒），默认 120。避免小文件被推导出过短的上限。
#   UPLOAD_MAX_TIMEOUT_SECONDS
#                    上传墙钟上限的上界（秒），默认 450。大 jar 在慢链路上也不会一次占满重试预算，
#                    留出第二次尝试的机会（rsync 通道下重试从断点继续，不从零重来）。
#   NET_MAX_ATTEMPTS 「远端尚未被改动」的网络准备步骤（建目录 + 两次上传）的最大尝试次数，默认 3。
#                    设 1 关闭重试。真正触发部署的那一步永不重试，理由见 retry_transient 注释。
#   NET_RETRY_DELAY_SECONDS
#                    两次尝试之间的等待秒数，默认 15。
#   NET_RETRY_BUDGET_SECONDS
#                    单个端点花在重试上的累计墙钟预算（秒），默认 900；超预算即放弃，
#                    避免链路整体劣化时把 Job 的总时长预算耗光（见 UPLOAD_MIN_KIBPS 注释里的事故）。
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
# rsync 通过 -e 拉起 ssh，sshpass 也在那条命令串里；密码必须在环境中对子进程可见。
export SSHPASS
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
#
# 下限为什么从 256 降到 64：上面那条经验来自 dev 全量部署，19 个端点实测 503 ~ 1247 KiB/s，
# 256 的下限还留着 2 ~ 5 倍余量。生产不是这个样子 —— 控制器在华为云境外，9 个产品里 7 个的
# 生产机在境内，上传全程跨境。2026-08-06 的 prod-all-deploy 实测：境外目标（geo-global / saas）
# 303 ~ 654 KiB/s，境内目标**全部**低于 256 KiB/s 而被这道守护判超时，8 个失败产品里 6 个
# 是这一个原因。下限定得比链路实际能力还高，等于让每次生产部署对境内端点必然失败。
# 64 只管「多慢算坏」，「最多拖多久」交给下面的 UPLOAD_MAX_TIMEOUT_SECONDS 与
# NET_RETRY_BUDGET_SECONDS —— 两件事分开之后，既不会因链路慢就误判故障，也保住了原来的时长保护。
UPLOAD_MIN_KIBPS="${UPLOAD_MIN_KIBPS:-64}"
UPLOAD_MIN_TIMEOUT_SECONDS="${UPLOAD_MIN_TIMEOUT_SECONDS:-120}"
UPLOAD_MAX_TIMEOUT_SECONDS="${UPLOAD_MAX_TIMEOUT_SECONDS:-450}"
if ! [[ "${UPLOAD_MIN_KIBPS}" =~ ^[0-9]+$ ]]; then
  echo "[ship] UPLOAD_MIN_KIBPS 必须是非负整数（当前 ${UPLOAD_MIN_KIBPS}）" >&2
  exit 1
fi
if ! [[ "${UPLOAD_MIN_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "[ship] UPLOAD_MIN_TIMEOUT_SECONDS 必须是正整数（当前 ${UPLOAD_MIN_TIMEOUT_SECONDS}）" >&2
  exit 1
fi
if ! [[ "${UPLOAD_MAX_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "[ship] UPLOAD_MAX_TIMEOUT_SECONDS 必须是正整数（当前 ${UPLOAD_MAX_TIMEOUT_SECONDS}）" >&2
  exit 1
fi
# 上界配得比下界还小属于配置笔误：按下界执行，不静默取一个比下界更短的上限。
if (( UPLOAD_MAX_TIMEOUT_SECONDS < UPLOAD_MIN_TIMEOUT_SECONDS )); then
  UPLOAD_MAX_TIMEOUT_SECONDS="${UPLOAD_MIN_TIMEOUT_SECONDS}"
fi

# 跨境链路重试：19 个目标端点分布在华为云境外、腾讯云与境内，实测单次上传吞吐集中在
# 500 KiB/s ~ 1.2 MiB/s，只有下限的 2 ~ 5 倍。这条链路上「连上后突然静默」「建连超时」
# 「吞吐塌到下限以下」都是分钟级的随机事件 —— 一轮全量部署要连续做 19 次几十到上百 MB 的
# 传输，撞上一次几乎是必然。此前任何一次都会让该端点在本轮里彻底出局，而失败点全部落在
# 远端尚未被改动的准备阶段，重来一次即可，没有任何副作用。
NET_MAX_ATTEMPTS="${NET_MAX_ATTEMPTS:-3}"
NET_RETRY_DELAY_SECONDS="${NET_RETRY_DELAY_SECONDS:-15}"
NET_RETRY_BUDGET_SECONDS="${NET_RETRY_BUDGET_SECONDS:-900}"
if ! [[ "${NET_MAX_ATTEMPTS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "[ship] NET_MAX_ATTEMPTS 必须是正整数（当前 ${NET_MAX_ATTEMPTS}）" >&2
  exit 1
fi
if ! [[ "${NET_RETRY_DELAY_SECONDS}" =~ ^[0-9]+$ ]]; then
  echo "[ship] NET_RETRY_DELAY_SECONDS 必须是非负整数（当前 ${NET_RETRY_DELAY_SECONDS}）" >&2
  exit 1
fi
if ! [[ "${NET_RETRY_BUDGET_SECONDS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "[ship] NET_RETRY_BUDGET_SECONDS 必须是正整数（当前 ${NET_RETRY_BUDGET_SECONDS}）" >&2
  exit 1
fi
# retry_transient 每次尝试前把「本端点还剩多少重试预算」写进这里，upload 据此收紧自己的墙钟上限。
# 不这样做的话，预算只在一次尝试**结束后**才被检查，最后一次尝试可以整段超出预算 —— 对
# 116 MiB 这种大 jar 就是多拖 450 秒。初值取满预算，供不经 retry_transient 的直接调用使用。
RETRY_BUDGET_REMAINING="${NET_RETRY_BUDGET_SECONDS}"

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
WORK_DIR="$(mktemp -d)"
# 用 if 而非 `[[ ... ]] && rm`：作为 EXIT trap 最后一条命令，`&&` 短路在条件为假时返回 1，
# 会把整个脚本退出码顶成 1（即便部署已成功）——用 if 保证清理不改动脚本退出码。
cleanup() {
  if [[ -n "${KNOWN_HOSTS_FILE}" && -f "${KNOWN_HOSTS_FILE}" ]]; then
    rm -f "${KNOWN_HOSTS_FILE}"
  fi
  if [[ -n "${WORK_DIR}" && -d "${WORK_DIR}" ]]; then
    rm -rf "${WORK_DIR}"
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

# rsync 的 -e 需要一整条 remote shell 命令串。sshpass 必须放在这条串里、而不是包在 rsync 外面：
# 它靠伪终端喂密码，只对自己的直接子进程有效；包在 rsync 外面时 ssh 是孙子进程，拿不到密码。
ssh_cmd_string() {
  local out="sshpass -e ssh" opt
  for opt in "${SSH_OPTS[@]}"; do
    out+=" $(printf '%q' "${opt}")"
  done
  printf '%s' "${out}"
}

# transfer_once <本地文件> <远端路径> <限时秒数，0 表示不限> <日志文件>
transfer_once() {
  local src="$1" dest="$2" limit="$3" log_file="$4"
  local -a cmd
  if [[ "${TRANSPORT}" == "rsync" ]]; then
    # --inplace + --partial：直接写目标文件并保留已传部分。retry_transient 重来一次时，rsync 的
    #                 分块校验会认出已经落盘的那一段，只补剩下的 —— scp 做不到这件事，它每次
    #                 都从零开始，慢链路上重试等于把同一段字节再传一遍。
    # --no-whole-file：显式要求走增量算法，不因任何启发式退化成整文件重传。
    # --ignore-times：关掉「大小 + mtime 相同就跳过」的快速判断。目标文件是上一版本的副本，
    #                 绝不能因为凑巧同大小同时间戳被判成「已是最新」跳过，那等于部署了旧 jar。
    # --info=progress2（本机 rsync 支持时）：把「到底传了多少」留在日志里 —— 超时被 kill 时
    #                 这是唯一的实测依据。
    cmd=(rsync --inplace --partial --no-whole-file --ignore-times "${RSYNC_PROGRESS_OPTS[@]}"
      -e "$(ssh_cmd_string)" "${src}" "${SSH_USER}@${SSH_HOST}:${dest}")
  else
    cmd=(sshpass -e scp "${SCP_OPTS[@]}" "${src}" "${SSH_USER}@${SSH_HOST}:${dest}")
  fi
  if (( limit > 0 )); then
    # TERM 之后再给 10 秒宽限，传输进程不理会 TERM 时由 timeout 补一刀 KILL。
    cmd=(timeout --signal=TERM --kill-after=10s "${limit}" "${cmd[@]}")
  fi
  "${cmd[@]}" >"${log_file}" 2>&1
}

# 传输被 kill 时最该知道的是「到底走了多少字节」—— 只说「低于下限 N」没法回答「那该调到多少」。
# rsync 的进度用 \r 原地刷新，落到文件里是一长行，取最后一条即可；scp 没有可解析的进度，
# 改用远端文件大小反推（只在失败路径上多开这一次 SSH，成功路径不付这个代价）。
report_transfer_progress() {
  local log_file="$1" dest="$2" elapsed="$3" last="" landed="" rate=""
  if [[ "${TRANSPORT}" == "rsync" ]]; then
    [[ -s "${log_file}" ]] || return 0
    last="$(tr '\r' '\n' < "${log_file}" | grep -E '[0-9]+%' | tail -n 1 | sed 's/^ *//;s/  */ /g' || true)"
    if [[ -n "${last}" ]]; then
      echo "[ship] (${APP_NAME}) 中断前进度: ${last}"
    fi
    return 0
  fi
  landed="$(remote "stat -c %s $(printf '%q' "${dest}") 2>/dev/null || echo 0" 2>/dev/null || echo '')"
  if [[ "${landed}" =~ ^[0-9]+$ ]] && (( landed > 0 )) && (( elapsed > 0 )); then
    rate=$(( landed / elapsed / 1024 ))
    echo "[ship] (${APP_NAME}) 中断前进度: 远端已落盘 $(human_size "${landed}")，${elapsed}s，实测约 ${rate} KiB/s"
  fi
}

dump_transfer_log() {
  local log_file="$1"
  [[ -s "${log_file}" ]] || return 0
  tr '\r' '\n' < "${log_file}" | grep -vE '[0-9]+%' | grep -v '^[[:space:]]*$' | tail -n 20 \
    | sed "s/^/[ship] (${APP_NAME}) ${TRANSPORT}: /" >&2 || true
}

# upload <本地文件> <远端绝对路径> <用途说明>
# 带吞吐守护地上传单个文件。成功时打印实测吞吐 —— 链路劣化是渐进的，把每次的实测值留在构建日志里，
# 下次再出问题可以直接对比历史，不必靠人工去减两行时间戳。
# 本函数只做一次尝试；重试由外面的 retry_transient 负责（rsync 通道下重试从断点继续）。
upload() {
  local src="$1" dest="$2" what="$3"
  local bytes limit started elapsed rate rc=0
  local log_file="${WORK_DIR}/upload.log"
  bytes="$(wc -c < "${src}")"
  echo "[ship] (${APP_NAME}) upload ${what} -> ${dest} ($(human_size "${bytes}")，transport=${TRANSPORT})"

  limit=0
  if [[ "${HAVE_TIMEOUT}" == "true" ]] && (( UPLOAD_MIN_KIBPS > 0 )); then
    limit=$(( bytes / (UPLOAD_MIN_KIBPS * 1024) + 1 ))
    # 用 if 而非 `(( ... )) && limit=...`：条件为假时 `(( ))` 返回 1，在 set -e 下会直接终止脚本。
    if (( limit < UPLOAD_MIN_TIMEOUT_SECONDS )); then
      limit="${UPLOAD_MIN_TIMEOUT_SECONDS}"
    fi
    if (( limit > UPLOAD_MAX_TIMEOUT_SECONDS )); then
      limit="${UPLOAD_MAX_TIMEOUT_SECONDS}"
    fi
    # 再收一道：本次尝试不许超出该端点剩余的重试预算，否则预算只能事后发现已经花超了。
    if (( RETRY_BUDGET_REMAINING > 0 )) && (( limit > RETRY_BUDGET_REMAINING )); then
      limit="${RETRY_BUDGET_REMAINING}"
    fi
  fi

  started="${SECONDS}"
  transfer_once "${src}" "${dest}" "${limit}" "${log_file}" || rc=$?
  elapsed=$(( SECONDS - started ))
  if (( elapsed < 1 )); then
    elapsed=1
  fi

  if (( rc == 0 )); then
    rate=$(( bytes / elapsed / 1024 ))
    # rsync 走增量时过线字节远小于文件本身，这里的数字是「等效吞吐」而非线路实测值。
    echo "[ship] (${APP_NAME}) uploaded ${what} in ${elapsed}s (等效 ${rate} KiB/s)"
    return 0
  fi

  # 124 = timeout 发出 TERM 后超时；137 = TERM 无效、由 --kill-after 补的 KILL。
  if (( rc == 124 || rc == 137 )); then
    echo "[ship] (${APP_NAME}) ERROR: 上传超时：$(human_size "${bytes}") 在 ${limit}s 内未传完，实测吞吐低于下限 ${UPLOAD_MIN_KIBPS} KiB/s。" >&2
    report_transfer_progress "${log_file}" "${dest}" "${elapsed}"
    echo "[ship] (${APP_NAME}) 目标 ${SSH_USER}@${SSH_HOST}:${SSH_PORT}。这是链路问题，不是构建问题。" >&2
    echo "[ship] (${APP_NAME}) 远端未被改动：jar 未替换、服务未重启，仍在跑上一版本（只会留下一个未传完的 ${dest}）。" >&2
    if [[ "${TRANSPORT}" == "rsync" ]]; then
      echo "[ship] (${APP_NAME}) 已传部分保留在远端：本轮重试与之后人工重跑单产品 Job 都从断点继续，不从零重来。" >&2
    fi
    echo "[ship] (${APP_NAME}) 定位：从本机 scp 一个 10 MB 测试文件到该主机计时，并用 mtr/ping 看 RTT 与丢包。" >&2
    echo "[ship] (${APP_NAME}) 确属慢速链路时，可在主机清单为该端点设 <KEY>_UPLOAD_MIN_KIBPS 放宽下限（设 0 关闭守护）。" >&2
    return 1
  fi
  echo "[ship] (${APP_NAME}) ERROR: 上传失败（${TRANSPORT} 退出码 ${rc}），耗时 ${elapsed}s" >&2
  report_transfer_progress "${log_file}" "${dest}" "${elapsed}"
  dump_transfer_log "${log_file}"
  return "${rc}"
}

# retry_transient <步骤说明> <命令...>
#
# 只包住「远端尚未被改动」的三个准备步骤：建目录、传 jar、传 remote-deploy.sh。三步都幂等 ——
# mkdir -p 可重复执行；两次 scp 的落点是暂存文件 app.jar.incoming 与尚未被调用的
# remote-deploy.sh，重传只是覆盖一份还没有生效的文件，目标服务全程在跑上一版本。
# 失败在这三步时，远端状态与本次部署开始前完全一致，重来一次没有任何副作用。
#
# 刻意**不**包住后面的 run remote deploy：那一步会停服务、换 jar、重启、等健康检查，
# 自动重试它就是「自动重新部署」，与仓库约定（部署失败不自动重部署，由人工用单产品 Job 重跑）
# 直接冲突。远端一旦开始变更，失败即上报，由 remote-deploy.sh 自己的回滚兜底。
retry_transient() {
  local what="$1"
  shift
  local attempt=1 rc=0 loop_started="${SECONDS}" spent=0
  while :; do
    # 把剩余预算交给被调用者（upload 用它收紧本次尝试的墙钟上限），
    # 使总耗时真正被 NET_RETRY_BUDGET_SECONDS 封住，而不是只在事后被发现已超。
    RETRY_BUDGET_REMAINING=$(( NET_RETRY_BUDGET_SECONDS - (SECONDS - loop_started) ))
    # 预算已见底就不再开始下一次尝试。这一段必须在下面的 rc=0 **之前**：break 出去时
    # rc 要保留上一次尝试的失败码，否则函数会以 0 返回，把一次失败的上传报成成功。
    if (( attempt > 1 )) && (( RETRY_BUDGET_REMAINING <= 0 )); then
      echo "[ship] (${APP_NAME}) ERROR: ${what} 已尝试 $(( attempt - 1 )) 次，重试预算 ${NET_RETRY_BUDGET_SECONDS}s 已耗尽，放弃本端点。" >&2
      break
    fi
    rc=0
    "$@" || rc=$?
    if (( rc == 0 )); then
      RETRY_BUDGET_REMAINING="${NET_RETRY_BUDGET_SECONDS}"
      return 0
    fi
    spent=$(( SECONDS - loop_started ))
    if (( attempt >= NET_MAX_ATTEMPTS )); then
      echo "[ship] (${APP_NAME}) ERROR: ${what} 连续 ${attempt} 次失败（累计 ${spent}s），放弃本端点。" >&2
      break
    fi
    if (( spent >= NET_RETRY_BUDGET_SECONDS )); then
      echo "[ship] (${APP_NAME}) ERROR: ${what} 已尝试 ${attempt} 次、累计 ${spent}s，超出重试预算 ${NET_RETRY_BUDGET_SECONDS}s，放弃本端点。" >&2
      break
    fi
    echo "[ship] (${APP_NAME}) WARNING: ${what} 第 ${attempt}/${NET_MAX_ATTEMPTS} 次失败（退出码 ${rc}，累计 ${spent}s），${NET_RETRY_DELAY_SECONDS}s 后重试" >&2
    sleep "${NET_RETRY_DELAY_SECONDS}"
    attempt=$(( attempt + 1 ))
  done
  echo "[ship] (${APP_NAME}) 远端未被改动：jar 未替换、服务未重启，仍在跑上一版本。" >&2
  return "${rc}"
}

# 远端目录做 shell 安全转义（printf %q），避免路径含空格 / 引号时被远端 shell 误解析。
q_app_dir="$(printf '%q' "${APP_DIR}")"

STAGED_REMOTE="${APP_DIR}/app.jar.incoming"
q_staged_remote="$(printf '%q' "${STAGED_REMOTE}")"
q_live_remote="$(printf '%q' "${APP_DIR}/app.jar")"

# 一次 SSH 往返做完三件事，跨境线路上每省一次建连就是省几秒到几十秒：
#   1. 建部署目录；
#   2. 探远端有没有 rsync（决定走增量续传还是 scp 全量）；
#   3. 远端还没有暂存文件、但有正在跑的 app.jar 时，就地 cp 一份当增量基线 —— 这一步在远端本地
#      完成，不占跨境带宽，却能让 rsync 只传两次构建之间真正变化的字节：Spring Boot 重打包的
#      fat jar 把依赖 jar 以 STORED（不压缩）方式嵌在 BOOT-INF/lib，依赖不变的部分字节完全相同
#      且偏移稳定，重复部署实际只需传变化的那几 MiB。
#      走 scp 时这份基线也无害：scp 以 O_TRUNC 打开目标，整体覆盖。
# 整个探测本身幂等，故与建目录一样包在 retry_transient 里。
probe_remote() {
  remote_probe="$(remote "mkdir -p ${q_app_dir} || exit 1
if command -v rsync >/dev/null 2>&1; then echo have_rsync; fi
if [ -f ${q_live_remote} ] && [ ! -f ${q_staged_remote} ]; then cp -f ${q_live_remote} ${q_staged_remote} 2>/dev/null || true; fi
exit 0")"
}

remote_probe=""
echo "[ship] (${APP_NAME}) ensure remote dir ${APP_DIR}（并探测远端 rsync）"
retry_transient '建远端目录与能力探测' probe_remote

TRANSPORT=scp
# --info=progress2 是 rsync 3.1 才有的选项；老版本上它会被当作未知参数直接报错，把一条本可以
# 正常完成的上传变成硬失败。探一次本机 rsync 支不支持，不支持就只少一行进度诊断。
RSYNC_PROGRESS_OPTS=()
if [[ "${remote_probe}" == *have_rsync* ]] && command -v rsync >/dev/null 2>&1; then
  TRANSPORT=rsync
  if rsync --help 2>&1 | grep -q -- '--info='; then
    RSYNC_PROGRESS_OPTS=(--info=progress2)
  else
    echo "[ship] (${APP_NAME}) NOTE: 控制器 rsync 过旧（无 --info），本次不打印传输进度，传输本身不受影响。" >&2
  fi
elif [[ "${remote_probe}" != *have_rsync* ]]; then
  echo "[ship] (${APP_NAME}) NOTE: 目标机没有 rsync，回落到 scp 全量传输（重试即从零重传）。" >&2
  echo "[ship] (${APP_NAME}) 跨境链路建议在目标机装上 rsync（apt-get install -y rsync / yum install -y rsync），" >&2
  echo "[ship] (${APP_NAME}) 之后每次部署只传变化的字节、重试也从断点继续。部署流程本身不改动目标机软件包。" >&2
else
  echo "[ship] (${APP_NAME}) NOTE: 控制器没有 rsync，回落到 scp 全量传输（在控制器上装 rsync 即可启用增量续传）。" >&2
fi

retry_transient '上传 jar' upload "${JAR_LOCAL}" "${STAGED_REMOTE}" jar

retry_transient '上传 remote-deploy.sh' upload "${REMOTE_DEPLOY_SRC}" "${APP_DIR}/remote-deploy.sh" remote-deploy.sh

# 完整性校验的一半在这里、一半在远端：本地算出 sha256 一并传过去，remote-deploy.sh 在**替换 jar
# 之前**比对暂存文件。走 --inplace 续传时暂存文件会被多次尝试反复改写，必须有一道明确的关口保证
# 「拿去替换的就是本次构建的那个 jar」，而不是某次半截传输留下的混合体。
# 控制器没有 sha256sum 时留空，远端据此跳过比对（只告警，不拒绝部署）。
STAGED_SHA256=""
if command -v sha256sum >/dev/null 2>&1; then
  STAGED_SHA256="$(sha256sum "${JAR_LOCAL}" | cut -d' ' -f1)"
else
  echo "[ship] (${APP_NAME}) WARNING: 控制器无 sha256sum，跳过 jar 完整性校验" >&2
fi

# 逐值转义每个环境变量后再拼进远端命令，任意值含空格 / 引号都不会破坏解析或注入。
remote_cmd="$(printf 'APP_NAME=%q APP_DIR=%q SERVICE_NAME=%q APP_PORT=%q HEALTH_PATH=%q HEALTH_SCHEME=%q RESTART_CMD=%q HEALTH_TIMEOUT_SECONDS=%q APP_PROFILE=%q BUILD_ID=%q STAGED_SHA256=%q bash %q' \
  "${APP_NAME}" "${APP_DIR}" "${SERVICE_NAME}" "${APP_PORT}" "${HEALTH_PATH}" "${HEALTH_SCHEME}" "${RESTART_CMD}" "${HEALTH_TIMEOUT_SECONDS}" "${APP_PROFILE}" "${BUILD_ID}" "${STAGED_SHA256}" "${APP_DIR}/remote-deploy.sh")"

# 这一步刻意**不设**墙钟上限：远端正在停服务、换 jar、重启、等健康检查（最长 HEALTH_TIMEOUT_SECONDS，
# 失败还要再走一轮回滚）。从客户端把它拦腰砍断，会让目标机停在一个谁也说不清的中间态 —— 比多等一会儿
# 危险得多。链路真死掉的情况由上面的 ServerAliveInterval/CountMax 兜底，约 60 秒内即断开报错。
echo "[ship] (${APP_NAME}) run remote deploy"
remote "${remote_cmd}"

echo "[ship] (${APP_NAME}) done"
