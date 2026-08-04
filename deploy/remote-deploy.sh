#!/usr/bin/env bash
# 运行位置：目标服务器（由 deploy/scripts/ship.sh 上传并通过 SSH 触发执行）。
# 职责：原子替换 jar -> 重启服务 -> 健康检查 -> 失败自动回滚上一个可用版本。
# 全部参数经环境变量传入，脚本内不硬编码任何主机 / 端口 / 凭据。
#
# 必需环境变量：APP_NAME APP_DIR SERVICE_NAME APP_PORT BUILD_ID
# 可选环境变量：
#   HEALTH_PATH             健康检查路径，默认 /bgssai/health/readiness（就绪探针，Standards §13.7）
#   RESTART_CMD             自定义重启命令。留空（默认）则进入「systemd 自托管」模式：
#                           自动创建并 enable 名为 <SERVICE_NAME> 的 systemd 服务（若不存在），
#                           每次部署以 stop -> 清端口 -> start 的方式切换到新 jar；
#                           服务器重启后该服务也会自动拉起。填了自定义命令则改用你的命令、不自动建服务。
#   HEALTH_TIMEOUT_SECONDS  健康检查最长等待秒数，默认 180（冷启动带前端的 Spring Boot 4 需留足时间）
#   KEEP_RELEASES           releases 目录保留的历史版本数，默认 5
#   DIAG_LOG_LINES          健康检查失败时回显的应用日志行数，默认 80
#   START_LIMIT_INTERVAL_SECONDS / START_LIMIT_BURST
#                           systemd 重启限流窗口与窗口内最大启动次数，默认 1800 秒 / 10 次。
#                           超出即停在 failed（Result=start-limit-hit），不再无限重启。
#   CREDENTIALS_ENV_FILE    可选的环境变量覆盖文件路径，默认 /etc/bgssai/<SERVICE_NAME>.env。
#                           自托管单元固定以 EnvironmentFile=- 引用它（缺失即忽略）。平时不需要
#                           存在——配置的正常落点是随 jar 打包的 properties，详见 ensure_service
#                           上方说明。
set -euo pipefail

APP_NAME="${APP_NAME:?APP_NAME is required}"
APP_DIR="${APP_DIR:?APP_DIR is required}"
SERVICE_NAME="${SERVICE_NAME:?SERVICE_NAME is required}"
APP_PORT="${APP_PORT:?APP_PORT is required}"
BUILD_ID="${BUILD_ID:?BUILD_ID is required}"
HEALTH_PATH="${HEALTH_PATH:-/bgssai/health/readiness}"
# 健康检查协议：http（默认）或 https。应用自身跑 TLS（server.ssl.enabled=true）的部署须设 https，
# 探测走 curl -k（自签/内部证书，本地回环，跳过证书校验的安全影响可忽略）。
HEALTH_SCHEME="${HEALTH_SCHEME:-http}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-180}"
KEEP_RELEASES="${KEEP_RELEASES:-5}"
DIAG_LOG_LINES="${DIAG_LOG_LINES:-80}"
# 重启限流：见 ensure_service 里 StartLimitIntervalSec / StartLimitBurst 的说明。
# 取 1800 秒 10 次。窗口要**宽**才管用：限流的触发条件是「窗口内启动满 BURST 次」，
# 窗口太窄反而会让崩得慢的应用永远凑不满次数（systemd 默认 10 秒 5 次失效正是此理）。
# 1800/10 可覆盖单周期 180 秒以内的任何启动期崩溃——比实测的约 15 秒宽出一个数量级，
# 连「数据库不可达、连接超时才崩」这类慢崩也兜得住；同时留出 9 次自愈机会，
# 依赖短暂不可用（network-online 抢跑、数据库刚起）仍能自行恢复。
START_LIMIT_INTERVAL_SECONDS="${START_LIMIT_INTERVAL_SECONDS:-1800}"
START_LIMIT_BURST="${START_LIMIT_BURST:-10}"
# 启动档：由部署流水线按目标环境注入（dev/test/prod，与 GitHub Environment 同名）。
# 缺省 dev。三档配置（数据源 / JWT / MQTT / SSL 等）一律写死在各 application-<profile>.properties
# 里随 jar 打包，服务器上不需要放任何配置文件、不需要建本机库——application-secrets.properties
# 注入机制已停用（见各产品仓 deploy/README.md）。
APP_PROFILE="${APP_PROFILE:-dev}"
# 可选的环境变量覆盖文件（只落在目标服务器上）。见 ensure_service 的说明。
CREDENTIALS_ENV_FILE="${CREDENTIALS_ENV_FILE:-/etc/bgssai/${SERVICE_NAME}.env}"

