package com.clara.config;

import com.clara.exception.ClaraError;
import com.clara.exception.ErrorCategory;
import com.clara.exception.ErrorCodes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 模型故障转移策略：当前模型用不了时，按「启动可用性检查得到的第一字符响应时间顺序」依次换下一个。
 *
 * <p>是什么、在哪一层：application 配置层的纯策略类（{@code com.clara.config}），与
 * {@link ModelRegistry}（谁是当前模型）、{@link ModelAvailabilityManager}（启动期探活并按响应时间排序）同层。
 * 它自己不做探活、不持有状态，只回答两个问题：这一轮失败该不该换模型、按什么顺序换。
 *
 * <p>为什么沿用启动期排序、而不是出故障时重新探活：探活要真发一次模型请求，慢的模型一次十几秒，
 * 故障时再等一轮探活只会让用户干等；启动期已经拿到「哪些可用 + 谁快」这份排序，直接顺着它走即可。
 * 换过去的模型若恰好也不可用，就沿同一份顺序继续往下。
 *
 * <p>线程安全：无状态，全部为静态纯函数；不修改入参。
 */
public final class ModelFailover {

    private ModelFailover() {
    }

    /**
     * 判断一次失败是否属于「当前模型用不了」——只有这类才值得换下一个模型。
     *
     * <p>为什么按类别判定、而不是「只要失败就换」：换模型只对「这个平台 / 模型现在服务不了我们」有效。
     * 请求本身有问题（入参非法、体积超限、内容安全）、用户主动取消、以及「副作用未知」这些情况换模型都
     * 解决不了，硬换还会把真实失败原因掩盖掉。
     *
     * <p>归类口径：
     * <ul>
     *   <li>鉴权 / 授权 / 限流 / 超时 / 外部服务失败 → 换：401、403、429、5xx、连接失败、响应协议不符，
     *       以及「调用被掐断、模型一个字节都没回」都归到这里；</li>
     *   <li>配置类里只有「凭据不可用」换：那是当前这份配置没拿到密钥，别的模型未必；端点不在允许清单这类
     *       本地策略问题不在此列；</li>
     *   <li>取消、入参非法、体积超限、内容安全、能力不支持、内部错误、副作用未知 → 不换。</li>
     * </ul>
     *
     * @param error 本轮错误，可为 null
     * @return 属于「模型不可用」时 true
     */
    public static boolean isUnavailable(ClaraError error) {
        if (error == null) {
            // 没有错误详情的失败：编排在「模型没有返回任何响应」时给出 null（连接被掐断、流被中断）。
            // 这种就是「用不了」，按不可用处理——但只有编排状态为 FAILED 的轮次才会走到这里（COMPLETED 也是 null）。
            return true;
        }
        if (ErrorCodes.MODEL_CREDENTIAL_UNAVAILABLE.equals(error.getCode())) {
            return true;
        }
        ErrorCategory category = error.getCategory();
        return category == ErrorCategory.AUTHENTICATION
                || category == ErrorCategory.AUTHORIZATION
                || category == ErrorCategory.RATE_LIMIT
                || category == ErrorCategory.TIMEOUT
                || category == ErrorCategory.EXTERNAL_SERVICE;
    }

    /**
     * 本轮要依次尝试的模型配置：当前模型优先，其后是按响应时间升序排好的其余可用模型。
     *
     * <p>为什么当前模型放最前：绝大多数轮次它都能用，不该为了「万一」先切到别的模型上去。
     * 只有它失败、且失败属于「模型不可用」时，调用方才沿这份顺序往后走。
     *
     * <p>去重按别名（{@link ConfigLocator#aliasOf(Path)}，大小写不敏感）而不是路径对象：同一份配置可能以
     * 相对 / 绝对两种写法传入，按路径比较会把同一个模型排两次、白白多打一次失败请求。
     *
     * @param ranked  启动期按首字符响应时间升序排好的可用模型配置，可为 null 或空
     * @param current 当前生效的模型配置，可为 null
     * @return 本轮尝试顺序，非 null；都为空时返回空列表（调用方据此只跑当前模型，不换）
     */
    public static List<Path> attemptOrder(List<Path> ranked, Path current) {
        Set<String> seen = new LinkedHashSet<String>();
        List<Path> order = new ArrayList<Path>();
        add(order, seen, current);
        if (ranked != null) {
            for (Path path : ranked) {
                add(order, seen, path);
            }
        }
        return Collections.unmodifiableList(order);
    }

    /**
     * 把一份配置追加进尝试顺序：无路径、无别名或别名已出现过时跳过。
     *
     * @param order 结果顺序，非 null
     * @param seen  已出现的别名（小写），非 null
     * @param path  候选配置，可为 null
     */
    private static void add(List<Path> order, Set<String> seen, Path path) {
        if (path == null) {
            return;
        }
        String alias = ConfigLocator.aliasOf(path);
        if (alias == null || alias.trim().isEmpty()) {
            return;
        }
        if (seen.add(alias.trim().toLowerCase(Locale.ROOT))) {
            order.add(path);
        }
    }
}
