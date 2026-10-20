package com.anode.factory.platform;

import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

/** JSON documents (definitions, outputs, artifacts) are described as any JSON, not as Jackson's tree class. */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    static {
        SpringDocUtils.getConfig().replaceWithSchema(JsonNode.class, new Schema<>().description("Any JSON value"));
    }
}