# 未提供 RESTART_CMD -> systemd 自托管模式（自动建服务并托管）；提供了 -> 用自定义命令、不自动建服务。
if [[ -n "${RESTART_CMD:-}" ]]; then
  AUTO_PROVISION=false
else
  AUTO_PROVISION=true
  RESTART_CMD="systemctl restart ${SERVICE_NAME}"
fi

STAGED_JAR="${APP_DIR}/app.jar.incoming"
TARGET_JAR="${APP_DIR}/app.jar"
BACKUP_JAR="${APP_DIR}/app.jar.bak"

log() { printf '[remote-deploy][%s] %s\n' "${APP_NAME}" "$*"; }

if [[ ! -f "${STAGED_JAR}" ]]; then
  log "staged jar not found at ${STAGED_JAR}"
  exit 1
fi

mkdir -p "${APP_DIR}/releases"
cp -f "${STAGED_JAR}" "${APP_DIR}/releases/app-${BUILD_ID}.jar"

# 备份当前 jar，供健康检查失败时回滚。
if [[ -f "${TARGET_JAR}" ]]; then
  cp -f "${TARGET_JAR}" "${BACKUP_JAR}"
fi

# 原子替换：同一文件系统内 mv 为原子操作。
mv -f "${STAGED_JAR}" "${TARGET_JAR}"

# 历史清理：application-secrets.properties 注入机制已停用，单元里不再有 --spring.config.import。
# 旧机器上可能还躺着这个文件，此时它已不参与加载、纯属残留；只提示一次，不擅自删除用户数据。
if [[ -f "${APP_DIR}/application-secrets.properties" ]]; then
  log "note: stale ${APP_DIR}/application-secrets.properties found; it is no longer imported. Safe to: rm -f ${APP_DIR}/application-secrets.properties"
fi

