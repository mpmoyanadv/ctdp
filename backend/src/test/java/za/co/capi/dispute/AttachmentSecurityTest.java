package za.co.capi.dispute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"demo", "test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AttachmentSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.storage.attachments-dir}")
    private String attachmentsDir;

    @MockBean
    private JavaMailSender javaMailSender;

    @BeforeEach
    void setupMailMock() {
        when(javaMailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
    }

    @Test
    void rejectsSpoofedSignaturesOversizedFilesMaxCountTerminalUploadAndUnauthorizedDownload() throws Exception {
        String customer1Token = loginAndExtractToken("customer@capi.test", "Password123!");
        String customer2Token = loginAndExtractToken("customer2@capi.test", "Password123!");
        String staffToken = loginAndExtractToken("staff@capi.test", "Password123!");

        String disputeId = createDispute(customer1Token);

        // 1. Spoofed PE executable / plain text renamed to .pdf -> 400 Bad Request
        MockMultipartFile exeAsPdf = new MockMultipartFile(
                "files", "payload.pdf", "application/pdf", new byte[]{0x4D, 0x5A, 0x00, 0x01, 0x02});
        mockMvc.perform(multipart("/api/v1/disputes/" + disputeId + "/attachments")
                        .file(exeAsPdf)
                        .header("Authorization", "Bearer " + customer1Token))
                .andExpect(status().isBadRequest());

        // 2. Mismatched extension vs magic bytes (PNG bytes named fake.pdf) -> 400 Bad Request
        MockMultipartFile pngAsPdf = new MockMultipartFile(
                "files", "fake.pdf", "application/pdf",
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        mockMvc.perform(multipart("/api/v1/disputes/" + disputeId + "/attachments")
                        .file(pngAsPdf)
                        .header("Authorization", "Bearer " + customer1Token))
                .andExpect(status().isBadRequest());

        // 3. Oversized file (> 5MB) -> 400 Bad Request
        byte[] oversized = new byte[5 * 1024 * 1024 + 10];
        oversized[0] = '%';
        oversized[1] = 'P';
        oversized[2] = 'D';
        oversized[3] = 'F';
        oversized[4] = '-';
        MockMultipartFile bigPdf = new MockMultipartFile(
                "files", "huge.pdf", "application/pdf", oversized);
        mockMvc.perform(multipart("/api/v1/disputes/" + disputeId + "/attachments")
                        .file(bigPdf)
                        .header("Authorization", "Bearer " + customer1Token))
                .andExpect(status().isBadRequest());

        // 4. Rollback cleanup: valid PDF + invalid second file in same batch must leave zero orphaned files on disk
        MockMultipartFile validPdf = new MockMultipartFile(
                "files", "valid1.pdf", "application/pdf", "%PDF-1.4 valid content".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile invalidSecond = new MockMultipartFile(
                "files", "bad.pdf", "application/pdf", "not-a-pdf".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/disputes/" + disputeId + "/attachments")
                        .file(validPdf)
                        .file(invalidSecond)
                        .header("Authorization", "Bearer " + customer1Token))
                .andExpect(status().isBadRequest());

        Path disputeStorageDir = Paths.get(attachmentsDir).toAbsolutePath().normalize().resolve(disputeId);
        if (Files.exists(disputeStorageDir)) {
            try (Stream<Path> files = Files.list(disputeStorageDir)) {
                assertThat(files.count()).isZero();
            }
        }

        // 5. Upload 5 valid attachments (max limit), then 6th must fail with 400 Bad Request
        MockMultipartFile validJpg = new MockMultipartFile(
                "files", "photo.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});
        MvcResult firstUpload = mockMvc.perform(multipart("/api/v1/disputes/" + disputeId + "/attachments")
                        .file(validPdf)
                        .file(validPdf)
                        .file(validPdf)
                        .file(validPdf)
                        .file(validJpg)
                        .header("Authorization", "Bearer " + customer1Token))
                .andExpect(status().isCreated())
                .andReturn();

        String attachmentId = objectMapper.readTree(firstUpload.getResponse().getContentAsString())
                .get(0).get("id").asText();

        mockMvc.perform(multipart("/api/v1/disputes/" + disputeId + "/attachments")
                        .file(validPdf)
                        .header("Authorization", "Bearer " + customer1Token))
                .andExpect(status().isBadRequest());

        // 6. Unauthorized download by another customer -> 403 Forbidden; owner download -> 200 OK with nosniff
        mockMvc.perform(get("/api/v1/disputes/" + disputeId + "/attachments/" + attachmentId)
                        .header("Authorization", "Bearer " + customer2Token))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/disputes/" + disputeId + "/attachments/" + attachmentId)
                        .header("Authorization", "Bearer " + customer1Token))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        // 7. Terminal dispute rejects further uploads -> 409 Conflict
        mockMvc.perform(post("/api/v1/disputes/" + disputeId + "/claim")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/disputes/" + disputeId + "/status")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\",\"resolutionSummary\":\"Closed\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(multipart("/api/v1/disputes/" + disputeId + "/attachments")
                        .file(validPdf)
                        .header("Authorization", "Bearer " + customer1Token))
                .andExpect(status().isConflict());
    }

    private String createDispute(String token) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "merchantName", "Dis-Chem Sandton",
                "paymentRail", "CARD_POS",
                "category", "UNAUTHORIZED_TRANSACTION",
                "amount", 215.00,
                "reason", "Attachment security test dispute."
        ));
        MvcResult res = mockMvc.perform(post("/api/v1/disputes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText();
    }

    private String loginAndExtractToken(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"email\":\"%s\",\"password\":\"%s\"}", email, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
}

