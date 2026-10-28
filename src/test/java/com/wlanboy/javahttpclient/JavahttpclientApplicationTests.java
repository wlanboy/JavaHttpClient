package com.wlanboy.javahttpclient;

import com.wlanboy.javahttpclient.client.K8sClientService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class JavahttpclientApplicationTests {

	// Verhindert, dass der Context die lokale Kubeconfig liest und einen Cluster anspricht
	@MockitoBean
	private K8sClientService k8sClientService;

	@Test
	void contextLoads() {
	}

}
