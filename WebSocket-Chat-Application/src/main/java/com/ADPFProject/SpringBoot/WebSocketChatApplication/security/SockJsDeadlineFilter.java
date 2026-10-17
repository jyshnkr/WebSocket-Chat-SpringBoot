package com.ADPFProject.SpringBoot.WebSocketChatApplication.security;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.function.BooleanSupplier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

@Component
public class SockJsDeadlineFilter extends OncePerRequestFilter {

    private final WebSocketSessionLifetime lifetime;

    public SockJsDeadlineFilter(WebSocketSessionLifetime lifetime) {
        this.lifetime = lifetime;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        String[] segments = StringUtils.tokenizeToStringArray(path, "/");
        if (segments.length != 4 || !"ws".equals(segments[0]) || "websocket".equals(segments[3])) {
            chain.doFilter(request, response);
            return;
        }
        BooleanSupplier allowed = lifetime.httpTransportGuard(segments[2]);
        if (!allowed.getAsBoolean()) {
            response.setStatus(HttpServletResponse.SC_GONE);
            return;
        }
        chain.doFilter(request, new DeadlineResponse(response, allowed));
    }

    private static final class DeadlineResponse extends HttpServletResponseWrapper {
        private final BooleanSupplier allowed;
        private ServletOutputStream output;
        private PrintWriter writer;

        private DeadlineResponse(HttpServletResponse response, BooleanSupplier allowed) {
            super(response);
            this.allowed = allowed;
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (output == null) {
                ServletOutputStream delegate = super.getOutputStream();
                output = new ServletOutputStream() {
                    @Override
                    public boolean isReady() {
                        return delegate.isReady();
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {
                        delegate.setWriteListener(listener);
                    }

                    @Override
                    public void write(int value) throws IOException {
                        requireActive();
                        delegate.write(value);
                    }

                    @Override
                    public void write(byte[] bytes, int offset, int length) throws IOException {
                        requireActive();
                        delegate.write(bytes, offset, length);
                    }

                    @Override
                    public void flush() throws IOException {
                        delegate.flush();
                    }

                    @Override
                    public void close() throws IOException {
                        delegate.close();
                    }
                };
            }
            return output;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), getCharacterEncoding()));
            }
            return writer;
        }

        private void requireActive() throws IOException {
            if (!allowed.getAsBoolean()) {
                throw new IOException("SockJS session is closed or expired");
            }
        }
    }
}
