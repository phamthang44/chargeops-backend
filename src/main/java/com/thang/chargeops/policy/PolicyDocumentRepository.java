package com.thang.chargeops.policy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PolicyDocumentRepository extends JpaRepository<PolicyDocument, UUID> {
}