# systemd 自托管：按 APP_PROFILE 生成服务单元，不存在则创建、已存在但内容变化（如 profile 从
# prod 改为 dev）则重写并 daemon-reload。以 --spring.profiles.active=${APP_PROFILE} 启动 jar，
# 普通配置取自 jar 内的 application-<profile>.properties。
#
# 单元固定带一行
#   EnvironmentFile=-/etc/bgssai/<SERVICE_NAME>.env
# 前缀 `-` 表示文件不存在就忽略，故对不用它的服务完全无副作用、也无需任何额外配置。
#
# 本产品线现行口径是**凭证明文写进 properties、随仓库与 jar 分发**（开发期取舍，见各产品仓
# docs/security 的「临时开发期明文凭证政策」与 deploy/README.md），所以这个文件**平时不需要
# 存在**，不是配置的正常落点。它只是一个逃生舱：想临时覆盖某一项而不重新构建时（例如轮换
# 密钥后先改文件再 systemctl restart）才用得上。
# 内容是 KEY=VALUE，键名用 Spring Boot 的 relaxed binding 形式，例如
# app.file.storage.obs-access-key 写作 APP_FILE_STORAGE_OBS_ACCESS_KEY；环境变量优先级高于
# jar 内 properties。
#
# 注意单元内容**不随该文件是否存在而变**：否则 ensure_service 的内容比对会在「有文件 / 无文件」
# 之间来回重写单元，每次部署都白白 daemon-reload 一次。
ensure_service() {
  command -v systemctl >/dev/null 2>&1 || { log "systemctl not found; cannot auto-provision service"; return 1; }
  local java_bin unit_path desired
  java_bin="$(command -v java || true)"
  if [[ -z "${java_bin}" ]]; then
    for candidate in /usr/bin/java /usr/lib/jvm/java/bin/java /usr/lib/jvm/jre/bin/java; do
      if [[ -x "${candidate}" ]]; then
        java_bin="${candidate}"
        break
      fi
    done
  fi
  if [[ -z "${java_bin}" || ! -x "${java_bin}" ]]; then
    log "ERROR: java executable not found on PATH and no usable fallback under /usr/bin/java or /usr/lib/jvm; refusing to write a broken systemd unit (would yield status=203/EXEC)"
    return 1
  fi
  unit_path="/etc/systemd/system/${SERVICE_NAME}.service"
  # 重启限流必须写在 [Unit] 段（systemd.unit(5)）。写进 [Service] 会被当作未知键忽略、
  # 只留一条 daemon-reload 警告，限流形同虚设——这正是本项配置容易配错的地方。
  #
  # 不设限流时，一个启动期必崩的应用会永远重启下去：systemd 默认的 10 秒 5 次限流对它无效，
  # 因为 Spring Boot 要跑到 context refresh 才失败（Tomcat 起、Druid 初始化完，约 10 秒），
  # 加 RestartSec=5 后一个周期约 15 秒 > 10 秒窗口，burst 计数每轮清零，条件永远不满足。
  # bgssai-vpn-admin 就这样以约 65% 单核的开销空转了三小时、重启 727 次，还把 journal 刷满，
  # 挤掉了 dev-collect-logs 那 24 小时窗口里的有用历史。
  desired="$(cat <<UNIT
[Unit]
Description=${APP_NAME} (auto-provisioned by deploy)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=${START_LIMIT_INTERVAL_SECONDS}
StartLimitBurst=${START_LIMIT_BURST}

[Service]
Type=simple
User=root
WorkingDirectory=${APP_DIR}
EnvironmentFile=-${CREDENTIALS_ENV_FILE}
ExecStart=${java_bin} -jar ${TARGET_JAR} --spring.profiles.active=${APP_PROFILE}
SuccessExitStatus=143
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT
)"
  # 单元已存在且内容一致：无需改动（避免无谓 daemon-reload）。
  if [[ -f "${unit_path}" ]] && printf '%s\n' "${desired}" | cmp -s - "${unit_path}"; then
    return 0
  fi
  if [[ -f "${unit_path}" ]]; then
    log "systemd unit ${SERVICE_NAME} changed; rewriting (profile=${APP_PROFILE}, java=${java_bin})"
  else
    log "systemd service ${SERVICE_NAME} not found; auto-creating and enabling it (profile=${APP_PROFILE}, java=${java_bin})"
  fi
  printf '%s\n' "${desired}" > "${unit_path}"
  systemctl daemon-reload
  systemctl enable "${SERVICE_NAME}" >/dev/null 2>&1 || true
}

# 列出当前监听 APP_PORT 的进程 PID（依次尝试 ss / lsof / fuser，取先可用者）。
port_listener_pids() {
  local pids=""
  if command -v ss >/dev/null 2>&1; then
    pids="$(ss -ltnp "sport = :${APP_PORT}" 2>/dev/null | grep -oP 'pid=\K[0-9]+' | sort -u || true)"
  fi
  if [[ -z "${pids}" ]] && command -v lsof >/dev/null 2>&1; then
    pids="$(lsof -tiTCP:"${APP_PORT}" -sTCP:LISTEN 2>/dev/null | sort -u || true)"
  fi
  if [[ -z "${pids}" ]] && command -v fuser >/dev/null 2>&1; then
    pids="$(fuser "${APP_PORT}/tcp" 2>/dev/null | tr -cs '0-9' '\n' | grep -E '^[0-9]+$' | sort -u || true)"
  fi
  echo ${pids}
}

