package dev.daiku.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Local single-user UI. Session-bound CSRF protection; webhook authentication uses HMAC. */
@Configuration
public class WebConfig implements WebMvcConfigurer {
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              @Override
              public boolean preHandle(
                  HttpServletRequest request, HttpServletResponse response, Object handler)
                  throws Exception {
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setHeader("X-Frame-Options", "DENY");
                response.setHeader("Referrer-Policy", "same-origin");
                response.setHeader(
                    "Content-Security-Policy",
                    "default-src 'self'; style-src 'self'; script-src 'self'; frame-ancestors"
                        + " 'none'; base-uri 'self'; form-action 'self'");
                if (request.getRequestURI().equals("/webhooks/github")) return true;
                var session = request.getSession();
                if (session.getAttribute("csrf") == null)
                  session.setAttribute("csrf", UUID.randomUUID().toString());
                String token = (String) session.getAttribute("csrf");
                request.setAttribute("csrf", token);
                if (!java.util.Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
                  String supplied = request.getParameter("_csrf");
                  if (supplied == null
                      || !MessageDigest.isEqual(
                          token.getBytes(StandardCharsets.UTF_8),
                          supplied.getBytes(StandardCharsets.UTF_8))) {
                    response.sendError(403, "Your session changed. Reload the page and try again.");
                    return false;
                  }
                }
                return true;
              }
            })
        .excludePathPatterns("/css/**", "/error");
  }
}
