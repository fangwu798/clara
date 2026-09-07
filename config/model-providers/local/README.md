# 本地模型配置

此目录用于保存当前机器实际使用的平台地址、模型 ID、密钥来源和参数。真实配置按平台拆分，例如：

```text
openai.yml
deepseek.yml
volcengine.yml
alibaba.yml
anthropic.yml
```

`.yml` 和 `.yaml` 文件已由仓库根目录的 `.gitignore` 忽略，不要使用强制提交。请从
`../examples/<platform>.example.yml` 复制对应样例后修改。

优先使用 `credential.environmentVariable` 引用进程环境变量。如果确实需要在单机配置中直接填写密钥，
删除 `environmentVariable` 并增加：

```yaml
credential:
  secretRef: platform-default
  apiKey: replace-with-real-secret
```

同一凭据中 `environmentVariable` 与 `apiKey` 只能存在一个。本地文件仍应限制操作系统访问权限，并避免在
日志、异常、截图或备份中泄露。
