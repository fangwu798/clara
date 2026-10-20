# Java 开发技术规范（SonarQube 强制门禁版）

> 规则来源：本地 SonarQube 质量配置 当前启用 **479** 条规则。
> 效力：本文件是编码、代码评审、提交前自检、合并门禁的统一依据。与个人编码习惯冲突时，以本文件为准。

---

## 1. 门禁与执行方式

### 1.1 硬性门槛

| 项目 | 要求 |
|---|---|
| 新增代码 | 0 个 BLOCKER、0 个 CRITICAL |
| 修改存量文件 | 不得引入新问题；同一文件内的 MINOR 问题顺手修复 |
| 编译 | `mvn -q -DskipTests package` 通过（JDK 8） |
| 单元测试 | `mvn -q test` 通过；新增或修改逻辑必须有断言 |
| 豁免 | 只允许 `// NOSONAR("原因；负责人；日期")`；CRITICAL 及以上必须经架构评审 |

### 1.2 问题处理时限

| 级别 | 含义 | 处理时限 |
|---|---|---|
| BLOCKER | 必然导致故障、数据损坏或严重安全风险 | 立即修复，禁止合入 |
| CRITICAL | 高概率缺陷或严重坏味道 | 当次提交内修复 |
| MAJOR | 明显影响可维护性 | 本迭代内修复 |
| MINOR | 轻微规范问题 | 修改该文件时顺手修复 |
| INFO | 提示性信息 | 可选 |

### 1.3 规则规模

| 维度 | 分布 |
|---|---|
| 类型 | BUG 139、CODE_SMELL 272、SECURITY_HOTSPOT 37、VULNERABILITY 31 |
| 级别 | BLOCKER 32、CRITICAL 93、MAJOR 218、MINOR 132、INFO 4 |

> 全部启用规则的逐条清单见附录 A。正文按开发场景归类，附录用于逐条核对，两者冲突时以 SonarQube 平台上的实际结果为准。

### 1.4 定制阈值

| 规则 | 含义 | 取值 |
|---|---|---|
| S100 | 方法名 | `^[a-z][a-zA-Z0-9]*$` |
| S101 | 类名 | `^[A-Z][a-zA-Z0-9]*$` |
| S107 | 方法/构造器参数个数上限 | 7 |
| S110 | 继承深度上限 | 5 |
| S115 | 常量名 | `^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$` |
| S116 | 字段名 | `^[a-z][a-zA-Z0-9]*$` |
| S117 | 局部变量/参数名 | `^[a-z][a-zA-Z0-9]*$` |
| S119 | 泛型类型参数名 | `^[A-Z][0-9]?$` |
| S1192 | 重复字符串字面量触发次数 | 3 |
| S1479 | 单个 switch 的 case 上限 | 30 |
| S3776 | 方法认知复杂度上限 | 15 |
| S5843 | 正则表达式复杂度上限 | 20 |
| S5961 | 单个测试方法断言数上限 | 25 |
| S3577 | 测试类命名 | `^((Test|IT)[a-zA-Z0-9_]+|[A-Z][a-zA-Z0-9_]*(Test|Tests|TestCase|IT|ITCase))$` |
| S2187 | 测试类命名 | `.*(Test|Tests|TestCase)` |
| S2068 | 敏感词 | `password,passwd,pwd,passphrase,java.naming.security.credentials` |
| S6418 | 敏感词 | `api[_.-]?key,auth,credential,secret,token` |
| S5693 | 请求体大小上限 | 8388608 字节（8 MB） |

---

## 2. 命名规范

### 2.1 强制命名规则

| 规则 | 对象 | 要求 | 正例 | 反例 |
|---|---|---|---|---|
| S101 | 类 | 大驼峰 | `McpClientFactory` | `mcpClientFactory`、`MCP_CLIENT` |
| S114 | 接口 | 大驼峰；统一使用 `I` 前缀 | `IMcpTransport` | `McpTransportInterface`（缺少 I 前缀） |
| S100 | 方法 | 小驼峰 | `loadServerConfig` | `LoadServerConfig` |
| S116 | 字段 | 小驼峰 | `retryCount` | `RetryCount`、`retry_count` |
| S117 | 局部变量/参数 | 小驼峰，禁止单字母（循环计数器除外） | `responseBody` | `rb`、`ResponseBody` |
| S115 | 常量 | 全大写 + 下划线分隔 | `MAX_RETRY_COUNT` | `maxRetryCount`、`Max_Retry` |
| S119 | 泛型参数 | 单大写字母，可带一位数字 | `T`、`E`、`K`、`V`、`T1` | `type`、`Element` |
| S120 | 包名 | 全小写，禁止下划线和大写 | `com.example.mcp.transport.http` | `com.example.MCP.Transport` |

### 2.2 禁止性命名约束

- **S1845（BLOCKER）**：方法与字段不得同名或仅大小写不同。例如字段 `token` 与方法 `Token()` 同时存在即违规。
- **S2387（BLOCKER）**：子类字段不得遮蔽父类字段。父类已有 `status` 时，子类不得再声明 `status`。
- **S2176（CRITICAL）**：类名不得与接口或父类同名。
- **S1190（BLOCKER）**：不得使用 Java 未来关键字作为标识符，包括 `var`、`record`、`yield`、`sealed`、`permits`。
- **S1220（MINOR）**：禁止使用默认无名包。
- **S4602（BLOCKER）**：`@SpringBootApplication`、`@ComponentScan` 不得放在默认包。

### 2.3 命名约定（团队强制）

| 类别 | 约定 | 示例 |
|---|---|---|
| 接口 | `I` 前缀，统一放在模块的 `api` 包 | `<基础包>.mcp.api.IMcpClient` |
| 默认实现 | `Default` 前缀 | `DefaultMcpClient` |
| 内存实现 | `InMemory` 前缀 | `InMemoryMcpServerRegistry` |
| 枚举 | `XxxEnum` 后缀，统一放公共枚举包 | `ErrorCategoryEnum` |
| DTO | 按用途 `XxxRequest` / `XxxResponse` / `XxxResult`，放 `dto.<领域>` 包 | `InteractionRequest` |
| 测试类 | `XxxTest`（单测）、`XxxIT`（集成测试） | `DefaultMcpClientTest` |
| 常量类 | `XxxConstants`，禁止散落魔法值 | `McpConstants` |

---

## 3. 类型与 API 设计

### 3.1 接口与实现分离

- 对外能力先定义接口，接口放 `api` 包并以 `I` 开头，实现类放具体功能包。
- 接口只声明能力，不暴露实现细节；接口内**禁止定义常量**（S1214，CRITICAL），常量放 `XxxConstants`。
- 返回类型**禁止使用通配符**（S1452，CRITICAL）。需要返回容器时使用 `List<T>`、`Map<K, V>` 等明确类型。
- 接口方法必须有 Javadoc，写明用途、参数、返回值、可能抛出的异常以及线程安全性。

```java
// 正例
public interface IMcpGateway {
    McpToolResult invoke(McpToolCall call) throws BusinessException;
}

// 反例：接口里塞常量
public interface IMcpGateway {
    int DEFAULT_TIMEOUT = 3000; // S1214
}
```

### 3.2 构造器与方法参数

- 参数上限为 **7**（S107）。超过时使用参数对象或构建器，禁止继续堆参数。

```java
// 反例：8 个参数
public Question(String id, String title, String body, List<String> options,
                boolean multiSelect, long timeout, String category, Map<String, Object> extra) { }

// 正例：收敛为参数对象
public Question(QuestionSpec spec) { }
```

- 构造器参数超过 7 个时，优先提取语义对象（如 `QuestionSpec`、`BudgetAmount`），不要用 `Map<String, Object>` 兜底。

### 3.3 继承与组合

- 继承深度不得超过 **5**（S110）。超过说明抽象层次失控，改用组合或策略模式。
- 优先组合而非继承；模板方法只用于流程骨架稳定的场景。

### 3.4 工具类

- 工具类必须是 `final`，构造器私有（S1118）。
- `public static` 字段必须是常量（S1444）；禁止 `public static` 可变字段（S2386）。

```java
public final class StringUtils {
    private StringUtils() {
        throw new AssertionError("No instances");
    }

    public static boolean isBlank(String value) { ... }
}
```

### 3.5 值对象与契约

| 规则 | 要求 |
|---|---|
| S1206 | 重写 `equals(Object)` 必须同时重写 `hashCode()` |
| S1210 | 实现 `Comparable` 时必须同时正确重写 `equals` |
| S2225 | `toString()`、`clone()` 不得返回 `null` |
| S2975（BLOCKER） | 禁止重写 `clone()`，用拷贝构造器或静态工厂替代 |
| S2116 | 禁止对数组调用 `hashCode()` / `toString()`，用 `Arrays.hashCode` / `Arrays.toString` |
| S1948 | `Serializable` 类的字段必须可序列化或声明 `transient` |
| S1113 / S1114 / S1174 / S1175 | 禁止重写 `finalize()`；如确需，必须 `protected`、签名匹配、最后调用 `super.finalize()` |

```java
// 正例：不可变值对象
public final class ModelId {
    private final String value;

    public ModelId(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ModelId)) {
            return false;
        }
        return value.equals(((ModelId) other).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
```

### 3.6 内部类与内部枚举

- **团队强制**：内部类、内部枚举、内部接口一律提取为独立顶层类型，放入职责对应的包。
- 原因：降低耦合、便于复用与单测、避免 Sonar 对内部类可访问性与命名规则的误判。
- 提取后按类型归位：枚举进统一枚举包，DTO 进 `dto` 包，值对象进领域包。

---

## 4. 方法设计与复杂度控制

### 4.1 认知复杂度

- 单个方法认知复杂度不得超过 **15**（S3776，CRITICAL）。
- 拆分手段按优先级：卫语句提前返回 → 提取私有方法 → 策略/枚举分派 → 状态机。
- 禁止用 `try/catch` 包裹大段逻辑来“降低”复杂度。

```java
// 反例：多层嵌套 + 多条件
if (config != null) {
    if (config.isEnabled()) {
        if (StringUtils.isNotBlank(config.getUrl())) {
            // 20 行逻辑
        }
    }
}

// 正例：卫语句 + 提取方法
if (config == null || !config.isEnabled() || StringUtils.isBlank(config.getUrl())) {
    return;
}
doInvoke(config);
```