# 判断某 PID 是否属于「本服务之外的另一个 BGSSAI 应用」。命中其一即判为外来：
#   1. systemd 单元名（读 /proc/<pid>/cgroup）是非本 SERVICE_NAME 的 *.service；
#   2. 命令行引用了 /opt/bgssai 下、但不属于本 APP_DIR 的另一部署目录。
# 是外来则打印其标识并返回 0；否则返回 1（视为本服务残留，可安全清理）。
foreign_owner_of_pid() {
  local pid="$1" unit="" cmd=""
  if [[ -r "/proc/${pid}/cgroup" ]]; then
    unit="$(grep -oE '[A-Za-z0-9@._-]+\.service' "/proc/${pid}/cgroup" 2>/dev/null | head -1 || true)"
  fi
  if [[ -n "${unit}" && "${unit}" != "${SERVICE_NAME}.service" ]]; then
    printf 'systemd unit %s (pid %s)' "${unit}" "${pid}"
    return 0
  fi
  if [[ -r "/proc/${pid}/cmdline" ]]; then
    cmd="$(tr '\0' ' ' < "/proc/${pid}/cmdline" 2>/dev/null || true)"
  fi
  if [[ "${cmd}" == *"/opt/bgssai/"* && "${cmd}" != *"${APP_DIR}"* ]]; then
    printf '%s (pid %s)' "$(printf '%s' "${cmd}" | grep -oE '/opt/bgssai/[^ ]+' | head -1 || true)" "${pid}"
    return 0
  fi
  return 1
}

# 清掉当前占用 APP_PORT 的「本服务」残留进程（如旧的 nohup java），让托管服务能干净地绑定端口，
# 也避免旧进程仍在监听导致健康检查误判成功。
# 加固（防一机双应用互踢）：清理前先探测端口占用者；若其中任一属于「另一个 BGSSAI 应用」
# （通常是本仓库某环境的 SERVER_ADMIN_HOST / SERVER_USER_HOST 被误配指到了本机），立即中止
# 部署、绝不杀掉对方应用——把过去的静默互踢变成一次响亮且安全的失败。返回非 0 即令部署中止。
#
# 中止时把占用者标识记进 FOREIGN_PORT_HOLDER，供回滚分支据此跳过必然失败的重试。
FOREIGN_PORT_HOLDER=""
free_port() {
  local pids pid owner
  pids="$(port_listener_pids)"
  [[ -n "${pids// /}" ]] || return 0
  for pid in ${pids}; do
    if owner="$(foreign_owner_of_pid "${pid}")"; then
      FOREIGN_PORT_HOLDER="${owner}"
      log "ABORT: port ${APP_PORT} is held by a DIFFERENT BGSSAI app: ${owner}"
      log "本机同时跑着两个 BGSSAI 应用。可能是主机清单把两端指到了同一台机器，也可能是对方应用被误部署到本机后残留至今。"
      log "请核对 ${APP_PROFILE} 档主机清单 bgssai-hosts.${APP_PROFILE}.env 里本产品的 <产品>_USER_HOST / <产品>_ADMIN_HOST；若清单本就不同，则是本机残留了对方的 systemd 服务，需人工停用。拒绝杀掉对方应用。"
      return 1
    fi
  done
  kill ${pids} 2>/dev/null || true
  sleep 2
  # 兜底：个别进程未响应 TERM 仍占端口时，对「本服务」残留再补一刀。
  pids="$(port_listener_pids)"
  if [[ -n "${pids// /}" ]]; then
    kill -9 ${pids} 2>/dev/null || true
    sleep 1
  fi
}

# 端口当前是否被「本服务之外的另一个 BGSSAI 应用」占用；是则打印其标识并返回 0，否则返回 1。
# 供健康检查确认「在 APP_PORT 上应答的确实是本服务」，而不是同机跑着的其它 BGSSAI 应用
# （否则回滚分支会把对方的健康误当成自己的、误报「回滚成功」）。
port_held_by_foreign() {
  local pids pid owner
  pids="$(port_listener_pids)"
  [[ -n "${pids// /}" ]] || return 1
  for pid in ${pids}; do
    if owner="$(foreign_owner_of_pid "${pid}")"; then
      printf '%s' "${owner}"
      return 0
    fi
  done
  return 1
}

