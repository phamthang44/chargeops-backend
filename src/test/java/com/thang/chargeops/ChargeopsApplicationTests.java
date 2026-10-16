package com.thang.chargeops;

import com.thang.chargeops.assistant.DifyClient;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class ChargeopsApplicationTests {
	@MockitoBean
	DifyClient difyClient;

	@MockitoBean
	Rest5Client elasticsearchRestClient;

	@Test
	void contextLoads() {
	}

}
