package yahoofinance.histquotes2;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Collectors;

/**
 * Created by Stijn on 23/05/2017.
 * Cookie and crumb for the Yahoo Finance endpoints that need them, with plain HTTP calls
 * (a first request sets the cookie, getcrumb returns the crumb for that cookie).
 */
public class CrumbManager {

  private static final String COOKIE_URL = "https://fc.yahoo.com";
  private static final String CRUMB_URL = "https://query1.finance.yahoo.com/v1/test/getcrumb";
  private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36";

  private static String crumb = "";
  private static String cookie = "";

  private static void setCookieAndCrumb() throws IOException {
    CookieManager cookies = new CookieManager();
    HttpClient client = HttpClient.newBuilder()
        .cookieHandler(cookies)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    try {
      // the answer is usually a 404, what matters is the cookie it sets
      client.send(request(COOKIE_URL), HttpResponse.BodyHandlers.discarding());
      HttpResponse<String> response = client.send(request(CRUMB_URL), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IOException("Yahoo Finance crumb: HTTP " + response.statusCode());
      }
      crumb = response.body().trim();
      cookie = cookies.getCookieStore().getCookies().stream()
          .map(HttpCookie::toString)
          .collect(Collectors.joining("; "));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while asking Yahoo Finance for a crumb", e);
    }
  }

  private static HttpRequest request(String url) {
    return HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", USER_AGENT)
        .timeout(Duration.ofSeconds(10))
        .GET()
        .build();
  }

  public static synchronized void refresh() throws IOException {
    setCookieAndCrumb();
  }

  public static synchronized String getCrumb() throws IOException {
    if (crumb == null || crumb.isEmpty()) {
      refresh();
    }
    return crumb;
  }

  public static synchronized String getCookie() throws IOException {
    if (cookie == null || cookie.isEmpty()) {
      refresh();
    }
    return cookie;
  }
}
