# Honeycomb Design 前端 UED 设计规范

> 文档版本：V1.0  
> 采集日期：2026-09-03  
> 规范来源：[Honeycomb Design](https://honeycomb.mingyuanyun.com/)  
> 适用范围：企业级 Web 中后台、业务工作台、数据可视化与 AI 交互界面  
> 覆盖方式：读取 VuePress 构建路由，逐页解析设计、指南、组件、AI、可视化正文，并核对首页、资源与案例页  
> 证据标记：`原站明确` 为页面文字、图片或 CSS 的直接值；`归纳` 为跨页面提炼；`建议` 为补足工程实施与无障碍要求

## 1. 规范目标

Honeycomb Design 是面向明源云企业级产品的设计体系，核心价值观为：**一致、灵活、高效、开放**。本规范将原站分散在“设计、指南、组件、最佳实践、资源、AI、可视化”等栏目中的规则，统一整理为可供产品、交互、视觉和前端共同使用的 UED 基线。

### 1.1 设计原则

| 原则 | 设计要求 | 典型应用 |
| --- | --- | --- |
| 框架 | 先确定平台层级和页面骨架，再组织业务模块 | 顶栏、侧栏、工作区、卡片区、可视化大屏 |
| 亲密性 | 相关信息靠近，无关信息用更大的间距或分组隔开 | 表单项、指标组、工具栏与表格 |
| 对齐 | 标题、表单标签、数字金额遵循稳定的对齐线 | 标签右对齐、金额右对齐、操作列对齐 |
| 重复 | 同类组件、文案格式和图标排版保持一致 | 卡片列表、状态标签、行操作 |
| 对比 | 用字号、字重、颜色和留白表达主次、总分和状态 | 主按钮、关键指标、异常信息 |
| 即时反馈 | 用户操作后及时显示成功、失败、校验或刷新状态 | Message、Alert、表单校验、Loading |

### 1.2 产品气质

- 面向高频、复杂、长时间使用的企业业务，视觉应克制、清晰、稳定。
- 信息密度优先于装饰性；颜色首先承担状态和信息编码职责。
- 通过标准组件、页面模板和业务组件减少重复设计与开发。
- 默认桌面端基线为 1440 px，同时兼容 1280、1366、1600、1920 px。

## 2. 信息架构与全局框架

### 2.1 一级导航

| 标签 | URL | 内容定位 |
| --- | --- | --- |
| 首页 | `/` | 体系介绍、解决方案、角色入口、案例与共建团队 |
| 设计 | `/design/` | 设计语言、视觉基础、原则与实践 |
| 指南 | `/guide/` | 基础规范、页面模式、业务模板 |
| 组件 | `/component/` | Honeycomb Vue 组件、API、状态与示例 |
| 最佳实践 | `/practice/` | 可运行的列表、表单、详情、工作台等案例 |
| 资源 | `/resource/` | Figma、Sketch、Axure 等设计资源 |
| AI | `/ai/` | AI 设计语言、交互流程、组件和业务案例 |
| 移动端 | 外部站点 | Honeycomb 移动端规范 |
| 可视化 | `/chart/` | 图表设计、终端适配、图形规范与案例 |

### 2.2 文档站框架（原站实测）

```text
┌─────────────────────────────────────────────────────────────┐
│ Logo     一级导航                              登录/外链入口 │
├──────────────┬──────────────────────────┬───────────────────┤
│ 二级侧栏     │ 正文：标题/说明/示例/API │ 当前页目录        │
│ 220 px       │ 最大内容容器约 1240 px   │ 随正文定位        │
├──────────────┴──────────────────────────┴───────────────────┤
│ 上一篇 / 下一篇 · 页脚入口 · 版权                           │
└─────────────────────────────────────────────────────────────┘
```

- 顶栏高度：`60px`；白底、底部 `1px` 分隔线。（原站 CSS）
- 首页 Logo 区约 `240×60px`；导航为 `14px/400`，链接高/行高约 `56px`，激活和 Hover 使用 `3px #005CF9` 底线。（原站 CSS）
- 正文默认最大宽度：`1240px`；设计文档侧栏宽度：`220px`；主区为 `calc(100% - 220px)` 并使用 `0 60px` 内边距。（原站 CSS）
- 文档标题 H1/H2/H3/H4/H5/H6 分别为 `36/26/20/18/16/14px`，字重 `500`。（原站 CSS）
- 窄屏断点中侧栏切换为抽屉式，主要断点见 8.1。
- 一级导航仅保留当前工作域；页面内导航使用左侧分组与右侧锚点目录。

### 2.3 业务产品推荐框架（归纳）

1. 顶部应用栏：品牌、应用/项目上下文、全局搜索、帮助、通知、用户菜单。
2. 左侧导航：一级业务模块固定；允许收起；当前项以主题色和浅色背景突出。
3. 内容头部：面包屑、页面标题、状态、主操作；避免同时出现多个等权主按钮。
4. 内容主体：筛选区、工具栏、数据区、分页区；复杂任务用 Tabs 或步骤条拆分。
5. 临时任务：轻量确认用 Popconfirm，聚焦任务用 Dialog，保留上下文的复杂编辑用 Drawer。

### 2.4 首页与资源卡片

首页首屏使用 Honeycomb Design System 品牌名称和企业级产品设计体系说明，随后展示中后台、移动端、可视化、AI 四类解决方案。原站解决方案容器宽 `1280px`，单卡约 `308×320px`，白底、`1px #E9EBEE` 边框、`6px` 圆角、内边距 `33px 20px 20px`；Hover 使用 `0 7px 20px #E9EBEE` 阴影和 `300ms` 过渡。

- 卡片内信息顺序固定为：解决方案名称/英文名、图形标识、组件规范/指南/实践入口、Sketch/Figma/Axure 资源。
- 案例轮播覆盖超级工作台、投资计划概览、找客户、客户详情、数据可视化大屏、移动轻应用；轮播必须可手动控制和暂停。
- 共建团队头像仅作为成员入口，悬浮 Popover 显示名称；触屏端应提供点击等价行为。
- 资源页按工具和用途分组，下载项明确格式、版本、更新时间和适用产品，不仅显示工具 Logo。

## 3. Design Tokens

### 3.1 色彩

#### 3.1.1 品牌与交互色

| Token | 色值 | 用途 | 状态 | 证据 |
| --- | --- | --- | --- | --- |
| `--hc-primary` | `#266EFF` | 主按钮、当前项、链接、选中控件 | Default | 原站明确 |
| `--hc-primary-hover` | `#518BFF` | 主按钮、文字按钮 Hover | Hover | 原站 CSS |
| `--hc-primary-active` | `#2263E6` | 主按钮、边框按钮按下 | Active | 原站 CSS |
| `--hc-primary-1` | `#EBF2FF` | 主题浅背景 | Subtle | 原站明确 |
| `--hc-primary-2` | `#CCDDFF` | 选中/提示浅背景 | Subtle | 原站明确 |
| `--hc-primary-3` | `#A8C5FF` | 装饰与禁用层级 | Extended | 原站明确 |
| `--hc-primary-4` | `#84ADFF` | 扩展色阶 | Extended | 原站明确 |
| `--hc-primary-5` | `#6397FF` | 扩展色阶 | Extended | 原站明确 |
| `--hc-primary-6` | `#4583FF` | 扩展色阶 | Extended | 原站明确 |
| `--hc-primary-8` | `#175BE5` | 深色交互 | Extended | 原站明确 |
| `--hc-primary-9` | `#044AD6` | 深色背景 | Extended | 原站明确 |
| `--hc-primary-10` | `#003EBD` | 最深主题色 | Extended | 原站明确 |

默认系统主题包含“景泰蓝”渐变 `#266EFF → #4A85F6 → #55B8FF`，以及“经典黑”`#434459 / #333742`。可选产品主题包括天空蓝 `#02A7F5`、宝石蓝 `#3A82CF`、橄榄绿 `#2D9F61`、欢快橙 `#FA5A1E`、活力火 `#F23C39`、经典黑 `#434459`。

扩展基础色板如下。业务界面应通过语义 Token 引用，不在组件中直接绑定某一档原始色值。

| 色系 | 由浅至深的 10 阶色值 |
| --- | --- |
| 天蓝 | `#D9EDFF` `#B3DAFF` `#8CC8FF` `#66B5FF` `#40A3FF` `#1990FF` `#1784EB` `#1579D6` `#126BBD` `#105CA3` |
| 青 | `#E5F7F8` `#C2EFF0` `#8ADDE2` `#58CBD3` `#2CB8C5` `#05A4B6` `#038698` `#016979` `#004D5B` `#003B47` |
| 绿 | `#EDFDF7` `#C9F9E8` `#A5F3D9` `#70EBC2` `#3CD4A1` `#1CC78D` `#13A374` `#167E5A` `#126246` `#0F523A` |
| 橄榄绿 | `#F3F8EC` `#E3F0D0` `#C8E2A5` `#ADD37E` `#93C55B` `#7BB63C` `#649830` `#4E7926` `#395B1B` `#253D12` |
| 黄 | `#FFFAEA` `#FEEFC3` `#FCE187` `#FBD34B` `#FBC824` `#F0BA06` `#E0AC01` `#CEA405` `#AF8B04` `#886C05` |
| 靛蓝 | `#ECEFF8` `#D1D8F0` `#A7B3E1` `#8090D3` `#5E6FC4` `#3F51B5` `#3342A1` `#28348C` `#1F2878` `#171D63` |
| 紫 | `#F3EDF9` `#E2D1F4` `#C4A7E9` `#A67FDD` `#885BD2` `#6A3BC7` `#5730B3` `#47269E` `#361C8A` `#281475` |
| 玫红 | `#FDECEF` `#FBCFD8` `#F6A0B5` `#F27396` `#ED487B` `#E91E63` `#C51356` `#A20B48` `#7E053A` `#5A012B` |

#### 3.1.2 功能色

| Token | 色值 | 语义 | 使用要求 |
| --- | --- | --- | --- |
| `--hc-info` | `#266EFF` | 链接、信息 | 不与普通正文仅靠颜色区分 |
| `--hc-success` | `#1CC78D` | 成功、完成、正常 | 配合图标或文字 |
| `--hc-warning` | `#FF9902` | 警示、待处理 | 避免用于普通装饰 |
| `--hc-danger` | `#FF4C4C` | 错误、失败、危险 | 删除确认必须同时写明后果 |

> 说明：组件库旧样式中还出现 `#13CE66`、`#F7BA2A`、`#FF4949` 等 Element 时代功能色。新页面应优先采用上表的规范功能色，存量迁移时统一语义映射，避免同屏混用。

#### 3.1.3 中性色

| Token | 色值 | 用途 |
| --- | --- | --- |
| `--hc-text-primary` | `#333333` | 主要文本 |
| `--hc-text-secondary` | `#666666` | 次要文本、表单 Label |
| `--hc-text-tertiary` | `#999999` | 说明文字、线性图标默认色 |
| `--hc-icon-disabled` | `#C5C5C5` | 面性图标默认、线性图标禁用 |
| `--hc-border` | `#DDDDDD` | 控件与容器边框 |
| `--hc-divider` | `#EAEAEA` | 分割线 |
| `--hc-fill-hover` | `#F5F5F5` | 表格 Hover、控件禁用背景 |
| `--hc-nav-bg` | `#F8F8F8` | 左侧导航背景 |
| `--hc-table-head` | `#FBFBFB` | 表头背景 |
| `--hc-surface` | `#FFFFFF` | 控件、页面内容背景 |

#### 3.1.4 数据可视化色板

可视化遵循“准确、清晰、有效、美观”。分类色应顺序稳定，同一指标跨页面保持同色。仓库中的色板核对图提供五色序列：`#FF5001`、`#FF9102`、`#04B77D`、`#3397FF`、`#6B4EFF`，详见 [chart-color-audit.png](./chart-color-audit.png)。该序列属于图表分类色，不等同于品牌主色。

- 连续数据：使用同一色相的明度/饱和度梯度。
- 发散数据：以中性零点为中心，正负方向使用两个可辨色相。
- 状态数据：必须沿用成功、警示、错误等功能色语义。
- 分类数量超过可辨色数时，优先分组、筛选或拆图，不继续堆叠近似色。
- 深色大屏采用“科技蓝”主题，普通报表和 PC 工作台采用“极简白”主题。

### 3.2 字体

#### 3.2.1 字体族

```css
font-family: "Helvetica Neue", Helvetica, Arial, "PingFang SC",
  "Microsoft YaHei UI", "Microsoft YaHei", sans-serif;
```

Mac 中文优先 `PingFang SC`，Windows 中文优先 `Microsoft YaHei`；英文与数字优先 Helvetica/Arial。禁止在业务正文中依赖用户不一定安装的定制字体。

#### 3.2.2 字阶与行高

| Token | 字号/行高 | 用途 | 字重 |
| --- | --- | --- | --- |
| `--hc-font-20` | `20/28px` | 特殊提示、关键大标题 | 500/600 |
| `--hc-font-16` | `16/24px` | 详情页标题 | 500 |
| `--hc-font-14` | `14/22px` | 导航、Tabs、分组标题；推荐基准字号 | 400/500 |
| `--hc-font-13` | `13/22px` | 正文、表格与表单主体 | 400 |
| `--hc-font-12` | `12/22px` | 辅助说明、时间、次级元数据 | 400 |

一个业务系统通常只使用 3 至 5 个字阶。常规字重只用 `400 Regular`、`500 Medium`；英文强调可使用 `600 Semibold`。正文颜色在浅色背景上使用 `#333 / #666 / #999` 三层，不用纯黑制造过强对比。

### 3.3 间距与栅格

- 基础单位：`4px`。
- 常用间距：`8 / 12 / 16 / 24 / 32px`。
- 特殊模块可使用其他 `4px` 倍数，例如 `20px`；不使用无规律的间距值。
- PC 栅格：`24` 栅格；1440 基准下内容区域 `1208px`，页边距 `12px`，水槽恒定 `12px`。
- 推荐画板：`1440 × 820px`（已扣除约 80px 浏览器栏）；兼容 1280、1366、1600、1920 宽度。

```css
:root {
  --hc-space-1: 4px;
  --hc-space-2: 8px;
  --hc-space-3: 12px;
  --hc-space-4: 16px;
  --hc-space-6: 24px;
  --hc-space-8: 32px;
  --hc-grid-gutter: 12px;
}
```

### 3.4 尺寸、圆角、边框与阴影

| 项目 | 规范值 | 说明 |
| --- | --- | --- |
| 默认输入框 | `36px` 高，`12px` 水平内边距 | 原站组件 CSS |
| 输入框尺寸 | `42 / 36 / 32 / 28px` | 大/默认/小/迷你变体 |
| 默认按钮 | `8px 15px` 内边距，最小宽 `100px` | 原站组件 CSS |
| 大/小/迷你按钮 | `11×19 / 8×9 / 7×15px` 内边距 | 原站组件 CSS |
| 单选/复选 | `16×16px` | 原站组件 CSS |
| Switch | `46×20px`，圆角 `10px` | 默认 `#D3D8DD`，选中 `#34A6F8` |
| 控件圆角 | `3px` | 按钮、输入框、文本域 |
| 对话框圆角 | `4px`（基础旧样式曾为 `5px`） | 新业务样式以 `4px` 为准 |
| 基础边框 | `1px solid #DDDDDD` | 控件和轻容器 |
| 对话框阴影 | `0 2px 4px rgba(0,0,0,.2)` | 原站组件 CSS |
| 动效 | `200–300ms` | 边框、背景、侧栏和浮层切换 |

### 3.5 图标

- 基于关键线和网格绘制，视觉重心居中；同一组图标线宽、圆角和透视一致。
- 常规线性图标默认 `#999999`，禁用 `#C5C5C5`；选中或可操作状态用主题色。
- 图标按钮使用熟悉的符号表达搜索、刷新、下载、更多、关闭等操作，并提供 Tooltip/可访问名称。
- 图标与文字并用时间距建议 `4–8px`；不要混用不同风格的线性和面性图标。

## 4. 组件规范

### 4.1 通用状态矩阵

每个可交互组件至少设计并验收以下状态：

| 状态 | 视觉与行为 |
| --- | --- |
| Default | 可识别、可操作，层级符合当前任务 |
| Hover | 改变边框/文字/背景，不引起布局位移 |
| Focus | 显示清晰焦点环；键盘焦点不可被 `outline: none` 吞掉 |
| Active | 使用 `#2263E6` 等更深状态色，反馈按下状态 |
| Selected | 主题色、选中图标或浅主题背景共同表达 |
| Disabled | `#F5F5F5` 背景、弱化文本、`not-allowed`，且不可触发事件 |
| Loading | 保持组件尺寸；阻止重复提交；必要时保留进度文本 |
| Error | 错误色、图标和可执行文案同时出现，焦点移到首个错误项 |
| Empty | 说明为何为空，并在用户可处理时给出单一主行动 |

### 4.2 按钮 Button

- 类型：主按钮、次按钮、文字按钮、图标按钮；危险操作单独使用危险语义。
- 同一操作区只设置一个主按钮。取消在左、确定在右；表单主操作位置跨页面保持一致。
- 原站状态：主按钮 `#266EFF`，Hover `#518BFF`，Active `#2263E6`；普通按钮白底 `#DDD` 边框。
- 图标按钮必须有 Tooltip；加载时保留原宽度；禁用和加载状态均不可重复触发。

```html
<div class="hc-actions" aria-label="表单操作">
  <button class="hc-btn">取消</button>
  <button class="hc-btn hc-btn--primary" type="submit">保存</button>
</div>
```

```css
.hc-btn {
  min-width: 100px;
  padding: 8px 15px;
  border: 1px solid #ddd;
  border-radius: 3px;
  background: #fff;
  color: #333;
  font: 400 13px/1 system-ui, sans-serif;
  cursor: pointer;
}
.hc-btn:hover { color: #266eff; border-color: #266eff; }
.hc-btn:focus-visible { outline: 2px solid #84adff; outline-offset: 2px; }
.hc-btn--primary { color: #fff; background: #266eff; border-color: #266eff; }
.hc-btn--primary:hover { color: #fff; background: #518bff; border-color: #518bff; }
.hc-btn--primary:active { background: #2263e6; border-color: #2263e6; }
```

### 4.3 数据录入

#### 4.3.1 输入与选择

覆盖 Textbox、Spinner、Select、TreeSelect、Cascader、AutoComplete、DatePicker、TimePicker、RangePicker、AddressSelect、LookPopup、ProjectSelect。

- Label 应简短且稳定，必填标记紧邻 Label，不放在 Placeholder 中。
- Placeholder 仅用于格式提示，不代替字段名；禁用和只读必须有不同语义。
- 默认输入框高 `36px`，`3px` 圆角，边框 `#DDD`；Focus 边框 `#266EFF`。
- 下拉超过 7–10 项提供搜索；层级选项使用 TreeSelect/Cascader；复杂跨实体选择使用弹出选择器。
- 日期、金额、百分比、手机号等遵循数据格式规则；范围输入必须明确起止与单位。
- 错误信息放在字段下方，说明“哪里错、如何改”，提交失败时汇总并定位。

#### 4.3.2 单选、复选与开关

- Radio：互斥且选项通常不超过 5–7 个；必须有默认值时选择安全、常用项。
- Checkbox：允许零到多项；全选使用 indeterminate 状态表达部分选中。
- Switch：设置立即生效时使用；需要提交或有严重后果时改用 Checkbox + 保存按钮。
- 选项标签本身可点击；点击热区不小于 `32×32px`，移动场景建议 `44×44px`。

#### 4.3.3 上传与富文本

- Upload 明确文件类型、数量、单文件大小；逐项显示上传中、成功、失败与重试。
- 图片上传提供预览、替换与删除；删除已上传资源需要确认或可撤销。
- Richtext 工具栏只暴露业务需要的格式；只读与禁用状态必须区分。

#### 4.3.4 表单 Form

- 支持基础、分组、分区和子表单；相关字段同组，组间距大于组内间距。
- 简单表单优先单列；高频短字段可双列；标签对齐方式同页统一。
- 长表单按业务语义分区，必要时使用锚点或步骤条，不用大量折叠隐藏必填项。
- 离开未保存页面时提醒；提交中锁定主操作；成功后明确去向。

### 4.4 数据展示

#### 4.4.1 表格 Grid

原站覆盖：无边框、分页、编辑校验、层级、汇总、多选、默认选中、Loading、序号、排序、冻结列、列头提示、多视图、全局/批量/行工具栏、列控制。

- 文本左对齐，日期按列统一，数字和金额右对齐，状态居中或左对齐但全表一致。
- 主标识列可用链接进入详情；行操作只保留 1–3 个高频操作，其余放“更多”。
- 表头用 `#FBFBFB`，Hover 行用 `#F5F5F5`；选中行需与 Hover 有明确差异。
- 组件源码中的表格为 `13px #333`，行高和表头高均为 `46px`，边线 `#E9EBEE`；存量样式的表头底色 `#F7FAFC` 可逐步映射到中性色 Token。
- 数据加载时保持表头与容器高度；空数据、无搜索结果、无权限使用不同文案。
- 分页默认位于表格右下；左侧可展示总数，支持每页数量和跳页时避免控件挤压。
- 可编辑表格必须提供单元格错误提示、未保存标识与批量提交反馈。

#### 4.4.2 卡片、列表与简档

- Card 用于独立、可重复的信息对象，不将普通页面分区全部卡片化。
- 卡片列表保持标题、摘要、指标、状态、操作的固定顺序和高度策略。
- InfoProfile 用于对象摘要，可组合状态、关键指标与工具栏；详情信息移入详情区。
- Tag 用于分类或短状态，不承载长句；相同语义跨页面保持相同颜色。
- Empty 区分首次无数据、筛选无结果、加载失败、无权限，并提供对应行动。

### 4.5 导航

- Menu 表达稳定的信息架构；Tabs 表达同一对象的平级视图，不用 Tabs 代替步骤流程。
- Tabs 默认字号 `14px`，标签项高 `49px`、文本行高约 `42px`、激活底线 `3px`；紧凑型可用 `37/29px`，同一区域不可混用。
- Breadcrumb 显示层级位置，末级不可点击；标题不与末级面包屑重复堆叠。
- Steps 表达有先后依赖的任务，显示当前、完成、等待、错误；允许返回时保留已填数据。
- Pagination 属于数据导航，切页后焦点/滚动回到数据区起点。
- Tree 用于层级浏览；节点选择与展开必须使用不同热区，避免误操作。

### 4.6 反馈与浮层

| 组件 | 适用场景 | 不适用场景 |
| --- | --- | --- |
| Message | 轻量、自动消失的操作结果 | 需要用户决策或阅读长文本 |
| Notification | 跨页面、较完整的异步结果 | 当前字段校验 |
| Alert | 页面内持续可见的重要信息 | 普通成功提示 |
| Popover | 依附触发元素的补充信息/轻操作 | 长表单或关键确认 |
| Popconfirm | 后果明确的单步确认 | 高风险、复杂影响说明 |
| Dialog | 需要聚焦的确认或短任务 | 超长、多区复杂编辑 |
| Drawer | 保留当前上下文的详情或复杂编辑 | 极简确认 |
| Loading | 数据或操作处理中 | 无法估算且长期阻塞时，应补进度/后台处理说明 |

- 弹窗默认聚焦第一个可操作元素，焦点限制在浮层内，关闭后归还触发点。
- Dialog 标题栏高 `48px`、左右内边距 `20px`，正文内边距 `20px`；底部操作栏有上分割线，按钮最小宽 `100px`。
- Message 最小宽 `300px`、内边距 `10px 12px`、圆角 `2px`；Alert 内边距 `8px 16px`、圆角 `3px`；Tag 高 `24px`、行高 `22px`、字号 `13px`、圆角 `4px`。
- Card 基础边框 `#E5E5E5`、圆角 `4px`、正文内边距 `20px`；阴影仅用于可抬升/可交互卡片，参考 `0 2px 4px rgba(0,0,0,.12), 0 0 6px rgba(0,0,0,.04)`。
- 危险确认按钮写具体动作，如“删除合同”，不写模糊的“确定”。
- 成功反馈说明结果；失败反馈说明原因和下一步；系统异常提供重试或错误编号。

## 5. 页面模式

### 5.1 工作台

用于任务、消息、常用入口和经营指标聚合。首屏优先放“待我处理”和关键异常；个性化模块要允许恢复默认。超级工作台与 SaaS 工作台均应保持模块标题、更新时间、跳转行为一致。

### 5.2 列表/查询页

```text
页面标题                                      主操作
筛选条件：常用条件 · 更多条件               查询 / 重置
Tabs/视图（可选）
工具栏：批量操作 · 导入导出 · 列设置
表格/树表格/卡片列表
总数                                           分页
```

- 常用筛选条件直接展示；高级条件折叠；条件多且复用频繁时支持保存模板。
- 简单筛选可立即生效；多条件组合使用“查询”，规则不得在同页混用。
- 批量操作只在选中后启用并显示选中数；导入任务显示校验、进度、结果下载。

### 5.3 表单页

- 弹出表单：字段少、任务短、不需跨页查阅。
- 侧滑表单：需保留列表上下文、字段中等复杂。
- 跳转/新标签表单：长流程、跨模块、需要大面积编辑或对照信息。
- 操作区位置固定；分区标题明确；高级表单可配步骤条与临时保存。

### 5.4 详情页

- 通栏详情适合线性信息；上下布局适合“概要 + 明细”；左右布局适合导航/对象列表与详情联动。
- 关键信息简档置顶，状态和主操作靠近标题；日志、附件、关联对象按语义分区。
- 弹窗/侧滑详情用于快速查看；完整任务跳转详情页；需并行处理可打开新标签页。

### 5.5 卡片页与关键路径

- 一级卡片页用于对象集合，二级卡片页用于对象分组；卡片不应承担完整详情。
- 关键路径可显示单条或多条业务流程；当前节点、阻塞、逾期和完成必须可区分。
- 过程详情提供责任人、时间、输入输出和异常原因，不只显示节点颜色。

### 5.6 登录、权限与异常

- 登录页聚焦身份验证，不混入大量营销信息；错误不暴露账户存在性等敏感信息。
- 无权限区分“未登录、无角色、无数据权限、功能未开通”，给出正确申请或返回路径。
- 404 页面提供返回首页、返回上一页和搜索/常用入口，不形成导航死路。

## 6. 数据与内容规范

### 6.1 数据格式

- 数值：使用一致的小数位和千分位；金额同时明确币种；百分比不得混用 0.25 与 25%。
- 日期：同一页面统一 `YYYY-MM-DD`；需要时显示时间与时区；相对时间应可查看绝对时间。
- 空值：`--` 表示无值；`0` 是有效数据，不得显示为空。
- 脱敏：按数据等级全量或部分脱敏；查看原文必须授权并记录审计。
- 状态：文本为主、颜色为辅；状态名称用业务结果，不用内部代码。
- Loading：骨架屏适合稳定布局，Spinner 适合局部短等待，长任务显示阶段或进度。

### 6.2 文案

- 一致：同一动作只使用一个名称，例如统一用“保存”，不混用“提交/确认”表达同一结果。
- 易读：先结果后原因，短句、主动语态，避免内部术语和重复敬语。
- 按钮：动词或“动词 + 对象”，例如“新建合同”“重新上传”。
- 错误：说明问题与修复方法，例如“结束日期不能早于开始日期，请重新选择”。
- 标点：中文使用全角标点；英文产品名、数字与单位按团队词典统一。
- 命名：设计图层、组件、埋点和代码命名应能相互映射，禁止 `copy 2`、`group 18` 等无语义名称。

## 7. AI 交互专项

### 7.1 对话生命周期

```text
触发入口 → 开场与能力预期 → 用户输入 → 理解/澄清
        → 执行过程 → 结果展示 → 结果确认/继续处理 → 结束
                         ↘ 错误/不支持/转人工
```

- 开始：热情但简短；明确能做什么；由用户主导，不要求记忆固定指令。
- 提示：给建议而非命令；无匹配项时提供可行改写或替代路径。
- 错误：区分无法解析、输入无效、能力不支持、系统异常，并给不同恢复动作。
- 执行：侧栏辅助适合保留业务上下文；静默处理只用于低风险、可撤销动作。
- 结果：消息卡、表单卡、检查卡按结果结构选择；显示置信度或证据时不制造虚假精确性。
- 结束：支持完成、放弃、不支持与系统问题；保存必要上下文并允许重新开始。

### 7.2 AI 组件

| 类别 | 组件 | 核心要求 |
| --- | --- | --- |
| 通用 | Button、Bubble、Conversations | 操作明确、角色可辨、历史可管理 |
| 开场 | Welcome、Prompts | 说明能力边界，示例可直接触发 |
| 输入 | Skill Catalog、Sender、Attachment、Image、Screenshot | 格式/大小提示、上传状态、发送可取消 |
| 输出 | Text、Image、Code、Table、Form、Attachment | 来源、复制/下载、结构化编辑与确认 |
| 反馈 | Exception Notification、Message Hint | 可恢复错误与下一步行动 |

### 7.3 AI 业务案例覆盖

原站案例包括新增督办任务、生成价格方案、合同审查、价格审查、明源知识库和在线客服，覆盖开始、上传、处理中、完成、连续对话与转人工等阶段。设计评审必须以完整生命周期验收，而不是只评审一张对话截图。

## 8. 可视化与响应式

### 8.1 响应式断点

原站 CSS 中出现以下断点：`419 / 719 / 720 / 767 / 768 / 959 / 991 / 992 / 1199 / 1200px`。业务产品建议收敛为：

| Token | 范围 | 行为 |
| --- | --- | --- |
| `compact` | `< 720px` | 单列；侧栏抽屉；工具栏收纳；表格转卡片或横向滚动 |
| `medium` | `720–1199px` | 内容自适应；次要栏可折叠；减少并列列数 |
| `wide` | `≥ 1200px` | 固定导航 + 24 栅格内容区 |

移动端不是桌面端等比缩小。需要重新决定信息优先级、触控热区、列表/卡片/平铺布局，以及图表标签密度。

> 原站首页自身设置了 `min-width: 1440px`，小屏会产生横向溢出。这是当前实现事实，不应作为新项目的响应式范式复制；新实现应按上表提供真实的 compact/medium 布局。

### 8.2 图表选型

| 目标 | 推荐图表 | 避免 |
| --- | --- | --- |
| 趋势 | 折线、面积 | 类别过多的多折线 |
| 类别比较 | 柱状、条形 | 难比较角度的饼图 |
| 构成占比 | 饼/环（少量分类）、百分比环 | 大量或接近比例的扇区 |
| 多维画像 | 雷达（维度有限） | 量纲未标准化 |
| 阶段转化 | 漏斗/金字塔 | 无顺序关系的分类 |
| 单指标达成 | 仪表盘/进度 | 多个仪表盘堆叠 |

所有图表页统一包含：介绍、应用场景、使用原则、标准样式和变体。轴、图例、Tooltip、单位、空数据、异常值与数据更新时间必须完整设计。

#### 8.2.1 默认主题色板（原站图像实测）

| 主题 | 12 色序列 |
| --- | --- |
| 极简白 | `#5182E4` `#69D4DB` `#F7CB4A` `#9BCC66` `#3DB37E` `#F88C48` `#F25352` `#CD62D6` `#8A54D4` `#52B4F1` `#51B3BB` `#D42D6B` |
| 科技蓝 | `#009FFF` `#00CD8F` `#FFE555` `#FFAD68` `#FF5F5E` `#C878D8` `#6563ED` `#2F82FE` `#8ED1F1` `#B2EA71` `#46C850` `#00AEB2` |

科技蓝示例底色约为 `#120A36`、面板约为 `#0F1450`。深色主题必须重新核验文字、网格线和 Tooltip 对比度，不能直接把浅色主题背景反转。

### 8.3 终端布局

| 终端 | 画板/栅格 | 布局规则 |
| --- | --- | --- |
| 大屏 | `1920×1080`，`16:9` | 常规（中心核心、周边次要）、`1:1:1` 均分、全局地图/3D 重点区；先确认观看距离与刷新频率 |
| 图表 PC | `1440×820`，24 栅格，页边距 `20px`、水槽 `16px` | 左右布局内容动态缩放；上下布局内容定宽、两侧留白自适应 |
| 移动 | `750×1334` 设计基准 | 列表、卡片、平铺三类；减少同时展示维度，详情下钻 |

> 图表 PC 的 `20px` 页边距、`16px` 水槽是可视化专项值；普通业务页面仍采用 3.3 节的 `12px` 页边距和水槽，不得混用。

## 9. 无障碍与可用性（实施补充）

- 正文与背景对比度至少 `4.5:1`，大字至少 `3:1`；状态不能只依赖颜色。
- 全部功能可键盘操作，焦点顺序与视觉顺序一致；`focus-visible` 清晰可见。
- 图标按钮、输入框、上传、图表和浮层提供可访问名称；装饰图片使用空 `alt`。
- 表格使用正确表头关联；排序状态用 `aria-sort`；错误字段用 `aria-describedby`。
- 动画尊重 `prefers-reduced-motion`；自动轮播可暂停；消息停留时间允许阅读。
- 页面缩放 200% 时不丢内容或操作；320px 宽度不出现无意义重叠。

## 10. 完整示范用例

### 10.1 查询列表页

```html
<main class="hc-page">
  <header class="hc-page__header">
    <div>
      <nav aria-label="面包屑">合同管理 / 合同列表</nav>
      <h1>合同列表</h1>
    </div>
    <button class="hc-btn hc-btn--primary">新建合同</button>
  </header>

  <form class="hc-filter" role="search">
    <label>合同名称 <input type="search" placeholder="请输入合同名称" /></label>
    <label>状态
      <select><option>全部</option><option>审批中</option><option>已生效</option></select>
    </label>
    <div class="hc-actions">
      <button class="hc-btn" type="reset">重置</button>
      <button class="hc-btn hc-btn--primary" type="submit">查询</button>
    </div>
  </form>

  <section aria-labelledby="result-title">
    <div class="hc-toolbar">
      <h2 id="result-title">查询结果</h2>
      <button class="hc-btn" disabled>批量导出</button>
    </div>
    <table>
      <thead><tr><th scope="col">合同名称</th><th scope="col">状态</th><th scope="col">金额（元）</th><th scope="col">操作</th></tr></thead>
      <tbody><tr><td><a href="#">示例采购合同</a></td><td>审批中</td><td class="is-number">20,000.00</td><td><button class="hc-link">查看</button></td></tr></tbody>
    </table>
  </section>
</main>
```

```css
.hc-page { min-width: 0; padding: 24px; color: #333; background: #fff; }
.hc-page__header, .hc-toolbar, .hc-actions { display: flex; align-items: center; gap: 12px; }
.hc-page__header { justify-content: space-between; margin-bottom: 24px; }
.hc-page h1 { margin: 4px 0 0; font-size: 20px; line-height: 28px; }
.hc-filter { display: grid; grid-template-columns: repeat(2, minmax(220px, 1fr)) auto; gap: 16px; padding: 16px; background: #fbfbfb; }
.hc-filter input, .hc-filter select { height: 36px; border: 1px solid #ddd; border-radius: 3px; }
.hc-toolbar { justify-content: space-between; margin: 24px 0 12px; }
.hc-toolbar h2 { font-size: 16px; line-height: 24px; }
table { width: 100%; border-collapse: collapse; font-size: 13px; line-height: 22px; }
th { background: #fbfbfb; color: #666; font-weight: 500; }
th, td { padding: 8px 12px; border-bottom: 1px solid #eaeaea; text-align: left; }
tbody tr:hover { background: #f5f5f5; }
.is-number { text-align: right; font-variant-numeric: tabular-nums; }
@media (max-width: 719px) { .hc-filter { grid-template-columns: 1fr; } .hc-page { padding: 16px; } }
```

### 10.2 表单校验反馈

```html
<div class="hc-field hc-field--error">
  <label for="end-date">结束日期 <span aria-hidden="true">*</span></label>
  <input id="end-date" type="date" aria-invalid="true" aria-describedby="end-date-error" />
  <p id="end-date-error" role="alert">结束日期不能早于开始日期，请重新选择。</p>
</div>
```

### 10.3 空状态文案

| 场景 | 标题 | 行动 |
| --- | --- | --- |
| 首次无数据 | 暂无合同 | `新建合同`（有权限时） |
| 筛选无结果 | 未找到符合条件的合同 | `清空筛选条件` |
| 加载失败 | 合同加载失败 | `重新加载` + 错误编号 |
| 无权限 | 暂无合同查看权限 | `申请权限` 或返回 |

## 11. 设计与开发交付

### 11.1 设计交付物

- 页面流与状态流：正常、空、加载、错误、无权限、边界数据。
- 使用语义化 Token 的高保真稿，不在页面中散落无命名色值和间距。
- 组件变体与交互说明：触发、反馈、焦点、关闭、撤销、超时。
- 响应式稿：至少覆盖 compact、medium、wide 三档关键变化。
- 内容清单：标题、字段、按钮、状态、错误和空状态文案。

### 11.2 前端验收清单

- [ ] 使用统一色彩、字号、字重、行高和 4 点间距体系。
- [ ] 1440 基准布局正确，1280/1366/1600/1920 无遮挡或溢出。
- [ ] 所有交互组件具备 Hover、Focus、Active、Disabled、Loading、Error 状态。
- [ ] 表格数字右对齐，空值、零值、超长文本与大数据量显示正确。
- [ ] 表单键盘可用，错误可定位，重复提交被阻止，未保存离开有提醒。
- [ ] 弹窗焦点管理正确，关闭后焦点回到触发元素。
- [ ] 空、错、无权限、断网和超时状态均可恢复。
- [ ] 颜色对比度、可访问名称、语义标签和减少动效通过检查。
- [ ] 浏览器缩放 200% 及 320px 宽度下内容不重叠。

## 12. 全标签页审计清单

以下按原站导航和构建路由归档。大小写、空格编码和原站拼写错误均保留，便于回溯。

### 12.1 设计 Design

| 分组 | 标签页（URL） |
| --- | --- |
| 概览 | 概览 `/design/`；设计语言 `/design/design-language/`；实践案例 `/design/practice.html`；设计资源 `/design/resource.html` |
| 视觉 | 色彩 `/design/style/color/`；布局 `/design/style/layout/`；字体 `/design/style/font/`；图标 `/design/style/icon/` |
| 原则 | 原则概览 `/design/principle/overview/`；框架 `/design/principle/frame/`；亲密性 `/design/principle/intimacy/`；对齐 `/design/principle/align/`；重复 `/design/principle/repeat/`；对比 `/design/principle/contrast/`；即时反馈 `/design/principle/feedback/` |
| PC 专题 | 整体框架 `/design/principle%20pc/frame/`；高频任务 `/design/principle%20pc/task/`（原站同时存在空格形式别名） |

### 12.2 指南 Guide

| 分组 | 标签页（URL） |
| --- | --- |
| 入口 | 概述 `/guide/`；关于 `/guide/about/` |
| 基础规范 | 通用 `/guide/general/`；数据录入 `/guide/data-input/`；数据展示 `/guide/data-show/`；数据格式 `/guide/data-format/`；反馈 `/guide/feedback/`；组件模板 `/guide/business-template/`；业务组件 `/guide/buiz-comp/`；文案 `/guide/article/`；批注与命名 `/guide/annotate/` |
| 页面指南 | 工作台 `/guide/workbench/`；表格页 `/guide/grid/`；单列树 `/guide/single-col-tree/`；卡片页 `/guide/card/`；表单页 `/guide/form/`；详情页 `/guide/detail/`；数据指标 `/guide/data-indicators/`；关键路径 `/guide/critical-path/`；提示反馈 `/guide/prompt-feedback/`；自定义页面 `/guide/custom/`；可视化 `/guide/visualization/`；空状态旧入口 `/guide/empty/` |
| 模板 | 工作台 `/guide/template/workbench/`；列表 `/guide/template/grid/`；卡片 `/guide/template/card/`；表单 `/guide/template/form/`；详情 `/guide/template/detail/`；设计器 `/guide/template/designer/`；数据可视化 `/guide/template/visualized/`；登录 `/guide/template/login/` |

### 12.3 组件 Component

| 分组 | 标签页（URL） |
| --- | --- |
| 入口与开发 | 总览 `/component/`；快速上手 `/component/quickstart.html`；更新日志 `/component/updatelog.html`；业务组件开发 `/component/businesscomponent.html` |
| 通用/导航 | Button `/component/button.html`；Icon `/component/icon.html`；Iframe `/component/iframe.html`；Toolbar `/component/toolbar.html`；Steps `/component/steps.html`；Pagination `/component/pagination.html`；Search `/component/search.html`；Menu `/component/menu.html`；Breadcrumb `/component/breadcrumb.html`；Tabs `/component/tabs.html`；Tree `/component/tree.html` |
| 数据录入 | Radio `/component/radio.html`；Checkbox `/component/checkbox.html`；Switch `/component/switch.html`；Select `/component/select.html`；TreeSelect `/component/treeselect.html`；Spinner `/component/spinner.html`；Textbox `/component/textbox.html`；LookPopup `/component/lookpopup.html`；DatePicker `/component/datepicker.html`；TimePicker `/component/timepicker.html`；RangePicker `/component/rangepicker.html`；AutoComplete `/component/autocomplete.html`；Upload `/component/upload.html`；Richtext `/component/richtext.html`；AddressSelect `/component/address.html`；Cascader `/component/cascader.html`；Form `/component/form.html` |
| 数据展示 | Grid `/component/table.html`；编辑 Grid `/component/table-editor.html`；Tag `/component/tag.html`；InfoProfile `/component/infoprofile.html`；List `/component/list.html`；Card `/component/card.html`；Brieffile `/component/brieffile.html`；Percentage `/component/percentage.html`；CriticalPath `/component/criticalpath.html` |
| 反馈 | Popover `/component/popover.html`；Alert `/component/alert.html`；Dialog `/component/dialog.html`；Message `/component/message.html`；Notification `/component/notification.html`；Popconfirm `/component/popconfirm.html`；Empty `/component/empty.html` |
| 业务/选择 | Checklist `/component/checklist.html`；ClassSelection `/component/classselection.html`；PopupSelection `/component/popupselection.html`；ProjectSelect `/component/projectselect.html`；Transfer `/component/transfrer.html`；Layout `/component/layout.html`（`transfrer` 为原站拼写） |

### 12.4 AI

| 分组 | 标签页（URL） |
| --- | --- |
| 设计入口 | AI 首页 `/ai/`；设计概述 `/ai/design/`；设计语言 `/ai/design/design-language/` |
| 对话规范 | 介绍 `/ai/design/introduce/`；开始 `/ai/design/start/`；提示 `/ai/design/Reminder/`；错误 `/ai/design/error/`；结束 `/ai/design/end/`；进行中 `/ai/design/in-progress/` |
| AI 交互 | 触发方式 `/ai/design/trigger-scenario/`；执行过程 `/ai/design/executive-process/`；结果展示 `/ai/design/resultsdisplay/`；结果处理 `/ai/design/Result-Processing/`；色彩/布局旧入口 `/ai/design/layout/` |
| 组件入口 | 总览 `/ai/component/`、`/ai/component/overview/` |
| 通用与开场 | Bubble `/ai/component/bubble/`；Button `/ai/component/button/`；Conversations `/ai/component/conversations/`；Welcome `/ai/component/welcome/`；Prompts `/ai/component/prompts/` |
| 输入 | Skill Catalog `/ai/component/skill-catalog/`；Sender `/ai/component/sender/`；附件 `/ai/component/intput-attachment/`；图片 `/ai/component/input-image/`；截图 `/ai/component/screenshot/`（`intput` 为原站拼写） |
| 输出 | Text `/ai/component/text/`；Image `/ai/component/output-image/`；Code `/ai/component/code/`；Table `/ai/component/table/`；Form `/ai/component/form/`；Attachment `/ai/component/output-attachment/` |
| 反馈 | Exception Notification `/ai/component/exception-notification/`；Message Hint `/ai/component/message-hint/` |
| 业务案例 | 概述 `/ai/business-case/`、`/ai/business-case/overview/`；新增督办 `/ai/business-case/add-supervision-tasks/`；价格审查 `/ai/business-case/bidding-risk-warning/`；合同审查 `/ai/business-case/contract-review/`；生成价格方案 `/ai/business-case/generate-price-adjustment-instructions/`；知识库 `/ai/business-case/mingyuan-knowledge-base/`；在线客服 `/ai/business-case/online-service/` |

### 12.5 可视化 Chart

| 分组 | 标签页（URL） |
| --- | --- |
| 入口 | 可视化首页 `/chart/`；设计语言 `/chart/design/`；关于 `/chart/design/about/`；案例 `/chart/design/case/` |
| 视觉与终端 | 色彩 `/chart/design/color/`；字体 `/chart/design/typeface/`；布局 `/chart/design/layout/`；PC `/chart/design/pc/`；移动 `/chart/design/mobile/`；可视化大屏 `/chart/design/visual%20large%20screen/` |
| 图表 | 折线 `/chart/graphic/`；面积/百分比 `/chart/graphic/area/`；条形 `/chart/graphic/bar/`；柱状 `/chart/graphic/column/`；饼图 `/chart/graphic/pie/`；雷达 `/chart/graphic/radar/`；漏斗 `/chart/graphic/funnel/`；仪表盘 `/chart/graphic/panel/` |
| 实践 | 最佳实践 `/chart/practice/`；实践详情 `/chart/practice/detail/` |

### 12.6 最佳实践、旧案例与资源

| 分组 | 标签页（URL） |
| --- | --- |
| 最佳实践 | 入口 `/practice/`；基础列表 `/practice/BasicList/`；查询列表 `/practice/QueryList/`；卡片列表 `/practice/CardList/`；基础表单 `/practice/BasicForm/`；高级表单 `/practice/AdvancedForm/`；基础详情 `/practice/BasicDetail/`；高级详情 `/practice/AdvancedDetail/`；仪表板 `/practice/Dashboard/`；工作台 `/practice/Workbench/`；权限 `/practice/Permission/`；异常 `/practice/Unusual/`；404 `/practice/404/` |
| 旧案例 | 入口 `/case/`；基础列表 `/case/grid.html`；树列表 `/case/tree-grid.html`；左树右列表 `/case/left-tree-right-grid.html`；卡片 `/case/card.html`；基础表单 `/case/form.html`；组合表单 `/case/form-compose.html`；布局 `/case/layout.html` |
| 资源 | 资源中心 `/resource/`：Figma、Sketch、Axure 模板、设计指南与专栏 |

> 路由核查备注：`/practice/detail/` 当前返回 404；`/oidc-callback/` 是登录回调，不属于内容标签页；`index.html` 路由多为同页重定向，未重复列入。案例与资源页使用自定义 Vue 页面，服务端 HTML 的正文标题少于普通文档页。

## 13. 参考与维护

- 规范站：https://honeycomb.mingyuanyun.com/
- 移动端：https://honeycomb-design-mobile.mypaas.com.cn/
- 本地色板核对图：[chart-color-audit.png](./chart-color-audit.png)
- 本文是对 2026-09-03 在线版本的结构化快照。原站更新组件、Token 或交互规则后，应同步更新版本号、采集日期和审计清单。
