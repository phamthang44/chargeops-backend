package com.thang.chargeops.legal.service;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.legal.domain.LegalDocType;
import com.thang.chargeops.legal.domain.LegalDocument;
import com.thang.chargeops.legal.domain.TargetAudience;
import com.thang.chargeops.legal.dto.CreateLegalDocumentRequest;
import com.thang.chargeops.legal.dto.LegalDocumentDetailResponse;
import com.thang.chargeops.legal.dto.LegalDocumentSummaryResponse;
import com.thang.chargeops.legal.dto.UpdateLegalDocumentRequest;
import com.thang.chargeops.legal.repository.LegalDocumentRepository;
import com.thang.chargeops.legal.repository.specs.LegalDocumentFilter;
import com.thang.chargeops.legal.service.impl.LegalDocumentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LegalDocumentServiceTest {

    @Mock
    private LegalDocumentRepository repository;

    private LegalDocumentService service;

    private LegalDocument sampleDoc;
    private UUID sampleId;

    @BeforeEach
    void setUp() {
        service = new LegalDocumentServiceImpl(repository);
        sampleId = UUID.randomUUID();
        sampleDoc = LegalDocument.builder()
                .slug("terms-of-service")
                .docType(LegalDocType.TERMS_OF_SERVICE)
                .targetAudience(TargetAudience.ALL)
                .title("Điều khoản dịch vụ")
                .eyebrow("ChargeOps Terms")
                .summary("Tóm tắt điều khoản dịch vụ")
                .content("# Nội dung điều khoản dịch vụ đầy đủ")
                .version("4.9.0")
                .locale("vi")
                .active(true)
                .effectiveFrom(Instant.now())
                .build();
        sampleDoc.setId(sampleId);
    }

    @Test
    void searchDocuments_delegatesToRepositoryWithFilterAndPageable() {
        Pageable pageable = PageRequest.of(0, 10);
        LegalDocumentFilter filter = LegalDocumentFilter.builder()
                .search("điều khoản")
                .docType(LegalDocType.TERMS_OF_SERVICE)
                .audience(TargetAudience.ALL)
                .active(true)
                .build();

        when(repository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(sampleDoc), pageable, 1));

        Page<LegalDocumentSummaryResponse> result = service.searchDocuments(filter, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).slug()).isEqualTo("terms-of-service");
        assertThat(result.getContent().get(0).title()).isEqualTo("Điều khoản dịch vụ");
        verify(repository, times(1)).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    void getDocumentBySlug_returnsDetailWhenFound() {
        when(repository.findBySlugAndActiveTrue("terms-of-service"))
                .thenReturn(Optional.of(sampleDoc));

        LegalDocumentDetailResponse response = service.getDocumentBySlug("terms-of-service");

        assertThat(response.slug()).isEqualTo("terms-of-service");
        assertThat(response.content()).isEqualTo("# Nội dung điều khoản dịch vụ đầy đủ");
        assertThat(response.docType()).isEqualTo(LegalDocType.TERMS_OF_SERVICE);
    }

    @Test
    void getDocumentBySlug_throwsResourceNotFoundWhenMissing() {
        when(repository.findBySlugAndActiveTrue("non-existent"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDocumentBySlug("non-existent"))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    AppException appException = (AppException) e;
                    assertThat(appException.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
                });
    }

    @Test
    void getDocumentById_returnsDetailWhenFound() {
        when(repository.findById(sampleId)).thenReturn(Optional.of(sampleDoc));

        LegalDocumentDetailResponse response = service.getDocumentById(sampleId);

        assertThat(response.id()).isEqualTo(sampleId);
        assertThat(response.title()).isEqualTo("Điều khoản dịch vụ");
    }

    @Test
    void getDocumentById_throwsResourceNotFoundWhenMissing() {
        UUID randomId = UUID.randomUUID();
        when(repository.findById(randomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDocumentById(randomId))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    AppException appException = (AppException) e;
                    assertThat(appException.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
                });
    }

    @Test
    void createDocument_createsAndReturnsWhenSlugUnique() {
        CreateLegalDocumentRequest request = CreateLegalDocumentRequest.builder()
                .slug("new-policy")
                .docType(LegalDocType.PRIVACY_POLICY)
                .targetAudience(TargetAudience.DRIVER)
                .title("Chính sách mới")
                .content("Nội dung chính sách")
                .version("1.0.0")
                .build();

        when(repository.existsBySlug("new-policy")).thenReturn(false);
        when(repository.save(any(LegalDocument.class))).thenAnswer(invocation -> {
            LegalDocument doc = invocation.getArgument(0);
            doc.setId(UUID.randomUUID());
            return doc;
        });

        LegalDocumentDetailResponse response = service.createDocument(request);

        assertThat(response.slug()).isEqualTo("new-policy");
        assertThat(response.title()).isEqualTo("Chính sách mới");
        assertThat(response.targetAudience()).isEqualTo(TargetAudience.DRIVER);
        assertThat(response.version()).isEqualTo("1.0.0");
        verify(repository, times(1)).save(any(LegalDocument.class));
    }

    @Test
    void createDocument_throwsConflictWhenSlugAlreadyExists() {
        CreateLegalDocumentRequest request = CreateLegalDocumentRequest.builder()
                .slug("terms-of-service")
                .docType(LegalDocType.TERMS_OF_SERVICE)
                .title("Điều khoản")
                .content("Nội dung")
                .version("1.0.0")
                .build();

        when(repository.existsBySlug("terms-of-service")).thenReturn(true);

        assertThatThrownBy(() -> service.createDocument(request))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    AppException appException = (AppException) e;
                    assertThat(appException.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_CONFLICT);
                });
        verify(repository, never()).save(any());
    }

    @Test
    void updateDocument_updatesFieldsAndReturns() {
        UpdateLegalDocumentRequest request = UpdateLegalDocumentRequest.builder()
                .title("Điều khoản dịch vụ (Đã sửa)")
                .content("Nội dung mới")
                .active(false)
                .build();

        when(repository.findById(sampleId)).thenReturn(Optional.of(sampleDoc));
        when(repository.save(any(LegalDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LegalDocumentDetailResponse response = service.updateDocument(sampleId, request);

        assertThat(response.title()).isEqualTo("Điều khoản dịch vụ (Đã sửa)");
        assertThat(response.content()).isEqualTo("Nội dung mới");
        assertThat(response.active()).isFalse();
        verify(repository, times(1)).save(sampleDoc);
    }

    @Test
    void deleteDocument_deletesEntity() {
        when(repository.findById(sampleId)).thenReturn(Optional.of(sampleDoc));

        service.deleteDocument(sampleId);

        verify(repository, times(1)).delete(sampleDoc);
    }
}