### 4.2 分支与 switch

| 规则 | 要求 |
|---|---|
| S1479 | 单个 switch 的 case 不超过 30 个，超过改用 `Map` 或枚举分派 |
| S131（CRITICAL） | switch 必须有 `default` 分支 |
| S4524（CRITICAL） | `default` 必须放在最后 |
| S128（BLOCKER） | 每个 case 必须以 `break` / `return` / `throw` 结束，禁止贯穿 |
| S1219（BLOCKER） | switch 内不得出现非 case 标签 |
| S1871 | 两个分支实现完全相同必须合并 |
| S4144 | 两个方法实现完全相同必须合并或删除其一 |

> 团队约定（非 Sonar 规则）：`if` / `for` / `while` 即使只有一行也必须加大括号；条件表达式跨行时，运算符置于行首。对应规范项 S3972、S3973 亦要求条件与执行体分行并缩进。

### 4.3 重复与冗余

- 同一字符串字面量出现 **3 次及以上**必须提取为常量（S1192，CRITICAL）。
- 正则表达式复杂度不得超过 **20**（S5843）；复杂正则必须写注释说明匹配目标，并补充单元测试。
- 禁止无意义的冗余括号（S1110）、空语句（S1116）、未使用标签（S1065）。
- 可合并的 `if` 必须合并（S1066）；`if (a) { if (b) { ... } }` 应写为 `if (a && b)`。
- 方法返回值不得恒定不变（S3516，BLOCKER）；无副作用且恒返回同一值的方法应改为常量或删除。
- 仅作为局部变量使用的私有字段必须改为局部变量（S1450）。

---

## 5. 空值与正确性

| 规则 | 级别 | 要求 |
|---|---|---|
| S2259 | MAJOR | 禁止对可能为 null 的引用解引用；入口参数用 `Objects.requireNonNull` 或显式判空 |
| S2583 | MAJOR | 禁止永远不可达的分支；条件写错导致死代码必须删除 |
| S2589 | MAJOR | 禁止冗余布尔表达式，如 `flag == true`、`x != null || x.y()` |
| S2447 | CRITICAL | 返回 `Boolean` 的方法不得返回 `null`，用 `boolean` 或 `Optional` |
| S1168 | MAJOR | 返回空数组/空集合而不是 `null` |
| S2225 | MAJOR | `toString()`、`clone()` 不得返回 `null` |
| S3518 | CRITICAL | 除数不得可能为 0，必须先校验 |
| S2692 | CRITICAL | `indexOf` 结果判断必须用 `>= 0`，禁止 `> 0` |
| S4635 | CRITICAL | 从指定位置查找子串时必须使用带 `fromIndex` 的重载 |
| S2178 | BLOCKER | 布尔表达式必须使用短路运算符 `&&` / `||`，禁止 `&` / `|` 代替 |
| S6104 | CRITICAL | `computeIfAbsent` / `computeIfPresent` 的映射函数不得返回 `null` |

```java
// 反例
if (text.indexOf("error") > 0) { ... }          // S2692
if (flag == true && list.size() > 0) { ... }    // S2589 + S1155

// 正例
if (text.indexOf("error") >= 0) { ... }
if (flag && !list.isEmpty()) { ... }
```

- 可空返回值优先使用 `Optional`；集合类返回值一律返回空集合，不返回 `null`。
- 外部输入（HTTP、文件、配置、模型输出）必须在使用前完成校验，禁止假设非空。

---

## 6. 异常处理与资源管理

### 6.1 资源关闭

- 所有 `InputStream` / `OutputStream` / `Reader` / `Writer` / `Connection` / `Channel` 必须关闭。
- 优先使用 try-with-resources（S2093，CRITICAL）；未关闭资源属于 BLOCKER（S2095）。

```java
// 正例
try (InputStream in = Files.newInputStream(path);
     BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
    return reader.readLine();
}
```

- **团队强制**：线程池、HTTP 客户端、SSE 连接等长生命周期资源，必须在模块关闭时统一释放，并补充关闭测试。

### 6.2 异常捕获

| 规则 | 级别 | 要求 |
|---|---|---|
| S112 | MAJOR | 禁止抛出 `Exception`、`RuntimeException`、`Throwable` 等通用异常，必须使用具体异常或项目统一业务异常 |
| S1181 | MAJOR | 禁止捕获 `Throwable` / `Error`；仅在最外层兜底且必须重新抛出或终止 |
| S1193 | MAJOR | 禁止在 `catch` 中用 `instanceof` 区分异常类型，应分设多个 `catch` |
| S2139 | MAJOR | 异常要么记录日志要么重新抛出，禁止既记录又抛出（避免重复日志） |
| S2142 | MAJOR | 捕获 `InterruptedException` 后必须恢复中断标志 `Thread.currentThread().interrupt()` 或向上抛出 |
| S2737 | MINOR | `catch` 块只做重新抛出时应直接删除该 `catch` |
| S4970 | CRITICAL | 派生异常不得被父类 `catch` 提前吞掉，注意 `catch` 顺序 |
| S1130 | MINOR | 删除不必要的 `throws` 声明 |
| S108 | MAJOR | 禁止空代码块；确需忽略必须写注释说明原因 |
| S1116 | MINOR | 删除多余分号等空语句 |

### 6.3 finally 与锁

- **S1163（CRITICAL）**：禁止在 `finally` 中抛出异常，会覆盖原始异常。
- **S1143（CRITICAL）**：禁止在 `finally` 中 `return` / `break` / `continue`。
- **S2222（CRITICAL）**：锁必须在所有执行路径释放，推荐 `try/finally` 或 `ReentrantLock` 的 `lockInterruptibly` + `finally`。

```java
// 正例
Lock lock = lockRef.get();
lock.lock();
try {
    updateState();
} finally {
    lock.unlock();
}
```

### 6.4 日志与格式串

- 禁止 `System.out` / `System.err`（S106），统一使用日志框架。
- 格式化字符串的参数个数与类型必须匹配（S2275 BLOCKER、S3457 MAJOR）；占位符数量与参数数量不一致会在运行时抛异常。
- 日志中禁止输出 API Key、Token、密码、完整请求头等敏感信息。

```java
// 反例：占位符与参数不匹配
logger.info("model={} cost={}", model);            // S3457

// 正例
logger.info("model={} cost={}", model, cost);
```

---

## 7. 并发与线程安全

### 7.1 共享状态

| 规则 | 级别 | 要求 |
|---|---|---|
| S2885 | MAJOR | 非线程安全类型不得声明为 `static` 可变字段（如 `SimpleDateFormat`、`ArrayList`） |
| S3077 | MINOR | 非基本类型字段不要用 `volatile` 保证线程安全；`volatile` 只能保证可见性与有序性，不能保证复合操作原子性 |
| S2168 | BLOCKER | 禁止双重检查锁定（DCL），改用静态内部类持有或枚举单例 |
| S3014 | BLOCKER | 禁止使用 `ThreadGroup` |

```java
// 反例：volatile 无法保证自增原子性
private volatile int counter;

public void increment() {
    counter++; // 仍然存在竞态
}

// 正例：使用原子类型
private final AtomicInteger counter = new AtomicInteger();

public void increment() {
    counter.incrementAndGet();
}
```

> **团队强制**：共享可变状态必须使用以下手段之一并在 Javadoc 中声明线程安全性：
> 1. 不可变对象（`final` 字段 + 防御性拷贝）；
> 2. `Atomic*` / `LongAdder`；
> 3. `ConcurrentHashMap`、`CopyOnWriteArrayList` 等并发容器；
> 4. 显式锁，且锁范围最小化。

### 7.2 等待与通知

| 规则 | 级别 | 要求 |
|---|---|---|
| S2274 | CRITICAL | `wait()` / `await()` 必须放在 `while` 循环中，防虚假唤醒 |
| S2273 | MAJOR | 调用 `wait` / `notify` / `notifyAll` 时必须明显持有对象锁 |
| S2276 | BLOCKER | 持锁时禁止用 `Thread.sleep` 等待条件，必须用 `wait` |
| S2236 | BLOCKER | 禁止在 `Thread` 实例上调用 `wait` / `notify` / `notifyAll` |
| S3046 | BLOCKER | 持有多把锁时禁止调用 `wait` |
| S1217 | MAJOR | 禁止直接调用 `Thread.run()`，必须 `start()` 或提交到线程池 |

```java
// 正例
synchronized (monitor) {
    while (!ready) {
        monitor.wait();
    }
    consume();
}
```

### 7.3 线程池与定时任务

- `ScheduledThreadPoolExecutor` 的 corePoolSize 不得为 0（S2122，CRITICAL）。
- 线程池必须命名线程、设置拒绝策略，并在关闭时 `shutdown` + `awaitTermination`。
- 禁止在循环中用 `Thread.sleep` 忙等（团队约定）；使用 `CountDownLatch`、`Condition` 或带超时的轮询。
- `Random` 对象必须复用（S2119）；安全场景使用 `SecureRandom`（S2245、S4347）。

---

## 8. 集合、泛型与 Map

| 规则 | 级别 | 要求 |
|---|---|---|
| S3740 | MAJOR | 禁止使用原始类型（raw type），必须写完整泛型 |
| S2293 | MINOR | 构造器必须使用菱形运算符 `<>` |
| S1319 | MINOR | 变量与参数声明使用接口类型（`List`、`Map`），而非实现类 |
| S1155 | MINOR | 判空集合必须用 `isEmpty()`，禁止 `size() == 0` / `size() > 0` |
| S1640 | MINOR | 以枚举为 key 的 Map 必须使用 `EnumMap` |
| S2116 | MAJOR | 数组的 `hashCode` / `toString` 必须使用 `Arrays` 工具方法 |
| S6104 | CRITICAL | `computeIfAbsent` / `computeIfPresent` 不得写入 `null` |

```java
// 反例
Map<String, List<String>> index = new HashMap<String, List<String>>(); // S2293
List<String> items = new ArrayList<String>();                          // S2293
if (items.size() > 0) { ... }                                          // S1155

// 正例
Map<String, List<String>> index = new HashMap<>();
List<String> items = new ArrayList<>();
if (!items.isEmpty()) { ... }
```

