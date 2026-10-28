package io.tetra.issuance.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.micrometer.observation.Observation;

/** 웜업 요청 표시 — 기동 때 만든 값이 맞고 원격 주소가 루프백일 때만 http_server_requests 관찰을 끈다. */
class WarmupRequestsTest {

	final WarmupRequests warmup = new WarmupRequests();

	boolean observed(String header, String remoteAddr) {
		var request = new MockHttpServletRequest("GET", "/api/issuance/health");
		if (header != null) {
			request.addHeader(WarmupRequests.HEADER, header);
		}
		request.setRemoteAddr(remoteAddr);
		return warmup.test("http.server.requests",
				new ServerRequestObservationContext(request, new MockHttpServletResponse()));
	}

	@Test
	void 웜업_값과_루프백이면_관찰하지_않는다() {
		assertThat(observed(warmup.token(), "127.0.0.1")).isFalse();
		assertThat(observed(warmup.token(), "0:0:0:0:0:0:0:1")).isFalse();
	}

	@Test
	void 원격_주소가_루프백이_아니면_웜업_값이_맞아도_관찰한다() {
		assertThat(observed(warmup.token(), "10.0.12.34")).isTrue();
		assertThat(observed(warmup.token(), "203.0.113.7")).isTrue();
	}

	@Test
	void 값이_다르거나_없으면_루프백이어도_관찰한다() {
		assertThat(observed("1", "127.0.0.1")).isTrue();
		assertThat(observed(new WarmupRequests().token(), "127.0.0.1")).isTrue(); // 다른 기동의 값
		assertThat(observed(null, "127.0.0.1")).isTrue();
	}

	@Test
	void 서버_요청이_아닌_관찰은_건드리지_않는다() {
		assertThat(warmup.test("tetra.other", new Observation.Context())).isTrue();
	}

}