restart_service() {
  if [[ "${AUTO_PROVISION}" == "true" ]]; then
    log "restart managed service ${SERVICE_NAME} (stop -> free port -> reset-failed -> start)"
    systemctl stop "${SERVICE_NAME}" 2>/dev/null || true
    free_port || return 1
    # 撞过 StartLimitBurst 的单元会停在 failed，此后 start 一律被拒（Start request repeated
    # too quickly），且 stop 不清计数。不先清就会让「上一次部署崩到限流」的机器再也部署不上去——
    # 连回滚那次 start 也一并被拒，等于把可恢复的故障变成必须登机器人工处理的故障。
    # reset-failed 同时清 failed 状态与限流计数；单元正常时是空操作，无条件执行即可。
    systemctl reset-failed "${SERVICE_NAME}" 2>/dev/null || true
    systemctl start "${SERVICE_NAME}"
  else
    log "restart via: ${RESTART_CMD}"
    bash -c "${RESTART_CMD}"
  fi
}

# 单次探测：按 HEALTH_SCHEME（http/https）向本机端口发起请求，返回 2xx/3xx（或 401/403/404，
# 视为已起来但根路径需鉴权/无映射）即判健康；400 / 5xx / 无响应一律判未健康。应用自身跑 TLS 的
# 部署必须设 HEALTH_SCHEME=https，否则以 http 探 TLS 端口会收到 400 而被误判未健康。
# 服务器无 curl 时回落为 bash 内建 /dev/tcp 的 TCP 连接探测（端口可连即视为已监听）；
# 绝不因缺少探测工具而直接判为健康（否则坏包会被误报成功且不回滚）。
LAST_PROBE_DETAIL="(未探测)"
probe_once() {
  if command -v curl >/dev/null 2>&1; then
    local code curl_opts=(-s -o /dev/null -w '%{http_code}' --max-time 5)
    [[ "${HEALTH_SCHEME}" == "https" ]] && curl_opts+=(-k)
    code="$(curl "${curl_opts[@]}" "${HEALTH_SCHEME}://127.0.0.1:${APP_PORT}${HEALTH_PATH}" || true)"
    LAST_PROBE_DETAIL="curl http_code=${code:-<无响应>}"
    case "${code}" in
      2[0-9][0-9]|3[0-9][0-9]|401|403|404) return 0 ;;
      *) return 1 ;;
    esac
  else
    if (exec 3<>"/dev/tcp/127.0.0.1/${APP_PORT}") 2>/dev/null; then
      LAST_PROBE_DETAIL="tcp connect ok（无 curl，仅探端口）"
      return 0
    fi
    LAST_PROBE_DETAIL="tcp connect 失败（无 curl，仅探端口）"
    return 1
  fi
}

