package io.github.marvinds1.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "reconciliation.api-keys=old-key-0123456789abcdef, new-key-0123456789abcdef",
        "reconciliation.max-concurrent-runs=16",
        "spring.servlet.multipart.max-file-size=1KB"})
@AutoConfigureMockMvc
@Testcontainers
class SecuredApiIntegrationTest {

    private static final String KEY = "new-key-0123456789abcdef";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    TestRestTemplate http;

    private static MockMultipartHttpServletRequestBuilder upload(String ledger, String statement) {
        return multipart("/api/v1/reconciliations")
                .file(new MockMultipartFile("ledgerFile", "l.csv", "text/csv", ledger.getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("statementFile", "s.csv", "text/csv",
                        statement.getBytes(StandardCharsets.UTF_8)));
    }

    private static final String HEADER = "reference,value_date,amount,currency\n";

    @Test
    void requiresAValidApiKeyAndSupportsRotation() throws Exception {
        String ledger = HEADER + "A,2026-11-02,1,IDR\n";
        mvc.perform(upload(ledger, HEADER))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "ApiKey"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(upload(ledger, HEADER).header("X-API-Key", "wrong-key-0123456789abcdef"))
                .andExpect(status().isUnauthorized());
        mvc.perform(upload(ledger, HEADER).header("X-API-Key", "old-key-0123456789abcdef"))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void concurrentIdenticalUploadsCreateExactlyOneRun() throws Exception {
        String ledger = HEADER + "RACE-1,2026-11-02,10,IDR\nRACE-2,2026-11-02,20,IDR\n";
        String statement = HEADER + "RACE-1,2026-11-02,10,IDR\n";
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Callable<MvcResult> call = () -> mvc.perform(upload(ledger, statement).header("X-API-Key", KEY))
                    .andReturn();
            calls.add(pool.submit(call));
        }
        List<Integer> statuses = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Future<MvcResult> f : calls) {
            MvcResult r = f.get();
            statuses.add(r.getResponse().getStatus());
            ids.add(JsonPath.read(r.getResponse().getContentAsString(), "$.id"));
        }
        pool.shutdown();

        assertThat(statuses).containsOnlyOnce(201).filteredOn(s -> s != 201).containsOnly(200);
        assertThat(ids).containsOnly(ids.get(0));
    }

    @Test
    void neutralisesSpreadsheetFormulasInExceptionExport() throws Exception {
        String ledger = HEADER + "=HYPERLINK(\"http://evil\"),2026-11-02,10,IDR\n";
        MvcResult created = mvc.perform(upload(ledger, HEADER).header("X-API-Key", KEY)).andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        MvcResult async = mvc.perform(get("/api/v1/reconciliations/{id}/exceptions.csv", id).header("X-API-Key", KEY))
                .andReturn();
        String csv = mvc.perform(asyncDispatch(async)).andReturn().getResponse().getContentAsString();

        assertThat(csv).contains("\"'=HYPERLINK(\"\"http://evil\"\")\"").doesNotContain(",=HYPERLINK");
    }

    @Test
    void rejectsOversizedUploadsAtTheServer() {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("ledgerFile", new ByteArrayResource((HEADER + "A,2026-11-02,1,IDR\n".repeat(200)).getBytes()) {
            @Override
            public String getFilename() {
                return "l.csv";
            }
        });
        body.add("statementFile", new ByteArrayResource(HEADER.getBytes()) {
            @Override
            public String getFilename() {
                return "s.csv";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("X-API-Key", KEY);

        var response = http.postForEntity("/api/v1/reconciliations", new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
    }
}
