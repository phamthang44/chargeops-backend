package com.thang.chargeops.legal.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.legal.domain.LegalDocType;
import com.thang.chargeops.legal.domain.TargetAudience;
import com.thang.chargeops.legal.dto.CreateLegalDocumentRequest;
import com.thang.chargeops.legal.dto.LegalDocumentDetailResponse;
import com.thang.chargeops.legal.dto.LegalDocumentSummaryResponse;
import com.thang.chargeops.legal.dto.UpdateLegalDocumentRequest;
import com.thang.chargeops.legal.service.LegalDocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminLegalDocumentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalHandlerError.class)
class AdminLegalDocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private LegalDocumentService legalDocumentService;

    @Test
    void getDocuments_returnsPaginatedResults() throws Exception {
        LegalDocumentSummaryResponse summary = LegalDocumentSummaryResponse.builder()
                .id(UUID.randomUUID())
                .slug("terms-of-service")
                .docType(LegalDocType.TERMS_OF_SERVICE)
                .targetAudience(TargetAudience.ALL)
                .title("Điều khoản dịch vụ")
                .version("4.9.0")
                .active(true)
                .build();

        when(legalDocumentService.searchDocuments(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(summary)));

        mockMvc.perform(get("/api/v1/admin/legal-documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].slug").value("terms-of-service"));
    }

    @Test
    void createDocument_returns201WhenValid() throws Exception {
        CreateLegalDocumentRequest request = CreateLegalDocumentRequest.builder()
                .slug("new-doc")
                .docType(LegalDocType.LICENSE_AGREEMENT)
                .targetAudience(TargetAudience.OWNER)
                .title("Thỏa thuận cấp phép mới")
                .content("# Nội dung")
                .version("1.0.0")
                .build();

        LegalDocumentDetailResponse response = LegalDocumentDetailResponse.builder()
                .id(UUID.randomUUID())
                .slug("new-doc")
                .docType(LegalDocType.LICENSE_AGREEMENT)
                .targetAudience(TargetAudience.OWNER)
                .title("Thỏa thuận cấp phép mới")
                .content("# Nội dung")
                .version("1.0.0")
                .active(true)
                .effectiveFrom(Instant.now())
                .build();

        when(legalDocumentService.createDocument(any(CreateLegalDocumentRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/admin/legal-documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.slug").value("new-doc"))
                .andExpect(jsonPath("$.data.targetAudience").value("OWNER"));
    }

    @Test
    void updateDocument_returns200WhenValid() throws Exception {
        UUID id = UUID.randomUUID();
        UpdateLegalDocumentRequest request = UpdateLegalDocumentRequest.builder()
                .title("Tiêu đề cập nhật")
                .content("Nội dung cập nhật")
                .build();

        LegalDocumentDetailResponse response = LegalDocumentDetailResponse.builder()
                .id(id)
                .slug("terms-of-service")
                .title("Tiêu đề cập nhật")
                .content("Nội dung cập nhật")
                .build();

        when(legalDocumentService.updateDocument(eq(id), any(UpdateLegalDocumentRequest.class)))
                .thenReturn(response);

        mockMvc.perform(put("/api/v1/admin/legal-documents/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Tiêu đề cập nhật"));
    }

    @Test
    void deleteDocument_returns204() throws Exception {
        UUID id = UUID.randomUUID();
        doNothing().when(legalDocumentService).deleteDocument(id);

        mockMvc.perform(delete("/api/v1/admin/legal-documents/" + id))
                .andExpect(status().isNoContent());
    }
}