- **团队强制**：对外返回的集合必须是不可变视图或防御性拷贝，禁止直接暴露内部可变集合。
- 遍历集合删除元素必须使用 `Iterator.remove()` 或 `removeIf()`，禁止在 for-each 中直接 `remove`。

---

## 9. 代码卫生与可维护性

### 9.1 死代码与冗余

| 规则 | 级别 | 要求 |
|---|---|---|
| S1068 | MAJOR | 删除未使用的私有字段 |
| S1144 | MAJOR | 删除未使用的私有方法 |
| S1481 | MINOR | 删除未使用的局部变量 |
| S1854 | MAJOR | 删除无效赋值（赋值后从未读取） |
| S1172 | MAJOR | 删除未使用的参数；接口实现必须保留的用 `@SuppressWarnings` 并注释 |
| S1128 | MINOR | 删除未使用的 import |
| S125 | MAJOR | 删除被注释掉的代码，历史交给 Git |
| S1065 | MAJOR | 删除未使用的标签 |
| S1659 | MINOR | 一行只声明一个变量 |

### 9.2 表达简洁

- Lambda 能替换为方法引用时必须替换（S1612）。
- 删除冗余括号（S1110）、合并可合并的 `if`（S1066）。
- 禁止无意义位运算（S2437，BLOCKER）。
- `for` 循环的增量部分必须修改循环计数器（S1994，CRITICAL）。
- 禁止显式调用 `System.gc()`（S1215，CRITICAL）。
- 字面量中的空白与控制字符必须用转义表示（S2479，CRITICAL），例如 `"\t"`、`"\u0000"`。

### 9.3 注释与文档

- 被 `@Deprecated` 标记的元素必须同时提供注解与 `@deprecated` Javadoc 说明（S1123）。
- **团队强制**：所有 `public` / `protected` 类型与成员必须有 Javadoc，至少包含：
  - 一句话职责说明；
  - `@param`（有参数时）；
  - `@return`（有返回值时）；
  - `@throws`（声明异常时）；
  - 线程安全性说明（共享组件必须写）。
- 禁止无意义注释（如 `// set name`）；注释解释“为什么”，而不是“做了什么”。

### 9.4 框架相关

| 规则 | 级别 | 要求 |
|---|---|---|
| S3305 | CRITICAL | `@Configuration` 类中 Bean 依赖必须通过方法参数注入，禁止字段注入 |
| S2229 | BLOCKER | 同一个类内方法调用不得出现不兼容的 `@Transactional` 传播配置 |
| S3753 | BLOCKER | 使用 `@SessionAttributes` 的控制器必须在完成后调用 `SessionStatus.setComplete()` |
| S4684 | CRITICAL | 持久化实体不得直接作为 `@RequestMapping` 方法参数 |
| S5803 | CRITICAL | `@VisibleForTesting` 成员不得被生产代码访问 |
| S4512 | CRITICAL | 禁止将不可信输入直接批量设置到 JavaBean 属性 |

- **团队强制**：依赖注入统一使用构造器注入；禁止字段注入与可变单例状态。
---

## 10. 测试规范

| 规则 | 级别 | 要求 |
|---|---|---|
| S2699 | BLOCKER | 每个测试方法必须包含断言，禁止只调用不校验 |
| S2187 | BLOCKER | 测试类必须包含测试方法，禁止空测试类 |
| S2970 | BLOCKER | 断言必须完整，`assertEquals` 必须给出期望值与实际值，禁止只写消息 |
| S2925 | MAJOR | 测试中禁止 `Thread.sleep`，改用 `CountDownLatch`、`Awaitility` 或带超时的轮询 |
| S3415 | MAJOR | JUnit 断言参数顺序必须是“期望值在前、实际值在后” |
| S3577 | MINOR | 测试类命名符合项目定制正则，统一 `XxxTest` / `XxxIT` |
| S5961 | MAJOR | 单个测试方法断言不超过 25 条，超过应拆分用例 |
| S5853 | MINOR | 连续 AssertJ `assertThat` 应链式书写 |
| S1607 | MAJOR | `@Ignore` / `@Disabled` 必须写明原因与恢复条件 |
| S2188 | BLOCKER | 继承 JUnit3 `TestCase` 时必须调用 `super.setUp` / `super.tearDown` |
| S5790 | CRITICAL | JUnit5 内部测试类必须标注 `@Nested` |
| S5826 | CRITICAL | JUnit4 起 `setUp` / `tearDown` 必须使用 `@Before` / `@After`，不得依赖命名约定 |
| S5969 | CRITICAL | 只 mock 必要的方法，禁止 mock 类的全部非私有方法 |

### 10.1 测试约定

- 测试类与被测类同包，目录结构镜像 `src/main/java`。
- 测试必须可重复执行：不依赖执行顺序、不依赖外部网络、不依赖系统时间与本地时区。
- 涉及时间、随机数、UUID 的逻辑必须注入可替换的时钟/生成器。
- 新增分支必须覆盖：正常路径、边界值、异常路径。
- 集成测试以 `IT` 结尾，默认不随 `mvn test` 执行；涉及容器的测试必须提供跳过开关。
- 禁止为了测试放宽生产代码可见性；确需放宽时使用 `@VisibleForTesting`，且生产代码不得调用（S5803）。

```java
// 正例
@Test
void shouldRejectBlankModelName() {
    BusinessException ex = assertThrows(BusinessException.class,
            () -> registry.register(" "));
    assertEquals(ErrorCodes.INVALID_ARGUMENT, ex.errorCode());
}
```

---

## 11. 安全编码

### 11.1 凭证与密钥

| 规则 | 级别 | 要求 |
|---|---|---|
| S2068 | BLOCKER | 禁止硬编码密码、口令、`pwd`、`passphrase` |
| S6418 | BLOCKER | 禁止硬编码 API Key、Token、Secret、Credential |
| S6437 | BLOCKER | 禁止在源码中硬编码任何凭证 |
| S2115 | BLOCKER | 数据库连接禁止使用空密码或默认弱口令 |
| S5344 | CRITICAL | 密码不得明文存储，也不得使用 MD5/SHA 等快速哈希；使用 BCrypt / Argon2 / PBKDF2 |
| S2053 | CRITICAL | 密码哈希必须使用不可预测的随机盐 |
| S4790 | CRITICAL | MD5、SHA-1 不得用于安全用途 |
| S4347 | CRITICAL | `SecureRandom` 不得使用可预测种子（时间戳、固定常量） |
| S2245 | CRITICAL | 安全场景禁止使用 `java.util.Random`，必须使用 `SecureRandom` |

```java
// 反例
private static final String API_KEY = "sk-xxxx"; // S6418 / S6437

// 正例：从环境变量或配置读取
String apiKey = System.getenv("CLARA_DEEPSEEK_API_KEY");
```

**团队强制**：

1. 所有平台密钥统一通过环境变量注入，命名规范 `CLARA_<PROVIDER>_API_KEY`。
2. 配置文件中只允许出现环境变量占位符，禁止出现真实密钥。
3. 日志、异常消息、控制台输出必须脱敏：仅保留前 4 位与后 4 位。
4. 密钥不得写入 Git、测试资源、Docker 镜像与构建产物。

### 11.2 加密与传输

| 规则 | 级别 | 要求 |
|---|---|---|
| S5542 | CRITICAL | 必须使用安全的加密模式与填充，如 AES/GCM/NoPadding |
| S5547 | CRITICAL | 禁止 DES、RC4、Blowfish 等弱算法 |
| S3329 | CRITICAL | CBC 模式的 IV 必须不可预测，禁止固定 IV |
| S6432 | CRITICAL | GCM 的计数器 IV 不得复用 |
| S4423 | CRITICAL | 禁止 SSLv3、TLSv1.0、TLSv1.1 |
| S4426 | CRITICAL | 密钥长度必须足够（RSA ≥ 2048 位，AES ≥ 128 位） |
| S5659 | CRITICAL | JWT 必须使用强算法并验签，禁止 `none` 算法 |
| S4830 | CRITICAL | 禁止信任所有证书 |
| S5527 | CRITICAL | 必须校验服务端主机名 |
| S2647 | CRITICAL | 禁止使用 HTTP Basic 认证（内网工具确需使用必须评审豁免） |
| S5332 | CRITICAL | 禁止使用明文协议（HTTP、FTP、Telnet），统一 HTTPS |

### 11.3 注入与解析

| 规则 | 级别 | 要求 |
|---|---|---|
| S2077 | MAJOR | 禁止字符串拼接 SQL，必须使用参数化查询 |
| S2755 | BLOCKER | XML 解析必须禁用外部实体，防 XXE |
| S6373 | BLOCKER | XML 解析禁止加载任意外部文件 |
| S6374 | MAJOR | XML 解析禁止加载外部 Schema |
| S6376 | MAJOR | XML 解析必须限制实体扩展，防 DoS |
| S6377 | MAJOR | XML 签名必须安全校验 |
| S4544 | CRITICAL | Jackson 禁止开启默认类型信息（`enableDefaultTyping`） |
| S4502 | CRITICAL | 禁止关闭 CSRF 防护（确需关闭必须评审并限定范围） |
| S4512 | CRITICAL | 禁止对不可信输入做 JavaBean 批量属性赋值 |
| S5876 | CRITICAL | 用户登录成功后必须新建 Session，防会话固定 |
| S5042 | CRITICAL | 解压必须限制文件数量、单文件大小与总大小，防 zip bomb |

```java
// 正例：安全 XML 解析
DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
factory.setXIncludeAware(false);
factory.setExpandEntityReferences(false);
```

### 11.4 文件、Cookie 与网络边界