# 健康检查失败后的现场取证。只读、不改变部署结果，唯一作用是把「定位原因」所需的信息打进
# 流水线控制台：仓库约定部署失败不得自动重跑、必须人工定位后再重跑，而此前失败时控制台只有
# 一行 health FAILED，操作者无从下手，只能登机器翻日志。
#
# 关键判据是「服务到底有没有活着」：
#   - 进程反复退出（Active: activating/failed、Restart 计数增长）-> 应用启动即崩，日志里有真因，
#     加长 HEALTH_TIMEOUT_SECONDS 无用；
#   - 进程活着但端口迟迟不监听 -> 冷启动确实慢，可对该机调大 <KEY>_HEALTH_TIMEOUT_SECONDS。
#
# 日志尾巴取自 systemd journal，即应用 stdout。按 Standards §6.1.6，SSH / 支付 / VPN 等敏感字段
# 从源头就不入日志，故此处回显不引入新的泄露面；行数按上限截断，避免刷屏。
dump_failure_diagnostics() {
  local phase="$1"
  log "----- 诊断信息（${phase}）begin -----"
  log "最后一次健康探测: ${LAST_PROBE_DETAIL} (${HEALTH_SCHEME}://127.0.0.1:${APP_PORT}${HEALTH_PATH})"

  if command -v systemctl >/dev/null 2>&1; then
    # systemctl 在异常环境下（如容器内无 systemd）会输出多行，压成一行避免日志错行。
    local is_active is_enabled
    is_active="$(systemctl is-active "${SERVICE_NAME}" 2>&1 | tr '\n' ' ' || true)"
    is_enabled="$(systemctl is-enabled "${SERVICE_NAME}" 2>&1 | tr '\n' ' ' || true)"
    log "systemd: is-active=${is_active% } is-enabled=${is_enabled% }"
    local props line
    props="$(systemctl show "${SERVICE_NAME}" \
      --property=ActiveState --property=SubState --property=Result \
      --property=NRestarts --property=ExecMainPID --property=ExecMainStatus \
      --no-pager 2>/dev/null || true)"
    if [[ -n "${props}" ]]; then
      while IFS= read -r line; do
        [[ -n "${line}" ]] && log "systemd: ${line}"
      done <<< "${props}"
    fi
  else
    log "systemd: systemctl 不可用，跳过服务状态"
  fi

  local listeners
  listeners="$(port_listener_pids)"
  if [[ -n "${listeners// /}" ]]; then
    local pid
    for pid in ${listeners}; do
      log "端口 ${APP_PORT} 占用: pid=${pid} cmd=$(tr '\0\n' '  ' < "/proc/${pid}/cmdline" 2>/dev/null | cut -c1-200 || true)"
    done
  else
    log "端口 ${APP_PORT} 当前无进程监听（服务没起来，或起来了但没绑上端口）"
  fi

  log "当前 jar: $(ls -l "${TARGET_JAR}" 2>/dev/null || echo '<不存在>')"

  if command -v journalctl >/dev/null 2>&1; then
    log "--- journalctl -u ${SERVICE_NAME} 最后 ${DIAG_LOG_LINES} 行 ---"
    journalctl -u "${SERVICE_NAME}" -n "${DIAG_LOG_LINES}" --no-pager 2>/dev/null \
      | sed "s/^/[remote-deploy][${APP_NAME}][journal] /" || true
  else
    log "journalctl 不可用，无法回显应用日志"
  fi

  log "----- 诊断信息（${phase}）end -----"
}

# systemd 单元是否已停在「终态失败」：ActiveState=failed 且 SubState=failed。
# 崩溃后还排着自动重启时，单元处于 activating / auto-restart，不会命中本条件；只有 systemd
# 决定不再拉起（撞满 StartLimitBurst，或 Restart= 不适用）才落到 failed/failed。
# 命中即等于「应用启动即崩、systemd 已彻底放弃」——此后不会再有进程去绑端口，继续空等到超时
# 只是白白拖长反馈（本次 geo-cn 生产部署即在第 109 秒撞限流停住，却仍空等满 180 秒）。
unit_terminally_failed() {
  [[ "${AUTO_PROVISION}" == "true" ]] || return 1
  command -v systemctl >/dev/null 2>&1 || return 1
  local props active sub
  props="$(systemctl show "${SERVICE_NAME}" --property=ActiveState --property=SubState --no-pager 2>/dev/null || true)"
  [[ -n "${props}" ]] || return 1
  active="$(printf '%s\n' "${props}" | sed -n 's/^ActiveState=//p')"
  sub="$(printf '%s\n' "${props}" | sed -n 's/^SubState=//p')"
  [[ "${active}" == "failed" && "${sub}" == "failed" ]]
}

# 判定终态失败需要连续命中的次数（每轮间隔 3 秒）。取 3 而非 1，是为了避开 systemd 状态机
# 切换瞬间的读数抖动：宁可多等约 9 秒，也不因一次误读把还会自愈的部署提前判死。
TERMINAL_FAILED_POLLS=3

