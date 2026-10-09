package com.clara;

/**
 * 最小可运行入口：构造 {@link ApplicationBootstrap} 并验证装配成功。
 *
 * <p>本入口不发起模型调用（无外部凭据时装配仅到构造阶段即可），只做装配自检并打印可用能力，
 * 以便作为宿主应用的启动参考。真实的 CLI 交互运行由 {@code com.clara.adapter.cli.ClaraCli} 承担。
 */
public final class ClaraApplication {

    private ClaraApplication() {
    }

    /**
     * 进程入口：装配各模块实现并打印装配结果。
     *
     * @param args 命令行参数，本入口当前不使用
     */
    public static void main(String[] args) {
        ApplicationBootstrap bootstrap = null;
        try {
            bootstrap = new ApplicationBootstrap();
            System.out.println("Clara application assembled successfully.");
            System.out.println("  orchestrator   : "
                    + bootstrap.orchestrator().getClass().getName());
            System.out.println("  workflowEngine : "
                    + bootstrap.workflowEngine().getClass().getName());
            System.out.println("  embeddingClient: "
                    + bootstrap.embeddingClient().getClass().getName());
        } catch (RuntimeException failure) {
            System.err.println("Failed to assemble Clara application: " + failure.getMessage());
            System.exit(1);
        } finally {
            if (bootstrap != null) {
                bootstrap.close();
            }
        }
    }
}
