# 本地代码扫描（SonarQube）

`scan.ps1` / `scan.cmd` 用于在本地执行 SonarQube 扫描：按需跑测试生成 JaCoCo 报告、调用 Maven
Scanner 上传分析结果，并等待质量门禁。

## 前置条件

- JDK 8+、Maven 已在 PATH 中；
- 本地 SonarQube 已启动（默认 `http://localhost:9100`）；
- SonarQube Token 已写入 `.local/sonar-token.txt`，或设置环境变量 `SONAR_TOKEN`。

## 使用

```powershell
# 默认：复用已有 JaCoCo 报告，扫描 com.clara:clara，并等待质量门禁
deploy\sonar\scan.cmd

# 先跑单测再扫描，并打开看板
deploy\sonar\scan.cmd -RunTests -Open

# 不跑测试、不等待门禁
deploy\sonar\scan.cmd -SkipTests -SkipQualityGate

# 指定地址 / 项目键 / Token
deploy\sonar\scan.cmd -SonarHostUrl "http://localhost:9100" -ProjectKey "com.clara:clara" -Token "<token>"
```

> 项目键固定使用 `sonar-project.properties` 中的 `com.clara:clara`；它和 Maven 的
> `artifactId`（`clara-parent`）无关，不要用 `com.clara:clara-parent`，否则 SonarQube 会多建一个项目。

PowerShell 下也可直接执行：

```powershell
powershell -ExecutionPolicy Bypass -File deploy\sonar\scan.ps1 -RunTests
```

## 输出

- 扫描日志：`.local/sonar/sonar-<时间戳>.log`
- 项目看板：`http://localhost:9100/dashboard?id=<项目键>`
- 退出码：`0` 表示扫描成功且门禁通过；非 `0` 表示扫描失败或门禁未通过。

## 质量门禁

首次使用本机 SonarQube 时，先执行一次门禁脚本，把项目的质量门禁从默认 `Sonar way`
切换为 `Clara Gate` 并绑定到 `com.clara:clara`：

```powershell
deploy\sonar\gate.cmd
```

`Clara Gate` 与 `Sonar way` 的差异只有覆盖率指标：

| 指标 | Sonar way | Clara Gate | 原因 |
|---|---|---|---|
| 覆盖率 | `new_coverage` ≥ 80 | `new_line_coverage` ≥ 80 | `new_coverage` 是行 + 分支的综合口径；团队标准（`tools/coverage/check-coverage.mjs`）约定按行覆盖率 80% |

其余条件（可靠性 / 安全性 / 可维护性评级、重复率 3%、安全热点复核率 100%）保持不变。
脚本可重复执行，会在重建条件后重新绑定项目；门禁结果需在下次扫描分析后才刷新。
