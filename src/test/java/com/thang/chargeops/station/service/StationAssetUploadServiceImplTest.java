package com.thang.chargeops.station.service;

import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.StationAssetRepository;
import com.thang.chargeops.station.service.impl.StationAssetUploadServiceImpl;
import io.imagekit.client.ImageKitClient;
import io.imagekit.services.blocking.FileService;
import io.imagekit.models.files.FileUploadParams;
import io.imagekit.models.files.FileUploadResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StationAssetUploadServiceImplTest {

    @Mock
    private ImageKitClient imageKitClient;

    @Mock
    private FileService fileService;

    @Mock
    private StationService stationService;

    @Mock
    private StationAssetRepository stationAssetRepository;

    @Mock
    private CurrentProfileProvider currentProfileProvider;

    private StationAssetUploadServiceImpl service;

    private UUID stationId;
    private UserProfile owner;
    private Station station;

    @BeforeEach
    void setUp() {
        service = new StationAssetUploadServiceImpl(
                imageKitClient,
                stationService,
                stationAssetRepository,
                currentProfileProvider
        );

        stationId = UUID.randomUUID();
        owner = new UserProfile();
        owner.setId(UUID.randomUUID());

        station = new Station();
        station.setId(stationId);
        station.setOwner(owner);
        station.setName("Test Station");

        lenient().when(stationService.getStationById(stationId)).thenReturn(station);
        lenient().when(currentProfileProvider.requireProfile()).thenReturn(owner);
        lenient().when(stationAssetRepository.findByStationIdOrderByDisplayOrderAsc(stationId))
                .thenReturn(Collections.emptyList());
        lenient().when(stationAssetRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        lenient().when(imageKitClient.files()).thenReturn(fileService);
        FileUploadResponse uploadResponse = mock(FileUploadResponse.class);
        lenient().when(uploadResponse.url()).thenReturn(Optional.of("https://ik.imagekit.io/chargeops/img.jpg"));
        lenient().when(uploadResponse.fileId()).thenReturn(Optional.of("file_123"));
        lenient().when(fileService.upload(any())).thenReturn(uploadResponse);
    }

    @Test
    @DisplayName("uploadStationImage throws AppException when file size exceeds 10MB limit")
    void uploadStationImage_throwsExceptionWhenFileExceedsSizeLimit() {
        byte[] oversizedContent = new byte[10 * 1024 * 1024 + 1];
        MockMultipartFile oversizedFile = new MockMultipartFile(
                "image",
                "large-image.jpg",
                "image/jpeg",
                oversizedContent
        );

        com.thang.chargeops.exception.AppException exception =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.thang.chargeops.exception.AppException.class,
                        () -> service.uploadStationImage(stationId, oversizedFile, true)
                );

        assertThat(exception.getErrorCode()).isEqualTo(com.thang.chargeops.exception.errorcode.CommonErrorCode.INVALID_REQUEST);
        assertThat(exception.getMessage()).contains("Image file size exceeds maximum limit of 10MB");
        verifyNoInteractions(fileService);
    }

    @Test
    @DisplayName("uploadStationImage sanitizes path traversal characters in originalFilename")
    void uploadStationImage_sanitizesPathTraversalInFilename() {
        MockMultipartFile file = new MockMultipartFile(
                "image",
                "../../malicious.png",
                "image/png",
                "dummy content".getBytes()
        );

        service.uploadStationImage(stationId, file, true);

        ArgumentCaptor<FileUploadParams> captor = ArgumentCaptor.forClass(FileUploadParams.class);
        verify(fileService).upload(captor.capture());

        String uploadedFileName = captor.getValue().fileName();
        assertFalse(uploadedFileName.contains(".."));
        assertFalse(uploadedFileName.contains("/"));
        assertFalse(uploadedFileName.contains("\\"));
        assertTrue(uploadedFileName.endsWith("-malicious.png"));
    }

    @Test
    @DisplayName("uploadStationImage sanitizes Windows path traversal in originalFilename")
    void uploadStationImage_sanitizesWindowsPathTraversal() {
        MockMultipartFile file = new MockMultipartFile(
                "image",
                "C:\\boot.ini",
                "image/png",
                "dummy content".getBytes()
        );

        service.uploadStationImage(stationId, file, true);

        ArgumentCaptor<FileUploadParams> captor = ArgumentCaptor.forClass(FileUploadParams.class);
        verify(fileService).upload(captor.capture());

        String uploadedFileName = captor.getValue().fileName();
        assertFalse(uploadedFileName.contains("\\"));
        assertFalse(uploadedFileName.contains(":"));
        assertTrue(uploadedFileName.endsWith("-boot.ini"));
    }
}
