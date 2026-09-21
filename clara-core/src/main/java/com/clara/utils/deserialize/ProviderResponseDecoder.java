package com.clara.utils.deserialize;

import com.clara.annoation.ResponseAdapter;
import com.clara.context.ConvContext;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.FinishReasonEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.api.IResponse;
import com.clara.kernel.http.RawHttpResponse;
import com.clara.kernel.stream.StreamEvent;
import com.clara.utils.ExceptionUtils;
import com.clara.utils.FinishReasonUtils;
import com.clara.utils.JsonNodeUtils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 响应解码器：把平台返回的 JSON 按字段上的 {@link ResponseAdapter} 填进统一响应对象。
 *
 * <p>是什么、在哪一层：契约层的静态工具类，与入参侧的
 * {@link ProviderRequestEncoder} 对称——编码器按注解把请求对象写成平台请求体，本类按注解
 * 把平台响应体读成响应对象。它不知道任何具体平台：平台与能力取自线程上下文（{@code ConvContext}），
 * 字段路径来自注解。
 *
 * <p>谁创建、谁调用：无状态、构造器私有；由传输层在拿到响应后调用 {@link #decode}，由流式链路用
 * {@link #streamAssembler} 边收边解。
 *
 * <p>同步解码规则：
 * <ol>
 *   <li>遍历响应类型及其父类的实例字段（父类字段在前），跳过静态与合成字段；</li>
 *   <li>未标注 {@link ResponseAdapter} 的字段不参与解码（例如原始响应、平台元数据等由调用方自行设置）；</li>
 *   <li>标注了注解但 {@code path} 为空、或没有一条规则的平台 / 能力命中时，跳过该字段；</li>
 *   <li>命中规则但路径缺失或为 null：{@code required = true} 时按协议错误抛出，否则跳过；</li>
 *   <li>命中时按字段类型转换：字符串、整型、长整型、浮点、布尔、枚举（
 *       {@link FinishReasonEnum} 走 {@link FinishReasonUtils}）直接转换，其余对象 / 集合类型交给 Jackson；
 *       类型或数值格式不符时按协议错误抛出；</li>
 *   <li>响应类型为 {@code RawHttpResponse} 的字段会被回填原始响应，便于调用方判状态码与错误体。</li>
 * </ol>
 *
 * <p>流式解码规则：{@link #streamAssembler(Class)} 返回的累加器对每条 SSE {@code data} 做四件事——
 * 只有 {@code path} 的字段按该路径取一次并写入（{@code id} / {@code model} / {@code usage.*} 都在首块或末块
 * 一次性给出，不在增量里）；命中 {@code terminalPath} 时写入结束原因；命中 {@code streamPath} 的字符串增量会
 * <b>追加</b>到目标字段并产出一个对应的流事件；对象 / 数组增量以 JSON 文本形式产出事件，同时不累加
 * （工具调用分片的合并由 {@code RecordingObserver} 之类的观察者处理）。
 *
 * <p>流式的终止条件是 {@code [DONE]}，<b>不是</b>结束原因：开启 {@code stream_options.include_usage} 后，
 * 平台会在 {@code finish_reason} 之后补发一个只带 {@code usage} 的数据块，把结束原因当终止条件会把 token
 * 用量整个丢掉。
 *
 * <p>生命周期与线程安全：本类无状态、内部 {@link ObjectMapper} 线程安全；累加器不是线程安全的，
 * 一次流对应一个累加器，只在读取线程上使用。
 *
 * <p>失败语义：入参为 null，或线程上下文未绑定平台配置 / 能力种类时抛 {@link IllegalArgumentException} /
 * {@link IllegalStateException}（调用方编程错误）；响应体不是合法 JSON、必需路径缺失、字段类型不符时抛
 * {@code ClaraException}（{@code MODEL_PROTOCOL_ERROR}，归因当前平台，不可重试）。
 */
public final class ProviderResponseDecoder {

    /**
     * SSE 流结束标记：OpenAI 兼容家族在最后一个数据块下发 {@code data: [DONE]}。
     */
    private static final String DONE = "[DONE]";

    /**
     * JSON 映射器，用于解析响应体与转换对象 / 集合字段。
     *
     * <p>关闭未知属性检查：平台常在已声明结构外附加字段（如 {@code object}、{@code system_fingerprint}），
     * 本类只取注解声明的路径，多出来的字段不应让整次解码失败。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ProviderResponseDecoder() {
    }

    /**
     * 把一次同步响应解码为响应对象。
     *
     * @param raw          原始 HTTP 响应，不可为 null
     * @param responseType 响应类型，必须有可访问的无参构造器，不可为 null
     * @param <R>          响应类型，需实现 {@link IResponse}
     * @return 已填充的响应对象，非 null；响应体为空时只回填原始响应
     * @throws IllegalArgumentException 入参为 null 或响应类型缺少无参构造器时
     * @throws IllegalStateException    线程上下文未绑定平台配置或能力种类时
     * @throws com.clara.kernel.exception.ClaraException 响应体非法、必需路径缺失或类型不符时
     */
    public static <R extends IResponse> R decode(RawHttpResponse raw, Class<R> responseType) {
        if (raw == null || responseType == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        ModelProviderEnum provider = provider();
        CapabilityKindEnum capability = capability();
        R target = newInstance(responseType);
        attachRaw(target, raw);
        if (raw.getBody().length == 0) {
            return target;
        }
        JsonNode root = parse(raw.getBody(), provider);
        for (Field field : fields(responseType)) {
            ResponseAdapter adapter = match(field, provider, capability);
            if (adapter == null || adapter.path().isEmpty()) {
                continue;
            }
            JsonNode node = valueAt(root, adapter.path());
            if (!present(node)) {
                if (adapter.required()) {
                    throw ExceptionUtils.protocolError(provider);
                }
                continue;
            }
            write(field, target, convert(field.getGenericType(), node, provider));
        }
        return target;
    }

    /**
     * 创建流式累加器：逐条喂入 SSE {@code data}，增量按注解累加，结束后从 {@link StreamAssembler#result()}
     * 取回完整响应。
     *
     * @param responseType 响应类型，必须有可访问的无参构造器，不可为 null
     * @param <R>          响应类型，需实现 {@link IResponse}
     * @return 累加器，非 null
     * @throws IllegalArgumentException responseType 为 null 或缺少无参构造器时
     * @throws IllegalStateException    线程上下文未绑定平台配置或能力种类时
     */
    public static <R extends IResponse> StreamAssembler<R> streamAssembler(Class<R> responseType) {
        if (responseType == null) {
            throw new IllegalArgumentException("responseType must not be null");
        }
        ModelProviderEnum provider = provider();
        CapabilityKindEnum capability = capability();
        List<Rule> deltas = new ArrayList<Rule>();
        List<Rule> singles = new ArrayList<Rule>();
        List<Rule> terminals = new ArrayList<Rule>();
        for (Field field : fields(responseType)) {
            ResponseAdapter adapter = match(field, provider, capability);
            if (adapter == null) {
                continue;
            }
            if (!adapter.streamPath().isEmpty()) {
                deltas.add(new Rule(field, adapter));
            } else if (!adapter.path().isEmpty()) {
                singles.add(new Rule(field, adapter));
            }
            if (!adapter.terminalPath().isEmpty()) {
                terminals.add(new Rule(field, adapter));
            }
        }
        return new StreamAssembler<R>(newInstance(responseType), deltas, singles, terminals, provider);
    }

    /**
     * 流式累加器：把 SSE 数据块解码为流事件，并把增量累加到同一个响应对象上。
     *
     * <p>使用方法：对每条 {@code data} 调 {@link #accept(String)}，把返回的事件按序发射给观察者；
     * {@link #isTerminated()} 为 true 时结束本轮，用 {@link #result()} 取累加结果。
     *
     * <p>三类字段的处理方式不同：
     * <ul>
     *   <li>带 {@code streamPath} 的字段：文本增量追加、对象 / 数组增量以 JSON 文本产出事件；</li>
     *   <li>只有 {@code path} 的字段：每个数据块都按该路径取一次，取到就写（如 {@code id} / {@code model} /
     *       {@code usage.*}——它们不在增量里，而是集中在首块或末块）；</li>
     *   <li>带 {@code terminalPath} 的字段：命中即写入结束原因。</li>
     * </ul>
     *
     * <p>结束时机：<b>不以结束原因作为终止条件</b>。OpenAI 兼容家族在 {@code finish_reason} 之后还会补发
     * 一个只带 {@code usage} 的数据块（开启 {@code stream_options.include_usage} 时），提前终止会把 token
     * 用量整个丢掉；因此只在收到 {@code [DONE]} 时终止，未收到 {@code [DONE]} 的平台则等响应体读完、
     * 由流式链路以正常结束收尾。
     *
     * <p>线程安全：非线程安全，一次流对应一个实例，只在读取线程上使用。
     *
     * @param <R> 响应类型
     */
    public static final class StreamAssembler<R extends IResponse> {
        /**
         * 累加目标，最终由 {@link #result()} 返回。
         */
        private final R target;
        /**
         * 带 {@code streamPath} 的字段规则，按声明顺序发射事件。
         */
        private final List<Rule> deltas;
        /**
         * 只有 {@code path} 的字段规则，每个数据块按路径取一次。
         */
        private final List<Rule> singles;
        /**
         * 带 {@code terminalPath} 的字段规则，命中即写入结束原因。
         */
        private final List<Rule> terminals;
        /**
         * 当前平台，用于错误归因。
         */
        private final ModelProviderEnum provider;
        /**
         * 事件序号，从 0 开始递增。
         */
        private long sequence;
        /**
         * 是否已收到结束标记（{@code [DONE]}）。
         */
        private boolean terminated;

        private StreamAssembler(R target, List<Rule> deltas, List<Rule> singles, List<Rule> terminals,
                ModelProviderEnum provider) {
            this.target = target;
            this.deltas = deltas;
            this.singles = singles;
            this.terminals = terminals;
            this.provider = provider;
        }

        /**
         * 处理一条 SSE 数据块。
         *
         * @param sseData SSE 事件的 {@code data} 内容，可为 null 或空白（此时不产生事件）
         * @return 需要发射的流事件列表，按字段声明顺序；无事件时为空列表，非 null
         * @throws com.clara.kernel.exception.ClaraException 数据块不是合法 JSON 或字段类型不符时
         */
        public List<StreamEvent> accept(String sseData) {
            if (terminated || sseData == null || sseData.trim().isEmpty()) {
                return Collections.emptyList();
            }
            String data = sseData.trim();
            if (DONE.equals(data)) {
                terminated = true;
                return Collections.emptyList();
            }
            JsonNode root;
            try {
                root = MAPPER.readTree(data);
            } catch (IOException exception) {
                throw ExceptionUtils.protocolError(provider);
            }
            if (root == null || !root.isObject()) {
                return Collections.emptyList();
            }
            for (Rule rule : singles) {
                JsonNode node = valueAt(root, rule.adapter.path());
                if (present(node)) {
                    write(rule.field, target, convert(rule.field.getGenericType(), node, provider));
                }
            }
            for (Rule rule : terminals) {
                JsonNode node = valueAt(root, rule.adapter.terminalPath());
                if (present(node)) {
                    write(rule.field, target, convert(rule.field.getGenericType(), node, provider));
                }
            }
            List<StreamEvent> events = new ArrayList<StreamEvent>();
            for (Rule rule : deltas) {
                JsonNode node = valueAt(root, rule.adapter.streamPath());
                if (!present(node)) {
                    continue;
                }
                if (node.isTextual()) {
                    String text = node.textValue();
                    if (text.isEmpty()) {
                        continue;
                    }
                    appendText(rule.field, target, text);
                    events.add(new StreamEvent(rule.adapter.event(), sequence++, text));
                } else {
                    events.add(new StreamEvent(rule.adapter.event(), sequence++, node.toString()));
                }
            }
            return events;
        }

        /**
         * 是否已收到结束标记（终态路径命中或 {@code [DONE]}）。
         *
         * @return 已结束时返回 true
         */
        public boolean isTerminated() {
            return terminated;
        }

        /**
         * 取累加完成的响应对象。
         *
         * @return 累加目标，非 null；流未结束时为已收到的部分内容
         */
        public R result() {
            return target;
        }
    }

    /**
     * 字段与命中规则的组合。
     */
    private static final class Rule {
        /**
         * 目标字段。
         */
        private final Field field;
        /**
         * 命中的出参规则。
         */
        private final ResponseAdapter adapter;

        private Rule(Field field, ResponseAdapter adapter) {
            this.field = field;
            this.adapter = adapter;
        }
    }

    /**
     * 取当前平台，来自线程上下文。
     *
     * @return 平台，非 null
     * @throws IllegalStateException 线程上下文未绑定平台配置时
     */
    private static ModelProviderEnum provider() {
        ConvContext context = ConvContext.current();
        if (context == null || context.getProviderConfiguration() == null) {
            throw new IllegalStateException("provider configuration is required to decode a response");
        }
        return context.getProviderConfiguration().getProvider();
    }

    /**
     * 取当前能力种类，来自线程上下文。
     *
     * @return 能力种类，非 null
     * @throws IllegalStateException 线程上下文未绑定能力种类时
     */
    private static CapabilityKindEnum capability() {
        ConvContext context = ConvContext.current();
        if (context == null || context.getCapabilityKind() == null) {
            throw new IllegalStateException("capability kind is required to decode a response");
        }
        return context.getCapabilityKind();
    }

    /**
     * 创建响应对象。
     *
     * @param responseType 响应类型，非 null
     * @param <R>          响应类型
     * @return 新实例，非 null
     * @throws IllegalArgumentException 缺少可访问的无参构造器时
     */
    private static <R extends IResponse> R newInstance(Class<R> responseType) {
        try {
            Constructor<R> constructor = responseType.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("response type needs a no-arg constructor: " + responseType.getName(), exception);
        }
    }

    /**
     * 回填原始 HTTP 响应。
     *
     * @param target 响应对象，非 null
     * @param raw    原始响应，非 null
     */
    private static void attachRaw(Object target, RawHttpResponse raw) {
        for (Field field : fields(target.getClass())) {
            if (RawHttpResponse.class.equals(field.getType())) {
                write(field, target, raw);
            }
        }
    }

    /**
     * 取响应类型及父类的实例字段，父类字段在前。
     *
     * @param type 响应类型，非 null
     * @return 字段列表，非 null
     */
    private static List<Field> fields(Class<?> type) {
        List<Class<?>> hierarchy = new ArrayList<Class<?>>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            hierarchy.add(0, current);
        }
        List<Field> fields = new ArrayList<Field>();
        for (Class<?> current : hierarchy) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                fields.add(field);
            }
        }
        return fields;
    }

    /**
     * 在字段的规则里找出当前平台与能力对应的第一条。
     *
     * @param field      目标字段，非 null
     * @param provider   当前平台，非 null
     * @param capability 当前能力，非 null
     * @return 命中的规则；没有命中时返回 null
     */
    private static ResponseAdapter match(Field field, ModelProviderEnum provider, CapabilityKindEnum capability) {
        for (ResponseAdapter adapter : field.getDeclaredAnnotationsByType(ResponseAdapter.class)) {
            if (adapter.capability() != capability) {
                continue;
            }
            ModelProviderEnum[] types = adapter.type();
            if (types.length == 0) {
                return adapter;
            }
            for (ModelProviderEnum type : types) {
                if (type == provider) {
                    return adapter;
                }
            }
        }
        return null;
    }

    /**
     * 按路径取值，支持 {@code a.b[0].c} 形式。
     *
     * @param root 根节点，非 null
     * @param path 路径，非 null
     * @return 命中的节点；任一层级缺失或下标非法时返回 null
     */
    private static JsonNode valueAt(JsonNode root, String path) {
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (current == null) {
                return null;
            }
            int bracket = segment.indexOf('[');
            if (bracket < 0) {
                current = current.get(segment);
                continue;
            }
            if (!segment.endsWith("]")) {
                return null;
            }
            String name = segment.substring(0, bracket);
            JsonNode array = name.isEmpty() ? current : current.get(name);
            if (array == null || !array.isArray()) {
                return null;
            }
            try {
                current = array.get(Integer.parseInt(segment.substring(bracket + 1, segment.length() - 1)));
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return current;
    }

    /**
     * 判断节点是否算「有值」：非空、非 NullNode、非空字符串。
     *
     * @param node 待检查节点，可为 null
     * @return 有值时返回 true
     */
    private static boolean present(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        return !node.isTextual() || !node.textValue().isEmpty();
    }

    /**
     * 按目标类型转换节点值。
     *
     * <p>入参取字段的 {@code getGenericType()} 而非 {@code getType()}：后者会把 {@code List<ToolCall>}
     * 擦成裸 {@code List}，Jackson 只能造出 {@code List<LinkedHashMap>}，元素类型在取值时才暴雷。
     *
     * @param type     目标类型（可为泛型），非 null
     * @param node     节点，非 null
     * @param provider 当前平台，用于错误归因，非 null
     * @return 转换后的值，非 null
     * @throws com.clara.kernel.exception.ClaraException 类型或取值不符时
     */
    private static Object convert(Type type, JsonNode node, ModelProviderEnum provider) {
        Class<?> raw = type instanceof Class ? (Class<?>) type : null;
        if (raw == String.class) {
            return node.isTextual() ? node.textValue() : node.asText();
        }
        if (raw == Integer.class || raw == Integer.TYPE) {
            if (!node.isIntegralNumber() || !node.canConvertToInt()) {
                throw ExceptionUtils.protocolError(provider);
            }
            return Integer.valueOf(node.intValue());
        }
        if (raw == Long.class || raw == Long.TYPE) {
            if (!node.isIntegralNumber()) {
                throw ExceptionUtils.protocolError(provider);
            }
            return Long.valueOf(node.longValue());
        }
        if (raw == Double.class || raw == Double.TYPE) {
            if (!node.isNumber()) {
                throw ExceptionUtils.protocolError(provider);
            }
            return Double.valueOf(node.doubleValue());
        }
        if (raw == Boolean.class || raw == Boolean.TYPE) {
            if (!node.isBoolean()) {
                throw ExceptionUtils.protocolError(provider);
            }
            return Boolean.valueOf(node.booleanValue());
        }
        if (raw == FinishReasonEnum.class) {
            return FinishReasonUtils.of(JsonNodeUtils.textOrNull(node));
        }
        if (raw != null && raw.isEnum()) {
            String literal = JsonNodeUtils.textOrNull(node);
            for (Object constant : raw.getEnumConstants()) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(literal)) {
                    return constant;
                }
            }
            throw ExceptionUtils.protocolError(provider);
        }
        try {
            return MAPPER.convertValue(node, MAPPER.getTypeFactory().constructType(type));
        } catch (IllegalArgumentException exception) {
            throw ExceptionUtils.protocolError(provider);
        }
    }

    /**
     * 解析响应体字节。
     *
     * @param body     响应体，非 null
     * @param provider 当前平台，用于错误归因，非 null
     * @return 根节点，非 null
     * @throws com.clara.kernel.exception.ClaraException 不是合法 JSON 时
     */
    private static JsonNode parse(byte[] body, ModelProviderEnum provider) {
        try {
            return MAPPER.readTree(body);
        } catch (IOException exception) {
            throw ExceptionUtils.protocolError(provider);
        }
    }

    /**
     * 把文本增量追加到目标字段上。
     *
     * @param field  目标字段，非 null
     * @param target 响应对象，非 null
     * @param text   增量文本，非 null
     */
    private static void appendText(Field field, Object target, String text) {
        Object current = read(field, target);
        write(field, target, current == null ? text : ((String) current) + text);
    }

    /**
     * 读取字段值。
     *
     * @param field  目标字段，非 null
     * @param target 响应对象，非 null
     * @return 字段值，可为 null
     * @throws IllegalArgumentException 字段不可访问时
     */
    private static Object read(Field field, Object target) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("cannot read response field: " + field.getName(), exception);
        }
    }

    /**
     * 写入字段值。
     *
     * @param field  目标字段，非 null
     * @param target 响应对象，非 null
     * @param value  字段值，可为 null（为 null 时不写入）
     * @throws IllegalArgumentException 字段不可访问时
     */
    private static void write(Field field, Object target, Object value) {
        if (value == null) {
            return;
        }
        try {
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("cannot write response field: " + field.getName(), exception);
        }
    }
}
