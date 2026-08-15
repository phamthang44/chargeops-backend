package com.thang.chargeops.common.response;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResultPaginationTest {

    @Test
    void exposesSpringPageIndexAsOneBasedMetadata() {
        Page<String> page = new PageImpl<>(
                List.of("station"),
                PageRequest.of(0, 20),
                1
        );

        ApiResult<List<String>> result = ApiResult.successPage(page);

        assertThat(result.getData()).containsExactly("station");
        assertThat(result.getMeta().getPage()).isEqualTo(1);
        assertThat(result.getMeta().getSize()).isEqualTo(20);
        assertThat(result.getMeta().getTotalElements()).isEqualTo(1);
        assertThat(result.getMeta().getTotalPages()).isEqualTo(1);
    }
}