| 规则 | 级别 | 要求 |
|---|---|---|
| S5443 | CRITICAL | 禁止使用全局可写目录存放临时文件 |
| S5445 | CRITICAL | 必须使用 `Files.createTempFile` 等安全 API 创建临时文件 |
| S2612 | MAJOR | 禁止设置过宽的文件权限（如 0777） |
| S5693 | MAJOR | 必须限制请求体大小（建议上限 8 MB） |
| S3330 | MINOR | Cookie 必须设置 `HttpOnly` |
| S2092 | MINOR | Cookie 必须设置 `Secure` |
| S5122 | MINOR | CORS 必须配置白名单，禁止 `*` 与凭据同时放开 |
| S1313 | MINOR | 禁止硬编码 IP 地址，必须走配置 |
| S1075 | MINOR | 禁止硬编码 URL/URI，必须走配置 |
| S5852 | CRITICAL | 禁止易受 ReDoS 攻击的正则，必须做复杂度与超时保护 |

**团队强制**：

- 文件上传必须校验扩展名、MIME、大小与内容头，并重命名后落盘。
- 外部 URL 请求必须限制协议、域名白名单与响应大小，禁止 SSRF。
- 反序列化只允许白名单类型；禁止反序列化不可信数据。

---

## 12. 项目附加强制约定

以下约定超出 SonarQube 规则范围，但同样是合并门禁的一部分。

### 12.1 语言与依赖

- 语言基线以项目构建配置（如 `pom.xml`）声明为准；禁止使用高于基线的语法。例如 Java 8 项目禁用 `var`、`record`、文本块、`switch` 表达式、`sealed`。
- 新增第三方依赖必须经评审，并纳入统一 BOM 管理（如 `clara-bom`）。
- 禁止引入与现有能力重复的工具库（如再引入一套 JSON 或日志门面）。

### 12.2 模块与包结构

| 内容 | 位置 |
|---|---|
| 接口 | `<module>/api`，类型名以 `I` 开头 |
| 默认实现 | 功能包，`Default` 前缀 |
| 枚举 | `<基础包>.enums`，`XxxEnum` 后缀 |
| DTO | `<基础包>.dto.<领域>` |
| 异常与错误码 | `<基础包>.exception`（统一业务异常、错误对象、错误码） |
| 公共工具 | `<基础包>.utils`（`StringUtils`、`ObjectUtils`、`MapUtils`） |
| 配置 | `config/`，运行期配置统一使用项目主配置文件 |

- **禁止在业务类中新增私有通用工具方法**。字符串判空、对象比较、Map 拷贝等能力必须复用 `<基础包>.utils` 下已有实现；缺失时先补充到公共基础模块并配套单测。
- 跨模块复用的类型必须下沉到公共基础模块，禁止在多个模块重复定义同义类型。

### 12.3 配置与运行

- 配置外置：端口、模型名、超时、开关、路径一律来自配置文件与环境变量，禁止硬编码。
- 配置项必须有默认值、类型校验与启动期日志（不打印敏感值）。
- 新增开关必须支持“关闭即完全不加载相关组件”，避免隐式依赖。

### 12.4 日志与可观测性

- 统一使用日志框架，禁止 `System.out` / `System.err`（S106），禁止 `printStackTrace()`。
- 日志级别使用规范：`ERROR` 仅用于需要人工介入的故障；`WARN` 用于可自愈异常；`INFO` 用于关键状态变更；`DEBUG` 用于排障细节。
- 关键链路必须打印耗时与结果状态，但不得打印完整报文与密钥。
- 异常日志必须包含：操作、关键标识、错误码；禁止只打印 `e.getMessage()`。

### 12.5 错误模型

- 业务失败统一抛出项目业务异常，携带错误对象（错误码 + 分类 + 用户可读消息）。
- 禁止吞异常、禁止返回 `null` 表示失败、禁止用布尔值表达多种失败原因。
- 对外错误消息不得暴露堆栈、SQL、文件路径与内部主机信息。

### 12.6 不可变与线程安全

- DTO、值对象、配置快照优先设计为不可变：字段 `final`，集合做防御性拷贝。
- 共享组件必须在类 Javadoc 中声明线程安全性（线程安全 / 非线程安全 / 不可变）。
- 禁止使用 `volatile` 修饰可变引用类型来“解决”线程安全问题（S3077）；使用原子类、并发容器或锁。

---

## 13. 豁免与不适用

### 13.1 豁免流程

1. 优先修复，不豁免。
2. 确因框架约束、兼容性要求无法修复时，在违规行上方添加：

```java
// NOSONAR("原因：框架要求签名一致；负责人：张三；到期：2026-12-31")
```

3. 豁免仅允许 MINOR / MAJOR；**CRITICAL / BLOCKER 一律需要架构评审并登记**。
4. 豁免必须可追踪：每季度复核一次，到期未续期视为必须修复。
5. 禁止使用 `@SuppressWarnings("all")`、全局关闭规则、修改质量配置来规避问题。

### 13.2 当前不适用的规则

以下规则在 JDK 8 基线项目中不会触发，升级语言版本后需重新评估：

| 规则 | 名称 | 不适用原因 |
|---|---|---|
| S6209 | Members ignored during record serialization should not be used | 基线低于 Java 16 时不适用 |
| S6203 | Text blocks should not be too long | 基线低于 Java 15 时不适用 |

以下规则仅适用于 Android / 移动端，当前后端仓库不涉及，若新增移动端模块再纳入门禁：

`S6363`、`S6362`、`S6288`、`S6291`、`S6301`、`S6300`、`S5322`、`S5324`、`S5320`。

### 13.3 规则清单维护

- 质量配置变更（增删规则、调整阈值）必须同步更新本文件与附录 A。
- 附录 A 由 SonarQube API 导出生成，禁止手工编造规则键。
- 每季度核对一次规则数量与阈值，确保文档与平台一致。

---

## 14. 提交前检查清单

- [ ] `mvn -q -DskipTests package` 通过
- [ ] `mvn -q test` 通过，新增逻辑有断言
- [ ] IDE SonarLint 无新增 BLOCKER / CRITICAL
- [ ] 无 `System.out` / `printStackTrace`
- [ ] 无硬编码密钥、密码、Token、IP、绝对路径
- [ ] 无注释掉的代码、无未使用字段/方法/变量/import
- [ ] 新增接口以 `I` 开头并位于 `api` 包
- [ ] 内部类、内部枚举已提取为独立类型
- [ ] 方法认知复杂度 ≤ 15，参数 ≤ 7，switch 有 `default`
- [ ] 资源已关闭，`InterruptedException` 已正确处理
- [ ] 共享状态线程安全，`volatile` 未被误用
- [ ] 公开 API 具备 Javadoc，错误模型使用统一业务异常
- [ ] 日志不含敏感信息，配置已外置
- [ ] 新增或修改的规则豁免均已登记原因、负责人与到期日
---

## 附录 A：启用规则全量清单（479 条）

> 数据来源：SonarQube 质量配置 `Clara Java`（qprofile key `AaFTg-cA23dAA9mQC31f`）。规则名称保留平台原文，便于在 SonarQube / SonarLint 中直接检索。
> 说明：正文按开发场景给出强制约定与示例；本附录用于逐条核对，覆盖全部启用规则。

### A.1 BLOCKER（32 条）

| 规则键 | 类型 | 规则名称 |
|---|---|---|
| `java:S128` | CODE_SMELL | Switch cases should end with an unconditional "break" statement |
| `java:S1190` | CODE_SMELL | Future keywords should not be used as names |
| `java:S1219` | CODE_SMELL | "switch" statements should not contain non-case labels |
| `java:S1845` | CODE_SMELL | Methods and field names should not be the same or differ only by capitalization |
| `java:S2068` | SECURITY_HOTSPOT | Hard-coded passwords are security-sensitive |
| `java:S2095` | BUG | Resources should be closed |
| `java:S2115` | VULNERABILITY | A secure password should be used when connecting to a database |
| `java:S2168` | BUG | Double-checked locking should not be used |
| `java:S2178` | CODE_SMELL | Short-circuit logic should be used in boolean contexts |
| `java:S2187` | CODE_SMELL | TestCases should contain tests |
| `java:S2188` | CODE_SMELL | JUnit test cases should call super methods |
| `java:S2189` | BUG | Loops should not be infinite |
| `java:S2229` | BUG | Methods should not call same-class methods with incompatible "@Transactional" values |
| `java:S2236` | BUG | Methods "wait(...)", "notify()" and "notifyAll()" should not be called on Thread instances |
| `java:S2275` | BUG | Printf-style format strings should not lead to unexpected behavior at runtime |
| `java:S2276` | BUG | "wait(...)" should be used instead of "Thread.sleep(...)" when a lock is held |
| `java:S2387` | CODE_SMELL | Child class fields should not shadow parent class fields |
| `java:S2437` | CODE_SMELL | Silly bit operations should not be performed |
| `java:S2689` | BUG | Files opened in append mode should not be used with ObjectOutputStream |
| `java:S2695` | BUG | "PreparedStatement" and "ResultSet" methods should be called with valid indices |
| `java:S2699` | CODE_SMELL | Tests should include assertions |
| `java:S2755` | VULNERABILITY | XML parsers should not be vulnerable to XXE attacks |
| `java:S2970` | CODE_SMELL | Assertions should be complete |
| `java:S2975` | CODE_SMELL | "clone" should not be overridden |
| `java:S3014` | CODE_SMELL | "ThreadGroup" should not be used |
| `java:S3046` | BUG | "wait" should not be called when multiple locks are held |
| `java:S3516` | CODE_SMELL | Methods returns should not be invariant |
| `java:S3753` | BUG | "@Controller" classes that use "@SessionAttributes" must call "setComplete" on their "SessionStatus" objects |
| `java:S4602` | BUG | "@SpringBootApplication" and "@ComponentScan" should not be used in the default package |
| `java:S6373` | VULNERABILITY | XML parsers should not allow inclusion of arbitrary files |
| `java:S6418` | SECURITY_HOTSPOT | Hard-coded secrets are security-sensitive |
| `java:S6437` | VULNERABILITY | Credentials should not be hard-coded |

### A.2 CRITICAL（93 条）

