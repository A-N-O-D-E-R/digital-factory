package com.anode.factory.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"factory.worker.slots=0", "factory.worker.token=t"})
class WorkerApplicationTest {

    @Autowired Dispatcher dispatcher;
    @Autowired Sandbox sandbox;

    @Test
    void theWorkerStartsWithDockerSandboxesByDefault() {
        assertThat(dispatcher.isRunning()).isTrue();
        assertThat(sandbox).isInstanceOf(DockerSandbox.class);
    }

    @Test
    void anUnknownSandboxIsRefusedByName() {
        WorkerProperties properties = new Binder(new MapConfigurationPropertySource(Map.of("factory.worker.sandbox", "lxc")))
                .bindOrCreate("factory.worker", WorkerProperties.class);
        assertThatThrownBy(() -> new WorkerApplication().sandbox(properties))
                .hasMessage("Unknown factory.worker.sandbox \"lxc\": docker");
    }
}
