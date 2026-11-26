package com.moniewise.moniewise_backend;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@SpringBootApplication
@EnableAsync // <--- ADD THIS
@EnableCaching
@EnableScheduling
public class MoniewiseBackendApplication extends SpringBootServletInitializer {

	@Override
	protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
		return builder.sources(MoniewiseBackendApplication.class);
	}

	public static void main(String[] args) {
		// The JVM's default timezone is otherwise UTC (Render's container default).
		// Every plain LocalDateTime.now() call across the codebase (notifications,
		// transaction logs, etc.) reads this default, then gets serialized to the
		// client with no offset/Z suffix — so Flutter's DateTime.parse() treats it
		// as already being device-local time. Since every user is in Nigeria,
		// pinning the JVM default here fixes every such call site at once instead
		// of touching each one individually.
		TimeZone.setDefault(TimeZone.getTimeZone("Africa/Lagos"));
		SpringApplication.run(MoniewiseBackendApplication.class, args);
	}

	@Bean
	public RestTemplate restTemplate() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(10_000);  // 10 s — fail fast if Payeelord is unreachable
		factory.setReadTimeout(45_000);     // 45 s — data purchases can be legitimately slow on the provider side
		return new RestTemplate(factory);
	}

	@Bean(name = "nameEnquiryRestTemplate")
	public RestTemplate nameEnquiryRestTemplate() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(5_000);   // 5s connect
		factory.setReadTimeout(10_000);     // 10s read — name enquiries should be fast
		return new RestTemplate(factory);
	}
	@Bean
	public WebMvcConfigurer corsConfigurer() {
		return new WebMvcConfigurer() {
			@Override
			public void addCorsMappings(CorsRegistry registry) {
				registry.addMapping("/**")  // Allow all endpoints
						.allowedOrigins("http://localhost:5000")  // Flutter app
						.allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
						.allowedHeaders("*")
						.allowCredentials(true);
			}
		};
	}
}