package gov.openfiling.s3.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import gov.openfiling.s3.S3Exception;

/** Minimal S3 XML rendering (error documents and the {@code 2006-03-01} namespace). */
public final class S3Xml {

    public static final String NAMESPACE = "http://s3.amazonaws.com/doc/2006-03-01/";
    public static final String PROLOG = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

    private S3Xml() {
    }

    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    public static StringBuilder element(StringBuilder sb, String name, Object value) {
        return sb.append('<').append(name).append('>').append(escape(String.valueOf(value)))
                .append("</").append(name).append('>');
    }

    public static String errorDocument(String code, String message, String resource, String requestId) {
        StringBuilder sb = new StringBuilder(PROLOG).append("<Error>");
        element(sb, "Code", code);
        element(sb, "Message", message);
        element(sb, "Resource", resource);
        element(sb, "RequestId", requestId);
        return sb.append("</Error>").toString();
    }

    /** Writes an S3 error response (no body for HEAD, as S3 does). */
    public static void writeError(HttpServletRequest request, HttpServletResponse response, S3Exception error)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(error.status());
        response.setHeader("Content-Disposition", null);
        response.setHeader("ETag", null);
        response.setHeader("Last-Modified", null);
        response.setHeader("Content-Range", null);
        String requestId = RequestIdFilter.currentId(request);
        String resource = request.getRequestURI();
        if ("HEAD".equals(request.getMethod())) {
            response.setContentLength(0);
            return;
        }
        byte[] body = errorDocument(error.code(), error.getMessage(), resource, requestId)
                .getBytes(StandardCharsets.UTF_8);
        response.setContentType("application/xml");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    public static void writeXml(HttpServletResponse response, int status, String xml) throws IOException {
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setContentType("application/xml");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
