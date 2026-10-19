package com.anode.factory.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/** A worker: claims runs from the control plane over HTTP and executes them in sandbox containers. */
@SpringBootApplication
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }

    @Bean
    ControlPlane controlPlane(RestClient.Builder builder, WorkerProperties properties) {
        RestClient client = builder.baseUrl(properties.controlPlaneUrl())
                .defaultHeader("Authorization", "Bearer " + properties.token())
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build()
                .createClient(ControlPlane.class);
    }
}