| 规则键 | 类型 | 规则名称 |
|---|---|---|
| `java:S115` | CODE_SMELL | Constant names should comply with a naming convention |
| `java:S131` | CODE_SMELL | "switch" statements should have "default" clauses |
| `java:S1113` | CODE_SMELL | The Object.finalize() method should not be overridden |
| `java:S1114` | BUG | "super.finalize()" should be called at the end of "Object.finalize()" implementations |
| `java:S1143` | BUG | Jump statements should not occur in "finally" blocks |
| `java:S1163` | CODE_SMELL | Exceptions should not be thrown in finally blocks |
| `java:S1174` | CODE_SMELL | "Object.finalize()" should remain protected (versus public) when overriding |
| `java:S1175` | BUG | The signature of "finalize()" should match that of "Object.finalize()" |
| `java:S1186` | CODE_SMELL | Methods should not be empty |
| `java:S1192` | CODE_SMELL | String literals should not be duplicated |
| `java:S1214` | CODE_SMELL | Constants should not be defined in interfaces |
| `java:S1215` | CODE_SMELL | Execution of the Garbage Collector should be triggered only by the JVM |
| `java:S1452` | CODE_SMELL | Generic wildcard types should not be used in return types |
| `java:S1598` | CODE_SMELL | Package declaration should match source file directory |
| `java:S1948` | CODE_SMELL | Fields in a "Serializable" class should either be transient or serializable |
| `java:S1994` | CODE_SMELL | "for" loop increment clauses should modify the loops' counters |
| `java:S2053` | VULNERABILITY | Hashes should include an unpredictable salt |
| `java:S2062` | CODE_SMELL | "readResolve" methods should be inheritable |
| `java:S2093` | CODE_SMELL | Try-with-resources should be used |
| `java:S2119` | BUG | "Random" objects should be reused |
| `java:S2122` | BUG | "ScheduledThreadPoolExecutor" should not have 0 core threads |
| `java:S2151` | BUG | "runFinalizersOnExit" should not be called |
| `java:S2157` | CODE_SMELL | "Cloneables" should implement "clone" |
| `java:S2176` | CODE_SMELL | Class names should not shadow interfaces or superclasses |
| `java:S2186` | CODE_SMELL | JUnit assertions should not be used in "run" methods |
| `java:S2222` | BUG | Locks should be released on all paths |
| `java:S2235` | CODE_SMELL | IllegalMonitorStateException should not be caught |
| `java:S2245` | SECURITY_HOTSPOT | Using pseudorandom number generators (PRNGs) is security-sensitive |
| `java:S2254` | VULNERABILITY | "HttpServletRequest.getRequestedSessionId()" should not be used |
| `java:S2257` | SECURITY_HOTSPOT | Using non-standard cryptographic algorithms is security-sensitive |
| `java:S2274` | CODE_SMELL | "Object.wait(...)" and "Condition.await(...)" should be called inside a "while" loop |
| `java:S2390` | CODE_SMELL | Classes should not access their own subclasses during initialization |
| `java:S2447` | CODE_SMELL | Null should not be returned from a "Boolean" method |
| `java:S2479` | CODE_SMELL | Whitespace and control characters in literals should be explicit |
| `java:S2638` | CODE_SMELL | Method overrides should not change contracts |
| `java:S2647` | VULNERABILITY | Basic authentication should not be used |
| `java:S2692` | CODE_SMELL | "indexOf" checks should not be for positive numbers |
| `java:S2696` | CODE_SMELL | Instance methods should not write to "static" fields |
| `java:S3252` | CODE_SMELL | "static" base class members should not be accessed via derived types |
| `java:S3305` | CODE_SMELL | Factory method injection should be used in "@Configuration" classes |
| `java:S3329` | VULNERABILITY | Cipher Block Chaining IVs should be unpredictable |
| `java:S3518` | BUG | Zero should not be a possible denominator |
| `java:S3776` | CODE_SMELL | Cognitive Complexity of methods should not be too high |
| `java:S3972` | CODE_SMELL | Conditionals should start on new lines |
| `java:S3973` | CODE_SMELL | A conditionally executed single line should be denoted by indentation |
| `java:S4275` | BUG | Getters and setters should access the expected fields |
| `java:S4347` | VULNERABILITY | "SecureRandom" seeds should not be predictable |
| `java:S4423` | VULNERABILITY | Weak SSL/TLS protocols should not be used |
| `java:S4426` | VULNERABILITY | Cryptographic keys should be robust |
| `java:S4433` | VULNERABILITY | LDAP connections should be authenticated |
| `java:S4454` | CODE_SMELL | "equals" method parameters should not be marked "@Nonnull" |
| `java:S4502` | SECURITY_HOTSPOT | Disabling CSRF protections is security-sensitive |
| `java:S4512` | SECURITY_HOTSPOT | Setting JavaBean properties is security-sensitive |
| `java:S4524` | CODE_SMELL | "default" clauses should be last |
| `java:S4544` | SECURITY_HOTSPOT | Using unsafe Jackson deserialization configuration is security-sensitive |
| `java:S4601` | VULNERABILITY | "HttpSecurity" URL patterns should be correctly ordered |
| `java:S4635` | CODE_SMELL | String offset-based methods should be preferred for finding substrings from offsets |
| `java:S4684` | VULNERABILITY | Persistent entities should not be used as arguments of "@RequestMapping" methods |
| `java:S4790` | SECURITY_HOTSPOT | Using weak hashing algorithms is security-sensitive |
| `java:S4792` | SECURITY_HOTSPOT | Configuring loggers is security-sensitive |
| `java:S4830` | VULNERABILITY | Server certificates should be verified during SSL/TLS connections |
| `java:S4970` | CODE_SMELL | Derived exceptions should not hide their parents' catch blocks |
| `java:S5042` | SECURITY_HOTSPOT | Expanding archive files without controlling resource consumption is security-sensitive |
| `java:S5320` | SECURITY_HOTSPOT | Broadcasting intents is security-sensitive |
| `java:S5322` | SECURITY_HOTSPOT | Receiving intents is security-sensitive |
| `java:S5324` | SECURITY_HOTSPOT | Accessing Android external storage is security-sensitive |
| `java:S5332` | SECURITY_HOTSPOT | Using clear-text protocols is security-sensitive |
| `java:S5344` | VULNERABILITY | Passwords should not be stored in plain-text or with a fast hashing algorithm |
| `java:S5361` | CODE_SMELL | "String#replace" should be preferred to "String#replaceAll" |
| `java:S5443` | SECURITY_HOTSPOT | Using publicly writable directories is security-sensitive |
| `java:S5445` | VULNERABILITY | Insecure temporary file creation methods should not be used |
| `java:S5527` | VULNERABILITY | Server hostnames should be verified during SSL/TLS connections |
| `java:S5542` | VULNERABILITY | Encryption algorithms should be used with secure mode and padding scheme |
| `java:S5547` | VULNERABILITY | Cipher algorithms should be robust |
| `java:S5659` | VULNERABILITY | JWT should be signed and verified with strong cipher algorithms |
| `java:S5779` | BUG | Assertion methods should not be used within the try block of a try-catch catching an Error |
| `java:S5783` | BUG | Only one method invocation is expected when testing checked exceptions |
| `java:S5790` | BUG | JUnit5 inner test classes should be annotated with @Nested |
| `java:S5803` | CODE_SMELL | Class members annotated with "@VisibleForTesting" should not be accessed from production code |
| `java:S5826` | CODE_SMELL | Methods setUp() and tearDown() should be correctly annotated starting with JUnit4 |
| `java:S5845` | BUG | Assertions comparing incompatible types should not be made |
| `java:S5846` | CODE_SMELL | Empty lines should not be tested with regex MULTILINE flag |
| `java:S5852` | SECURITY_HOTSPOT | Using slow regular expressions is security-sensitive |
| `java:S5856` | BUG | Regular expressions should be syntactically valid |
| `java:S5876` | VULNERABILITY | A new session should be created during user authentication |
| `java:S5969` | CODE_SMELL | Mocking all non-private methods of a class should be avoided |
| `java:S5994` | BUG | Regex patterns following a possessive quantifier should not always fail |
| `java:S5996` | BUG | Regex boundaries should not be used in a way that can never be matched |
| `java:S6001` | BUG | Back references in regular expressions should only refer to capturing groups that are matched before the reference |
| `java:S6002` | BUG | Regex lookahead assertions should not be contradictory |
| `java:S6104` | BUG | Map "computeIfAbsent()" and "computeIfPresent()" should not be used to add "null" values. |
| `java:S6209` | BUG | Members ignored during record serialization should not be used |
| `java:S6432` | VULNERABILITY | Counter Mode initialization vectors should not be reused |

### A.3 MAJOR（218 条）

