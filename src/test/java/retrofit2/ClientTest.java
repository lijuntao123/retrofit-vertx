package retrofit2;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.GET;
import retrofit2.http.Path;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class ClientTest {

  public static final String API_URL = "http://localhost:8080";

  public static class Contributor {
    public final String login;
    public final int contributions;

    public Contributor(String login, int contributions) {
      this.login = login;
      this.contributions = contributions;
    }
  }

  public interface GitHub {
    @GET("/repos/{owner}/{repo}/contributors")
    Call<List<Contributor>> contributors(
        @Path("owner") String owner,
        @Path("repo") String repo);
  }

  Vertx vertx;

  @Before
  public void setUp() throws Exception {
    vertx = Vertx.vertx();
    startServer();
  }

  @After
  public void tearDown() {
    vertx.close();
  }

  @Test
  public void doTest() throws Exception {
    HttpClient client = vertx.createHttpClient();

    // Create a very simple REST adapter which points the GitHub API.
    Retrofit retrofit = new Retrofit.Builder()
        .callFactory(new okhttp3.Call.Factory() {
          @Override
          public okhttp3.Call newCall(Request request) {
            return new okhttp3.Call() {
              @Override
              public Request request() {
                return request;
              }
              @Override
              public Response execute() throws IOException {
                CompletableFuture<Response> future = new CompletableFuture<>();
                enqueue(new Callback() {
                  @Override
                  public void onResponse(okhttp3.Call call, Response response) throws IOException {
                    future.complete(response);
                  }
                  @Override
                  public void onFailure(okhttp3.Call call, IOException e) {
                    future.completeExceptionally(e);
                  }
                });
                try {
                  return future.get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                  throw new IOException(e);
                }
              }
              @Override
              public void enqueue(Callback callback) {
                HttpMethod method = HttpMethod.valueOf(request.method());
                client.requestAbs(method, request.url().toString(), resp -> {
                  resp.bodyHandler(body -> {
                    try {
                      Response.Builder builder = new Response.Builder();
                      builder.protocol(Protocol.HTTP_1_1);
                      builder.request(request);
                      builder.code(resp.statusCode());
                      for (Map.Entry<String, String> header : resp.headers()) {
                        builder.addHeader(header.getKey(), header.getValue());
                      }
                      String mediaTypeHeader = resp.getHeader("Content-Type");
                      MediaType mediaType =  mediaTypeHeader != null ? MediaType.parse(mediaTypeHeader) : null;
                      builder.body(ResponseBody.create(mediaType, body.getBytes()));
                      callback.onResponse(this, builder.build());
                    } catch (Exception e) {
                      callback.onFailure(this, new IOException(e));
                    }
                  });
                }).end();
              }
              @Override
              public void cancel() {
              }
              @Override
              public boolean isExecuted() {
                return false;
              }
              @Override
              public boolean isCanceled() {
                return false;
              }
            };
          }
        })
        .baseUrl(API_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build();

    // Create an instance of our GitHub API interface.
    GitHub github = retrofit.create(GitHub.class);

    // Create a call instance for looking up Retrofit contributors.
    Call<List<Contributor>> asyncCall = github.contributors("square", "retrofit");

    CountDownLatch latch = new CountDownLatch(1);

    // Test async
    asyncCall.enqueue(new retrofit2.Callback<List<Contributor>>() {
      @Override
      public void onResponse(Call<List<Contributor>> call, retrofit2.Response<List<Contributor>> response) {
        for (Contributor contributor : response.body()) {
          System.out.println(contributor.login + " (" + contributor.contributions + ")");
        }
        latch.countDown();
      }
      @Override
      public void onFailure(Call<List<Contributor>> call, Throwable throwable) {
        throwable.printStackTrace();
        latch.countDown();
      }
    });

    //
    latch.await(10, TimeUnit.SECONDS);

    // Create a call instance for looking up Retrofit contributors.
    Call<List<Contributor>> syncCall = github.contributors("square", "retrofit");

    // Test sync
    List<Contributor> contributors = syncCall.execute().body();
    for (Contributor contributor : contributors) {
      System.out.println(contributor.login + " (" + contributor.contributions + ")");
    }
  }

  private void startServer() throws Exception {
    HttpServer server = vertx.createHttpServer();
    CompletableFuture<Void> latch = new CompletableFuture<>();
    server.requestHandler(req -> {
      switch (req.path()) {
        case "/repos/square/retrofit/contributors":
          req.response().sendFile("result.json");
          break;
        default:
          req.response().setStatusCode(404).end();
      }
    }).listen(8080, "localhost", ar -> {
      if (ar.succeeded()) {
        latch.complete(null);
      } else {
        latch.completeExceptionally(ar.cause());
      }
    });
    latch.get(10, TimeUnit.SECONDS);
  }
}