package roomescape.payment.client.idempotency;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

public class ByteArrayClientHttpResponse implements ClientHttpResponse {

    private final byte[] body;
    private final HttpHeaders headers;
    private final HttpStatusCode statusCode;

    public static ByteArrayClientHttpResponse from(byte[] body, HttpStatusCode statusCode) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, "application/json");
        return new ByteArrayClientHttpResponse(body, headers, statusCode);
    }

    public ByteArrayClientHttpResponse(byte[] body, HttpHeaders headers, HttpStatusCode statusCode) {
        this.body = body;
        this.headers = headers;
        this.statusCode = statusCode;
    }

    @Override
    public HttpStatus getStatusCode() {
        return HttpStatus.valueOf(statusCode.value());
    }

    @Override
    public String getStatusText() {
        return getStatusCode().getReasonPhrase();
    }

    @Override
    public void close() {}

    @Override
    public InputStream getBody() {
        return new ByteArrayInputStream(body);
    }

    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }
}
