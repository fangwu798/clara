#!/usr/bin/env node
/**
 * 代码覆盖率门禁：汇总各模块 JaCoCo 报告的总行覆盖率，低于阈值时以非零码退出。
 *
 * 用法：node deploy/coverage/check-coverage.mjs [阈值]
 *   阈值默认 0.80（80%）
 *
 * 口径：扫描当前目录下 clara-* 模块的 target/site/jacoco/jacoco.xml，取每个报告根节点最后一条
 * LINE 计数器（模块整体汇总）后求和。该口径与 mvn test 生成的报告一致，且不受聚合报告
 * “只包含依赖模块、不含当前模块”的影响。
 */
import fs from 'node:fs';
import path from 'node:path';

const threshold = Number(process.argv[2] ?? '0.80');
const root = process.cwd();

if (!Number.isFinite(threshold) || threshold <= 0 || threshold > 1) {
  console.error(`[coverage] 阈值非法：${process.argv[2]}`);
  process.exit(2);
}

const modules = fs.readdirSync(root)
  .filter((name) => name.startsWith('clara-'))
  .map((name) => path.join(root, name, 'target', 'site', 'jacoco', 'jacoco.xml'))
  .filter((file) => fs.existsSync(file));

if (modules.length === 0) {
  console.error('[coverage] 未找到任何模块报告，请先执行 mvn -B test。');
  process.exit(2);
}

let covered = 0;
let total = 0;
for (const file of modules) {
  const xml = fs.readFileSync(file, 'utf8');
  const counters = [...xml.matchAll(/<counter type="LINE" missed="(\d+)" covered="(\d+)"\/>/g)];
  if (counters.length === 0) {
    continue;
  }
  const last = counters[counters.length - 1];
  const moduleMissed = Number(last[1]);
  const moduleCovered = Number(last[2]);
  const moduleTotal = moduleMissed + moduleCovered;
  covered += moduleCovered;
  total += moduleTotal;
  const ratio = moduleTotal === 0 ? 0 : moduleCovered / moduleTotal;
  console.log(`[coverage] ${path.basename(path.dirname(path.dirname(path.dirname(path.dirname(file))))).padEnd(22)} ${(ratio * 100).toFixed(1).padStart(5)}%  ${moduleCovered}/${moduleTotal}`);
}

if (total === 0) {
  console.error('[coverage] 报告中没有可统计的行。');
  process.exit(2);
}

const overall = covered / total;
const percent = (overall * 100).toFixed(1);
const required = (threshold * 100).toFixed(1);
console.log(`[coverage] 合计行覆盖率 ${percent}%（${covered}/${total}），要求 ${required}%`);

if (overall + 1e-9 < threshold) {
  console.error(`[coverage] 未达标：还差 ${Math.ceil(threshold * total - covered)} 行。`);
  process.exit(1);
}
console.log('[coverage] 通过。');