health_check() {
  local deadline=$(( SECONDS + HEALTH_TIMEOUT_SECONDS ))
  local foreign dead_polls=0
  # 给重启一点落地时间，避免旧进程尚未退出时探到旧监听。
  sleep 2
  while (( SECONDS < deadline )); do
    # 端口被「另一个 BGSSAI 应用」占用时，探到的是对方而非本服务：立即判失败，
    # 避免把对方的健康误当成自己的（尤其回滚分支会据此误报「回滚成功」）。
    if foreign="$(port_held_by_foreign)"; then
      # 同样记进 FOREIGN_PORT_HOLDER：自定义 RESTART_CMD 模式不走 free_port，占用只会在此暴露。
      FOREIGN_PORT_HOLDER="${foreign}"
      log "health FAILED: port ${APP_PORT} held by a DIFFERENT BGSSAI app: ${foreign}; ${SERVICE_NAME} is NOT serving"
      return 1
    fi
    if probe_once; then
      log "health ok at ${HEALTH_SCHEME}://127.0.0.1:${APP_PORT}${HEALTH_PATH}"
      return 0
    fi
    if unit_terminally_failed; then
      dead_polls=$(( dead_polls + 1 ))
      if (( dead_polls >= TERMINAL_FAILED_POLLS )); then
        log "health FAILED: ${SERVICE_NAME} 已停在 failed 且 systemd 不再自动拉起（应用启动即崩），提前结束等待，不再空等满 ${HEALTH_TIMEOUT_SECONDS}s"
        return 1
      fi
    else
      dead_polls=0
    fi
    sleep 3
  done
  log "health FAILED at ${HEALTH_SCHEME}://127.0.0.1:${APP_PORT}${HEALTH_PATH} within ${HEALTH_TIMEOUT_SECONDS}s"
  return 1
}

# systemd 自托管模式：首次部署时自动创建并 enable 服务（开机自启）。
if [[ "${AUTO_PROVISION}" == "true" ]]; then
  ensure_service || log "auto-provision service failed; will still attempt restart"
fi

# 重启失败一律视为部署失败并进入回滚，绝不因端口仍被旧进程占用而误报成功。
deploy_ok=false
if restart_service; then
  if health_check; then
    deploy_ok=true
  else
    dump_failure_diagnostics "首次部署"
  fi
else
  log "restart command returned non-zero"
  dump_failure_diagnostics "首次部署（重启即失败）"
fi

if [[ "${deploy_ok}" == "true" ]]; then
  log "deploy succeeded (build ${BUILD_ID})"
  # 仅保留最近 KEEP_RELEASES 个历史版本；清理失败不影响已成功的部署结果。
  { find "${APP_DIR}/releases" -maxdepth 1 -type f -name 'app-*.jar' -printf '%T@ %p\n' 2>/dev/null \
      | sort -rn | tail -n +"$(( KEEP_RELEASES + 1 ))" | cut -d' ' -f2- | xargs -r rm -f; } || true
  exit 0
fi

log "deploy unhealthy, attempting rollback"
if [[ -f "${BACKUP_JAR}" ]]; then
  if ! cp -f "${BACKUP_JAR}" "${TARGET_JAR}"; then
    log "rollback copy failed"
  fi
  if [[ -n "${FOREIGN_PORT_HOLDER}" ]]; then
    # 端口被另一个 BGSSAI 应用占死时，重启必然再次 ABORT、健康检查必然再次失败，重跑一遍只会
    # 刷出第二份一模一样的 ABORT 与诊断，把真因埋进噪声，还会以「回滚后仍不健康」误导操作者
    # 去查应用本身。jar 已还原为上一版本，剩下的只能人工处理。
    log "rollback: jar 已还原为上一版本；端口 ${APP_PORT} 仍被 ${FOREIGN_PORT_HOLDER} 占用，跳过重启与健康检查（重试必然同样中止）"
    log "人工处理：登录本机 systemctl disable --now <上述单元名> 停用并禁止开机自启，确认端口 ${APP_PORT} 空出后，再用单产品 Job 手动重跑部署。"
  else
    if ! restart_service; then
      log "rollback restart command returned non-zero"
    fi
    if health_check; then
      log "rolled back to previous jar successfully; new build ${BUILD_ID} NOT live"
    else
      log "rollback still unhealthy - manual intervention required"
      dump_failure_diagnostics "回滚后仍不健康"
    fi
  fi
else
  log "no backup jar available to roll back to"
fi
exit 1
