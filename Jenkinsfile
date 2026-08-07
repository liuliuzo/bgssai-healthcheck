// bgssai-healthcheck 部署流水线（Jenkins）。
//
// 单实例巡检平台：无 user/admin、无前端。构建与 ship 逻辑在中央仓 bgssai-workflows 的
// Jenkins 共享库（vars/ + resources/），本文件只声明阶段。
//
// **目标环境由 Job 名前缀决定**（内部名 dev-healthcheck-deploy / prod-healthcheck-deploy，
// 界面显示名 bgssai-healthcheck deploy(dev) / deploy(prod)），不提供 environment 下拉框。
//
// 开发与生产共用同一台境内机（123.60.68.201）；dev 部署按运维约定直接覆盖同名服务。
//
// 触发方式：仅手动，点 Build 即执行。部署失败时不自动重试、不自动重新部署。
//
// 真正的推送与远端部署仍由本仓 deploy/scripts/ship.sh 与 deploy/remote-deploy.sh 承担，
// 这两个脚本须与中央仓 bgssai-workflows 的 deploy/ 逐字节一致。
//
// **开发环境的部署通道已改为「目标机自建」**：dev 不再在控制器上构建 fat jar、也不再把
// 几十到上百 MiB 的 jar 推过跨境链路，而是由目标机自己 git 拉最新代码、就地构建、就地部署。
// 生产环境不变，仍是「控制器构建 + 推 jar」。走哪条由共享库 bgssaiDeployEnd 按环境判断。
// 目标机因此需要具备构建工具链，一次性准备见中央仓 jenkins/install/provision-build-host.sh。

@Library('bgssai') _

// 本产品的构建口径只声明一次，两条部署通道共用：
//   dev  -> bgssaiRemoteBuild：目标机自己 git 拉代码、就地构建、就地部署
//   prod -> bgssaiShip：控制器构建 fat jar 后推到目标机
// 「哪个环境走哪条」只写在共享库 bgssaiDeployEnd 里一处，不在 10 个产品仓各写一遍 if。
//
// 刻意不写 def：声明式流水线里，pipeline 块外用 def 定义的变量只是脚本方法的局部量，
// steps / when 块未必看得见（视 Jenkins 版本而定，症状是运行期报 No such property）。
// 不加 def 则落进脚本 binding，各阶段一律可见。
PRODUCT = [
  product: 'healthcheck',
  layout: 'single',
  appPort: '8080',
  healthScheme: 'http',
]

pipeline {
  agent any

  options {
    disableConcurrentBuilds()
    timestamps()
    buildDiscarder(logRotator(numToKeepStr: '30'))
    // 90 而非原来的 30：dev 通道把构建挪到了目标机（本产品的 dev 与 prod 共用境内机
    // 123.60.68.201），首次部署要在那台机器上冷启 ~/.m2。这是上限不是开销：单次构建另由
    // remote-build.sh 的 BGSSAI_BUILD_TIMEOUT_SECONDS（默认 2700s）单独封顶。
    timeout(time: 90, unit: 'MINUTES')
  }

  stages {
    stage('Resolve environment') {
      steps {
        script {
          env.BGSSAI_ENV = bgssaiResolveEnvironment(action: '部署', requireConfirm: false)
        }
      }
    }

    stage('Build') {
      // dev 整段跳过：目标机会自己拉代码、自己打 jar，控制器这一步没有任何产物要产出。
      when { expression { env.BGSSAI_ENV != 'dev' } }
      steps {
        bgssaiBuildJars(PRODUCT)
      }
    }

    stage('Deploy') {
      steps {
        bgssaiDeployEnd(PRODUCT + [environment: env.BGSSAI_ENV])
      }
    }
  }

  post {
    success {
      echo "部署成功: bgssai-healthcheck environment=${env.BGSSAI_ENV}"
    }
    failure {
      echo "部署失败: bgssai-healthcheck environment=${env.BGSSAI_ENV}"
      echo '按仓库约定：不自动重跑本流水线、不自动重新部署。请先定位原因，再由人工手动触发。'
      echo 'dev 通道构建失败时远端未被改动；健康检查失败时 remote-deploy.sh 已自动回滚到上一个可用 jar，服务应仍在跑旧版本。'
    }
  }
}
