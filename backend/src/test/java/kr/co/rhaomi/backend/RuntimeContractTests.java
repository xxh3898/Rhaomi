package kr.co.rhaomi.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RuntimeContractTests {

    @Test
    void should_runOnJava25_when_backendTestsExecute() {
        assertEquals(25, Runtime.version().feature());
    }
}