| 规则键 | 类型 | 规则名称 |
|---|---|---|
| `java:S106` | CODE_SMELL | Standard outputs should not be used directly to log anything |
| `java:S107` | CODE_SMELL | Methods should not have too many parameters |
| `java:S108` | CODE_SMELL | Nested blocks of code should not be left empty |
| `java:S110` | CODE_SMELL | Inheritance tree of classes should not be too deep |
| `java:S112` | CODE_SMELL | Generic exceptions should never be thrown |
| `java:S125` | CODE_SMELL | Sections of code should not be commented out |
| `java:S127` | CODE_SMELL | "for" loop stop conditions should be invariant |
| `java:S1065` | CODE_SMELL | Unused labels should be removed |
| `java:S1066` | CODE_SMELL | Collapsible "if" statements should be merged |
| `java:S1068` | CODE_SMELL | Unused "private" fields should be removed |
| `java:S1110` | CODE_SMELL | Redundant pairs of parentheses should be removed |
| `java:S1111` | BUG | The Object.finalize() method should not be called |
| `java:S1117` | CODE_SMELL | Local variables should not shadow class fields |
| `java:S1118` | CODE_SMELL | Utility classes should not have public constructors |
| `java:S1119` | CODE_SMELL | Labels should not be used |
| `java:S1121` | CODE_SMELL | Assignments should not be made from within sub-expressions |
| `java:S1123` | CODE_SMELL | Deprecated elements should have both the annotation and the Javadoc tag |
| `java:S1134` | CODE_SMELL | Track uses of "FIXME" tags |
| `java:S1141` | CODE_SMELL | Try-catch blocks should not be nested |
| `java:S1144` | CODE_SMELL | Unused "private" methods should be removed |
| `java:S1149` | CODE_SMELL | Synchronized classes Vector, Hashtable, Stack and StringBuffer should not be used |
| `java:S1150` | CODE_SMELL | Enumeration should not be implemented |
| `java:S1161` | CODE_SMELL | "@Override" should be used on overriding and implementing methods |
| `java:S1168` | CODE_SMELL | Empty arrays and collections should be returned instead of null |
| `java:S1171` | CODE_SMELL | Only static class initializers should be used |
| `java:S1172` | CODE_SMELL | Unused method parameters should be removed |
| `java:S1181` | CODE_SMELL | Throwable and Error should not be caught |
| `java:S1191` | CODE_SMELL | Classes from "sun.*" packages should not be used |
| `java:S1193` | CODE_SMELL | Exception types should not be tested using "instanceof" in catch blocks |
| `java:S1201` | BUG | "equals" method overrides should accept "Object" parameters |
| `java:S1217` | BUG | "Thread.run()" should not be called directly |
| `java:S1221` | BUG | Methods should not be named "tostring", "hashcode" or "equal" |
| `java:S1223` | CODE_SMELL | Non-constructor methods should not have the same name as the enclosing class |
| `java:S1317` | BUG | "StringBuilder" and "StringBuffer" should not be instantiated with a character |
| `java:S1479` | CODE_SMELL | "switch" statements should not have too many "case" clauses |
| `java:S1604` | CODE_SMELL | Anonymous inner classes containing only one method should become lambdas |
| `java:S1607` | CODE_SMELL | JUnit4 @Ignored and JUnit5 @Disabled annotations should be used to disable tests and should provide a rationale |
| `java:S1656` | BUG | Variables should not be self-assigned |
| `java:S1700` | CODE_SMELL | A field should not duplicate the name of its containing class |
| `java:S1751` | BUG | Loops with at most one iteration should be refactored |
| `java:S1764` | BUG | Identical expressions should not be used on both sides of a binary operator |
| `java:S1844` | CODE_SMELL | "Object.wait(...)" should never be called on objects that implement "java.util.concurrent.locks.Condition" |
| `java:S1849` | BUG | "Iterator.hasNext()" should not call "Iterator.next()" |
| `java:S1854` | CODE_SMELL | Unused assignments should be removed |
| `java:S1860` | BUG | Synchronization should not be done on instances of value-based classes |
| `java:S1862` | BUG | Related "if/else if" statements should not have the same condition |
| `java:S1871` | CODE_SMELL | Two branches in a conditional structure should not have exactly the same implementation |
| `java:S1872` | BUG | Classes should not be compared by name |
| `java:S2060` | BUG | "Externalizable" classes should have no-arguments constructors |
| `java:S2061` | BUG | Custom serialization method signatures should meet requirements |
| `java:S2077` | SECURITY_HOTSPOT | Formatting SQL queries is security-sensitive |
| `java:S2109` | BUG | Reflection should not be used to check non-runtime annotations |
| `java:S2110` | BUG | Invalid "Date" values should not be used |
| `java:S2111` | BUG | "BigDecimal(double)" should not be used |
| `java:S2112` | CODE_SMELL | "URL.hashCode" and "URL.equals" should be avoided |
| `java:S2114` | BUG | Collections should not be passed as arguments to their own methods |
| `java:S2116` | BUG | "hashCode" and "toString" should not be called on array instances |
| `java:S2118` | BUG | Non-serializable classes should not be written |
| `java:S2121` | BUG | Silly String operations should not be made |
| `java:S2123` | BUG | Values should not be uselessly incremented |
| `java:S2127` | BUG | "Double.longBitsToDouble" should not be used for "int" |
| `java:S2129` | CODE_SMELL | Constructors should not be used to instantiate "String", "BigInteger", "BigDecimal" and primitive-wrapper classes |
| `java:S2133` | CODE_SMELL | Objects should not be created only to "getClass" |
| `java:S2134` | BUG | Classes extending java.lang.Thread should override the "run" method |
| `java:S2139` | CODE_SMELL | Exceptions should be either logged or rethrown but not both |
| `java:S2142` | BUG | "InterruptedException" should not be ignored |
| `java:S2154` | BUG | Dissimilar primitive wrappers should not be used with the ternary operator without explicit casting |
| `java:S2159` | BUG | Silly equality checks should not be made |
| `java:S2166` | CODE_SMELL | Classes named like "Exception" should extend "Exception" or a subclass |
| `java:S2175` | BUG | Inappropriate "Collection" calls should not be made |
| `java:S2177` | BUG | Child class methods named for parent class methods should be overrides |
| `java:S2185` | CODE_SMELL | Silly math should not be performed |
| `java:S2201` | BUG | Return values from functions without side effects should not be ignored |
| `java:S2204` | BUG | ".equals()" should not be used to test the values of "Atomic" classes |
| `java:S2209` | CODE_SMELL | "static" members should be accessed statically |
| `java:S2225` | BUG | "toString()" and "clone()" methods should not return null |
| `java:S2226` | BUG | Servlets should not have mutable instance fields |
| `java:S2230` | BUG | Non-public methods should not be "@Transactional" |
| `java:S2232` | CODE_SMELL | "ResultSet.isLast()" should not be used |
| `java:S2234` | CODE_SMELL | Parameters should be passed in the correct order |
| `java:S2251` | BUG | A "for" loop update clause should move the counter in the right direction |
| `java:S2252` | BUG | Loop conditions should be true at least once |
| `java:S2259` | BUG | Null pointers should not be dereferenced |
| `java:S2273` | BUG | "wait", "notify" and "notifyAll" should only be called when a lock is obviously held on an object |
| `java:S2326` | CODE_SMELL | Unused type parameters should be removed |
| `java:S2388` | CODE_SMELL | Inner class calls to super class methods should be unambiguous |
| `java:S2438` | CODE_SMELL | "Threads" should not be used where "Runnables" are expected |
| `java:S2440` | CODE_SMELL | Classes with only "static" methods should not be instantiated |
| `java:S2441` | BUG | Non-serializable objects should not be stored in "HttpSession" objects |
| `java:S2442` | CODE_SMELL | "Lock" objects should not be "synchronized" |
| `java:S2445` | BUG | Blocks should be synchronized on "private final" fields |
| `java:S2446` | BUG | "notifyAll" should be used |
| `java:S2583` | BUG | Conditionally executed code should be reachable |
| `java:S2589` | CODE_SMELL | Boolean expressions should not be gratuitous |
| `java:S2612` | SECURITY_HOTSPOT | Setting loose POSIX file permissions is security-sensitive |
| `java:S2629` | CODE_SMELL | "Preconditions" and logging arguments should not require evaluation |
| `java:S2639` | BUG | Inappropriate regular expressions should not be used |
| `java:S2675` | CODE_SMELL | "readObject" should not be "synchronized" |
| `java:S2677` | BUG | "read" and "readLine" return values should be used |
| `java:S2681` | CODE_SMELL | Multiline blocks should be enclosed in curly braces |
| `java:S2718` | CODE_SMELL | "DateUtils.truncate" from Apache Commons Lang library should not be used |
| `java:S2757` | BUG | "=+" should not be used instead of "+=" |
| `java:S2761` | BUG | Unary prefix operators should not be repeated |
| `java:S2789` | BUG | "null" should not be used with "Optional" |
| `java:S2864` | CODE_SMELL | "entrySet()" should be iterated when both the key and value are needed |
| `java:S2885` | BUG | Non-thread-safe fields should not be static |
| `java:S2886` | BUG | Getters and setters should be synchronized in pairs |
| `java:S2925` | CODE_SMELL | "Thread.sleep" should not be used in tests |
| `java:S3010` | CODE_SMELL | Static fields should not be updated in constructors |
| `java:S3011` | CODE_SMELL | Reflection should not be used to increase accessibility of classes, methods, or fields |
| `java:S3034` | BUG | Raw byte values should not be used in bitwise operations in combination with shifts |
| `java:S3039` | BUG | "String" calls should not go beyond their bounds |
| `java:S3042` | CODE_SMELL | "writeObject" should not be the only "synchronized" code in a class |
| `java:S3064` | BUG | Assignment of lazy-initialized members should be the last step with double-checked locking |
| `java:S3065` | BUG | Min and max used in combination should not always return the same value |
| `java:S3067` | BUG | "getClass" should not be used for synchronization |
| `java:S3078` | BUG | "volatile" variables should not be used with compound operators |
| `java:S3346` | BUG | Expressions used in "assert" should not produce side effects |
| `java:S3358` | CODE_SMELL | Ternary operators should not be nested |
| `java:S3415` | CODE_SMELL | Assertion arguments should be passed in the correct order |
| `java:S3436` | BUG | Value-based classes should not be used for locking |
| `java:S3457` | CODE_SMELL | Printf-style format strings should be used correctly |
| `java:S3551` | BUG | Overrides should match their parent class methods in synchronization |
| `java:S3631` | CODE_SMELL | "Arrays.stream" should be used for primitive arrays |
| `java:S3655` | BUG | Optional value should only be accessed after calling isPresent() |
| `java:S3740` | CODE_SMELL | Raw types should not be used |
| `java:S3751` | CODE_SMELL | "@RequestMapping" methods should not be "private" |
| `java:S3824` | CODE_SMELL | "Map.get" and value test should be replaced with single method call |
| `java:S3864` | CODE_SMELL | "Stream.peek" should be used with caution |
| `java:S3923` | BUG | All branches in a conditional structure should not have exactly the same implementation |
| `java:S3958` | BUG | Intermediate Stream methods should not be left unused |
| `java:S3959` | BUG | Consumed Stream pipelines should not be reused |
| `java:S3981` | BUG | Collection sizes and array length comparisons should make sense |
| `java:S3984` | BUG | Exceptions should not be created without being thrown |
| `java:S3985` | CODE_SMELL | Unused "private" classes should be removed |
| `java:S3986` | BUG | Week Year ("YYYY") should not be used for date formatting |
| `java:S4042` | CODE_SMELL | "java.nio.Files#delete" should be preferred |
| `java:S4143` | BUG | Map values should not be replaced unconditionally |
| `java:S4144` | CODE_SMELL | Methods should not have identical implementations |
| `java:S4165` | CODE_SMELL | Assignments should not be redundant |
| `java:S4274` | CODE_SMELL | Asserts should not be used to check the parameters of a public method |
| `java:S4348` | BUG | "iterator" should not return "this" |
| `java:S4351` | BUG | "compareTo" should not be overloaded |
| `java:S4425` | CODE_SMELL | "Integer.toHexString" should not be used to build hexadecimal strings |
| `java:S4434` | SECURITY_HOTSPOT | Allowing deserialization of LDAP objects is security-sensitive |
| `java:S4449` | CODE_SMELL | Nullness of parameters should be guaranteed |
| `java:S4517` | BUG | InputSteam.read() implementation should not return a signed byte |
| `java:S4738` | CODE_SMELL | Java features should be preferred to Guava |
| `java:S4925` | CODE_SMELL | "Class.forName()" should not load JDBC 4.0+ drivers |
| `java:S4973` | BUG | Strings and Boxed types should be compared using "equals()" |
| `java:S5164` | BUG | "ThreadLocal" variables should be cleaned up when no longer used |
| `java:S5247` | SECURITY_HOTSPOT | Disabling auto-escaping in template engines is security-sensitive |
| `java:S5261` | CODE_SMELL | "else" statements should be clearly matched with an "if" |
| `java:S5329` | CODE_SMELL | Collection constructors should not be used as java.util.function.Function |
| `java:S5413` | CODE_SMELL | 'List.remove()' should not be used in ascending 'for' loops |
| `java:S5664` | CODE_SMELL | Whitespace for text block indent should be consistent |
| `java:S5669` | CODE_SMELL | Vararg method arguments should not be confusing |
| `java:S5679` | VULNERABILITY | OpenSAML2 should be configured to prevent authentication bypass |
| `java:S5693` | SECURITY_HOTSPOT | Allowing requests with excessive content length is security-sensitive |
| `java:S5738` | CODE_SMELL | "@Deprecated" code marked for removal should never be used |
| `java:S5776` | CODE_SMELL | Exception testing via JUnit ExpectedException rule should not be mixed with other assertions |
| `java:S5778` | CODE_SMELL | Only one method invocation is expected when testing runtime exceptions |
| `java:S5785` | CODE_SMELL | JUnit assertTrue/assertFalse should be simplified to the corresponding dedicated assertion |
| `java:S5804` | SECURITY_HOTSPOT | Allowing user enumeration is security-sensitive |
| `java:S5808` | VULNERABILITY | Authorizations should be based on strong decisions |
| `java:S5810` | BUG | JUnit5 test classes and methods should not be silently ignored |
| `java:S5831` | BUG | AssertJ configuration should be applied |
| `java:S5833` | BUG | AssertJ methods setting the assertion context should come before an assertion |
| `java:S5843` | CODE_SMELL | Regular expressions should not be too complicated |
| `java:S5850` | BUG | Alternatives in regular expressions should be grouped when used with anchors |
| `java:S5854` | CODE_SMELL | Regexes containing characters subject to normalization should use the CANON_EQ flag |
| `java:S5855` | BUG | Regex alternatives should not be redundant |
| `java:S5860` | CODE_SMELL | Names of regular expressions named groups should be used |
| `java:S5863` | BUG | Assertions should not compare an object to itself |
| `java:S5866` | BUG | Case insensitive Unicode regular expressions should enable the "UNICODE_CASE" flag |
| `java:S5868` | BUG | Unicode Grapheme Clusters should be avoided inside regex character classes |
| `java:S5869` | CODE_SMELL | Character classes in regular expressions should not contain the same character twice |
| `java:S5917` | BUG | DateTimeFormatters should not use mismatched year and week numbers |
| `java:S5958` | CODE_SMELL | AssertJ "assertThatThrownBy" should not be used alone |
| `java:S5960` | BUG | Assertions should not be used in production code |
| `java:S5961` | CODE_SMELL | Test methods should not contain too many assertions |
| `java:S5967` | BUG | Tests method should not be annotated with competing annotations |
| `java:S5973` | CODE_SMELL | Tests should be stable |
| `java:S5976` | CODE_SMELL | Similar tests should be grouped in a single Parameterized test |
| `java:S5993` | CODE_SMELL | Constructors of an "abstract" class should not be declared "public" |
| `java:S5998` | BUG | Regular expressions should not overflow the stack |
| `java:S6019` | CODE_SMELL | Reluctant quantifiers in regular expressions should be followed by an expression that can't match the empty string |
| `java:S6035` | CODE_SMELL | Single-character alternations in regular expressions should be replaced with character classes |
| `java:S6070` | BUG | The regex escape sequence \cX should only be used with characters in the @-_ range |
| `java:S6103` | BUG | AssertJ assertions with "Consumer" arguments should contain assertion inside consumers |
| `java:S6126` | CODE_SMELL | String multiline concatenation should be replaced with Text Blocks |
| `java:S6202` | CODE_SMELL | Operator "instanceof" should be used instead of "A.class.isInstance()" |
| `java:S6204` | CODE_SMELL | "Stream.toList()" method should be used instead of "collectors" when unmodifiable list needed |
| `java:S6206` | CODE_SMELL | Records should be used instead of ordinary classes when representing immutable data structure |
| `java:S6207` | CODE_SMELL | Redundant constructors/methods should be avoided in records |
| `java:S6213` | CODE_SMELL | Restricted Identifiers should not be used as Identifiers |
| `java:S6216` | BUG | Reflection should not be used to increase accessibility of records' fields |
| `java:S6218` | BUG | Equals method should be overridden in records containing array fields |
| `java:S6241` | CODE_SMELL | Region should be set explicitly when creating a new "AwsClient" |
| `java:S6242` | CODE_SMELL | Credentials Provider should be set explicitly when creating a new "AwsClient" |
| `java:S6243` | CODE_SMELL | Reusable resources should be initialized at construction time of Lambda functions |
| `java:S6263` | SECURITY_HOTSPOT | Using long-term access keys is security-sensitive |
| `java:S6288` | SECURITY_HOTSPOT | Authorizing non-authenticated users to use keys in the Android KeyStore is security-sensitive |
| `java:S6291` | SECURITY_HOTSPOT | Using unencrypted databases in mobile applications is security-sensitive |
| `java:S6293` | SECURITY_HOTSPOT | Using biometric authentication without a cryptographic solution is security-sensitive |
| `java:S6300` | SECURITY_HOTSPOT | Using unencrypted files in mobile applications is security-sensitive |
| `java:S6301` | VULNERABILITY | Mobile database encryption keys should not be disclosed |
| `java:S6326` | CODE_SMELL | Regular expressions should not contain multiple spaces |
| `java:S6331` | CODE_SMELL | Regular expressions should not contain empty groups |
| `java:S6355` | CODE_SMELL | Deprecated annotations should include explanations |
| `java:S6362` | SECURITY_HOTSPOT | Enabling JavaScript support for WebViews is security-sensitive |
| `java:S6363` | SECURITY_HOTSPOT | Enabling file access for WebViews is security-sensitive |
| `java:S6374` | VULNERABILITY | XML parsers should not load external schemas |
| `java:S6376` | VULNERABILITY | XML parsers should not be vulnerable to Denial of Service attacks |
| `java:S6377` | VULNERABILITY | XML signatures should be validated securely |
| `java:S6395` | CODE_SMELL | Non-capturing groups without quantifier should not be used |
| `java:S6396` | CODE_SMELL | Superfluous curly brace quantifiers should be avoided |
| `java:S6397` | CODE_SMELL | Character classes in regular expressions should not contain only one character |

