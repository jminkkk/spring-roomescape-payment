package roomescape.payment.client.idempotency;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.SSLException;

import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.client5.http.utils.DateUtils;
import org.apache.hc.core5.concurrent.CancellableDependency;
import org.apache.hc.core5.http.ConnectionClosedException;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.TimeValue;

public class IdempotencyKeyRetryStrategy implements HttpRequestRetryStrategy {

    protected static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final IdempotencyKeyRetryStrategy INSTANCE = new IdempotencyKeyRetryStrategy();

    private final int maxRetries;

    private final TimeValue defaultRetryInterval;

    private final Set<Class<? extends IOException>> nonRetriableIOExceptionClasses;

    private final Set<Integer> retriableCodes;

    public IdempotencyKeyRetryStrategy(
            final int maxRetries,
            final TimeValue defaultRetryInterval
    ) {
        Args.notNegative(maxRetries, "maxRetries");
        Args.notNegative(defaultRetryInterval.getDuration(), "defaultRetryInterval");
        this.maxRetries = maxRetries;
        this.defaultRetryInterval = defaultRetryInterval;
        this.nonRetriableIOExceptionClasses = new HashSet<>(
                Arrays.asList(
                        InterruptedIOException.class,
                        UnknownHostException.class,
                        ConnectException.class,
                        ConnectionClosedException.class,
                        NoRouteToHostException.class,
                        SSLException.class));
        this.retriableCodes = new HashSet<>(
                Arrays.asList(
                        HttpStatus.SC_TOO_MANY_REQUESTS,
                        HttpStatus.SC_SERVICE_UNAVAILABLE));
    }

    public IdempotencyKeyRetryStrategy() {
        this(1, TimeValue.ofSeconds(1L));
    }

    @Override
    public boolean retryRequest(
            final HttpRequest request,
            final IOException exception,
            final int execCount,
            final HttpContext context
    ) {
        if (execCount > this.maxRetries) {
            return false;
        }
        if (this.nonRetriableIOExceptionClasses.contains(exception.getClass())) {
            return false;
        }
        for (final Class<? extends IOException> rejectException : this.nonRetriableIOExceptionClasses) {
            if (rejectException.isInstance(exception)) {
                return false;
            }
        }
        if (request instanceof CancellableDependency && ((CancellableDependency) request).isCancelled()) {
            return false;
        }

        return handleAsIdempotent(request);
    }

    @Override
    public boolean retryRequest(
            final HttpResponse response,
            final int execCount,
            final HttpContext context
    ) {
        Args.notNull(response, "response");

        return execCount <= this.maxRetries && retriableCodes.contains(response.getCode());
    }

    @Override
    public TimeValue getRetryInterval(
            final HttpResponse response,
            final int execCount,
            final HttpContext context
    ) {
        final Header header = response.getFirstHeader(HttpHeaders.RETRY_AFTER);
        TimeValue retryAfter = null;
        if (header != null) {
            final String value = header.getValue();
            try {
                retryAfter = TimeValue.ofSeconds(Long.parseLong(value));
            } catch (final NumberFormatException ignore) {
                final Instant retryAfterDate = DateUtils.parseStandardDate(value);
                if (retryAfterDate != null) {
                    retryAfter =
                            TimeValue.ofMilliseconds(retryAfterDate.toEpochMilli() - System.currentTimeMillis());
                }
            }

            if (TimeValue.isPositive(retryAfter)) {
                return retryAfter;
            }
        }
        return this.defaultRetryInterval;
    }

    protected boolean handleAsIdempotent(final HttpRequest request) {
        return Method.isIdempotent(request.getMethod())
                    || request.containsHeader(IDEMPOTENCY_KEY_HEADER);
    }
}
