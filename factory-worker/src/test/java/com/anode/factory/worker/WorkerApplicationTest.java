package com.anode.factory.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"factory.worker.slots=0", "factory.worker.token=t"})
class WorkerApplicationTest {

    @Autowired Dispatcher dispatcher;

    @Test
    void theWorkerStarts() {
        assertThat(dispatcher.isRunning()).isTrue();
    }
}