### A.4 MINOR（132 条）

| 规则键 | 类型 | 规则名称 |
|---|---|---|
| `java:S100` | CODE_SMELL | Method names should comply with a naming convention |
| `java:S101` | CODE_SMELL | Class names should comply with a naming convention |
| `java:S114` | CODE_SMELL | Interface names should comply with a naming convention |
| `java:S116` | CODE_SMELL | Field names should comply with a naming convention |
| `java:S117` | CODE_SMELL | Local variable and method parameter names should comply with a naming convention |
| `java:S119` | CODE_SMELL | Type parameter names should comply with a naming convention |
| `java:S120` | CODE_SMELL | Package names should comply with a naming convention |
| `java:S135` | CODE_SMELL | Loops should not contain more than a single "break" or "continue" statement |
| `java:S899` | BUG | Return values should not be ignored when they contain the operation status code |
| `java:S1075` | CODE_SMELL | URIs should not be hardcoded |
| `java:S1104` | CODE_SMELL | Class variable fields should not have public accessibility |
| `java:S1116` | CODE_SMELL | Empty statements should be removed |
| `java:S1124` | CODE_SMELL | Modifiers should be declared in the correct order |
| `java:S1125` | CODE_SMELL | Boolean literals should not be redundant |
| `java:S1126` | CODE_SMELL | Return of boolean expressions should not be wrapped into an "if-then-else" statement |
| `java:S1128` | CODE_SMELL | Unnecessary imports should be removed |
| `java:S1130` | CODE_SMELL | "throws" declarations should not be superfluous |
| `java:S1153` | CODE_SMELL | String.valueOf() should not be appended to a String |
| `java:S1155` | CODE_SMELL | Collection.isEmpty() should be used to test for emptiness |
| `java:S1157` | CODE_SMELL | Case insensitive string comparisons should be made without intermediate upper or lower casing |
| `java:S1158` | CODE_SMELL | Primitive wrappers should not be instantiated only for "toString" or "compareTo" calls |
| `java:S1165` | CODE_SMELL | Exception classes should be immutable |
| `java:S1170` | CODE_SMELL | Public constants and fields initialized at declaration should be "static final" rather than merely "final" |
| `java:S1182` | CODE_SMELL | Classes that override "clone" should be "Cloneable" and call "super.clone()" |
| `java:S1185` | CODE_SMELL | Overriding methods should do more than simply call the same method in the super class |
| `java:S1195` | CODE_SMELL | Array designators "[]" should be located after the type in method signatures |
| `java:S1197` | CODE_SMELL | Array designators "[]" should be on the type, not the variable |
| `java:S1199` | CODE_SMELL | Nested code blocks should not be used |
| `java:S1206` | BUG | "equals(Object obj)" and "hashCode()" should be overridden in pairs |
| `java:S1210` | CODE_SMELL | "equals(Object obj)" should be overridden along with the "compareTo(T obj)" method |
| `java:S1220` | CODE_SMELL | The default unnamed package should not be used |
| `java:S1226` | BUG | Method parameters, caught exceptions and foreach variables' initial values should not be ignored |
| `java:S1264` | CODE_SMELL | A "while" loop should be used instead of a "for" loop |
| `java:S1301` | CODE_SMELL | "switch" statements should have at least 3 "case" clauses |
| `java:S1313` | SECURITY_HOTSPOT | Using hardcoded IP addresses is security-sensitive |
| `java:S1319` | CODE_SMELL | Declarations should use Java collection interfaces such as "List" rather than specific implementation classes such as "LinkedList" |
| `java:S1444` | CODE_SMELL | "public static" fields should be constant |
| `java:S1450` | CODE_SMELL | Private fields only used as local variables in methods should become local variables |
| `java:S1481` | CODE_SMELL | Unused local variables should be removed |
| `java:S1488` | CODE_SMELL | Local variables should not be declared and then immediately returned or thrown |
| `java:S1596` | CODE_SMELL | "Collections.EMPTY_LIST", "EMPTY_MAP", and "EMPTY_SET" should not be used |
| `java:S1602` | CODE_SMELL | Lambdas containing only one statement should not nest this statement in a block |
| `java:S1610` | CODE_SMELL | Abstract classes without fields should be converted to interfaces |
| `java:S1611` | CODE_SMELL | Parentheses should be removed from a single lambda input parameter when its type is inferred |
| `java:S1612` | CODE_SMELL | Lambdas should be replaced with method references |
| `java:S1640` | CODE_SMELL | Maps with keys that are enum values should be replaced with EnumMap |
| `java:S1643` | CODE_SMELL | Strings should not be concatenated using '+' in a loop |
| `java:S1659` | CODE_SMELL | Multiple variables should not be declared on the same line |
| `java:S1710` | CODE_SMELL | Annotation repetitions should not be wrapped |
| `java:S1858` | CODE_SMELL | "toString()" should never be called on a String object |
| `java:S1874` | CODE_SMELL | "@Deprecated" code should not be used |
| `java:S1905` | CODE_SMELL | Redundant casts should not be used |
| `java:S1940` | CODE_SMELL | Boolean checks should not be inverted |
| `java:S1989` | VULNERABILITY | Exceptions should not be thrown from servlet methods |
| `java:S2055` | BUG | The non-serializable super class of a "Serializable" class should have a no-argument constructor |
| `java:S2065` | CODE_SMELL | Fields in non-serializable classes should not be "transient" |
| `java:S2066` | BUG | "Serializable" inner classes of non-serializable classes should be "static" |
| `java:S2092` | SECURITY_HOTSPOT | Creating cookies without the "secure" flag is security-sensitive |
| `java:S2094` | CODE_SMELL | Classes should not be empty |
| `java:S2097` | BUG | "equals(Object obj)" should test argument type |
| `java:S2130` | CODE_SMELL | Parsing should be used to convert "Strings" to primitives |
| `java:S2140` | CODE_SMELL | Methods of "Random" that return floating point values should not be used in random integer generation |
| `java:S2147` | CODE_SMELL | Catches should be combined |
| `java:S2153` | BUG | Boxing and unboxing should not be immediately reversed |
| `java:S2160` | CODE_SMELL | Subclasses that add fields should override "equals" |
| `java:S2165` | CODE_SMELL | "finalize" should not set fields to "null" |
| `java:S2167` | BUG | "compareTo" should not return "Integer.MIN_VALUE" |
| `java:S2183` | BUG | Ints and longs should not be shifted by zero or more than their number of bits-1 |
| `java:S2184` | BUG | Math operands should be cast before assignment |
| `java:S2200` | BUG | "compareTo" results should not be checked for specific values |
| `java:S2272` | BUG | "Iterator.next()" methods should throw "NoSuchElementException" |
| `java:S2293` | CODE_SMELL | The diamond operator ("<>") should be used |
| `java:S2386` | CODE_SMELL | Mutable fields should not be "public static" |
| `java:S2637` | BUG | "@NonNull" values should not be set to null |
| `java:S2674` | BUG | The value returned from a stream read should be checked |
| `java:S2676` | BUG | Neither "Math.abs" nor negation should be used on numbers that could be "MIN_VALUE" |
| `java:S2737` | CODE_SMELL | "catch" clauses should do more than rethrow |
| `java:S2786` | CODE_SMELL | Nested "enum"s should not be declared static |
| `java:S2924` | CODE_SMELL | JUnit rules should be used |
| `java:S3008` | CODE_SMELL | Static non-final field names should comply with a naming convention |
| `java:S3012` | CODE_SMELL | Arrays should not be copied using loops |
| `java:S3020` | BUG | "toArray" should be passed an array of the proper type |
| `java:S3038` | CODE_SMELL | Abstract methods should not be redundant |
| `java:S3066` | CODE_SMELL | "enum" fields should not be publicly mutable |
| `java:S3077` | BUG | Non-primitive fields should not be "volatile" |
| `java:S3330` | SECURITY_HOTSPOT | Creating cookies without the "HttpOnly" flag is security-sensitive |
| `java:S3398` | CODE_SMELL | "private" methods called only by inner classes should be moved to those classes |
| `java:S3400` | CODE_SMELL | Methods should not return constants |
| `java:S3416` | CODE_SMELL | Loggers should be named for their enclosing classes |
| `java:S3577` | CODE_SMELL | Test classes should comply with a naming convention |
| `java:S3599` | BUG | Double Brace Initialization should not be used |
| `java:S3626` | CODE_SMELL | Jump statements should not be redundant |
| `java:S3752` | SECURITY_HOTSPOT | Allowing both safe and unsafe HTTP methods is security-sensitive |
| `java:S3878` | CODE_SMELL | Arrays should not be created for varargs parameters |
| `java:S4032` | CODE_SMELL | Packages containing only "package-info.java" should be removed |
| `java:S4034` | CODE_SMELL | "Stream" call chains should be simplified when possible |
| `java:S4036` | SECURITY_HOTSPOT | Searching OS commands in PATH is security-sensitive |
| `java:S4065` | CODE_SMELL | "ThreadLocal.withInitial" should be preferred |
| `java:S4087` | CODE_SMELL | "close()" calls should not be redundant |
| `java:S4201` | CODE_SMELL | Null checks should not be used with "instanceof" |
| `java:S4276` | CODE_SMELL | Functional Interfaces should be as specialised as possible |
| `java:S4349` | CODE_SMELL | "write(byte[],int,int)" should be overridden |
| `java:S4488` | CODE_SMELL | Composed "@RequestMapping" variants should be preferred |
| `java:S4507` | SECURITY_HOTSPOT | Delivering code in production with debug features activated is security-sensitive |
| `java:S4682` | CODE_SMELL | "@CheckForNull" or "@Nullable" should not be used on primitive types |
| `java:S4719` | CODE_SMELL | "StandardCharsets" constants should be preferred |
| `java:S4838` | CODE_SMELL | An iteration on a Collection should be performed on the type handled by the Collection |
| `java:S4929` | CODE_SMELL | "read(byte[],int,int)" should be overridden |
| `java:S4968` | CODE_SMELL | The upper bound of type variables and wildcards should not be "final" |
| `java:S4977` | CODE_SMELL | Type parameters should not shadow other type parameters |
| `java:S5122` | SECURITY_HOTSPOT | Having a permissive Cross-Origin Resource Sharing policy is security-sensitive |
| `java:S5301` | VULNERABILITY | "ActiveMQConnectionFactory" should not be vulnerable to malicious code deserialization |
| `java:S5411` | CODE_SMELL | Avoid using boxed "Boolean" types directly in boolean expressions |
| `java:S5663` | CODE_SMELL | Simple string literal should be used for single line strings |
| `java:S5665` | CODE_SMELL | Escape sequences should not be used in text blocks |
| `java:S5689` | SECURITY_HOTSPOT | Disclosing fingerprints from web application technologies is security-sensitive |
| `java:S5777` | CODE_SMELL | Exception testing via JUnit @Test annotation should be avoided |
| `java:S5838` | CODE_SMELL | Chained AssertJ assertions should be simplified to the corresponding dedicated assertion |
| `java:S5841` | BUG | AssertJ assertions "allMatch" and "doesNotContains" should also test for emptiness |
| `java:S5842` | BUG | Repeated patterns in regular expressions should not match the empty string |
| `java:S5853` | CODE_SMELL | Consecutive AssertJ "assertThat" statements should be chained |
| `java:S5857` | CODE_SMELL | Character classes should be preferred over reluctant quantifiers in regular expressions |
| `java:S6068` | CODE_SMELL | Call to Mockito method "verify", "when" or "given" should be simplified |
| `java:S6201` | CODE_SMELL | Pattern Matching for "instanceof" operator should be used instead of simple "instanceof" + cast |
| `java:S6203` | CODE_SMELL | Text blocks should not be used in complex expressions |
| `java:S6205` | CODE_SMELL | Switch arrow labels should not use redundant keywords |
| `java:S6217` | CODE_SMELL | Permitted types of a sealed class should be omitted if they are declared in the same file |
| `java:S6219` | CODE_SMELL | 'serialVersionUID' field should not be set to '0L' in records |
| `java:S6244` | CODE_SMELL | Consumer Builders should be used |
| `java:S6246` | CODE_SMELL | Lambdas should not invoke other lambdas synchronously |
| `java:S6262` | CODE_SMELL | AWS region should not be set with a hardcoded String |
| `java:S6353` | CODE_SMELL | Regular expression quantifiers and character classes should be used concisely |

### A.5 INFO（4 条）

| 规则键 | 类型 | 规则名称 |
|---|---|---|
| `java:S1133` | CODE_SMELL | Deprecated code should be removed |
| `java:S1135` | CODE_SMELL | Track uses of "TODO" tags |
| `java:S5786` | CODE_SMELL | JUnit5 test classes and methods should have default package visibility |
| `java:S6208` | CODE_SMELL | Comma-separated labels should be used in Switch with colon case |