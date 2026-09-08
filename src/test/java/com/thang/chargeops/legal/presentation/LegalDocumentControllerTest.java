package com.thang.chargeops.legal.presentation;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.legal.domain.LegalDocType;
import com.thang.chargeops.legal.domain.TargetAudience;
import com.thang.chargeops.legal.dto.LegalDocumentDetailResponse;
import com.thang.chargeops.legal.dto.LegalDocumentSummaryResponse;
import com.thang.chargeops.legal.repository.specs.LegalDocumentFilter;
import com.thang.chargeops.legal.service.LegalDocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LegalDocumentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalHandlerError.class)
class LegalDocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

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
                .eyebrow("ChargeOps Terms")
                .summary("Tóm tắt điều khoản")
                .version("4.9.0")
                .locale("vi")
                .active(true)
                .effectiveFrom(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(legalDocumentService.searchDocuments(any(LegalDocumentFilter.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(summary)));

        mockMvc.perform(get("/api/v1/legal-documents")
                        .param("search", "điều khoản")
                        .param("audience", "DRIVER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].slug").value("terms-of-service"))
                .andExpect(jsonPath("$.data[0].title").value("Điều khoản dịch vụ"))
                .andExpect(jsonPath("$.data[0].targetAudience").value("ALL"));
    }

    @Test
    void getDocumentBySlug_returnsDetailWhenFound() throws Exception {
        UUID docId = UUID.randomUUID();
        LegalDocumentDetailResponse detail = LegalDocumentDetailResponse.builder()
                .id(docId)
                .slug("privacy-policy")
                .docType(LegalDocType.PRIVACY_POLICY)
                .targetAudience(TargetAudience.ALL)
                .title("Chính sách bảo mật")
                .content("# Nội dung chính sách bảo mật")
                .version("4.9.0")
                .active(true)
                .build();

        when(legalDocumentService.getDocumentBySlug("privacy-policy"))
                .thenReturn(detail);

        mockMvc.perform(get("/api/v1/legal-documents/privacy-policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slug").value("privacy-policy"))
                .andExpect(jsonPath("$.data.content").value("# Nội dung chính sách bảo mật"));
    }

    @Test
    void getDocumentBySlug_returns404WhenNotFound() throws Exception {
        when(legalDocumentService.getDocumentBySlug("unknown-slug"))
                .thenThrow(new AppException(CommonErrorCode.RESOURCE_NOT_FOUND, "Document not found"));

        mockMvc.perform(get("/api/v1/legal-documents/unknown-slug"))
                .andExpect(status().isNotFound());
    }
}
