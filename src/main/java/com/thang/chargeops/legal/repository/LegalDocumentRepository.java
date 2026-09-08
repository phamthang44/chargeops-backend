package com.thang.chargeops.legal.repository;

import com.thang.chargeops.legal.domain.LegalDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LegalDocumentRepository extends JpaRepository<LegalDocument, UUID>, JpaSpecificationExecutor<LegalDocument> {

    Optional<LegalDocument> findBySlugAndActiveTrue(String slug);

    Optional<LegalDocument> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
