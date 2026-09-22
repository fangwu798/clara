package com.clara.kernel.http;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 将 {@link RawHttpRequest} / {@link RawHttpResponse} 渲染为 HTTP 线格式（raw）字符串的辅助工具。
 *
 * <p>定位：无状态的展示辅助，仅用于日志、调试与错误排查，不做任何编解码校验；输出格式贴近
 * RFC 7230 的报文线格式，请求行与响应状态行均使用 {@code HTTP/1.1}。
 *
 * <p>使用方法：无需实例化，构造器私有；对请求调用 {@link #format(RawHttpRequest)}，
 * 对响应调用 {@link #format(RawHttpResponse)}。
 *
 * <p>输出约定：
 * <ul>
 *   <li>请求：{@code METHOD path?query HTTP/1.1}，随后逐行输出请求头，并补全 {@code Host} 头，
 *       空行后跟请求体（UTF-8 解码）；</li>
 *   <li>响应：{@code HTTP/1.1 状态码 原因短语}，随后逐行输出响应头（多值头分行输出），
 *       空行后跟响应体（UTF-8 解码）。</li>
 * </ul>
 *
 * <p>线程安全：无状态、无可变字段，可跨线程并发调用。
 */
public final class RawHttpFormatter {

    private static final String CRLF = "\r\n";

    private RawHttpFormatter() {
    }

    /**
     * 将请求渲染为 HTTP raw 字符串。
     *
     * @param request 线格式请求，不可为 null
     * @return 完整请求报文，非 null
     */
    public static String format(RawHttpRequest request) {
        StringBuilder out = new StringBuilder();

        String path = request.getEndpoint().getRawPath();
        String query = request.getEndpoint().getRawQuery();
        String target = query == null ? path : path + "?" + query;
        out.append(request.getMethod()).append(' ').append(target).append(" HTTP/1.1").append(CRLF);

        appendHost(out, request.getEndpoint());
        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            out.append(header.getKey()).append(": ").append(header.getValue()).append(CRLF);
        }

        out.append(CRLF);
        out.append(new String(request.getBody(), StandardCharsets.UTF_8));
        return out.toString();
    }

    /**
     * 将响应渲染为 HTTP raw 字符串。
     *
     * @param response 线格式响应，不可为 null
     * @return 完整响应报文，非 null
     */
    public static String format(RawHttpResponse response) {
        StringBuilder out = new StringBuilder();

        out.append("HTTP/1.1 ").append(response.getStatusCode()).append(' ')
                .append(reasonPhrase(response.getStatusCode())).append(CRLF);

        for (Map.Entry<String, List<String>> header : response.getHeaders().entrySet()) {
            for (String value : header.getValue()) {
                out.append(header.getKey()).append(": ").append(value).append(CRLF);
            }
        }

        out.append(CRLF);
        out.append(new String(response.getBody(), StandardCharsets.UTF_8));
        return out.toString();
    }

    /**
     * 从请求端点补全 Host 头（含非默认端口）。
     */
    private static void appendHost(StringBuilder out, java.net.URI endpoint) {
        String host = endpoint.getHost();
        if (host == null) {
            return;
        }
        int port = endpoint.getPort();
        out.append("Host: ").append(host);
        if (port > 0) {
            out.append(':').append(port);
        }
        out.append(CRLF);
    }

    /**
     * 返回常见状态码的原因短语，未收录时返回空串。
     */
    private static String reasonPhrase(int statusCode) {
        switch (statusCode) {
            case 200: return "OK";
            case 201: return "Created";
            case 202: return "Accepted";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 408: return "Request Timeout";
            case 409: return "Conflict";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            case 422: return "Unprocessable Entity";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            case 504: return "Gateway Timeout";
            default: return "";
        }
    }
}
