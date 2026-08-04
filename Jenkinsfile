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

@Library('bgssai') _

pipeline {
  agent any

  options {
    disableConcurrentBuilds()
    timestamps()
    buildDiscarder(logRotator(numToKeepStr: '30'))
    timeout(time: 30, unit: 'MINUTES')
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
      steps {
        bgssaiBuildJars(product: 'healthcheck', layout: 'single')
      }
    }

    stage('Ship') {
      steps {
        bgssaiShip(
          product: 'healthcheck',
          environment: env.BGSSAI_ENV,
          appPort: '8080',
          healthScheme: 'http',
        )
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
      echo '远端健康检查失败时 remote-deploy.sh 已自动回滚到上一个可用 jar，服务应仍在跑旧版本。'
    }
  }
}
