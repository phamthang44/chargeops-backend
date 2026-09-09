package com.thang.chargeops.common.entity;

import com.thang.chargeops.common.enums.ValueType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

@SQLRestriction("deleted_at is null")
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "system_configs", indexes = {
        @Index(name = "idx_system_configs_key", columnList = "config_key", unique = true)
})
public class SystemConfig extends SoftDeletableEntity {

    @Column(name = "config_key", nullable = false, unique = true, length = 100)
    private String configKey;

    @Column(name = "config_value", nullable = false, columnDefinition = "TEXT")
    private String configValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 50)
    private ValueType valueType;

    @Column(name = "description", length = 255)
    private String description;
}
