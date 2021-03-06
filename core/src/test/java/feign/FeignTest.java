/*
 * Copyright 2013 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package feign;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.SocketPolicy;
import com.squareup.okhttp.mockwebserver.rule.MockWebServerRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import feign.Target.HardCodedTarget;
import feign.codec.Decoder;
import feign.codec.EncodeException;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;
import feign.codec.StringDecoder;

import static feign.Util.UTF_8;
import static feign.assertj.MockWebServerAssertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FeignTest {

  @Rule
  public final ExpectedException thrown = ExpectedException.none();
  @Rule
  public final MockWebServerRule server = new MockWebServerRule();

  @Test
  public void iterableQueryParams() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setBody("foo"));

    TestInterface api = new TestInterfaceBuilder().target("http://localhost:" + server.getPort());

    api.queryParams("user", Arrays.asList("apple", "pear"));

    assertThat(server.takeRequest())
        .hasPath("/?1=user&2=apple&2=pear");
  }

  @Test
  public void postTemplateParamsResolve() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setBody("foo"));

    TestInterface api = new TestInterfaceBuilder().target("http://localhost:" + server.getPort());

    api.login("netflix", "denominator", "password");

    assertThat(server.takeRequest())
        .hasBody(
            "{\"customer_name\": \"netflix\", \"user_name\": \"denominator\", \"password\": \"password\"}");
  }

  @Test
  public void responseCoercesToStringBody() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setBody("foo"));

    TestInterface api = new TestInterfaceBuilder().target("http://localhost:" + server.getPort());

    Response response = api.response();
    assertTrue(response.body().isRepeatable());
    assertEquals("foo", response.body().toString());
  }

  @Test
  public void postFormParams() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setBody("foo"));

    TestInterface api = new TestInterfaceBuilder().target("http://localhost:" + server.getPort());

    api.form("netflix", "denominator", "password");

    assertThat(server.takeRequest())
        .hasBody(
            "{\"customer_name\":\"netflix\",\"user_name\":\"denominator\",\"password\":\"password\"}");
  }

  @Test
  public void postBodyParam() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setBody("foo"));

    TestInterface api = new TestInterfaceBuilder().target("http://localhost:" + server.getPort());

    api.body(Arrays.asList("netflix", "denominator", "password"));

    assertThat(server.takeRequest())
        .hasHeaders("Content-Length: 32")
        .hasBody("[netflix, denominator, password]");
  }

  /**
   * The type of a parameter value may not be the desired type to encode as. Prefer the interface
   * type.
   */
  @Test
  public void bodyTypeCorrespondsWithParameterType() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setBody("foo"));

    final AtomicReference<Type> encodedType = new AtomicReference<Type>();
    TestInterface api = new TestInterfaceBuilder()
        .encoder(new Encoder.Default() {
          @Override
          public void encode(Object object, Type bodyType, RequestTemplate template)
              throws EncodeException {
            encodedType.set(bodyType);
          }
        })
        .target("http://localhost:" + server.getPort());

    api.body(Arrays.asList("netflix", "denominator", "password"));

    server.takeRequest();

    assertThat(encodedType.get()).isEqualTo(new TypeToken<List<String>>() {
    }.getType());
  }

  @Test
  public void postGZIPEncodedBodyParam() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setBody("foo"));

    TestInterface api = new TestInterfaceBuilder().target("http://localhost:" + server.getPort());

    api.gzipBody(Arrays.asList("netflix", "denominator", "password"));

    assertThat(server.takeRequest())
        .hasNoHeaderNamed("Content-Length")
        .hasGzippedBody("[netflix, denominator, password]".getBytes(UTF_8));
  }

  @Test
  public void singleInterceptor() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setBody("foo"));

    TestInterface api = new TestInterfaceBuilder()
        .requestInterceptor(new ForwardedForInterceptor())
        .target("http://localhost:" + server.getPort());

    api.post();

    assertThat(server.takeRequest())
        .hasHeaders("X-Forwarded-For: origin.host.com");
  }

  @Test
  public void multipleInterceptor() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setBody("foo"));

    TestInterface api = new TestInterfaceBuilder()
        .requestInterceptor(new ForwardedForInterceptor())
        .requestInterceptor(new UserAgentInterceptor())
        .target("http://localhost:" + server.getPort());

    api.post();

    assertThat(server.takeRequest()).hasHeaders("X-Forwarded-For: origin.host.com",
                                                "User-Agent: Feign");
  }

  @Test
  public void customExpander() throws Exception {
    server.enqueue(new MockResponse());

    TestInterface api = new TestInterfaceBuilder().target("http://localhost:" + server.getPort());

    api.expand(new Date(1234l));

    assertThat(server.takeRequest())
        .hasPath("/?date=1234");
  }

  @Test
  public void toKeyMethodFormatsAsExpected() throws Exception {
    assertEquals("TestInterface#post()",
                 Feign.configKey(TestInterface.class.getDeclaredMethod("post")));
    assertEquals("TestInterface#uriParam(String,URI,String)",
                 Feign.configKey(TestInterface.class
                                     .getDeclaredMethod("uriParam", String.class, URI.class,
                                                        String.class)));
  }

  @Test
  public void canOverrideErrorDecoder() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setResponseCode(404).setBody("foo"));
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("zone not found");

    TestInterface api = new TestInterfaceBuilder()
        .errorDecoder(new IllegalArgumentExceptionOn404())
        .target("http://localhost:" + server.getPort());

    api.post();
  }

  @Test
  public void retriesLostConnectionBeforeRead() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
    server.enqueue(new MockResponse().setBody("success!"));

    TestInterface api = new TestInterfaceBuilder().target("http://localhost:" + server.getPort());

    api.post();

    assertEquals(2, server.getRequestCount());
  }

  @Test
  public void overrideTypeSpecificDecoder() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setBody("success!"));

    TestInterface api = new TestInterfaceBuilder()
        .decoder(new Decoder() {
          @Override
          public Object decode(Response response, Type type) {
            return "fail";
          }
        }).target("http://localhost:" + server.getPort());

    assertEquals(api.post(), "fail");
  }

  /**
   * when you must parse a 2xx status to determine if the operation succeeded or not.
   */
  @Test
  public void retryableExceptionInDecoder() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setBody("retry!"));
    server.enqueue(new MockResponse().setBody("success!"));

    TestInterface api = new TestInterfaceBuilder()
        .decoder(new StringDecoder() {
          @Override
          public Object decode(Response response, Type type) throws IOException {
            String string = super.decode(response, type).toString();
            if ("retry!".equals(string)) {
              throw new RetryableException(string, null);
            }
            return string;
          }
        }).target("http://localhost:" + server.getPort());

    assertEquals(api.post(), "success!");
    assertEquals(2, server.getRequestCount());
  }

  @Test
  public void doesntRetryAfterResponseIsSent() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setBody("success!"));
    thrown.expect(FeignException.class);
    thrown.expectMessage("error reading response POST http://");

    TestInterface api = new TestInterfaceBuilder()
        .decoder(new Decoder() {
          @Override
          public Object decode(Response response, Type type) throws IOException {
            throw new IOException("error reading response");
          }
        }).target("http://localhost:" + server.getPort());

    try {
      api.post();
    } finally {
      assertEquals(1, server.getRequestCount());
    }
  }

  @Test
  public void equalsHashCodeAndToStringWork() {
    Target<TestInterface>
        t1 =
        new HardCodedTarget<TestInterface>(TestInterface.class, "http://localhost:8080");
    Target<TestInterface>
        t2 =
        new HardCodedTarget<TestInterface>(TestInterface.class, "http://localhost:8888");
    Target<OtherTestInterface> t3 =
        new HardCodedTarget<OtherTestInterface>(OtherTestInterface.class, "http://localhost:8080");
    TestInterface i1 = Feign.builder().target(t1);
    TestInterface i2 = Feign.builder().target(t1);
    TestInterface i3 = Feign.builder().target(t2);
    OtherTestInterface i4 = Feign.builder().target(t3);

    assertThat(i1)
        .isEqualTo(i2)
        .isNotEqualTo(i3)
        .isNotEqualTo(i4);

    assertThat(i1.hashCode())
        .isEqualTo(i2.hashCode())
        .isNotEqualTo(i3.hashCode())
        .isNotEqualTo(i4.hashCode());

    assertThat(i1.toString())
        .isEqualTo(i2.toString())
        .isNotEqualTo(i3.toString())
        .isNotEqualTo(i4.toString());

    assertThat(t1)
        .isNotEqualTo(i1);

    assertThat(t1.hashCode())
        .isEqualTo(i1.hashCode());

    assertThat(t1.toString())
        .isEqualTo(i1.toString());
  }

  @Test
  public void decodeLogicSupportsByteArray() throws Exception {
    byte[] expectedResponse = {12, 34, 56};
    server.enqueue(new MockResponse().setBody(expectedResponse));

    OtherTestInterface
        api =
        Feign.builder().target(OtherTestInterface.class, "http://localhost:" + server.getPort());

    assertThat(api.binaryResponseBody())
        .containsExactly(expectedResponse);
  }

  @Test
  public void encodeLogicSupportsByteArray() throws Exception {
    byte[] expectedRequest = {12, 34, 56};
    server.enqueue(new MockResponse());

    OtherTestInterface
        api =
        Feign.builder().target(OtherTestInterface.class, "http://localhost:" + server.getPort());

    api.binaryRequestBody(expectedRequest);

    assertThat(server.takeRequest())
        .hasBody(expectedRequest);
  }

  interface TestInterface {

    @RequestLine("POST /")
    Response response();

    @RequestLine("POST /")
    String post();

    @RequestLine("POST /")
    @Body("%7B\"customer_name\": \"{customer_name}\", \"user_name\": \"{user_name}\", \"password\": \"{password}\"%7D")
    void login(
        @Param("customer_name") String customer, @Param("user_name") String user,
        @Param("password") String password);

    @RequestLine("POST /")
    void body(List<String> contents);

    @RequestLine("POST /")
    @Headers("Content-Encoding: gzip")
    void gzipBody(List<String> contents);

    @RequestLine("POST /")
    void form(
        @Param("customer_name") String customer, @Param("user_name") String user,
        @Param("password") String password);

    @RequestLine("GET /{1}/{2}")
    Response uriParam(@Param("1") String one, URI endpoint, @Param("2") String two);

    @RequestLine("GET /?1={1}&2={2}")
    Response queryParams(@Param("1") String one, @Param("2") Iterable<String> twos);

    @RequestLine("POST /?date={date}")
    void expand(@Param(value = "date", expander = DateToMillis.class) Date date);

    class DateToMillis implements Param.Expander {

      @Override
      public String expand(Object value) {
        return String.valueOf(((Date) value).getTime());
      }
    }
  }


  interface OtherTestInterface {

    @RequestLine("POST /")
    String post();

    @RequestLine("POST /")
    byte[] binaryResponseBody();

    @RequestLine("POST /")
    void binaryRequestBody(byte[] contents);
  }

  static class ForwardedForInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
      template.header("X-Forwarded-For", "origin.host.com");
    }
  }

  static class UserAgentInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
      template.header("User-Agent", "Feign");
    }
  }

  static class IllegalArgumentExceptionOn404 extends ErrorDecoder.Default {

    @Override
    public Exception decode(String methodKey, Response response) {
      if (response.status() == 404) {
        return new IllegalArgumentException("zone not found");
      }
      return super.decode(methodKey, response);
    }
  }

  static final class TestInterfaceBuilder {

    private final Feign.Builder delegate = new Feign.Builder()
        .decoder(new Decoder.Default())
        .encoder(new Encoder() {
          @Override
          public void encode(Object object, Type bodyType, RequestTemplate template) {
            if (object instanceof Map) {
              template.body(new Gson().toJson(object));
            } else {
              template.body(object.toString());
            }
          }
        });

    TestInterfaceBuilder requestInterceptor(RequestInterceptor requestInterceptor) {
      delegate.requestInterceptor(requestInterceptor);
      return this;
    }

    TestInterfaceBuilder encoder(Encoder encoder) {
      delegate.encoder(encoder);
      return this;
    }

    TestInterfaceBuilder decoder(Decoder decoder) {
      delegate.decoder(decoder);
      return this;
    }

    TestInterfaceBuilder errorDecoder(ErrorDecoder errorDecoder) {
      delegate.errorDecoder(errorDecoder);
      return this;
    }

    TestInterface target(String url) {
      return delegate.target(TestInterface.class, url);
    }
  }
}
