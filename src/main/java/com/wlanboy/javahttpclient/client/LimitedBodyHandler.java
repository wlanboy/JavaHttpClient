package com.wlanboy.javahttpclient.client;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import org.springframework.http.MediaType;

/**
 * Wie BodyHandlers.ofString(), liest aber höchstens maxBytes und bricht den Download danach ab.
 * Verhindert, dass ein großer Response den Container per OOM-Kill beendet.
 */
final class LimitedBodyHandler implements HttpResponse.BodyHandler<LimitedBodyHandler.LimitedBody> {

	record LimitedBody(String text, boolean truncated) {}

	private final int maxBytes;

	LimitedBodyHandler(int maxBytes) {
		this.maxBytes = maxBytes;
	}

	@Override
	public HttpResponse.BodySubscriber<LimitedBody> apply(HttpResponse.ResponseInfo info) {
		return new LimitedSubscriber(maxBytes, charsetOf(info.headers().firstValue("content-type").orElse(null)));
	}

	private static Charset charsetOf(String contentType) {
		if (contentType == null) return StandardCharsets.UTF_8;
		try {
			Charset charset = MediaType.parseMediaType(contentType).getCharset();
			return charset != null ? charset : StandardCharsets.UTF_8;
		} catch (Exception e) {
			return StandardCharsets.UTF_8;
		}
	}

	private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<LimitedBody> {
		private final int maxBytes;
		private final Charset charset;
		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		private final CompletableFuture<LimitedBody> result = new CompletableFuture<>();
		private Flow.Subscription subscription;
		private boolean truncated;

		LimitedSubscriber(int maxBytes, Charset charset) {
			this.maxBytes = maxBytes;
			this.charset = charset;
		}

		@Override
		public CompletionStage<LimitedBody> getBody() {
			return result;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(List<ByteBuffer> items) {
			if (result.isDone()) return;
			for (ByteBuffer item : items) {
				int take = Math.min(item.remaining(), maxBytes - buffer.size());
				byte[] chunk = new byte[take];
				item.get(chunk);
				buffer.write(chunk, 0, take);
				if (item.hasRemaining()) {
					truncated = true;
					break;
				}
			}
			if (truncated) {
				// Rest nicht mehr herunterladen: Verbindung bzw. HTTP/2-Stream abbrechen
				subscription.cancel();
				complete();
			}
		}

		@Override
		public void onError(Throwable throwable) {
			result.completeExceptionally(throwable);
		}

		@Override
		public void onComplete() {
			complete();
		}

		private void complete() {
			result.complete(new LimitedBody(buffer.toString(charset), truncated));
		}
	}
}
