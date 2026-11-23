package io.github.marvinds1.reconciliation;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ReconciliationApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    private static MockMultipartFile file(String part, String name, byte[] content) {
        return new MockMultipartFile(part, name, "text/csv", content);
    }

    private static byte[] sample(String name) throws Exception {
        return Files.readAllBytes(Path.of("samples", name));
    }

    private String createSampleRun(int tolerance) throws Exception {
        MvcResult result = mvc.perform(multipart("/api/v1/reconciliations")
                        .file(file("ledgerFile", "ledger.csv", sample("ledger.csv")))
                        .file(file("statementFile", "statement.csv", sample("statement.csv")))
                        .param("dateToleranceDays", String.valueOf(tolerance)))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void reconcilesSampleFilesAndReportsEveryOutcome() throws Exception {
        mvc.perform(multipart("/api/v1/reconciliations")
                        .file(file("ledgerFile", "ledger.csv", sample("ledger.csv")))
                        .file(file("statementFile", "statement.csv", sample("statement.csv")))
                        .param("dateToleranceDays", "2"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/reconciliations/")))
                .andExpect(jsonPath("$.ledgerFileName").value("ledger.csv"))
                .andExpect(jsonPath("$.summary.ledgerEntries").value(7))
                .andExpect(jsonPath("$.summary.statementEntries").value(7))
                .andExpect(jsonPath("$.summary.matched").value(4))
                .andExpect(jsonPath("$.summary.amountMismatch").value(1))
                .andExpect(jsonPath("$.summary.missingInStatement").value(2))
                .andExpect(jsonPath("$.summary.missingInLedger").value(1))
                .andExpect(jsonPath("$.summary.duplicate").value(1))
                .andExpect(jsonPath("$.summary.exceptions").value(5));
    }

    @Test
    void uploadingTheSameFilesAgainReplaysTheOriginalRun() throws Exception {
        String first = createSampleRun(1);

        mvc.perform(multipart("/api/v1/reconciliations")
                        .file(file("ledgerFile", "renamed.csv", sample("ledger.csv")))
                        .file(file("statementFile", "statement.csv", sample("statement.csv")))
                        .param("dateToleranceDays", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(first));
    }

    @Test
    void differentToleranceIsANewRunWithDifferentResults() throws Exception {
        String id = createSampleRun(0);

        mvc.perform(get("/api/v1/reconciliations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateToleranceDays").value(0))
                .andExpect(jsonPath("$.summary.matched").value(3));
    }

    @Test
    void listsItemsFilteredByStatusWithKeysetPagination() throws Exception {
        String id = createSampleRun(1);

        mvc.perform(get("/api/v1/reconciliations/{id}/items", id)
                        .param("status", "MISSING_IN_STATEMENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].ledger.reference").value("TRF-1003"))
                .andExpect(jsonPath("$.items[0].statement").value(nullValue()))
                .andExpect(jsonPath("$.nextAfterId").value(nullValue()));

        MvcResult page = mvc.perform(get("/api/v1/reconciliations/{id}/items", id).param("limit", "5"))
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andReturn();
        Number next = JsonPath.read(page.getResponse().getContentAsString(), "$.nextAfterId");

        mvc.perform(get("/api/v1/reconciliations/{id}/items", id).param("afterId", next.toString()))
                .andExpect(jsonPath("$.items", hasSize(4)));
    }

    @Test
    void exportsExceptionsAsCsv() throws Exception {
        String id = createSampleRun(1);

        MvcResult async = mvc.perform(get("/api/v1/reconciliations/{id}/exceptions.csv", id))
                .andExpect(request().asyncStarted())
                .andReturn();
        String csv = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .asyncDispatch(async))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsString();

        assertLines(csv, 6);
        org.assertj.core.api.Assertions.assertThat(csv)
                .contains("AMOUNT_MISMATCH,IDR,3,TRF-1002,2026-11-02,-25000000,3,TRF-1002,2026-11-02,-25500000,-500000")
                .doesNotContain("MATCHED,");
    }

    @Test
    void rejectsInvalidFileWithLineLevelErrors() throws Exception {
        byte[] bad = "reference,value_date,amount,currency\nA,2026-13-01,1,IDR\n".getBytes();

        mvc.perform(multipart("/api/v1/reconciliations")
                        .file(file("ledgerFile", "ledger.csv", bad))
                        .file(file("statementFile", "statement.csv", sample("statement.csv"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE"))
                .andExpect(jsonPath("$.file").value("LEDGER"))
                .andExpect(jsonPath("$.errors[0]").value("line 2: value_date must be YYYY-MM-DD"));
    }

    @Test
    void validatesToleranceAndUnknownRuns() throws Exception {
        mvc.perform(multipart("/api/v1/reconciliations")
                        .file(file("ledgerFile", "l.csv", sample("ledger.csv")))
                        .file(file("statementFile", "s.csv", sample("statement.csv")))
                        .param("dateToleranceDays", "40"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mvc.perform(get("/api/v1/reconciliations/{id}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
    }

    private static void assertLines(String csv, int expected) {
        org.assertj.core.api.Assertions.assertThat(csv.strip().split("\r?\n")).hasSize(expected);
    }
}
